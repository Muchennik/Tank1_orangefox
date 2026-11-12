#!/usr/bin/env python3
"""Adds a second torch button to the OrangeFox action bar (Menu page) for OSCAL TANK 1.

The stock OrangeFox flashlight button toggles OF_FL_PATH1 (the big front lamp here).
This inserts a button right next to it that toggles the camera-block flash
(mt6360_flash_ch1/ch2), so the two lights can be switched separately.
usage: fox_theme_patch.py <theme dir, e.g. bootable/recovery/gui/theme/portrait_hdpi>
"""
import os
import sys

theme = sys.argv[1]
page = os.path.join(theme, "pages", "advanced.xml")
s = open(page, encoding="utf-8").read()
if "fox_camflash" in s:
    print("already patched"); sys.exit(0)

anchor = '<image resource="actionbar_flash_on"/>\n\t\t\t</image>\n'
i = s.find(anchor)
if i < 0:
    sys.exit("anchor (flashlight on icon) not found in " + page)
i += len(anchor)

L = "/sys/class/leds/mt6360_flash_"
on = "for c in ch1 ch2; do cat %s$c/max_brightness &gt; %s$c/brightness; done" % (L, L)
off = "for c in ch1 ch2; do echo 0 &gt; %s$c/brightness; done" % L
X = "228"  # one action-bar slot right of the stock flashlight (84)
block = f'''
\t\t\t<!-- TANK 1: camera-block flash, separate from the big lamp -->
\t\t\t<button style="actionbar">
\t\t\t\t<condition var1="fox_camflash" op="!=" var2="1"/>
\t\t\t\t<placement x="{X}" y="%ab_y%" placement="4"/>
\t\t\t\t<action function="cmd">{on}</action>
\t\t\t\t<action function="set">fox_camflash=1</action>
\t\t\t</button>

\t\t\t<button style="actionbar">
\t\t\t\t<condition var1="fox_camflash" var2="1"/>
\t\t\t\t<placement x="{X}" y="%ab_y%" placement="4"/>
\t\t\t\t<action function="cmd">{off}</action>
\t\t\t\t<action function="set">fox_camflash=0</action>
\t\t\t</button>

\t\t\t<image>
\t\t\t\t<condition var1="fox_camflash" op="!=" var2="1"/>
\t\t\t\t<placement x="{X}" y="%ab_y%" placement="4"/>
\t\t\t\t<image resource="actionbar_flash"/>
\t\t\t</image>

\t\t\t<image>
\t\t\t\t<condition var1="fox_camflash" var2="1"/>
\t\t\t\t<placement x="{X}" y="%ab_y%" placement="4"/>
\t\t\t\t<image resource="actionbar_flash_on"/>
\t\t\t</image>
'''
s = s[:i] + block + s[i:]
open(page, "w", encoding="utf-8").write(s)
print("patched", page)
