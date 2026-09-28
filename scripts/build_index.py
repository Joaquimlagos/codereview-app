#!/usr/bin/env python3
"""Builds the RAG embedding index (index.json, version 2) consumed by codereview-lambda.

collect files → chunk them (scripts/chunking.py: one chunk per Java method, blocks for other
files, nothing over the embedding limit) → validate → embed in batches → write index.json.

The index is written only after every embedding succeeded, so a failed build never
publishes a partial index: the workflow's upload step doesn't run, and the index already in
S3 stays in place.

`--dry-run` stops after chunking and prints the counts, with no API key and no network.
"""

import argparse
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from chunking import SPLIT_THRESHOLD_TOKENS, Chunk, chunk_file, embedding_input, estimate_tokens

INDEX_VERSION = 2
EMBEDDING_MODEL = "gemini-embedding-001"
EMBEDDING_DIMENSIONS = 768
TASK_TYPE = "RETRIEVAL_DOCUMENT"
DEFAULT_API_BASE = "https://generativelanguage.googleapis.com/v1beta"
# batchEmbedContents accepts at most this many inputs per request.
MAX_BATCH_SIZE = 100

OUTPUT_FILE = Path("index.json")
INDEXED_DIRECTORIES = ("src",)
# Source and build definition only. Prose about what the repository is for
# adds no retrieval value for a code review and skews the context the
# reviewer receives, so README.md is deliberately left out.
INDEXED_FILES = ("pom.xml",)
EXCLUDED_DIRECTORIES = {"target", ".git"}

# Retry policy (codereview-lambda research.md, R5): the statuses actually seen from this
# API are 429 (rate limit) and 503 (high demand); timeouts and connection errors are
# retried too. Anything else — a bad key, a malformed request — fails at once.
RETRYABLE_STATUSES = frozenset({429, 503})
MAX_ATTEMPTS = 5
BASE_DELAY_SECONDS = 2.0
MAX_DELAY_SECONDS = 60.0
BUILD_DEADLINE_SECONDS = 5 * 60
REQUEST_TIMEOUT_SECONDS = 60


class EmbeddingFailed(Exception):
    """The API refused, or every retry ran out. The build must stop without an index."""


def collect_files() -> list[Path]:
    files = []
    for directory in INDEXED_DIRECTORIES:
        for path in sorted(Path(directory).rglob("*")):
            if path.is_file() and EXCLUDED_DIRECTORIES.isdisjoint(path.parts):
                files.append(path)
    files.extend(Path(name) for name in INDEXED_FILES if Path(name).is_file())
    return files


def read_indexable_text(path: Path) -> str | None:
    """Returns None for binary or empty files, which have no useful embedding."""
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return None
    return text if text.strip() else None


def build_chunks(files: list[Path]) -> list[Chunk]:
    chunks: list[Chunk] = []
    for path in files:
        text = read_indexable_text(path)
        if text is None:
            print(f"Skipping {path.as_posix()} (binary or empty)")
            continue
        chunks.extend(chunk_file(path.as_posix(), text))
    return chunks


def validate(chunks: list[Chunk]) -> None:
    """Checks that must hold before a single token is sent (contracts/index-v2.md, rule 4)."""
    if not chunks:
        raise SystemExit("No indexable chunks found — refusing to publish an empty index.")
    duplicates = [chunk_id for chunk_id, n in Counter(c.id for c in chunks).items() if n > 1]
    if duplicates:
        raise SystemExit(f"Duplicate chunk ids: {duplicates}")
    oversized = [
        c.id
        for c in chunks
        if estimate_tokens(embedding_input(c.header, c.text)) > SPLIT_THRESHOLD_TOKENS
    ]
    if oversized:
        raise SystemExit(f"Chunks over the embedding limit after splitting: {oversized}")
    empty = [c.id for c in chunks if not c.text.strip()]
    if empty:
        raise SystemExit(f"Chunks with empty text: {empty}")


def _retry_delay(error: urllib.error.HTTPError | None, attempt: int) -> float:
    """What the API asks for (a Retry-After header, or Gemini's RetryInfo.retryDelay in the
    error body), else exponential backoff with jitter."""
    if error is not None:
        retry_after = error.headers.get("Retry-After") if error.headers else None
        if retry_after and retry_after.strip().isdigit():
            return float(retry_after)
        body = getattr(error, "body_text", "")
        if match := re.search(r'"retryDelay"\s*:\s*"(\d+(?:\.\d+)?)s"', body):
            return float(match.group(1))
    return min(MAX_DELAY_SECONDS, BASE_DELAY_SECONDS * 2**attempt) + random.uniform(0, 1)


