#!/usr/bin/env python3
"""Create six explicitly grouped spherical dimensions in a new datapack directory."""

from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path


def create_pack(output: Path, planet: str, scale: float, sea: int, min_y: int, height: int) -> list[Path]:
    if not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", planet):
        raise ValueError("planet must be a namespace:path identifier")
    namespace, name = planet.split(":", 1)
    if any(part in ("", ".", "..") for part in name.split("/")):
        raise ValueError("planet path must not contain empty, . or .. segments")
    if not math.isfinite(scale) or scale <= 0 or math.ceil(math.pi * 6_371_008.8 / (2 * scale)) > 2_147_483_647 - 1024:
        raise ValueError("world scale produces an unsupported face size")
    if min_y % 16 or height % 16 or height < 16 or min_y < -2032 or min_y + height > 2032:
        raise ValueError("vertical range must be section-aligned within [-2032, 2032)")
    if not min_y <= sea < min_y + height:
        raise ValueError("sea level must lie within the dimension height range")
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError("output must be a new or empty directory")

    dimension_type = {
        "ultrawarm": False, "natural": False, "coordinate_scale": 1.0,
        "has_skylight": True, "has_ceiling": False, "ambient_light": 0.0,
        "piglin_safe": False, "bed_works": True, "respawn_anchor_works": False,
        "has_raids": False, "logical_height": height, "min_y": min_y, "height": height,
        "infiniburn": "#minecraft:infiniburn_overworld", "effects": "minecraft:overworld",
        "monster_spawn_block_light_limit": 0, "monster_spawn_light_level": 0,
    }
    documents = {
        "pack.mcmeta": {"pack": {"pack_format": 15, "description": f"Tellus spherical planet {planet}, scale {scale}"}},
        f"data/{namespace}/dimension_type/{name}_surface.json": dimension_type,
    }
    for face in range(6):
        documents[f"data/{namespace}/dimension/{name}_face_{face}.json"] = {
            "type": f"{planet}_surface",
            "generator": {
                "type": "tellus:sphere_face", "geometry_version": 2,
                "mapping": "rancic-ccam30-double-v1",
                "excluded_radius_meters": 300000.0, "transition_width_meters": 50000.0,
                "biome_source": {"biome": "minecraft:plains"}, "planet": planet,
                "face": face, "world_scale": scale, "sea_level": sea, "min_y": min_y, "height": height,
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
    parser.add_argument("--output", type=Path, required=True, help="New world/datapacks directory; existing content is refused.")
    parser.add_argument("--planet", default="tellus:earth")
    parser.add_argument("--scale", type=float, default=1.0)
    parser.add_argument("--sea-level", type=int, default=64)
    parser.add_argument("--min-y", type=int, default=-2032)
    parser.add_argument("--height", type=int, default=4064)
    args = parser.parse_args()
    try:
        files = create_pack(args.output, args.planet, args.scale, args.sea_level, args.min_y, args.height)
    except ValueError as error:
        parser.error(str(error))
    print(f"Created {len(files)} files for {args.planet}: {args.output}")
    print("Available after server restart: /tellus sphere status")
    print(f"Enter: /tellus sphere enter {args.planet} <latitude> <longitude>")
    print("Terrain outside the configured radial height range fails explicitly; scale 1 does not yet cover all elevations.")


if __name__ == "__main__":
    main()
