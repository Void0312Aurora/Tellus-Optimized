"""Compare nearest-shoreline distances under feature-area policies."""
from __future__ import annotations

import argparse
import csv
import json
import struct
from pathlib import Path

import numpy as np

from fit_orientation import unit
from gshhg_geometry import RADIUS_M, nearest_on_segments


HEADER = struct.Struct(">11i")
CATEGORIES = (
    "all_land",
    "land_area_ge_10km2",
    "continent_polygons",
)


def accepts(category: str, polygon_id: int, level: int, area_km2: float) -> bool:
    if category == "all_land":
        return level in (1, 5, 6)
    if category == "land_area_ge_10km2":
        return level in (5, 6) or (level == 1 and area_km2 >= 10.0)
    if category == "continent_polygons":
        # The level-1 continent records are the first records in the GSHHG set.
        # Antarctic ice contours (levels 5/6) are intentionally excluded here.
        return polygon_id <= 5 and level == 1
    raise ValueError(f"unknown category: {category}")


def load_vertices(candidate_path: Path, orientation_path: Path):
    with candidate_path.open(newline="", encoding="utf-8") as source:
        supplied = list(csv.DictReader(source))
    orientation = json.loads(orientation_path.read_text(encoding="utf-8"))
    fitted = orientation["vertices"]
    points = {
        "supplied": unit(
            np.array([float(row["latitude_deg"]) for row in supplied]),
            np.array([float(row["longitude_deg"]) for row in supplied]),
        ),
        "fitted": unit(
            np.array([float(row["fitted_latitude_deg"]) for row in fitted]),
            np.array([float(row["fitted_longitude_deg"]) for row in fitted]),
        ),
    }
    supplied_distance = {
        int(row["vertex"]): float(row["supplied_nearest_land_km"])
        for row in supplied
    }
    return points, supplied_distance


def study(source_path: Path, points: dict[str, np.ndarray]):
    best = {
        (variant, category): [None] * len(values)
        for variant, values in points.items()
        for category in CATEGORIES
    }
    with source_path.open("rb") as source:
        while header := source.read(HEADER.size):
            if len(header) != HEADER.size:
                raise ValueError("truncated GSHHG header")
            polygon_id, count, flags, west, east, south, north, area, area_full, container, ancestor = HEADER.unpack(header)
            level = flags & 255
            scale = 10.0 ** (flags >> 26)
            area_km2 = area / scale
            selected = [
                category
                for category in CATEGORIES
                if accepts(category, polygon_id, level, area_km2)
            ]
            if not selected:
                source.seek(count * 8, 1)
                continue
            coordinates = np.frombuffer(source.read(count * 8), dtype=">i4").reshape(-1, 2).astype(float) / 1e6
            if len(coordinates) < 3:
                raise ValueError(f"polygon {polygon_id} has too few points")
            vectors = unit(coordinates[:, 1], coordinates[:, 0])
            for variant, values in points.items():
                distances, indices, closest = nearest_on_segments(values, vectors[:-1], vectors[1:])
                for category in selected:
                    current = best[(variant, category)]
                    for index, distance in enumerate(distances):
                        candidate = (float(distance), polygon_id, level, area_km2)
                        if current[index] is None or candidate[0] < current[index][0]:
                            current[index] = candidate
    return best


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--orientation", type=Path, required=True)
    parser.add_argument("--candidates", type=Path, default=Path("tools/geography/candidate-vertices.csv"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    points, supplied_distance = load_vertices(args.candidates, args.orientation)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    rows = []
    for resolution in ("f", "h"):
        best = study(args.cache / f"gshhs_{resolution}.b", points)
        for variant in points:
            for category in CATEGORIES:
                for index, candidate in enumerate(best[(variant, category)], start=1):
                    if candidate is None:
                        raise ValueError(f"no shoreline found for {resolution}/{variant}/{category}/{index}")
                    distance_m, polygon_id, level, area_km2 = candidate
                    measured_km = distance_m * RADIUS_M / 1000.0
                    rows.append({
                        "resolution": resolution,
                        "coordinate_variant": variant,
                        "category": category,
                        "vertex": index,
                        "supplied_nearest_land_km": supplied_distance[index],
                        "measured_nearest_shore_km": measured_km,
                        "difference_from_supplied_km": measured_km - supplied_distance[index],
                        "gshhg_polygon_id": polygon_id,
                        "gshhg_level": level,
                        "polygon_area_km2": area_km2,
                    })
    fields = list(rows[0])
    with args.output.open("w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    print(f"Feature-threshold shoreline study saved to {args.output}")


if __name__ == "__main__":
    main()
