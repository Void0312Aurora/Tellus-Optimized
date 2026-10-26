gamemode creative @s
execute in tellus:earth_cube_net run gamerule doDaylightCycle false
execute in tellus:earth_cube_net run gamerule doWeatherCycle false
execute in tellus:earth_cube_net run time set noon
execute in tellus:earth_cube_net run weather clear
# POS_X approach and its mapped NEG_Z apron, at Y=99. The extended platform
# stays visible before and after the cut/rebase, while the canonical landing
# strip is long enough to inspect post-rebase motion.
execute in tellus:earth_cube_net run fill 15352 99 -256 15368 99 -1 minecraft:smooth_stone
execute in tellus:earth_cube_net run fill 15352 99 0 15368 99 63 minecraft:smooth_stone
execute in tellus:earth_cube_net run fill 10000 99 5104 10239 99 5136 minecraft:smooth_stone
execute in tellus:earth_cube_net run fill 15360 100 -256 15360 100 63 minecraft:air

# A continuous center guide and a three-color cadence. Blue at apron depth 24
# marks the later canonical chart rebase. Proxy fills write through to NEG_Z.
execute in tellus:earth_cube_net run fill 15360 99 -256 15360 99 63 minecraft:lime_concrete
execute in tellus:earth_cube_net run fill 10000 99 5120 10239 99 5120 minecraft:lime_concrete
execute in tellus:earth_cube_net run fill 15352 99 -1 15368 99 -1 minecraft:blue_concrete
execute in tellus:earth_cube_net run fill 15352 99 8 15368 99 8 minecraft:red_concrete
execute in tellus:earth_cube_net run fill 15352 99 16 15368 99 16 minecraft:yellow_concrete
execute in tellus:earth_cube_net run fill 15352 99 24 15368 99 24 minecraft:blue_concrete
execute in tellus:earth_cube_net run fill 15352 99 40 15368 99 40 minecraft:red_concrete
execute in tellus:earth_cube_net run fill 15352 99 56 15368 99 56 minecraft:yellow_concrete

# Stable light sources: one on the approach and one in the apron beyond rebase.
execute in tellus:earth_cube_net run setblock 15355 99 -200 minecraft:sea_lantern
execute in tellus:earth_cube_net run setblock 15365 99 40 minecraft:sea_lantern

# A contained water pool on the source approach, near Z=-205. Placing
# through the proxy also exercises write-through and cache refresh.
execute in tellus:earth_cube_net run fill 15363 100 -206 15367 100 -201 minecraft:smooth_stone
execute in tellus:earth_cube_net run fill 15364 100 -205 15366 100 -202 minecraft:air
execute in tellus:earth_cube_net run fill 15364 101 -205 15366 101 -202 minecraft:air
execute in tellus:earth_cube_net run fill 15364 100 -205 15366 100 -202 minecraft:water[level=0]
