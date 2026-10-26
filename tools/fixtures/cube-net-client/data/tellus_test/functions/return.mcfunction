gamemode creative @s
execute in tellus:earth_cube_net run tp @s 10215.5 100 5120.5 270 0
tellraw @s {"text":"RETURN seam: walk forward east over green stripe. Expected: X~15360.5, Z<0, facing south (yaw 180).", "color":"green"}
