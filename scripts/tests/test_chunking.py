"""Method-level chunking (codereview-lambda specs/002-method-chunking, FR-001 to FR-006,
FR-011)."""

import textwrap
import unittest

from chunking import (
    SPLIT_THRESHOLD_TOKENS,
    chunk_blocks,
    chunk_file,
    chunk_java,
    embedding_input,
    estimate_tokens,
)

PATH = "src/main/java/p/Service.java"

SERVICE = textwrap.dedent(
    """\
    package p;

    import java.util.Map;

    @Component
    public class Service {

        private final Map<String, Integer> counts;
        private int limit = 10;

        public Service(Map<String, Integer> counts) {
            this.counts = counts;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public void reset() {
        }

        public int count(String key) {
            return counts.getOrDefault(key, 0);
        }

        public int count(String key, int fallback) {
            return counts.getOrDefault(key, fallback);
        }

        public void log(String format, Object... args) {
            System.out.printf(format, args);
        }

        static class Inner {
            private final String name;

            String shout() {
                return name.toUpperCase();
            }
        }
    }
    """
)


def _by_id(chunks):
    return {chunk.id.split("#", 1)[1]: chunk for chunk in chunks}


class JavaChunkingTest(unittest.TestCase):
    def setUp(self):
        self.chunks = _by_id(chunk_java(PATH, SERVICE))

    def test_trivial_methods_are_dropped_and_real_ones_kept(self):
        # getter, setter, empty body and assign-only constructor are all trivial
        self.assertEqual(
            sorted(self.chunks),
            sorted(
                [
                    "Service.count(String):26-28",
                    "Service.count(String,int):30-32",
                    "Service.log(String,Object...):34-36",
                    "Service.Inner.shout():41-43",
                ]
            ),
        )

    def test_overloads_have_distinct_ids(self):
        ids = [key for key in self.chunks if key.startswith("Service.count(")]
        self.assertEqual(len(ids), 2)

    def test_header_has_package_declaration_and_fields_but_no_imports(self):
        header = self.chunks["Service.count(String):26-28"].header
        self.assertTrue(header.startswith("package p;\n"))
        self.assertIn("@Component public class Service {", header)
        self.assertIn("private final Map<String, Integer> counts;", header)
        self.assertIn("private int limit = 10;", header)
        self.assertNotIn("import", header)

    def test_nested_type_header_names_the_enclosing_chain(self):
        chunk = self.chunks["Service.Inner.shout():41-43"]
        self.assertEqual(chunk.type_name, "Service.Inner")
        self.assertIn("@Component public class Service {", chunk.header)
        self.assertIn("static class Inner {", chunk.header)
        self.assertIn("private final String name;", chunk.header)
        # the outer type's fields are not repeated for the inner type
        self.assertNotIn("counts", chunk.header)

    def test_method_text_is_dedented_source(self):
        text = self.chunks["Service.count(String):26-28"].text
        self.assertEqual(
            text,
            "public int count(String key) {\n    return counts.getOrDefault(key, 0);\n}",
        )

    def test_ids_are_stable_across_runs(self):
        again = [c.id for c in chunk_java(PATH, SERVICE)]
        self.assertEqual(sorted(again), sorted(c.id for c in self.chunks.values()))


