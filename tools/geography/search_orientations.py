"""Search small rigid-orientation perturbations against local GSHHG coastlines."""
from __future__ import annotations

import argparse
import csv
import json
import struct
from pathlib import Path

import numpy as np

from fit_orientation import RADIUS_M, angular_distance, latlon, unit
from gshhg_geometry import local_window, nearest_on_segments


HEADER = struct.Struct(">11i")
CATEGORIES = ("all_land", "land_area_ge_10km2")


def rotation(axis: int, angle_rad: float) -> np.ndarray:
    c, s = np.cos(angle_rad), np.sin(angle_rad)
    result = np.eye(3)
    other = [index for index in range(3) if index != axis]
    a, b = other
    result[a, a] = c
    result[b, b] = c
    result[a, b] = -s
    result[b, a] = s
    return result


def polygon_area_km2(area: int, flags: int) -> float:
    return area / (10.0 ** (flags >> 26))


def include_polygon(category: str, polygon_id: int, level: int, area_km2: float) -> bool:
    if category == "all_land":
        return level in (1, 5, 6)
    if category == "land_area_ge_10km2":
        return level in (5, 6) or (level == 1 and area_km2 >= 10.0)
    raise ValueError(category)


def load_base(orientation_path: Path, candidate_path: Path):
    metadata = json.loads(orientation_path.read_text(encoding="utf-8"))
    rotation_matrix = np.array(metadata["rotation_matrix"], dtype=float)
    canonical = np.array([vertex["canonical_signs"] for vertex in metadata["vertices"]], dtype=float) / np.sqrt(3.0)
    base_points = canonical @ rotation_matrix.T
    with candidate_path.open(newline="", encoding="utf-8") as source:
        candidates = list(csv.DictReader(source))
    input_points = unit(
        np.array([float(row["latitude_deg"]) for row in candidates]),
        np.array([float(row["longitude_deg"]) for row in candidates]),
    )
    return base_points, input_points


def local_segments(source_path: Path, base_points: np.ndarray, radius_km: float):
    windows = []
    for point in base_points:
        latitude, longitude = latlon(point)
        windows.append(local_window(float(latitude), float(longitude), radius_km * 1000.0))
    segments = {
        category: [[] for _ in range(len(base_points))]
        for category in CATEGORIES
    }
    selected_polygons = [set() for _ in range(len(base_points))]
    with source_path.open("rb") as source:
        while header := source.read(HEADER.size):
            if len(header) != HEADER.size:
                raise ValueError("truncated GSHHG header")
            polygon_id, count, flags, west, east, south, north, area, area_full, container, ancestor = HEADER.unpack(header)
            level = flags & 255
            area_km2 = polygon_area_km2(area, flags)
            relevant = [
                category for category in CATEGORIES
                if include_polygon(category, polygon_id, level, area_km2)
            ]
            if not relevant:
                source.seek(count * 8, 1)
                continue
            west_deg, east_deg = west / 1e6, east / 1e6
            south_deg, north_deg = south / 1e6, north / 1e6
            coordinates = None
            for index, window in enumerate(windows):
                xmin, ymin, xmax, ymax = window.bounds
                if north_deg < ymin or south_deg > ymax:
                    continue
                if not any(
                    (west_deg + 360 * shift <= xmax and east_deg + 360 * shift >= xmin)
                    for shift in (-1, 0, 1)
                ):
                    continue
                if coordinates is None:
                    coordinates = np.frombuffer(source.read(count * 8), dtype=">i4").reshape(-1, 2).astype(float) / 1e6
                selected_polygons[index].add(polygon_id)
                vectors = unit(coordinates[:, 1], coordinates[:, 0])
                polygon_segments = np.stack((vectors[:-1], vectors[1:]), axis=1)
                for category in relevant:
                    segments[category][index].append(polygon_segments)
            if coordinates is None:
                source.seek(count * 8, 1)
    result = {}
    for category in CATEGORIES:
        result[category] = []
        for index, pieces in enumerate(segments[category]):
            if not pieces:
                raise ValueError(f"no local shoreline segments for vertex {index + 1}/{category}")
            result[category].append(np.concatenate(pieces, axis=0))
    return result, selected_polygons


