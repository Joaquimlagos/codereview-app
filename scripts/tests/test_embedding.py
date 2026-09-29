"""Batch embedding and retry against a local stub of the Gemini API
(codereview-lambda specs/002-method-chunking, FR-007, FR-008; research R4, R5)."""

import json
import os
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

import build_index
from build_index import MAX_BATCH_SIZE, Embedder, EmbeddingFailed


class StubGemini:
    """A local HTTP server answering batchEmbedContents. `script` is a list of responses to
    give in order: an int status (error body), "slow" (no answer before the client times
    out), or "ok" (one 768-float vector per request, first value = running input index)."""

    def __init__(self, script):
        self.script = list(script)
        self.batches: list[int] = []
        self.inputs_seen = 0
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                step = stub.script.pop(0) if stub.script else "ok"
                if step == "slow":
                    threading.Event().wait(0.5)
                    return
                if isinstance(step, int):
                    body = {"error": {"code": step, "details": [{"retryDelay": "0.01s"}]}}
                    self.send_response(step)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(json.dumps(body).encode())
                    return
                n = len(payload["requests"])
                stub.batches.append(n)
                vectors = [
                    {"values": [float(stub.inputs_seen + i)] + [0.0] * 767} for i in range(n)
                ]
                stub.inputs_seen += n
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"embeddings": vectors}).encode())

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.base = f"http://127.0.0.1:{self.server.server_port}/v1beta"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def _embedder(stub: StubGemini) -> Embedder:
    return Embedder("test-key", stub.base, sleep=lambda seconds: None)


class BatchTest(unittest.TestCase):
    def test_inputs_are_sent_in_batches_of_at_most_100_and_order_is_kept(self):
        stub = StubGemini([])
        self.addCleanup(stub.close)
        embedder = _embedder(stub)

        vectors = embedder.embed([f"chunk {i}" for i in range(MAX_BATCH_SIZE + 30)])

        self.assertEqual(stub.batches, [MAX_BATCH_SIZE, 30])
        self.assertEqual([v[0] for v in vectors], [float(i) for i in range(MAX_BATCH_SIZE + 30)])
        self.assertEqual((embedder.calls, embedder.retries), (2, 0))


class RetryTest(unittest.TestCase):
    def test_429_then_503_then_success(self):
        stub = StubGemini([429, 503, "ok"])
        self.addCleanup(stub.close)
        embedder = _embedder(stub)

        vectors = embedder.embed(["a", "b"])

        self.assertEqual(len(vectors), 2)
        self.assertEqual((embedder.calls, embedder.retries), (3, 2))

    def test_timeout_is_retried(self):
        stub = StubGemini(["slow", "ok"])
        self.addCleanup(stub.close)
        embedder = _embedder(stub)

        with mock.patch.object(build_index, "REQUEST_TIMEOUT_SECONDS", 0.1):
            vectors = embedder.embed(["a"])

        self.assertEqual(len(vectors), 1)
        self.assertEqual(embedder.retries, 1)

    def test_retry_delay_comes_from_retry_info_when_present(self):
        stub = StubGemini([429, "ok"])
        self.addCleanup(stub.close)
        delays = []
        Embedder("k", stub.base, sleep=delays.append).embed(["a"])
        self.assertEqual(delays, [0.01])

    def test_a_non_retryable_status_fails_at_once(self):
        stub = StubGemini([400, "ok"])
        self.addCleanup(stub.close)
        embedder = _embedder(stub)

        with self.assertRaises(EmbeddingFailed):
            embedder.embed(["a"])
        self.assertEqual(embedder.calls, 1)

    def test_retries_run_out(self):
        stub = StubGemini([503] * 10)
        self.addCleanup(stub.close)
        embedder = _embedder(stub)

        with self.assertRaisesRegex(EmbeddingFailed, "Gave up after 5 attempts"):
            embedder.embed(["a"])
        self.assertEqual(embedder.calls, 5)

    def test_misaligned_response_is_rejected(self):
        with self.assertRaisesRegex(EmbeddingFailed, "1 embeddings for 2 inputs"):
            Embedder._vectors({"embeddings": [{"values": [0.0] * 768}]}, 2)


class BuildTest(unittest.TestCase):
    """main() end to end in a temporary repo: the index is written only on success."""

    def _run(self, script):
        stub = StubGemini(script)
        self.addCleanup(stub.close)
        workdir = tempfile.TemporaryDirectory()
        self.addCleanup(workdir.cleanup)
        root = Path(workdir.name)
        (root / "src").mkdir()
        (root / "src" / "Task.java").write_text("package p;\n\nrecord Task(Long id) {\n}\n")
        (root / "pom.xml").write_text("<project>\n</project>\n")
        env = {
            "GEMINI_API_KEY": "k",
            "GEMINI_API_BASE": stub.base,
            "GITHUB_SHA": "a" * 40,
            "GITHUB_REF_NAME": "develop",
        }
        cwd = os.getcwd()
        os.chdir(root)
        self.addCleanup(os.chdir, cwd)
        with (
            mock.patch.dict(os.environ, env),
            mock.patch("sys.argv", ["build_index.py"]),
            mock.patch.object(build_index.time, "sleep", lambda s: None),
            mock.patch("builtins.print"),
        ):
            try:
                build_index.main()
                return None, root / "index.json"
            except SystemExit as exit_:
                return exit_, root / "index.json"

    def test_success_writes_a_version_2_index(self):
        error, output = self._run([503, "ok"])
        self.assertIsNone(error)
        index = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(index["version"], 2)
        self.assertEqual(index["commit"], "a" * 40)
        self.assertEqual(
            sorted(c["id"] for c in index["chunks"]), ["pom.xml#L1-2", "src/Task.java#Task:3-4"]
        )
        for chunk in index["chunks"]:
            self.assertEqual(len(chunk["vector"]), 768)
            self.assertTrue(chunk["path"] and chunk["text"])

    def test_retries_running_out_writes_no_index(self):
        error, output = self._run([503] * 10)
        self.assertIsNotNone(error)
        self.assertIn("no index written", str(error.code))
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
