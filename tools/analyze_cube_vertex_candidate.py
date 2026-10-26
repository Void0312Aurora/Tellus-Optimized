"""Audit a proposed eight-vertex rotation for a cubed-sphere singular cap.

The built-in coordinates are transcribed from the current design proposal. The
script deliberately does not decide whether a cap is safe to remove; it only
checks whether the rounded points have the algebraic signature of a rotated
cube and reports the error introduced by rounding.
"""

from __future__ import annotations

import itertools
import math
import argparse


POINTS = (
    (2.6, -21.7),
    (-35.9, 41.9),
    (70.6, 6.8),
    (18.2, 89.8),
    (-18.2, -90.2),
    (-70.6, -173.2),
    (35.9, -138.1),
    (-2.6, 158.3),
)

NEAREST_LAND_KM = (1117.0, 1185.0, 360.0, 386.0, 1553.0, 580.0, 1293.0, 488.0)
EARTH_RADIUS_KM = 6371.0088


def unit(latitude: float, longitude: float) -> tuple[float, float, float]:
    lat = math.radians(latitude)
    lon = math.radians(longitude)
    return (
        math.cos(lat) * math.cos(lon),
        math.cos(lat) * math.sin(lon),
        math.sin(lat),
    )


def dot(first: tuple[float, float, float], second: tuple[float, float, float]) -> float:
    return sum(a * b for a, b in zip(first, second))


def angular_error_from_antipode(first, second) -> float:
    value = max(-1.0, min(1.0, -dot(first, second)))
    return math.degrees(math.acos(value))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--radii-km",
        nargs="*",
        type=float,
        default=(100.0, 250.0, 360.0, 500.0),
        help="cap radii to summarize; values are not safety decisions",
    )
    args = parser.parse_args()
    vectors = [unit(*point) for point in POINTS]
    antipodal_pairs = []
    for first, second in itertools.combinations(range(len(vectors)), 2):
        if dot(vectors[first], vectors[second]) < -0.9:
            antipodal_pairs.append((first + 1, second + 1))

    non_antipodal_errors = []
    for first, second in itertools.combinations(range(len(vectors)), 2):
        value = dot(vectors[first], vectors[second])
        if value > -0.9:
            non_antipodal_errors.append(abs(abs(value) - 1.0 / 3.0))

    print(f"antipodal_pairs={antipodal_pairs}")
    print(
        "max_antipodal_error_degrees="
        f"{max(angular_error_from_antipode(vectors[a - 1], vectors[b - 1]) for a, b in antipodal_pairs):.9g}"
    )
    print(f"max_non_antipodal_dot_error={max(non_antipodal_errors):.9g}")
    print(f"mean_non_antipodal_dot_error={sum(non_antipodal_errors) / len(non_antipodal_errors):.9g}")
    earth_area = 4.0 * math.pi * EARTH_RADIUS_KM * EARTH_RADIUS_KM
    for radius_km in args.radii_km:
        if not 0.0 <= radius_km < math.pi * EARTH_RADIUS_KM:
            raise SystemExit(f"invalid radius: {radius_km}")
        one_cap = 2.0 * math.pi * EARTH_RADIUS_KM**2 * (1.0 - math.cos(radius_km / EARTH_RADIUS_KM))
        eight_caps = 8.0 * one_cap
        margins = [distance - radius_km for distance in NEAREST_LAND_KM]
        print(
            f"radius_km={radius_km:g} eight_cap_area_km2={eight_caps:.3f}"
            f" earth_fraction={eight_caps / earth_area:.9f}"
            f" min_reported_land_margin_km={min(margins):.3f}"
        )


if __name__ == "__main__":
    main()
