"""Compare cap-radius scenarios against a saved UNESCO reference snapshot."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from gshhg_geometry import equal_area_transformers
from run_coverage import projected_perimeters, write_csv
from score_reference_pois import (
    classify_record_points,
    iter_records,
    parse_points,
    validate_boundary_parameters,
)


DEFAULT_CAPS = (50, 100, 150, 175, 200, 250, 300, 360)


def evaluate(output: Path, unesco_csv: Path, caps: tuple[int, ...], transition_width_km: int) -> None:
    metadata = json.loads((output / "candidate-cube-orientation.json").read_text(encoding="utf-8"))
    contexts = []
    for vertex in metadata["vertices"]:
        forward, inverse = equal_area_transformers(
            vertex["fitted_latitude_deg"], vertex["fitted_longitude_deg"]
        )
        contexts.append({"forward": forward, "inverse": inverse})

    boundary_path = output / "quarantine-boundaries-32.geojson"
    for cap in caps:
        validate_boundary_parameters(boundary_path, cap, transition_width_km)
    regions, _ = projected_perimeters(boundary_path, metadata, contexts)
    records = list(iter_records(unesco_csv))

    summary: list[dict[str, int]] = []
    intersections: list[dict[str, object]] = []
    for cap in caps:
        hits: dict[str, dict[str, object]] = {}
        for record in records:
            record_id = record.get("id_no", "")
            hit = classify_record_points(parse_points(record), contexts, regions, cap)
            if hit is None:
                continue
            vertex_index, status, longitude, latitude = hit
            hits[record_id] = {
                "cap": cap,
                "vertex": vertex_index + 1,
                "status": status,
                "id_no": record_id,
                "name_en": record.get("name_en", ""),
                "category": record.get("category", ""),
                "states_names": record.get("states_names", ""),
                "matched_longitude": longitude,
                "matched_latitude": latitude,
            }
        excluded_count = sum(item["status"] == "EXCLUDED" for item in hits.values())
        transition_count = sum(item["status"] == "TRANSITION" for item in hits.values())
        summary.append(
            {
                "cap_radius_km": cap,
                "transition_width_km": transition_width_km,
                "unesco_records": len(records),
                "excluded_sites": excluded_count,
                "transition_sites": transition_count,
                "total_sites": len(hits),
            }
        )
        intersections.extend(hits.values())

    write_csv(
        output / "unesco-poi-scenario-summary.csv",
        list(summary[0].keys()),
        summary,
    )
    write_csv(
        output / "unesco-poi-scenario-intersections.csv",
        [
            "cap",
            "vertex",
            "status",
            "id_no",
            "name_en",
            "category",
            "states_names",
            "matched_longitude",
            "matched_latitude",
        ],
        intersections,
    )
    print(f"Compared {len(caps)} cap scenarios against {len(records)} UNESCO records")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("build/geography/results"))
    parser.add_argument("--unesco-csv", type=Path, required=True)
    parser.add_argument("--caps", type=int, nargs="+", default=DEFAULT_CAPS)
    parser.add_argument("--transition-width-km", type=int, default=50)
    args = parser.parse_args()
    evaluate(args.output, args.unesco_csv, tuple(args.caps), args.transition_width_km)
