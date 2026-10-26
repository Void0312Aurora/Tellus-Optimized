# Keep asymmetric landmarks ahead of the boat at the depth-24 rebase.
# Both lie inside the two-chunk apron and write through to their canonical cells.
execute in tellus:earth_cube_net run fill 15355 100 30 15355 111 30 minecraft:red_concrete
execute in tellus:earth_cube_net run fill 15365 100 28 15365 106 28 minecraft:yellow_concrete
execute in tellus:earth_cube_net run setblock 15355 112 30 minecraft:sea_lantern
execute in tellus:earth_cube_net run setblock 15365 107 28 minecraft:sea_lantern
