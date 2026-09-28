"""Splits indexed files into the chunks of the RAG index (version 2).

Java files are chunked per method with tree-sitter (javalang cannot parse Java 21 records).
Each method chunk carries a header, stored apart from the body, so the method can be
understood on its own: the package, the declaration line of every enclosing type, and the
innermost type's fields. Trivial methods (plain getters and setters, empty bodies,
constructors that only assign fields) are dropped, since they add nothing a reviewer needs.
A type left with no chunk, such as a record without methods, becomes one `type` chunk, so
it doesn't disappear from the index. Other files (pom.xml, application.yml) are cut into
blocks at blank lines.

No chunk may exceed the embedding model's input limit: gemini-embedding-001 silently drops
everything past 2,048 tokens. Anything estimated above SPLIT_THRESHOLD_TOKENS is split into
consecutive parts, each keeping the header.

The index format is a contract with codereview-lambda; see its
specs/002-method-chunking/contracts/index-v2.md.
"""

import math
import re
import sys
import textwrap
from dataclasses import dataclass

import tree_sitter_java
from tree_sitter import Language, Node, Parser

# Estimated as characters / 3, which over-counts every tokenizer measured on this code
# (gpt-oss ~4.1-4.4 chars per token, Gemini ~3.4). At 1,800 estimated tokens, the worst real
# size that passes is ~1,590 embedding tokens, well inside the 2,048 limit. codereview-lambda
# uses the same estimate and threshold for its query side.
CHARS_PER_TOKEN = 3.0
SPLIT_THRESHOLD_TOKENS = 1800
# Target size when packing non-Java blocks together.
BLOCK_TARGET_TOKENS = 400

_JAVA = Parser(Language(tree_sitter_java.language()))
_TYPE_NODES = {
    "class_declaration",
    "record_declaration",
    "interface_declaration",
    "enum_declaration",
}
_METHOD_NODES = {"method_declaration", "constructor_declaration", "compact_constructor_declaration"}
_TRIVIAL_BODY = (
    re.compile(r"^\{\s*\}$"),
    re.compile(r"^\{\s*return\s+(this\.)?\w+\s*;\s*\}$"),
    re.compile(r"^\{\s*this\.\w+\s*=\s*\w+\s*;\s*\}$"),
)
_FIELD_ASSIGNMENT = re.compile(r"^\s*this\.\w+\s*=\s*\w+\s*$")


def estimate_tokens(text: str) -> int:
    return math.ceil(len(text) / CHARS_PER_TOKEN)


def embedding_input(header: str, text: str) -> str:
    """What gets embedded for a chunk: its header, then its body."""
    return f"{header}\n{text}" if header else text


@dataclass(frozen=True)
class Chunk:
    path: str
    kind: str  # "method" | "type" | "block"
    type_name: str | None  # dotted enclosing chain, e.g. "Outer.Inner"
    method: str | None
    params: str | None  # "String,int" for methods, None otherwise
    start_line: int
    end_line: int
    header: str
    text: str
    part: tuple[int, int] | None = None  # (index, count), 1-based

    @property
    def id(self) -> str:
        if self.kind == "block":
            base = f"{self.path}#L{self.start_line}-{self.end_line}"
        elif self.kind == "type":
            base = f"{self.path}#{self.type_name}:{self.start_line}-{self.end_line}"
        else:
            base = (
                f"{self.path}#{self.type_name}.{self.method}({self.params})"
                f":{self.start_line}-{self.end_line}"
            )
        return f"{base}#part-{self.part[0]}" if self.part else base

    def to_index(self, vector: list[float]) -> dict:
        """The chunk as index-v2 JSON (field names per the contract)."""
        symbol = None
        if self.type_name:
            symbol = {"type": self.type_name, "method": self.method}
        return {
            "id": self.id,
            "path": self.path,
            "kind": self.kind,
            "symbol": symbol,
            "startLine": self.start_line,
            "endLine": self.end_line,
            "part": {"index": self.part[0], "count": self.part[1]} if self.part else None,
            "header": self.header,
            "text": self.text,
            "vector": vector,
        }


# --- Java -----------------------------------------------------------------------------------


def _text(node: Node, source: bytes) -> str:
    return source[node.start_byte : node.end_byte].decode("utf-8")


def _dedented(node: Node, source: bytes) -> str:
    """The node's source with its own indentation removed (its first line starts at the
    node's column, so pad it back before dedenting)."""
    return textwrap.dedent(" " * node.start_point.column + _text(node, source))


