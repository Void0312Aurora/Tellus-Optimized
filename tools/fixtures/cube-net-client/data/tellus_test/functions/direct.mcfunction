gamemode creative @s
execute in tellus:earth_cube_net run fill 5116 99 -10252 5124 99 -10228 minecraft:stone
execute in tellus:earth_cube_net run fill 5116 99 -10241 5124 99 -10241 minecraft:yellow_concrete
execute in tellus:earth_cube_net run fill 5116 99 -10240 5124 99 -10240 minecraft:lime_concrete
execute in tellus:earth_cube_net run tp @s 5120.5 100 -10235.5 180 0
tellraw @s {"text":"DIRECT edge: walk forward across yellow/green stripes. Expected: X~5120, continuous Z across -10240.","color":"yellow"}
