import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from create_cube_net_datapack import create_pack


class CubeNetTravelDatapackTest(unittest.TestCase):
    def test_release_metadata_and_real_setting_keys(self):
        for version, pack_format in (("1.20.1", 15), ("1.21.1", 48)):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as temp:
                root = Path(temp) / "pack"
                create_pack(root, "tellus:travel", 30, 64, -64, 384, custom_trees=False,
                            huge_red_mushrooms=True, climate_based_built_up_terrain=False,
                            minecraft_version=version)
                self.assertEqual(pack_format, json.loads((root / "pack.mcmeta").read_text())["pack"]["pack_format"])
                source = json.loads((root / "data/tellus/dimension/travel_cube_net.json").read_text())["generator"]["biome_source"]
                self.assertFalse(source["settings"]["custom_trees"])
                self.assertTrue(source["settings"]["huge_red_mushrooms"])
                self.assertFalse(source["settings"]["climate_based_built_up_terrain"])

    def test_defaults_and_existing_output_remain_intact(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "pack"
            files = create_pack(root, "tellus:travel", 30, 64, -64, 384)
            before = {path: path.read_bytes() for path in files}
            settings = json.loads(files[-1].read_text())["generator"]["biome_source"]["settings"]
            self.assertTrue(settings["custom_trees"])
            self.assertFalse(settings["huge_red_mushrooms"])
            self.assertFalse(settings["climate_based_built_up_terrain"])
            with self.assertRaisesRegex(ValueError, "new or empty"):
                create_pack(root, "tellus:travel", 30, 64, -64, 384)
            self.assertEqual(before, {path: path.read_bytes() for path in files})
            with self.assertRaisesRegex(ValueError, "minecraft_version"):
                create_pack(Path(temp) / "other", "tellus:travel", 30, 64, -64, 384, minecraft_version="future")

    def test_cli_routes_target_release_and_options(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "pack"
            result = subprocess.run([sys.executable, "-B", str(Path(__file__).with_name("create_cube_net_datapack.py")),
                                     "--output", str(root), "--world", "tellus:travel", "--scale", "30",
                                     "--minecraft-version", "1.21.1", "--no-custom-trees", "--huge-red-mushrooms",
                                     "--no-climate-based-built-up-terrain"], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(48, json.loads((root / "pack.mcmeta").read_text())["pack"]["pack_format"])
            settings = json.loads((root / "data/tellus/dimension/travel_cube_net.json").read_text())["generator"]["biome_source"]["settings"]
            self.assertEqual((False, True, False), (settings["custom_trees"], settings["huge_red_mushrooms"],
                                                    settings["climate_based_built_up_terrain"]))


if __name__ == "__main__":
    unittest.main()
