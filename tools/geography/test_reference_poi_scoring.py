"""Regression tests for record-level UNESCO point classification."""
from __future__ import annotations

import unittest
from types import SimpleNamespace

from shapely.geometry import box

from score_reference_pois import classify_record_points


class IdentityTransformer:
    def transform(self, longitude: float, latitude: float) -> tuple[float, float]:
        return longitude, latitude


class ReferencePoiScoringTest(unittest.TestCase):
    def test_excluded_component_takes_priority_over_earlier_transition(self) -> None:
        contexts = [{"forward": IdentityTransformer()}]
        regions = {
            (300, "excluded", 0): box(9, -1, 11, 1),
            (300, "quarantined", 0): box(-1, -1, 1, 1),
        }

        hit = classify_record_points(
            [(0, 0), (10, 0)], contexts, regions, cap_radius_km=300
        )

        self.assertEqual((0, "EXCLUDED", 10, 0), hit)


if __name__ == "__main__":
    unittest.main()
