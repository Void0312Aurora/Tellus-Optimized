gamemode creative @s
execute in tellus:earth_cube_net run fill 15352 99 -24 15368 99 -1 minecraft:smooth_stone
execute in tellus:earth_cube_net run fill 15352 99 0 15368 99 31 minecraft:smooth_stone
execute in tellus:earth_cube_net run fill 10208 99 5112 10239 99 5128 minecraft:smooth_stone

# One-block trench on POS_X continues into the mapped NEG_Z apron.
execute in tellus:earth_cube_net run fill 15359 100 -24 15359 100 31 minecraft:stone
execute in tellus:earth_cube_net run fill 15361 100 -24 15361 100 31 minecraft:stone
execute in tellus:earth_cube_net run fill 15360 100 -24 15360 100 31 minecraft:air
execute in tellus:earth_cube_net run fill 10208 100 5119 10239 100 5119 minecraft:stone
execute in tellus:earth_cube_net run fill 10208 100 5121 10239 100 5121 minecraft:stone
execute in tellus:earth_cube_net run fill 10208 100 5120 10239 100 5120 minecraft:air
execute in tellus:earth_cube_net run fill 15360 100 -3 15360 100 31 minecraft:air

# The single source is three cells from the cut so it has enough flow range to
# enter the apron. Inspect the flowing state in proxy and canonical positions.
execute in tellus:earth_cube_net run setblock 15360 100 -3 minecraft:water[level=0]
tellraw @s {"text":"Forward water probe active. After 5 seconds inspect proxy cells (15360,100,0..3) and canonical cells (10239..10236,100,5120).","color":"blue"}
