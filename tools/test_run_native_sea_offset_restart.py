"""Copied-level preparation contracts; actual restart acceptance is a separate native gate."""
import gzip
import json
from pathlib import Path
import struct
import tempfile
import unittest
import run_native_sea_offset_restart as offset


def compound(name, fields):
    return offset.tag(10,name,b''.join(fields)+b'\0')


def level_record():
    gen=compound('generator',[
        offset.tag(8,'type',offset.text('tellus:cube_net')),
        offset.tag(8,'mapping',offset.text('rancic-ccam30-double-v1')),
        offset.tag(3,'geometry_version',struct.pack('>i',3)),
        offset.tag(6,'world_scale',struct.pack('>d',1.0)),
        offset.tag(3,'sea_level',struct.pack('>i',64)),
        compound('biome_source',[offset.tag(8,'biome',offset.text('minecraft:plains'))]),
        offset.tag(3,'min_y',struct.pack('>i',-2032)),offset.tag(3,'height',struct.pack('>i',4064))])
    dimensions=compound('dimensions',[compound('tellus:earth_cube_net',[gen])])
    return b'\x0a\x00\x00'+compound('Data',[compound('WorldGenSettings',[dimensions]),offset.tag(4,'unchanged',struct.pack('>q',123456789))])+b'\0'


class CopiedLevelContracts(unittest.TestCase):
    def test_authoritative_dimension_pack_configuration_retains_geometry_and_leaves_level_bytes_untouched(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);pack=root/'datapacks/cube/data/tellus/dimension/earth_cube_net.json';pack.parent.mkdir(parents=True)
            (root/'level.dat').write_bytes(b'unchanged historical native level')
            generator={'type':'tellus:cube_net','world_scale':1.0,'sea_level':64,'experimental_apron_chunks':2,
                       'excluded_radius_meters':300000.0,'transition_width_meters':50000.0,'biome_source':{'biome':'minecraft:plains'}}
            pack.write_text(json.dumps({'type':'tellus:earth_surface','generator':generator}),encoding='utf-8')
            report=offset.configure_bootstrap_copy(root,'tellus:earth_cube_net',76,76)
            self.assertEqual(report['authority'],'copied-datapack-first-process-only')
            self.assertEqual((root/'level.dat').read_bytes(),b'unchanged historical native level')
            actual=json.loads(pack.read_text())['generator'];self.assertEqual(actual['sea_level'],76)
            self.assertEqual(actual['biome_source']['apron_chunks'],2)
            self.assertEqual(actual['biome_source']['excluded_radius_meters'],300000.0)
            self.assertEqual(actual['biome_source']['transition_width_meters'],50000.0)
            self.assertEqual({**actual,'biome_source':generator['biome_source'],'sea_level':64},generator)

    def test_only_native_generator_fields_change_and_existing_scalar_types_stay_exact(self):
        for height,sea in [(52,64),(76,64),(76,76)]:
            with self.subTest(height=height,sea=sea), tempfile.TemporaryDirectory() as directory:
                root=Path(directory); (root/'level.dat').write_bytes(gzip.compress(level_record()))
                report=offset.configure_copy(root,'tellus:earth_cube_net',height,sea)
                self.assertTrue(report['onlyTwoExistingFieldsChanged'])
                actual=offset.generator_record(root,'tellus:earth_cube_net')
                self.assertEqual(actual['sea_level'],sea)
                self.assertEqual(actual['biome_source']['settings']['height_offset'],height)
                self.assertEqual(actual['biome_source']['apron_chunks'],5)
                self.assertEqual(actual['biome_source']['fallback'],{'biome':'minecraft:plains'})
                self.assertEqual(actual['min_y'],-2032);self.assertEqual(actual['height'],4064)

    def test_default_offset_and_already_configured_source_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'level.dat').write_bytes(gzip.compress(level_record()))
            with self.assertRaises(ValueError):offset.configure_copy(root,'tellus:earth_cube_net',64,64)
            offset.configure_copy(root,'tellus:earth_cube_net',76,76)
            with self.assertRaises(ValueError):offset.configure_copy(root,'tellus:earth_cube_net',76,76)

    def test_truncation_duplicate_keys_and_trailing_bytes_fail_before_configuration(self):
        duplicate=b'\x0a\x00\x00'+offset.tag(3,'same',struct.pack('>i',1))*2+b'\0'
        for raw in [b'',level_record()[:-1],level_record()+b'junk',duplicate]:
            with self.subTest(raw=raw[:10]),self.assertRaises((ValueError,struct.error)):
                offset.field_ranges(raw)


if __name__=='__main__':unittest.main()
