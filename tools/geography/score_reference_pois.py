"""Score reference high-value points against the exported quarantine boundaries.

This is an optional research follow-up to the deterministic geographic study.
It deliberately consumes a local UNESCO World Heritage CSV snapshot instead of
making a network request during scoring.  The boundary used for acceptance is
the exported whole-cell quarantine perimeter, not an ideal spherical cap.
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Iterable

from shapely.geometry import Point

from gshhg_geometry import equal_area_transformers
from run_coverage import projected_perimeters, write_csv


def parse_points(row: dict[str, str]) -> list[tuple[float, float]]:
    """Return ``(longitude, latitude)`` points from one UNESCO record."""
    points: list[tuple[float, float]] = []
    raw = row.get("geo_point_2d", "")
    if raw:
        try:
            latitude, longitude = (float(value.strip()) for value in raw.split(",", 1))
            points.append((longitude, latitude))
        except ValueError:
            pass
    try:
        components = json.loads(row.get("components_list") or "[]")
    except json.JSONDecodeError:
        components = []
    for component in components:
        try:
            points.append((float(component["longitude"]), float(component["latitude"])))
        except (KeyError, TypeError, ValueError):
            continue
    return points


def iter_records(path: Path) -> Iterable[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as source:
        yield from csv.DictReader(source, delimiter=";")


def validate_boundary_parameters(
    path: Path, cap_radius_km: int, transition_width_km: int
) -> None:
    document = json.loads(path.read_text(encoding="utf-8"))
    matching = [
        feature.get("properties", {})
        for feature in document.get("features", [])
        if feature.get("properties", {}).get("cap_radius_km") == cap_radius_km
    ]
    if not matching:
        raise ValueError(f"Boundary file has no cap_radius_km={cap_radius_km} features")
    widths = {item.get("transition_width_km") for item in matching}
    if widths != {transition_width_km}:
        raise ValueError(
            "Boundary transition width does not match the requested value: "
            f"file={sorted(widths)}, requested={transition_width_km}"
        )


def classify_record_points(
    points: list[tuple[float, float]], contexts: list[dict[str, object]],
    regions: dict[tuple[int, str, int], object], cap_radius_km: int,
) -> tuple[int, str, float, float] | None:
    """Choose a record's highest-severity hit across every point and vertex.

    Return ``(vertex_index, status, longitude, latitude)``. Iteration order is
    stable, so equal-severity hits retain the first point/vertex encountered.
    """
    best: tuple[int, str, float, float] | None = None
    priority = {"TRANSITION": 1, "EXCLUDED": 2}
    for vertex_index, context in enumerate(contexts):
        excluded = regions[(cap_radius_km, "excluded", vertex_index)]
        quarantined = regions[(cap_radius_km, "quarantined", vertex_index)]
        forward = context["forward"]
        for longitude, latitude in points:
            x, y = forward.transform(longitude, latitude)
            projected_point = Point(x, y)
            status = None
            if excluded.covers(projected_point):
                status = "EXCLUDED"
            elif quarantined.covers(projected_point):
                status = "TRANSITION"
            if status is not None and (best is None or priority[status] > priority[best[1]]):
                best = (vertex_index, status, longitude, latitude)
    return best


def score(output: Path, unesco_csv: Path, cap_radius_km: int, transition_width_km: int) -> None:
    metadata = json.loads((output / "candidate-cube-orientation.json").read_text(encoding="utf-8"))
    contexts = []
    for vertex in metadata["vertices"]:
        forward, inverse = equal_area_transformers(
            vertex["fitted_latitude_deg"], vertex["fitted_longitude_deg"]
        )
        contexts.append({"forward": forward, "inverse": inverse})

    boundary_path = output / "quarantine-boundaries-32.geojson"
    validate_boundary_parameters(boundary_path, cap_radius_km, transition_width_km)
    regions, _ = projected_perimeters(boundary_path, metadata, contexts)
    rows: list[dict[str, object]] = []
    record_count = 0
    for record in iter_records(unesco_csv):
        record_count += 1
        points = parse_points(record)
        if not points:
            continue
        hit = classify_record_points(points, contexts, regions, cap_radius_km)
        if hit is None:
            continue
        vertex_index, status, longitude, latitude = hit
        rows.append(
            {
                "vertex": vertex_index + 1,
                "status": status,
                "id_no": record.get("id_no", ""),
                "name_en": record.get("name_en", ""),
                "category": record.get("category", ""),
                "states_names": record.get("states_names", ""),
                "matched_longitude": longitude,
                "matched_latitude": latitude,
            }
        )

    fields = [
        "vertex",
        "status",
        "id_no",
        "name_en",
        "category",
        "states_names",
        "matched_longitude",
        "matched_latitude",
    ]
    write_csv(output / "unesco-poi-intersections.csv", fields, rows)
    summary = {
        "dataset": "UNESCO World Heritage List",
        "dataset_path": str(unesco_csv),
        "records_scanned": record_count,
        "intersections": len(rows),
        "cap_radius_km": cap_radius_km,
        "transition_width_km": transition_width_km,
        "boundary": "quarantine-boundaries-32.geojson",
        "interpretation": (
            "A hit is evidence that a registered World Heritage point or component "
            "lies inside the actual whole-cell quarantine boundary. It is not a "
            "complete tourism or gameplay-value census."
        ),
    }
    (output / "unesco-poi-summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"Scanned {record_count} UNESCO records; found {len(rows)} intersections", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("build/geography/results"))
    parser.add_argument("--unesco-csv", type=Path, required=True)
    parser.add_argument("--cap-radius-km", type=int, default=300)
    parser.add_argument("--transition-width-km", type=int, default=50)
    args = parser.parse_args()
    score(args.output, args.unesco_csv, args.cap_radius_km, args.transition_width_km)
