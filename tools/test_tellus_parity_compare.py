import json
import tempfile
import unittest
from pathlib import Path

from tellus_parity_compare import ManifestError, main, read_manifest, run


class ParityComparatorTest(unittest.TestCase):
    def _write(self, directory: Path, name: str, text: str) -> str:
        path = directory / name
        path.write_text(text, encoding="utf-8")
        return str(path)

    def test_jsonl_and_csv_parse(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            columns = self._write(directory, "columns.jsonl", '{"key":"a","material":"STONE","height":"80","status":"AVAILABLE"}\n')
            biome = self._write(directory, "biome.csv", "case,earth,cube\nbare/BWh,desert,stony_peaks\n")
            self.assertEqual(read_manifest(columns, "columns")[0]["key"], "a")
            self.assertEqual(read_manifest(biome, "biome")[0]["case"], "bare/BWh")

    def test_empty_manifest_fails_closed(self):
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "empty.jsonl"
            path.write_text("# intentionally empty\n", encoding="utf-8")
            with self.assertRaises(ManifestError):
                read_manifest(path, "columns")

    def test_missing_manifest_is_evidence_gap(self):
        code, result = run([
            "--columns-left", "missing-left.jsonl",
            "--columns-right", "missing-right.jsonl",
            "--biome", "missing-biome.jsonl",
        ])
        self.assertEqual(code, 2)
        self.assertEqual(result["status"], "EVIDENCE_GAP")

    def test_explicit_small_manifests_compare_without_sample_generation(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            rows = '{"key":"a","material":"STONE","height":"80","status":"AVAILABLE"}\n'
            left = self._write(directory, "left.jsonl", rows)
            right = self._write(directory, "right.jsonl", rows)
            biome = self._write(directory, "biome.jsonl", '{"case":"bare/BWh","earth":"desert","cube":"stony_peaks"}\n')
            code, result = run([
                "--columns-left", left,
                "--columns-right", right,
                "--biome", biome,
                "--expected-columns", "1",
                "--expected-biome-cases", "1",
            ])
            self.assertEqual(code, 0)
            self.assertEqual(result["status"], "PASS")
            self.assertEqual(result["columns"]["mismatches"], [])
            self.assertEqual(len(result["biome"]["mismatches"]), 1)


if __name__ == "__main__":
    unittest.main()
