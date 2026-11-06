#!/usr/bin/env python3
"""Create one single-Level cube-net dimension in a new datapack directory."""

from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path


IDENTIFIER = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")
MAPPING = "rancic-ccam30-double-v1"


def create_pack(output: Path, world: str, scale: float, sea: int, min_y: int, height: int,
                apron_chunks: int = 5, geodetic_biomes: bool = True,
                caves: bool = True, deep_dark: bool = False, underground_depth: int = 256,
                ore_veins: bool = True, geological_stone_patches: bool = True,
                caves_reach_surface: bool = False, roads: bool = True,
                buildings: bool = True, structures: bool = False,
                 lake_freezing: bool = True, lava_pools: bool = False, geodes: bool = False,
                 enable_water: bool = True, teleport_progress: bool = True, height_offset: int = 64,
                 add_strongholds: bool | None = None, add_villages: bool | None = None,
                 add_mineshafts: bool | None = None, add_ocean_monuments: bool | None = None,
                 add_woodland_mansions: bool | None = None, add_desert_temples: bool | None = None,
                 add_jungle_temples: bool | None = None, add_pillager_outposts: bool | None = None,
                 add_ruined_portals: bool | None = None, add_shipwrecks: bool | None = None,
                 add_ocean_ruins: bool | None = None, add_buried_treasure: bool | None = None,
                 add_igloos: bool | None = None, add_witch_huts: bool | None = None,
                 add_ancient_cities: bool | None = None, add_trial_chambers: bool | None = None,
                 add_trail_ruins: bool | None = None, add_abandoned_camps: bool | None = None,
                  spawn_latitude: float = 27.9881, spawn_longitude: float = 86.925) -> list[Path]:
    if not IDENTIFIER.fullmatch(world):
        raise ValueError("world must be a namespace:path identifier")
    namespace, name = world.split(":", 1)
    if any(part in ("", ".", "..") for part in name.split("/")):
        raise ValueError("world path must not contain empty, . or .. segments")
    if not math.isfinite(scale) or scale <= 0 or math.ceil(math.pi * 6_371_008.8 / (2 * scale)) > 2_147_483_647 - 1024:
        raise ValueError("world scale produces an unsupported face size")
    if min_y % 16 or height % 16 or height < 16 or min_y < -2032 or min_y + height > 2032:
        raise ValueError("vertical range must be section-aligned within [-2032, 2032)")
    if not min_y <= sea < min_y + height:
        raise ValueError("sea level must lie within the dimension height range")
    if not 0 <= apron_chunks <= 35:
        raise ValueError("apron width must be between 0 and 35 chunks")
    if not 64 <= underground_depth <= 512:
        raise ValueError("underground depth must be between 64 and 512")
    if not math.isfinite(spawn_latitude) or not -90.0 <= spawn_latitude <= 90.0:
        raise ValueError("spawn_latitude must be finite and within [-90, 90]")
    if not math.isfinite(spawn_longitude) or not -180.0 <= spawn_longitude <= 180.0:
        raise ValueError("spawn_longitude must be finite and within [-180, 180]")
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError("output must be a new or empty directory")

    dimension_type = {
        "ultrawarm": False,
        "natural": False,
        "coordinate_scale": 1.0,
        "has_skylight": True,
        "has_ceiling": False,
        "ambient_light": 0.0,
        "piglin_safe": False,
        "bed_works": True,
        "respawn_anchor_works": False,
        "has_raids": False,
        "logical_height": height,
        "min_y": min_y,
        "height": height,
        "infiniburn": "#minecraft:infiniburn_overworld",
        "effects": "minecraft:overworld",
        "monster_spawn_block_light_limit": 0,
        "monster_spawn_light_level": 0,
    }
    structure_values = {
        "add_strongholds": structures if add_strongholds is None else add_strongholds,
        "add_villages": structures if add_villages is None else add_villages,
        "add_mineshafts": structures if add_mineshafts is None else add_mineshafts,
        "add_ocean_monuments": structures if add_ocean_monuments is None else add_ocean_monuments,
        "add_woodland_mansions": structures if add_woodland_mansions is None else add_woodland_mansions,
        "add_desert_temples": structures if add_desert_temples is None else add_desert_temples,
        "add_jungle_temples": structures if add_jungle_temples is None else add_jungle_temples,
        "add_pillager_outposts": structures if add_pillager_outposts is None else add_pillager_outposts,
        "add_ruined_portals": structures if add_ruined_portals is None else add_ruined_portals,
        "add_shipwrecks": structures if add_shipwrecks is None else add_shipwrecks,
        "add_ocean_ruins": structures if add_ocean_ruins is None else add_ocean_ruins,
        "add_buried_treasure": structures if add_buried_treasure is None else add_buried_treasure,
        "add_igloos": structures if add_igloos is None else add_igloos,
        "add_witch_huts": structures if add_witch_huts is None else add_witch_huts,
        "add_ancient_cities": structures if add_ancient_cities is None else add_ancient_cities,
        "add_trial_chambers": structures if add_trial_chambers is None else add_trial_chambers,
        "add_trail_ruins": structures if add_trail_ruins is None else add_trail_ruins,
        "add_abandoned_camps": structures if add_abandoned_camps is None else add_abandoned_camps,
    }

    biome_source = {"biome": "minecraft:plains"}
    if geodetic_biomes:
        biome_source = {
            "world_scale": scale,
            "apron_chunks": apron_chunks,
            "excluded_radius_meters": 300000.0,
            "transition_width_meters": 50000.0,
            "fallback": {"biome": "minecraft:plains"},
            "settings": {
                "random_biomes": False,
                "random_biome_density": 0.12,
                "random_biome_seed": 0,
                "random_biome_ids": [],
                "cave_generation": caves,
                "deep_dark": deep_dark,
                "underground_depth": underground_depth,
                "ore_veins": ore_veins,
                "geological_stone_patches": geological_stone_patches,
                "caves_reach_surface": caves_reach_surface,
                "roads": roads,
                "buildings": buildings,
                "lake_freezing": lake_freezing,
                "lava_pools": lava_pools,
                "geodes": geodes,
                "enable_water": enable_water,
                "teleport_progress": teleport_progress,
                "height_offset": height_offset,
                "spawn_latitude": spawn_latitude,
                "spawn_longitude": spawn_longitude,
                **structure_values,
            },
        }
    generator = {
        "type": "tellus:cube_net",
        "geometry_version": 3,
        "mapping": MAPPING,
        "excluded_radius_meters": 300000.0,
        "transition_width_meters": 50000.0,
        "biome_source": biome_source,
        "world_scale": scale,
        "sea_level": sea,
        "min_y": min_y,
        "height": height,
        "experimental_apron_chunks": apron_chunks,
    }
    documents = {
        "pack.mcmeta": {
            "pack": {
                "pack_format": 15,
                "description": f"Tellus cube-net world {world}, scale {scale}",
            }
        },
        f"data/{namespace}/dimension_type/{name}_surface.json": dimension_type,
        f"data/{namespace}/dimension/{name}_cube_net.json": {
            "type": f"{world}_surface",
            "generator": generator,
        },
    }
    files = []
    for relative, document in documents.items():
        path = output / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
        files.append(path)
    return files


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True,
                        help="New world/datapacks directory; existing content is refused.")
    parser.add_argument("--world", default="tellus:earth",
                        help="Namespace and path used for the dimension (default: tellus:earth).")
    parser.add_argument("--scale", type=float, default=1.0)
    parser.add_argument("--sea-level", type=int, default=64)
    parser.add_argument("--min-y", type=int, default=-2032)
    parser.add_argument("--height", type=int, default=4064)
    parser.add_argument("--apron-chunks", type=int, default=5,
                        help="Native mirror band width, 0..35 chunks (default: 5, only for effective view "
                             "and simulation distance 2). For other distances choose "
                             "3 + max(min(client view, server view), server simulation distance).")
    parser.add_argument("--geodetic-biomes", action="store_true", dest="geodetic_biomes", default=True,
                        help="Use the Cube-Net GeoPoint biome source (the default).")
    parser.add_argument("--fixed-plains", action="store_false", dest="geodetic_biomes",
                        help="Use fixed plains for a minimal compatibility world.")
    parser.add_argument("--caves", action=argparse.BooleanOptionalAction, default=True,
                        help="Enable Cube-Net cave generation (default: true).")
    parser.add_argument("--deep-dark", action=argparse.BooleanOptionalAction, default=False,
                        help="Enable Deep Dark generation (default: false).")
    parser.add_argument("--underground-depth", type=int, default=256,
                        help="Underground depth, 64..512 blocks (default: 256).")
    parser.add_argument("--ore-veins", action=argparse.BooleanOptionalAction, default=True,
                        help="Enable Cube-Net ore veins (default: true).")
    parser.add_argument("--geological-stone-patches", action=argparse.BooleanOptionalAction, default=True,
                        help="Enable Cube-Net geological stone patches (default: true).")
    parser.add_argument("--caves-reach-surface", action=argparse.BooleanOptionalAction, default=False,
                        help="Allow Cube-Net caves to reach the surface (default: false).")
    parser.add_argument("--roads", action=argparse.BooleanOptionalAction, default=True,
                        help="Enable geodetic road features (default: true).")
    parser.add_argument("--buildings", action=argparse.BooleanOptionalAction, default=True,
                        help="Enable geodetic building features (default: true).")
    parser.add_argument("--lake-freezing", action=argparse.BooleanOptionalAction, default=True,
                        help="Enable freezing Tellus inland lakes (default: true).")
    parser.add_argument("--lava-pools", action=argparse.BooleanOptionalAction, default=False,
                        help="Enable vanilla lava pools (default: false).")
    parser.add_argument("--geodes", action=argparse.BooleanOptionalAction, default=False,
                        help="Enable vanilla geodes (default: false).")
    parser.add_argument("--enable-water", action=argparse.BooleanOptionalAction, default=True,
                        help="Write sampled water columns (default: true).")
    parser.add_argument("--teleport-progress", action=argparse.BooleanOptionalAction, default=True,
                        help="Show GeoTP progress (default: true).")
    parser.add_argument("--height-offset", type=int, default=64,
                        help="Cube-Net terrain baseline offset (default: 64).")
    parser.add_argument("--spawn-latitude", type=float, default=27.9881,
                        help="Initial spawn latitude in degrees (default: 27.9881).")
    parser.add_argument("--spawn-longitude", type=float, default=86.925,
                        help="Initial spawn longitude in degrees (default: 86.925).")
    structure_group = parser.add_mutually_exclusive_group()
    structure_group.add_argument("--structures", dest="structures", action="store_true",
                                help="Enable all Tellus structure sets.")
    structure_group.add_argument("--no-structures", dest="structures", action="store_false",
                                help="Disable all Tellus structure sets (the default).")
    parser.set_defaults(structures=False)
    for name in ("strongholds", "villages", "mineshafts", "ocean-monuments", "woodland-mansions",
                 "desert-temples", "jungle-temples", "pillager-outposts", "ruined-portals", "shipwrecks",
                 "ocean-ruins", "buried-treasure", "igloos", "witch-huts", "ancient-cities",
                 "trial-chambers", "trail-ruins", "abandoned-camps"):
        parser.add_argument(f"--add-{name}", f"--{name}",
                            action=argparse.BooleanOptionalAction, dest=f"add_{name.replace('-', '_')}", default=None,
                            help=f"Override the structure toggle add_{name.replace('-', '_')}.")
    args = parser.parse_args()
    try:
        files = create_pack(args.output, args.world, args.scale, args.sea_level, args.min_y, args.height,
                            args.apron_chunks, args.geodetic_biomes,
                            args.caves, args.deep_dark, args.underground_depth, args.ore_veins,
                            args.geological_stone_patches, args.caves_reach_surface,
                            args.roads, args.buildings, args.structures,
                             args.lake_freezing, args.lava_pools, args.geodes, args.enable_water,
                             args.teleport_progress, args.height_offset,
                             args.add_strongholds, args.add_villages, args.add_mineshafts,
                             args.add_ocean_monuments, args.add_woodland_mansions, args.add_desert_temples,
                             args.add_jungle_temples, args.add_pillager_outposts, args.add_ruined_portals,
                             args.add_shipwrecks, args.add_ocean_ruins, args.add_buried_treasure,
                             args.add_igloos, args.add_witch_huts, args.add_ancient_cities,
                             args.add_trial_chambers, args.add_trail_ruins, args.add_abandoned_camps,
                             args.spawn_latitude, args.spawn_longitude)
    except ValueError as error:
        parser.error(str(error))
    print(f"Created {len(files)} files for {args.world}: {args.output}")
    print("Restart the server, then use /execute in <namespace:path_cube_net> run ...")
    print("The server must contain exactly one cube-net dimension for seam routing.")
    print("Terrain outside the configured radial height range fails explicitly; scale 1 does not yet cover all elevations.")


if __name__ == "__main__":
    main()