class RecordAndTypeChunksTest(unittest.TestCase):
    def test_record_without_methods_becomes_one_type_chunk(self):
        source = "package p;\n\npublic record Task(Long id, String title) {\n}\n"
        [chunk] = chunk_java("src/Task.java", source)
        self.assertEqual(chunk.kind, "type")
        self.assertEqual(chunk.id, "src/Task.java#Task:3-4")
        self.assertIn("public record Task(Long id, String title)", chunk.text)

    def test_validating_compact_constructor_is_kept(self):
        source = textwrap.dedent(
            """\
            package p;

            public record Range(int low, int high) {
                public Range {
                    if (low > high) throw new IllegalArgumentException();
                }
            }
            """
        )
        [chunk] = chunk_java("src/Range.java", source)
        self.assertEqual(chunk.kind, "method")
        self.assertEqual(chunk.id, "src/Range.java#Range.Range(compact):4-6")
        self.assertIn("public record Range(int low, int high) {", chunk.header)

    def test_class_with_only_trivial_methods_still_gets_a_type_chunk(self):
        source = "package p;\n\nclass Empty {\n    void noop() {}\n}\n"
        [chunk] = chunk_java("src/Empty.java", source)
        self.assertEqual(chunk.kind, "type")

    def test_syntax_error_falls_back_to_blocks(self):
        chunks = chunk_file("src/Broken.java", "package p;\n\nclass Broken {\n  void x( {\n}\n")
        self.assertTrue(chunks)
        self.assertTrue(all(chunk.kind == "block" for chunk in chunks))


class BlocksTest(unittest.TestCase):
    def test_paragraphs_are_packed_up_to_the_target(self):
        text = "a: 1\nb: 2\n\nc: 3\n\nd: 4\n"
        [block] = chunk_blocks("app.yml", text)
        self.assertEqual((block.start_line, block.end_line), (1, 6))
        self.assertEqual(block.id, "app.yml#L1-6")
        self.assertEqual(block.header, "")

    def test_large_paragraphs_start_new_blocks(self):
        big = "\n".join(f"<dep{i}>value</dep{i}>" for i in range(60))  # ~1,500 chars
        text = f"{big}\n\n{big}\n"
        blocks = chunk_blocks("pom.xml", text)
        self.assertEqual(len(blocks), 2)
        self.assertEqual(blocks[1].start_line, 62)


class SizeGuardTest(unittest.TestCase):
    def test_oversized_method_is_split_into_parts_that_each_fit_and_keep_the_header(self):
        body = "\n".join(f"        total += compute{i}(value, other, more);" for i in range(400))
        source = f"package p;\n\nclass Big {{\n    int sum(int value) {{\n{body}\n        return total;\n    }}\n}}\n"
        chunks = chunk_file("src/Big.java", source)

        self.assertGreater(len(chunks), 1)
        counts = {chunk.part[1] for chunk in chunks}
        self.assertEqual(counts, {len(chunks)})
        for index, chunk in enumerate(chunks, start=1):
            self.assertEqual(chunk.part[0], index)
            self.assertTrue(chunk.id.endswith(f"#part-{index}"))
            self.assertIn("class Big {", chunk.header)
            self.assertLessEqual(
                estimate_tokens(embedding_input(chunk.header, chunk.text)), SPLIT_THRESHOLD_TOKENS
            )
        # consecutive, non-overlapping line ranges covering the whole method
        self.assertEqual(chunks[0].start_line, 4)
        for previous, current in zip(chunks, chunks[1:]):
            self.assertEqual(current.start_line, previous.end_line + 1)
        self.assertEqual(chunks[-1].end_line, 406)

    def test_a_single_overlong_line_is_cut_by_characters(self):
        text = "x" * 20_000
        chunks = chunk_file("data.txt", text)
        self.assertGreater(len(chunks), 1)
        self.assertEqual(sum(len(chunk.text) for chunk in chunks), 20_000)
        for chunk in chunks:
            self.assertLessEqual(estimate_tokens(chunk.text), SPLIT_THRESHOLD_TOKENS)

    def test_to_index_uses_the_contract_field_names(self):
        [chunk] = chunk_java("src/Task.java", "package p;\n\nrecord Task(Long id) {\n}\n")
        entry = chunk.to_index([0.0] * 768)
        self.assertEqual(
            set(entry),
            {"id", "path", "kind", "symbol", "startLine", "endLine", "part", "header", "text", "vector"},
        )
        self.assertEqual(entry["symbol"], {"type": "Task", "method": None})
        self.assertIsNone(entry["part"])


if __name__ == "__main__":
    unittest.main()
