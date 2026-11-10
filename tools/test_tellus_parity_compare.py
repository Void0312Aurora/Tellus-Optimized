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
                "--biome-baseline", "historical-v1",
            ])
            self.assertEqual(code, 0)
            self.assertEqual(result["status"], "PASS")
            self.assertEqual(result["columns"]["mismatches"], [])
            self.assertEqual(len(result["biome"]["mismatches"]), 1)

    def test_complete_block_digests_are_not_ignored(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            base = {"key": "a", "material": "STONE", "height": "80", "status": "AVAILABLE"}
            left = self._write(directory, "left.jsonl", json.dumps({**base, "blockSha256": "a" * 64}))
            right = self._write(directory, "right.jsonl", json.dumps({**base, "blockSha256": "b" * 64}))
            biome = self._write(directory, "biome.jsonl", '{"case":"bare/BWh","earth":"desert","cube":"stony_peaks"}')
            args = ["--columns-left", left, "--columns-right", right, "--biome", biome,
                    "--expected-columns", "1", "--expected-biome-cases", "1", "--require-block-digests",
                    "--biome-baseline", "historical-v1"]
            code, result = run(args)
            self.assertEqual((code, result["status"]), (1, "MISMATCH"))
            self.assertEqual(len(result["columns"]["mismatches"]), 1)
            Path(right).write_text(json.dumps({**base, "blockSha256": "a" * 64}), encoding="utf-8")
            self.assertEqual(run(args)[0], 0)
            Path(right).write_text(json.dumps(base), encoding="utf-8")
            self.assertEqual(run(args)[0], 2)

    def test_invalid_digest_and_one_sided_digest_fail(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            base = {"key": "a", "material": "STONE", "height": "80", "status": "AVAILABLE"}
            invalid = self._write(directory, "invalid.jsonl", json.dumps({**base, "blockSha256": "not-a-hash"}))
            with self.assertRaises(ManifestError):
                read_manifest(invalid, "columns")
            left = self._write(directory, "left.jsonl", json.dumps({**base, "blockSha256": "a" * 64}))
            right = self._write(directory, "right.jsonl", json.dumps(base))
            biome = self._write(directory, "biome.jsonl", '{"case":"bare/BWh","earth":"desert","cube":"stony_peaks"}')
            code, result = run(["--columns-left", left, "--columns-right", right, "--biome", biome,
                                "--expected-columns", "1", "--expected-biome-cases", "1",
                                "--biome-baseline", "historical-v1"])
            self.assertEqual((code, result["status"]), (1, "MISMATCH"))

    def test_strict_original_biome_case_domain_rejects_same_count_substitution(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            column = '{"key":"a","material":"STONE","height":"80","status":"AVAILABLE"}'
            left = self._write(directory, "left.jsonl", column)
            right = self._write(directory, "right.jsonl", column)
            cases = ["forest/Cfb", "grassland/Cfa", "cropland/Dfb", "bare/BWh", "snow/ET", "wetland/Cfa", "mangrove/Af"]
            rows = [{"case": case, "earth": "desert" if case == "bare/BWh" else "forest",
                     "cube": "stony_peaks" if case == "bare/BWh" else "forest"} for case in cases]
            biome = self._write(directory, "biome.jsonl", "\n".join(map(json.dumps, rows)))
            args = ["--columns-left", left, "--columns-right", right, "--biome", biome,
                    "--expected-columns", "1", "--require-original-biome-cases",
                    "--biome-baseline", "historical-v1"]
            self.assertEqual(run(args)[0], 0)
            rows[0]["case"] = "fabricated/AAA"
            Path(biome).write_text("\n".join(map(json.dumps, rows)), encoding="utf-8")
            code, result = run(args)
            self.assertEqual((code, result["status"]), (1, "MISMATCH"))
            self.assertFalse(result["checks"]["biomeCaseSet"])
            self.assertTrue(result["checks"]["biomeCount"])

    def test_current_seven_zero_and_explicit_historical_seven_one_are_distinct(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            column = '{"key":"a","material":"STONE","height":"80","status":"AVAILABLE"}'
            left = self._write(directory, "left.jsonl", column)
            right = self._write(directory, "right.jsonl", column)
            cases = ["forest/Cfb", "grassland/Cfa", "cropland/Dfb", "bare/BWh", "snow/ET", "wetland/Cfa", "mangrove/Af"]
            rows = [{"case": case, "earth": "desert" if case == "bare/BWh" else "forest",
                     "cube": "desert" if case == "bare/BWh" else "forest"} for case in cases]
            biome = self._write(directory, "biome.jsonl", "\n".join(map(json.dumps, rows)))
            args = ["--columns-left", left, "--columns-right", right, "--biome", biome,
                    "--expected-columns", "1", "--require-original-biome-cases"]
            code, result = run(args)
            self.assertEqual((code, result["biome"]["cases"], result["biome"]["mismatches"]), (0, 7, []))
            self.assertEqual(result["expected"]["biomeBaseline"], "travel-environment-v2")
            self.assertEqual(run(args + ["--biome-baseline", "historical-v1"])[0], 1)
            rows[3]["cube"] = "stony_peaks"
            Path(biome).write_text("\n".join(map(json.dumps, rows)), encoding="utf-8")
            self.assertEqual(run(args)[0], 1, "historical mismatch must not pass the new gate")
            self.assertEqual(run(args + ["--biome-baseline", "historical-v1"])[0], 0)
            self.assertEqual(run(args + ["--expected-biome-mismatches", "1"])[0], 0)
            rows[3]["case"] = "substituted/BWh"
            Path(biome).write_text("\n".join(map(json.dumps, rows)), encoding="utf-8")
            self.assertEqual(run(args + ["--biome-baseline", "historical-v1"])[0], 1)


if __name__ == "__main__":
    unittest.main()