def _declaration_head(node: Node, source: bytes) -> str:
    """A type's declaration up to (not including) its body: annotations, modifiers, name,
    record components, extends/implements."""
    body = node.child_by_field_name("body")
    end = body.start_byte if body else node.end_byte
    head = source[node.start_byte : end].decode("utf-8")
    return " ".join(line.strip() for line in head.strip().splitlines()) + " {"


def _field_line(node: Node, source: bytes) -> str:
    """One field declaration on one line; a multi-line initializer is left out."""
    text = _text(node, source)
    if "\n" in text:
        text = text.split("=", 1)[0].rstrip() + ";"
    return text


def _param_types(node: Node, source: bytes) -> str:
    if node.type == "compact_constructor_declaration":
        return "compact"
    params = node.child_by_field_name("parameters")
    types = []
    for child in params.named_children if params else []:
        if child.type in ("formal_parameter", "spread_parameter"):
            type_node = child.child_by_field_name("type")
            if type_node is None:  # spread_parameter keeps its type as the first child
                type_node = child.named_children[0]
            type_text = re.sub(r"\s+", "", _text(type_node, source))
            types.append(type_text + ("..." if child.type == "spread_parameter" else ""))
    return ",".join(types)


def is_trivial(node: Node, source: bytes) -> bool:
    """Research R1's rule: an empty body, a single `return <field>;`, a single
    `this.<field> = <param>;`, or a constructor made only of such assignments. Methods with
    no body (abstract, interface) are trivial too: there is no code to show. Compact record
    constructors are never trivial; they exist to validate."""
    if node.type == "compact_constructor_declaration":
        return False
    body = node.child_by_field_name("body")
    if body is None:
        return True
    body_text = _text(body, source)
    if any(pattern.match(body_text) for pattern in _TRIVIAL_BODY):
        return True
    if node.type == "constructor_declaration":
        statements = [s for s in body_text.strip()[1:-1].split(";") if s.strip()]
        return all(_FIELD_ASSIGNMENT.match(s) for s in statements)
    return False


def _method_name(node: Node, source: bytes, type_chain: list[str]) -> str:
    if node.type in ("constructor_declaration", "compact_constructor_declaration"):
        return type_chain[-1]
    return _text(node.child_by_field_name("name"), source)


def _chunk_type(
    node: Node,
    source: bytes,
    path: str,
    package_line: str,
    outer_heads: list[str],
    outer_names: list[str],
) -> list[Chunk]:
    name = _text(node.child_by_field_name("name"), source)
    names = outer_names + [name]
    heads = outer_heads + [_declaration_head(node, source)]
    body = node.child_by_field_name("body")
    members = body.named_children if body else []
    # enum bodies keep their fields and methods one level down
    for member in list(members):
        if member.type == "enum_body_declarations":
            members = members + member.named_children

    indent = "    "
    fields = [
        indent * len(heads) + _field_line(m, source)
        for m in members
        if m.type in ("field_declaration", "constant_declaration")
    ]
    header_lines = ([package_line, ""] if package_line else []) + [
        indent * depth + head for depth, head in enumerate(heads)
    ]
    header = "\n".join(header_lines + fields)
    type_name = ".".join(names)

    chunks: list[Chunk] = []
    nested: list[Chunk] = []
    for member in members:
        if member.type in _METHOD_NODES and not is_trivial(member, source):
            chunks.append(
                Chunk(
                    path=path,
                    kind="method",
                    type_name=type_name,
                    method=_method_name(member, source, names),
                    params=_param_types(member, source),
                    start_line=member.start_point.row + 1,
                    end_line=member.end_point.row + 1,
                    header=header,
                    text=_dedented(member, source),
                )
            )
        elif member.type in _TYPE_NODES:
            nested.extend(_chunk_type(member, source, path, package_line, heads, names))

    if not chunks:
        # FR-004: a type with nothing left (a record, a DTO, an interface) still gets indexed,
        # as its own source.
        chunks.append(
            Chunk(
                path=path,
                kind="type",
                type_name=type_name,
                method=None,
                params=None,
                start_line=node.start_point.row + 1,
                end_line=node.end_point.row + 1,
                header=("\n".join([package_line, ""]) if package_line else ""),
                text=_dedented(node, source),
            )
        )
    return chunks + nested


