gamemode creative @s
execute in tellus:earth_cube_net run fill 15355 99 -16 15365 99 -1 minecraft:stone
execute in tellus:earth_cube_net run fill 15355 99 -1 15365 99 -1 minecraft:yellow_concrete
execute in tellus:earth_cube_net run fill 10228 99 5114 10252 99 5124 minecraft:stone
execute in tellus:earth_cube_net run fill 10240 99 5114 10240 99 5124 minecraft:lime_concrete
execute in tellus:earth_cube_net run tp @s 15360.5 100 -5.5 0 0
tellraw @s {"text":"CUT seam: walk forward over yellow stripe. Expected: arrive near X=10216, Z=5120, facing east (yaw 90).", "color":"yellow"}
