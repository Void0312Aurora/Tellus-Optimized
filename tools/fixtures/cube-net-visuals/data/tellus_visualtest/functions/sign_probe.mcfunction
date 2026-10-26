execute in tellus:earth_cube_net run setblock 15360 100 12 minecraft:oak_sign[rotation=0]
execute in tellus:earth_cube_net run data merge block 15360 100 12 {front_text:{messages:['{"text":"Proxy seam sign"}','{"text":"Tellus"}','{"text":"2026-10-18"}','{"text":"POS_X"}'],color:"black",has_glowing_text:0b}}
tellraw @s {"text":"Placed a text sign in the POS_X apron. Its front_text payload should appear at canonical X=10227, Z=5120 and mirror back when edited there.","color":"aqua"}