class Embedder:
    """Batch embedding with retry. Counts calls and retries for the build summary."""

    def __init__(self, api_key: str, api_base: str = DEFAULT_API_BASE, clock=time.monotonic,
                 sleep=time.sleep):
        self._api_key = api_key
        self._url = f"{api_base}/models/{EMBEDDING_MODEL}:batchEmbedContents"
        self._clock = clock
        self._sleep = sleep
        self._deadline = clock() + BUILD_DEADLINE_SECONDS
        self.calls = 0
        self.retries = 0

    def embed(self, texts: list[str]) -> list[list[float]]:
        vectors: list[list[float]] = []
        for start in range(0, len(texts), MAX_BATCH_SIZE):
            vectors.extend(self._embed_batch(texts[start : start + MAX_BATCH_SIZE]))
        return vectors

    def _embed_batch(self, texts: list[str]) -> list[list[float]]:
        payload = json.dumps(
            {
                "requests": [
                    {
                        "model": f"models/{EMBEDDING_MODEL}",
                        "content": {"parts": [{"text": text}]},
                        "taskType": TASK_TYPE,
                        "outputDimensionality": EMBEDDING_DIMENSIONS,
                    }
                    for text in texts
                ]
            }
        ).encode("utf-8")

        for attempt in range(MAX_ATTEMPTS):
            request = urllib.request.Request(
                self._url,
                data=payload,
                headers={"Content-Type": "application/json", "x-goog-api-key": self._api_key},
                method="POST",
            )
            self.calls += 1
            http_error: urllib.error.HTTPError | None = None
            try:
                with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
                    body = json.load(response)
                return self._vectors(body, len(texts))
            except urllib.error.HTTPError as error:
                error.body_text = error.read().decode("utf-8", errors="replace")
                error.close()
                if error.code not in RETRYABLE_STATUSES:
                    raise EmbeddingFailed(
                        f"Gemini API returned HTTP {error.code}: {error.body_text[:500]}"
                    ) from error
                http_error, reason = error, f"HTTP {error.code}"
            except (urllib.error.URLError, TimeoutError) as error:
                reason = f"{type(error).__name__}: {getattr(error, 'reason', error)}"

            if attempt + 1 == MAX_ATTEMPTS:
                raise EmbeddingFailed(f"Gave up after {MAX_ATTEMPTS} attempts; last: {reason}")
            delay = _retry_delay(http_error, attempt)
            if self._clock() + delay > self._deadline:
                raise EmbeddingFailed(
                    f"Build deadline ({BUILD_DEADLINE_SECONDS}s) reached; last: {reason}"
                )
            self.retries += 1
            print(f"Embedding batch failed ({reason}); retrying in {delay:.1f}s")
            self._sleep(delay)
        raise AssertionError("unreachable")

    @staticmethod
    def _vectors(body: dict, expected: int) -> list[list[float]]:
        embeddings = body.get("embeddings") or []
        if len(embeddings) != expected:
            raise EmbeddingFailed(f"Got {len(embeddings)} embeddings for {expected} inputs")
        vectors = [(e or {}).get("values") or [] for e in embeddings]
        wrong = [i for i, v in enumerate(vectors) if len(v) != EMBEDDING_DIMENSIONS]
        if wrong:
            raise EmbeddingFailed(f"Expected {EMBEDDING_DIMENSIONS} dimensions; wrong at {wrong}")
        return vectors


def summary(chunks: list[Chunk]) -> str:
    """FR-013: what was built, printed on every run (the dry run included)."""
    kinds = Counter(c.kind for c in chunks)
    largest = max(chunks, key=lambda c: estimate_tokens(embedding_input(c.header, c.text)))
    split = len({c.id.rsplit("#part-", 1)[0] for c in chunks if c.part})
    return (
        f"{len(chunks)} chunks: {kinds['method']} method, {kinds['type']} type, "
        f"{kinds['block']} block; {split} split into parts; largest "
        f"{estimate_tokens(embedding_input(largest.header, largest.text))} estimated tokens "
        f"({largest.id})"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dry-run", action="store_true", help="chunk and report, no API calls")
    args = parser.parse_args()

    chunks = build_chunks(collect_files())
    validate(chunks)
    print(summary(chunks))
    if args.dry_run:
        return

    api_key = os.environ.get("GEMINI_API_KEY")
    commit = os.environ.get("GITHUB_SHA")
    branch = os.environ.get("GITHUB_REF_NAME")
    missing = [
        name
        for name, value in (
            ("GEMINI_API_KEY", api_key),
            ("GITHUB_SHA", commit),
            ("GITHUB_REF_NAME", branch),
        )
        if not value
    ]
    if missing:
        sys.exit(f"Missing required environment variable(s): {', '.join(missing)}")

    embedder = Embedder(api_key, os.environ.get("GEMINI_API_BASE", DEFAULT_API_BASE))
    try:
        vectors = embedder.embed([embedding_input(c.header, c.text) for c in chunks])
    except EmbeddingFailed as error:
        sys.exit(f"Embedding failed, no index written: {error}")
    print(f"Embedded with {embedder.calls} call(s), {embedder.retries} retr(y/ies)")

    index = {
        "version": INDEX_VERSION,
        "branch": branch,
        "commit": commit,
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "model": EMBEDDING_MODEL,
        "dimensions": EMBEDDING_DIMENSIONS,
        "chunks": [chunk.to_index(vector) for chunk, vector in zip(chunks, vectors, strict=True)],
    }
    OUTPUT_FILE.write_text(json.dumps(index, ensure_ascii=False), encoding="utf-8")
    print(f"Wrote {OUTPUT_FILE} (version {INDEX_VERSION}) with {len(chunks)} chunks")


if __name__ == "__main__":
    main()
