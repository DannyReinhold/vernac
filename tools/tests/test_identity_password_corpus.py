"""Corpus curation/release proofs use synthetic vectors and tracked resources only; no network."""
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("identity_password_corpus", Path(__file__).parents[1] / "identity_password_corpus.py")
corpus = importlib.util.module_from_spec(spec)
spec.loader.exec_module(corpus)
ROOT = Path(__file__).resolve().parents[2]


class IdentityPasswordCorpusTests(unittest.TestCase):
    def test_curation_obeys_scalar_nfc_length_and_line_rules_without_case_folding(self):
        first = "e\u0301" * 15
        data = (first + "\r\n" + "é" * 15 + "\n" + "😀" * 15 + "\nshort\n" + "x" * 129 + "\n" + "a\rb" + "\n" + " A preserved phrase \n").encode()
        result, stats = corpus.curate(data)
        self.assertEqual(sorted(["é" * 15, "😀" * 15, " A preserved phrase "], key=lambda v: v.encode()), result.decode().splitlines())
        self.assertEqual({"sourceLines": 7, "tooShort": 1, "tooLong": 1, "unrepresentable": 1, "duplicates": 1, "retained": 3}, stats)
        self.assertEqual(result, corpus.curate(data)[0])

    def test_wrong_upstream_or_invalid_utf8_is_rejected_without_echoing_data(self):
        with self.assertRaisesRegex(ValueError, "reviewed pin"):
            corpus.outputs(b"seeded-secret")
        with self.assertRaisesRegex(ValueError, "strict UTF-8") as failure:
            corpus.curate(b"seeded-secret\xc3\x28")
        self.assertNotIn("seeded-secret", str(failure.exception))

    def test_tracked_payload_manifest_and_provenance_are_consistent(self):
        base = ROOT / corpus.RESOURCE
        data = (base / "blocked-v1.txt").read_bytes()
        metadata = dict(line.split("=", 1) for line in (base / "manifest.properties").read_text().splitlines())
        review = json.loads((base / "curation.json").read_text())
        self.assertEqual(192161, len(data))
        self.assertEqual("8b53ba0f86311b36c33cf92dc6b123367f6590bc80fe3d0b37a79577630c964d", corpus.digest(data))
        self.assertEqual(corpus.digest(data), metadata["sha256"])
        self.assertEqual(corpus.UPSTREAM_COMMIT, review["upstreamCommit"])
        self.assertEqual(corpus.UPSTREAM_SHA256, review["upstreamSha256"])
        self.assertEqual(1000000, review["sourceLines"])
        self.assertEqual(10908, review["retained"])
        self.assertEqual(989092, review["tooShort"])
        self.assertEqual(10908, int(metadata["entries"]))
        values = data.decode("utf-8").split("\n")[:-1]
        self.assertEqual(len(values), len(set(values)))
        self.assertTrue(all(15 <= len(v) <= 128 for v in values))
        self.assertEqual(sorted(v.encode() for v in values), [v.encode() for v in values])
        self.assertNotIn(b"\r", data)
        self.assertEqual(corpus.LICENSE_SHA256, hashlib.sha256((ROOT / corpus.LICENSE).read_bytes()).hexdigest())
        attributes = (ROOT / ".gitattributes").read_text()
        self.assertIn("passwords/* text eol=lf", attributes)
