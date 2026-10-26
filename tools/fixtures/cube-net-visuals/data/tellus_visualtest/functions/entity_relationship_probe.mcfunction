kill @e[tag=tellus_entity_relationship_probe]
execute in tellus:earth_cube_net run summon minecraft:boat 15360.5 101 24.5 {NoGravity:1b,Tags:["tellus_entity_relationship_probe"],Rotation:[45f,0f],Passengers:[{id:"minecraft:armor_stand",NoGravity:1b,Tags:["tellus_entity_relationship_probe"]}]}
tellraw @s {"text":"Spawned a boat with an armor-stand passenger in the POS_X apron. Both entities should rebase to canonical NEG_Z while retaining the passenger relationship.","color":"aqua"}
