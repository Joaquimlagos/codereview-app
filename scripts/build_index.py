#!/usr/bin/env python3
"""Builds the RAG embedding index (index.json) consumed by codereview-lambda."""

import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

INDEX_VERSION = 1
EMBEDDING_MODEL = "gemini-embedding-001"
EMBEDDING_DIMENSIONS = 768
TASK_TYPE = "RETRIEVAL_DOCUMENT"
EMBED_URL = f"https://generativelanguage.googleapis.com/v1beta/models/{EMBEDDING_MODEL}:embedContent"

OUTPUT_FILE = Path("index.json")
INDEXED_DIRECTORIES = ("src",)
# Source and build definition only. Prose about what the repository is for
# adds no retrieval value for a code review and skews the context the
# reviewer receives, so README.md is deliberately left out.
INDEXED_FILES = ("pom.xml",)
EXCLUDED_DIRECTORIES = {"target", ".git"}

# The free tier meters embedding calls per minute; one call per second leaves
# a wide margin as the number of indexed files grows.
REQUEST_INTERVAL_SECONDS = 1.0


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


def embed(text: str, api_key: str, path: Path) -> list[float]:
    payload = json.dumps(
        {
            "model": f"models/{EMBEDDING_MODEL}",
            "content": {"parts": [{"text": text}]},
            "taskType": TASK_TYPE,
            "outputDimensionality": EMBEDDING_DIMENSIONS,
        }
    ).encode("utf-8")

    request = urllib.request.Request(
        EMBED_URL,
        data=payload,
        headers={"Content-Type": "application/json", "x-goog-api-key": api_key},
        method="POST",
    )

    try:
        with urllib.request.urlopen(request) as response:
            body = json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        sys.exit(f"Gemini API returned HTTP {error.code} for {path}: {detail}")
    except urllib.error.URLError as error:
        sys.exit(f"Could not reach the Gemini API for {path}: {error.reason}")

    vector = body.get("embedding", {}).get("values")
    if not vector:
        sys.exit(f"Gemini API response for {path} carries no embedding values: {body}")
    if len(vector) != EMBEDDING_DIMENSIONS:
        sys.exit(f"Expected {EMBEDDING_DIMENSIONS} dimensions for {path}, got {len(vector)}")
    return vector


def main() -> None:
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

    chunks = []
    for path in collect_files():
        text = read_indexable_text(path)
        if text is None:
            print(f"Skipping {path.as_posix()} (binary or empty)")
            continue
        if chunks:
            time.sleep(REQUEST_INTERVAL_SECONDS)
        print(f"Embedding {path.as_posix()} ({len(text)} chars)")
        chunks.append(
            {
                "path": path.as_posix(),
                "text": text,
                "vector": embed(text, api_key, path),
            }
        )

    if not chunks:
        sys.exit("No indexable files found — refusing to publish an empty index.")

    index = {
        "version": INDEX_VERSION,
        "branch": branch,
        "commit": commit,
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "model": EMBEDDING_MODEL,
        "dimensions": EMBEDDING_DIMENSIONS,
        "chunks": chunks,
    }

    OUTPUT_FILE.write_text(json.dumps(index, ensure_ascii=False), encoding="utf-8")
    print(f"Wrote {OUTPUT_FILE} with {len(chunks)} chunks")


if __name__ == "__main__":
    main()
