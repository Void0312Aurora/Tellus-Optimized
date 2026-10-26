kill @e[tag=tellus_entity_probe]
execute in tellus:earth_cube_net run summon minecraft:arrow 15360.5 100 24.5 {NoGravity:1b,Tags:["tellus_entity_probe"],Rotation:[45f,0f],Motion:[0.0d,0.0d,0.25d]}
tellraw @s {"text":"Spawned a moving tagged arrow probe in the POS_X apron; position, yaw, and horizontal velocity should rotate into canonical NEG_Z after target chunks are ready.","color":"aqua"}
