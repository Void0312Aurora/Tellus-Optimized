function tellus_visualtest:visual_setup
execute in tellus:earth_cube_net run tp @s 15360.5 100 -29.5 0 0
tellraw @s {"text":"VISUAL cut probe ready: record the cut crossing and the later 24-block apron rebase separately.","color":"aqua"}
