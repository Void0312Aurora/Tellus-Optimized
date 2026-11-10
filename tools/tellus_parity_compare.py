#!/usr/bin/env python3
"""Fail-closed offline parity comparator.

The command consumes only explicit JSONL or CSV manifests. It never generates
samples, expands ranges, or supplies defaults for missing rows.

Column manifests require: key, material, height, status.
Biome manifest requires: case, earth, cube.
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path
from typing import Iterable


class ManifestError(ValueError):
    """A supplied manifest is absent, empty, malformed, or ambiguous."""


COLUMN_FIELDS = ("key", "material", "height", "status")
BIOME_FIELDS = ("case", "earth", "cube")
ORIGINAL_BIOME_CASES = frozenset(("forest/Cfb", "grassland/Cfa", "cropland/Dfb", "bare/BWh", "snow/ET", "wetland/Cfa", "mangrove/Af"))


def _clean(value: object, field: str, line: int) -> str:
    if value is None:
        raise ManifestError(f"line {line}: missing {field}")
    text = str(value).strip()
    if not text:
        raise ManifestError(f"line {line}: empty {field}")
    return text


def _validate_row(raw: dict[str, object], fields: tuple[str, ...], line: int) -> dict[str, str]:
    missing = [field for field in fields if field not in raw]
    if missing:
        raise ManifestError(f"line {line}: missing fields {','.join(missing)}")
    row = {field: _clean(raw[field], field, line) for field in fields}
    if fields == COLUMN_FIELDS and "blockSha256" in raw:
        digest = _clean(raw["blockSha256"], "blockSha256", line)
        if re.fullmatch(r"[0-9a-f]{64}", digest) is None:
            raise ManifestError(f"line {line}: invalid blockSha256")
        row["blockSha256"] = digest
    return row


def read_manifest(path: str | Path, kind: str) -> list[dict[str, str]]:
    """Read an explicit JSONL or CSV manifest; reject empty input."""
    file_path = Path(path)
    if not file_path.is_file():
        raise ManifestError(f"manifest not found: {file_path}")
    fields = COLUMN_FIELDS if kind == "columns" else BIOME_FIELDS
    text = file_path.read_text(encoding="utf-8")
    if not text.strip():
        raise ManifestError(f"manifest is empty: {file_path}")

    rows: list[dict[str, str]] = []
    if file_path.suffix.lower() == ".csv":
        reader = csv.DictReader(text.splitlines())
        if reader.fieldnames is None:
            raise ManifestError(f"CSV manifest has no header: {file_path}")
        for line, raw in enumerate(reader, start=2):
            if not any(value not in (None, "") for value in raw.values()):
                continue
            rows.append(_validate_row(raw, fields, line))
    else:
        for line, raw_text in enumerate(text.splitlines(), start=1):
            raw_text = raw_text.strip()
            if not raw_text or raw_text.startswith("#"):
                continue
            try:
                raw = json.loads(raw_text)
            except json.JSONDecodeError as error:
                raise ManifestError(f"line {line}: invalid JSONL: {error.msg}") from error
            if not isinstance(raw, dict):
                raise ManifestError(f"line {line}: JSONL row must be an object")
            rows.append(_validate_row(raw, fields, line))
    if not rows:
        raise ManifestError(f"manifest has no data rows: {file_path}")

    key_field = "key" if kind == "columns" else "case"
    seen: set[str] = set()
    for row in rows:
        key = row[key_field]
        if key in seen:
            raise ManifestError(f"duplicate {key_field}: {key}")
        seen.add(key)
        if kind == "columns":
            try:
                int(row["height"], 10)
            except ValueError as error:
                raise ManifestError(f"invalid integer height for {key}: {row['height']}") from error
    return rows


def _index(rows: Iterable[dict[str, str]], field: str) -> dict[str, dict[str, str]]:
    return {row[field]: row for row in rows}


def compare_columns(left: list[dict[str, str]], right: list[dict[str, str]]) -> dict[str, object]:
    """Compare canonical key plus material/height/status, without reordering semantics."""
    left_index = _index(left, "key")
    right_index = _index(right, "key")
    mismatches: list[dict[str, object]] = []
    for key in sorted(set(left_index) | set(right_index)):
        left_row = left_index.get(key)
        right_row = right_index.get(key)
        if left_row is None or right_row is None:
            mismatches.append({"key": key, "left": left_row, "right": right_row, "reason": "missing-row"})
        elif any(left_row.get(field) != right_row.get(field) for field in ("material", "height", "status", "blockSha256")):
            mismatches.append({"key": key, "left": left_row, "right": right_row, "reason": "value-mismatch"})
    return {"rows": len(left), "rightRows": len(right), "mismatches": mismatches}


def compare_biomes(rows: list[dict[str, str]]) -> dict[str, object]:
    """Compare Earth/Cube values contained in each explicit biome case row."""
    mismatches = [
        {"case": row["case"], "earth": row["earth"], "cube": row["cube"]}
        for row in rows
        if row["earth"] != row["cube"]
    ]
    return {"cases": len(rows), "mismatches": mismatches}


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--columns-left", required=True, help="explicit baseline JSONL/CSV manifest")
    parser.add_argument("--columns-right", required=True, help="explicit candidate JSONL/CSV manifest")
    parser.add_argument("--biome", required=True, help="explicit Earth/Cube biome JSONL/CSV manifest")
    parser.add_argument("--expected-columns", type=int, default=13_824)
    parser.add_argument("--expected-column-mismatches", type=int, default=0)
    parser.add_argument("--expected-biome-cases", type=int, default=7)
    parser.add_argument("--biome-baseline", choices=("travel-environment-v2", "historical-v1"),
                        default="travel-environment-v2",
                        help="current seven/zero gate; explicitly select historical-v1 to replay archived seven/one evidence")
    parser.add_argument("--expected-biome-mismatches", type=int, default=None,
                        help="explicit mismatch-count override (also supports legacy replay commands)")
    parser.add_argument("--known-biome-case", default="bare/BWh")
    parser.add_argument("--require-block-digests", action="store_true", help="require full-column SHA-256 on every native column row")
    parser.add_argument("--require-original-biome-cases", action="store_true", help="require the seven original case/climate identifiers")
    parser.add_argument("--json", action="store_true", help="emit machine-readable result")
    return parser.parse_args(argv)


def run(argv: list[str]) -> tuple[int, dict[str, object]]:
    args = _parse_args(argv)
    if args.expected_biome_mismatches is None:
        args.expected_biome_mismatches = 1 if args.biome_baseline == "historical-v1" else 0
    try:
        left = read_manifest(args.columns_left, "columns")
        right = read_manifest(args.columns_right, "columns")
        biome = read_manifest(args.biome, "biome")
        if args.require_block_digests and any("blockSha256" not in row for row in left + right):
            raise ManifestError("full-column blockSha256 is required on every column row")
    except ManifestError as error:
        return 2, {"status": "EVIDENCE_GAP", "error": str(error)}

    columns = compare_columns(left, right)
    biome_result = compare_biomes(biome)
    column_count_ok = columns["rows"] == args.expected_columns and columns["rightRows"] == args.expected_columns
    column_mismatch_ok = len(columns["mismatches"]) == args.expected_column_mismatches
    biome_count_ok = biome_result["cases"] == args.expected_biome_cases
    biome_case_set_ok = not args.require_original_biome_cases or {row["case"] for row in biome} == ORIGINAL_BIOME_CASES
    biome_mismatch_rows = biome_result["mismatches"]
    known_ok = (
        args.expected_biome_mismatches != 1
        or len(biome_mismatch_rows) == 1
        and biome_mismatch_rows[0]["case"] == args.known_biome_case
    )
    biome_mismatch_ok = len(biome_mismatch_rows) == args.expected_biome_mismatches and known_ok
    result = {
        "status": "PASS" if column_count_ok and column_mismatch_ok and biome_count_ok and biome_case_set_ok and biome_mismatch_ok else "MISMATCH",
        "columns": columns,
        "biome": biome_result,
        "expected": {
            "biomeBaseline": args.biome_baseline,
            "columns": args.expected_columns,
            "columnMismatches": args.expected_column_mismatches,
            "biomeCases": args.expected_biome_cases,
            "biomeMismatches": args.expected_biome_mismatches,
            "knownBiomeCase": args.known_biome_case,
            "originalBiomeCases": sorted(ORIGINAL_BIOME_CASES) if args.require_original_biome_cases else None,
        },
        "checks": {
            "columnCount": column_count_ok,
            "columnMismatches": column_mismatch_ok,
            "biomeCount": biome_count_ok,
            "biomeCaseSet": biome_case_set_ok,
            "biomeMismatches": biome_mismatch_ok,
        },
    }
    return (0 if result["status"] == "PASS" else 1), result


def main(argv: list[str] | None = None) -> int:
    code, result = run(sys.argv[1:] if argv is None else argv)
    if "status" in result and result["status"] == "PASS":
        print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    else:
        print(json.dumps(result, sort_keys=True, separators=(",", ":")), file=sys.stderr)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