def chunk_java(path: str, text: str) -> list[Chunk] | None:
    """Method-level chunks, or None when tree-sitter finds a syntax error (the caller then
    falls back to blocks)."""
    source = text.encode("utf-8")
    tree = _JAVA.parse(source)
    if tree.root_node.has_error:
        return None
    package_line = ""
    chunks: list[Chunk] = []
    for node in tree.root_node.named_children:
        if node.type == "package_declaration":
            package_line = _text(node, source)
        elif node.type in _TYPE_NODES:
            chunks.extend(_chunk_type(node, source, path, package_line, [], []))
    return chunks


# --- other files ----------------------------------------------------------------------------


def chunk_blocks(path: str, text: str) -> list[Chunk]:
    """Paragraphs (split at blank lines) packed up to BLOCK_TARGET_TOKENS each. A paragraph
    larger than that becomes its own block; split_oversized() cuts it further if needed."""
    lines = text.splitlines()
    paragraphs: list[tuple[int, int]] = []  # 0-based [start, end) line ranges
    start = None
    for i, line in enumerate(lines + [""]):
        if line.strip() and start is None:
            start = i
        elif not line.strip() and start is not None:
            paragraphs.append((start, i))
            start = None

    blocks: list[tuple[int, int]] = []
    for para_start, para_end in paragraphs:
        if blocks:
            merged = "\n".join(lines[blocks[-1][0] : para_end])
            if estimate_tokens(merged) <= BLOCK_TARGET_TOKENS:
                blocks[-1] = (blocks[-1][0], para_end)
                continue
        blocks.append((para_start, para_end))

    return [
        Chunk(
            path=path,
            kind="block",
            type_name=None,
            method=None,
            params=None,
            start_line=block_start + 1,
            end_line=block_end,
            header="",
            text="\n".join(lines[block_start:block_end]),
        )
        for block_start, block_end in blocks
    ]


# --- size guard -----------------------------------------------------------------------------


def split_oversized(chunk: Chunk) -> list[Chunk]:
    """FR-006: a chunk whose embedding input is estimated above SPLIT_THRESHOLD_TOKENS is cut
    at line boundaries into consecutive parts, each repeating the header, each within the
    threshold. A single line that alone is too long is cut by characters, the last resort,
    so that nothing is ever silently truncated by the API instead."""
    if estimate_tokens(embedding_input(chunk.header, chunk.text)) <= SPLIT_THRESHOLD_TOKENS:
        return [chunk]

    budget_chars = int(SPLIT_THRESHOLD_TOKENS * CHARS_PER_TOKEN) - len(chunk.header) - 1
    if budget_chars <= 0:
        raise ValueError(f"header of {chunk.id} alone exceeds the embedding limit")

    pieces: list[tuple[int, str]] = []  # (line offset from start_line, line text)
    for offset, line in enumerate(chunk.text.split("\n")):
        if len(line) + 1 <= budget_chars:
            pieces.append((offset, line))
        else:
            print(f"warning: {chunk.id}: line {chunk.start_line + offset} cut by characters", file=sys.stderr)
            pieces.extend((offset, line[i : i + budget_chars]) for i in range(0, len(line), budget_chars))

    parts: list[list[tuple[int, str]]] = [[]]
    size = 0
    for offset, line in pieces:
        if parts[-1] and size + len(line) + 1 > budget_chars:
            parts.append([])
            size = 0
        parts[-1].append((offset, line))
        size += len(line) + 1

    return [
        Chunk(
            path=chunk.path,
            kind=chunk.kind,
            type_name=chunk.type_name,
            method=chunk.method,
            params=chunk.params,
            start_line=chunk.start_line + part[0][0],
            end_line=chunk.start_line + part[-1][0],
            header=chunk.header,
            text="\n".join(line for _, line in part),
            part=(index, len(parts)),
        )
        for index, part in enumerate(parts, start=1)
    ]


def chunk_file(path: str, text: str) -> list[Chunk]:
    """All chunks for one file, every one within the embedding limit."""
    chunks: list[Chunk] | None = None
    if path.endswith(".java"):
        chunks = chunk_java(path, text)
        if chunks is None:
            print(f"warning: {path}: Java syntax error, chunked by blocks instead", file=sys.stderr)
    if chunks is None:
        chunks = chunk_blocks(path, text)
    return [part for chunk in chunks for part in split_oversized(chunk)]
