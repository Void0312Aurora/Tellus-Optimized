# Disposable, warm synthetic-world fixture. Keep the ordinary visual guide and
# asymmetric landmarks, then raise the runway above sea Y=100. Player Y=102
# must be onGround; the old Y=99 platform allowed water movement.
function tellus_visualtest:visual_setup
function tellus_visualtest:mounted_landmarks
execute in tellus:earth_cube_net run fill 15352 101 -256 15368 101 63 minecraft:smooth_stone
execute in tellus:earth_cube_net run fill 10000 101 5104 10239 101 5136 minecraft:smooth_stone
execute in tellus:earth_cube_net run fill 15360 101 -256 15360 101 63 minecraft:lime_concrete
execute in tellus:earth_cube_net run fill 10000 101 5120 10239 101 5120 minecraft:lime_concrete
execute in tellus:earth_cube_net run setblock 15348 101 -32 minecraft:red_concrete
execute in tellus:earth_cube_net run setblock 10220 101 5120 minecraft:blue_concrete
execute in tellus:earth_cube_net run setblock 10210 101 5120 minecraft:yellow_concrete
