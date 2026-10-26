function tellus_visualtest:visual_setup
execute in tellus:earth_cube_net run tp @s 10210.5 100 5120.5 270 0
tellraw @s {"text":"VISUAL return probe ready: walk east across the cut and record through the 24-block apron rebase.","color":"aqua"}
