gamemode creative @s
execute in tellus:earth_cube_net run fill 10208 99 5112 10239 99 5128 minecraft:smooth_stone
execute in tellus:earth_cube_net run fill 10240 99 5112 10271 99 5128 minecraft:smooth_stone
execute in tellus:earth_cube_net run fill 15352 99 -24 15368 99 -1 minecraft:smooth_stone

# Reverse trench on NEG_Z continues into its mapped POS_X apron.
execute in tellus:earth_cube_net run fill 10208 100 5119 10271 100 5119 minecraft:stone
execute in tellus:earth_cube_net run fill 10208 100 5121 10271 100 5121 minecraft:stone
execute in tellus:earth_cube_net run fill 10208 100 5120 10271 100 5120 minecraft:air
execute in tellus:earth_cube_net run fill 15359 100 -24 15359 100 -1 minecraft:stone
execute in tellus:earth_cube_net run fill 15361 100 -24 15361 100 -1 minecraft:stone
execute in tellus:earth_cube_net run fill 15360 100 -24 15360 100 -1 minecraft:air

# The source lies three cells from the cut; observe the NEG_Z apron at X>10240
# and the canonical POS_X cells beneath that derived view.
execute in tellus:earth_cube_net run fill 10240 100 5120 10271 100 5120 minecraft:air
execute in tellus:earth_cube_net run setblock 10237 100 5120 minecraft:water[level=0]
tellraw @s {"text":"Return water probe active. After 5 seconds inspect proxy cells (10240..10243,100,5120) and canonical cells (15360,100,-1..-4).","color":"blue"}