def nearest_segment_pools(local: dict[str, list[np.ndarray]], base_points: np.ndarray, pool_size: int):
    pools = {}
    for category in CATEGORIES:
        pools[category] = []
        for index, segments in enumerate(local[category]):
            midpoints = segments[:, 0] + segments[:, 1]
            scores = midpoints @ base_points[index]
            count = min(pool_size, len(scores))
            indices = np.argpartition(scores, -count)[-count:]
            pools[category].append(segments[indices])
    return pools


def evaluate(points: np.ndarray, input_points: np.ndarray, local: dict[str, list[np.ndarray]]):
    values = {}
    for category in CATEGORIES:
        distances = []
        for index, point in enumerate(points):
            distance, _, _ = nearest_on_segments(point[None, :], local[category][index][:, 0], local[category][index][:, 1])
            distances.append(float(distance[0] * RADIUS_M / 1000.0))
        values[f"min_{category}_km"] = min(distances)
        values[f"max_{category}_km"] = max(distances)
        values[f"vertex_{category}_km"] = distances
    residuals = angular_distance(input_points, points) * RADIUS_M / 1000.0
    point_latitude, point_longitude = latlon(points)
    input_latitude, input_longitude = latlon(input_points)
    longitude_error = (point_longitude - input_longitude + 180.0) % 360.0 - 180.0
    latitude_error = point_latitude - input_latitude
    values["rms_candidate_residual_km"] = float(np.sqrt(np.mean(residuals ** 2)))
    values["max_candidate_residual_km"] = float(np.max(residuals))
    values["vertex_residual_km"] = residuals.tolist()
    values["max_latitude_error_deg"] = float(np.max(np.abs(latitude_error)))
    values["max_longitude_error_deg"] = float(np.max(np.abs(longitude_error)))
    values["within_input_rounding_cells"] = bool(
        np.all(np.abs(latitude_error) <= 0.05 + 1e-12)
        and np.all(np.abs(longitude_error) <= 0.05 + 1e-12)
    )
    return values


def points_for_row(row: dict[str, str], base_points: np.ndarray) -> np.ndarray:
    perturb = rotation(2, np.deg2rad(float(row["yaw_deg"]))) @ rotation(1, np.deg2rad(float(row["pitch_deg"]))) @ rotation(0, np.deg2rad(float(row["roll_deg"])))
    return base_points @ perturb.T


def exact_scan(source_path: Path, points: np.ndarray):
    best = {category: np.full(len(points), np.inf) for category in CATEGORIES}
    with source_path.open("rb") as source:
        while header := source.read(HEADER.size):
            if len(header) != HEADER.size:
                raise ValueError("truncated GSHHG header")
            polygon_id, count, flags, west, east, south, north, area, area_full, container, ancestor = HEADER.unpack(header)
            level = flags & 255
            area_km2 = polygon_area_km2(area, flags)
            relevant = [
                category for category in CATEGORIES
                if include_polygon(category, polygon_id, level, area_km2)
            ]
            if not relevant:
                source.seek(count * 8, 1)
                continue
            coordinates = np.frombuffer(source.read(count * 8), dtype=">i4").reshape(-1, 2).astype(float) / 1e6
            vectors = unit(coordinates[:, 1], coordinates[:, 0])
            for offset in range(0, len(vectors) - 1, 20000):
                a = vectors[offset:offset + 20000]
                b = vectors[offset + 1:offset + 1 + len(a)]
                a = a[:len(b)]
                if len(a) == 0:
                    continue
                distances, _, _ = nearest_on_segments(points, a, b)
                for category in relevant:
                    best[category] = np.minimum(best[category], distances)
    return best


def select_verification_rows(rows: list[dict[str, float | str]]):
    selected = []
    keys = set()
    def add(row):
        key = (row["roll_deg"], row["pitch_deg"], row["yaw_deg"])
        if key not in keys:
            keys.add(key)
            selected.append(row)
    for row in rows:
        if all(float(row[field]) == 0.0 for field in ("roll_deg", "pitch_deg", "yaw_deg")):
            add(row)
            break
    for row in rows:
        if row.get("within_input_rounding_cells") is True:
            add(row)
    for budget in (15.0, 25.0, 40.0):
        for row in rows:
            if float(row["max_candidate_residual_km"]) <= budget:
                add(row)
                break
    for row in rows[:5]:
        add(row)
    return selected


