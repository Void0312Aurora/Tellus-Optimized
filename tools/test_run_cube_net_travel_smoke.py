import json
from pathlib import Path
import tempfile
import unittest

from create_cube_net_datapack import create_pack
from run_cube_net_travel_smoke import configure_disposable, TRAVEL


class TravelSmokeAdmissionTest(unittest.TestCase):
    def test_only_explicit_disposable_definition_is_configured(self):
        for module, pack_format in (("mc1201", 15), ("mc1211", 48)):
            with self.subTest(module=module), tempfile.TemporaryDirectory() as temporary:
                world = Path(temporary) / "world"
                pack = world / "datapacks/tellus"
                files = create_pack(pack, "tellus:travel", 30, 64, -64, 384, geodetic_biomes=False)
                dimension_type = files[1].read_bytes()
                report = configure_disposable(world, "tellus:travel_cube_net", module)
                self.assertEqual(TRAVEL, report["configuredGenerator"]["biome_source"]["settings"])
                self.assertEqual(dimension_type, files[1].read_bytes())
                self.assertEqual(pack_format, json.loads(files[0].read_text())["pack"]["pack_format"])
                self.assertNotEqual(report["beforeSHA256"], report["configuredSHA256"])
                with self.assertRaisesRegex(ValueError, "fixed-biome"):
                    configure_disposable(world, "tellus:travel_cube_net", module)

    def test_missing_definition_is_not_silently_created(self):
        with tempfile.TemporaryDirectory() as temporary:
            world = Path(temporary)
            with self.assertRaisesRegex(ValueError, "exactly one"):
                configure_disposable(world, "tellus:missing", "mc1201")
            self.assertEqual([], list(world.iterdir()))


if __name__ == "__main__":
    unittest.main()
