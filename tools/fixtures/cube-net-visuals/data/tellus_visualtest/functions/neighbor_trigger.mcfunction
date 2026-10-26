# This single proxy write should power canonical wire/lamp neighbor updates,
# then mirror each resulting canonical state back to the loaded proxy line.
execute in tellus:earth_cube_net run setblock 15360 100 21 minecraft:redstone_block
tellraw @s {"text":"Triggered the proxy redstone block. Both the proxy lamp at Z=25 and canonical lamp at X=10214, Z=5120 should be lit.","color":"aqua"}