def write_exact_verification(path: Path, rows, base_points: np.ndarray, cache: Path):
    selected = select_verification_rows(rows)
    points = np.concatenate([points_for_row(row, base_points) for row in selected], axis=0)
    exact = exact_scan(cache / "gshhs_f.b", points)
    output_rows = []
    for candidate_index, row in enumerate(selected):
        item = dict(row)
        for category in CATEGORIES:
            distances = exact[category][candidate_index * 8:(candidate_index + 1) * 8] * RADIUS_M / 1000.0
            item[f"exact_min_{category}_km"] = float(np.min(distances))
            for vertex, distance in enumerate(distances, start=1):
                item[f"exact_{category}_v{vertex}_km"] = float(distance)
        output_rows.append(item)
    fields = list(output_rows[0])
    with path.open("w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=fields)
        writer.writeheader()
        writer.writerows(output_rows)
    print(f"Exact full-resolution verification saved to {path}: {len(output_rows)}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--orientation", type=Path, required=True)
    parser.add_argument("--candidates", type=Path, default=Path("tools/geography/candidate-vertices.csv"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--max-delta-deg", type=float, default=0.25)
    parser.add_argument("--step-deg", type=float, default=0.125)
    parser.add_argument("--local-radius-km", type=float, default=1000.0)
    parser.add_argument("--pool-size", type=int, default=10000)
    parser.add_argument("--verification-output", type=Path)
    args = parser.parse_args()
    if args.max_delta_deg < 0 or args.step_deg <= 0 or args.local_radius_km <= 0:
        raise ValueError("search bounds must be positive")

    base_points, input_points = load_base(args.orientation, args.candidates)
    local, selected = local_segments(args.cache / "gshhs_f.b", base_points, args.local_radius_km)
    print("Local shoreline segment counts: " + ", ".join(
        f"{category}=" + "/".join(str(len(local[category][i])) for i in range(8))
        for category in CATEGORIES
    ), flush=True)
    print("Local polygon counts: " + "/".join(str(len(ids)) for ids in selected), flush=True)
    if args.pool_size <= 0:
        raise ValueError("pool size must be positive")
    local = nearest_segment_pools(local, base_points, args.pool_size)
    print("Nearest segment pool counts: " + ", ".join(
        f"{category}=" + "/".join(str(len(local[category][i])) for i in range(8))
        for category in CATEGORIES
    ), flush=True)

    values = np.arange(-args.max_delta_deg, args.max_delta_deg + args.step_deg * 0.5, args.step_deg)
    rows = []
    base_rotation = np.array(json.loads(args.orientation.read_text(encoding="utf-8"))["rotation_matrix"], dtype=float)
    for roll in values:
        for pitch in values:
            for yaw in values:
                perturb = rotation(2, np.deg2rad(yaw)) @ rotation(1, np.deg2rad(pitch)) @ rotation(0, np.deg2rad(roll))
                points = base_points @ perturb.T
                metrics = evaluate(points, input_points, local)
                rows.append({
                    "roll_deg": roll,
                    "pitch_deg": pitch,
                    "yaw_deg": yaw,
                    "min_all_land_km": metrics["min_all_land_km"],
                    "min_land_area_ge_10km2_km": metrics["min_land_area_ge_10km2_km"],
                    "max_all_land_km": metrics["max_all_land_km"],
                    "max_land_area_ge_10km2_km": metrics["max_land_area_ge_10km2_km"],
                    "rms_candidate_residual_km": metrics["rms_candidate_residual_km"],
                    "max_candidate_residual_km": metrics["max_candidate_residual_km"],
                    "max_latitude_error_deg": metrics["max_latitude_error_deg"],
                    "max_longitude_error_deg": metrics["max_longitude_error_deg"],
                    "within_input_rounding_cells": metrics["within_input_rounding_cells"],
                    **{f"all_land_v{i + 1}_km": value for i, value in enumerate(metrics["vertex_all_land_km"])},
                    **{f"land_area_ge_10km2_v{i + 1}_km": value for i, value in enumerate(metrics["vertex_land_area_ge_10km2_km"])},
                })
    rows.sort(key=lambda row: (
        -float(row["min_land_area_ge_10km2_km"]),
        float(row["max_candidate_residual_km"]),
        -float(row["min_all_land_km"]),
    ))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fields = list(rows[0])
    with args.output.open("w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    print(f"Orientation candidates saved to {args.output}: {len(rows)}", flush=True)
    if args.verification_output:
        args.verification_output.parent.mkdir(parents=True, exist_ok=True)
        write_exact_verification(args.verification_output, rows, base_points, args.cache)


if __name__ == "__main__":
    main()
