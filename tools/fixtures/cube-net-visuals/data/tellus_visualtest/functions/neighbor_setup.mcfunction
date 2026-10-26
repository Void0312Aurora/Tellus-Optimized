# Build an unpowered redstone line in proxy coordinates. The line crosses the
# rebase boundary; its canonical cells lie along NEG_Z at Y=100, Z=5120.
execute in tellus:earth_cube_net run fill 15360 99 20 15360 99 26 minecraft:smooth_stone
execute in tellus:earth_cube_net run fill 15360 100 20 15360 100 26 minecraft:air
execute in tellus:earth_cube_net run fill 15360 100 22 15360 100 24 minecraft:redstone_wire[power=0]
execute in tellus:earth_cube_net run setblock 15360 100 25 minecraft:redstone_lamp
tellraw @s {"text":"Neighbor probe ready. Trigger the redstone block at apron cell Z=21, then compare the proxy lamp at Z=25 with its canonical owner at X=10214, Z=5120.","color":"aqua"}
