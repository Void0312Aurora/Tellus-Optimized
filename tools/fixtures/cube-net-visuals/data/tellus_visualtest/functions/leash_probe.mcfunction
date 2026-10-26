execute in tellus:earth_cube_net run kill @e[tag=tellus_leash_probe]
execute in tellus:earth_cube_net run summon minecraft:cow 15360.5 101 96.5 {NoAI:1b,NoGravity:1b,Tags:["tellus_leash_probe_target"]}
execute in tellus:earth_cube_net run summon minecraft:cow 15360.5 101 102.5 {NoAI:1b,NoGravity:1b,Tags:["tellus_leash_probe_holder"]}
execute in tellus:earth_cube_net run data modify entity @e[tag=tellus_leash_probe_target,limit=1] Leash.UUID set from entity @e[tag=tellus_leash_probe_holder,limit=1] UUID
execute in tellus:earth_cube_net run tag @e[tag=tellus_leash_probe_target] add tellus_leash_probe
execute in tellus:earth_cube_net run tag @e[tag=tellus_leash_probe_holder] add tellus_leash_probe
schedule function tellus_visualtest:leash_probe_move 2t replace
tellraw @s {"text":"Spawned a local non-player leash probe in the POS_X apron. The two cows should rebase together into canonical NEG_Z while retaining the leash relationship.","color":"aqua"}
