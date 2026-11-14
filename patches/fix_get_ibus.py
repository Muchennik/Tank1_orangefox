#!/usr/bin/env python3
"""MediaPad X2 (GEM-701L, bq2429x charger): CONFIG_GRACE_SELECT_2A_1A_AC is a Huawei P8 feature
that calls di->ops->get_ibus() 10x when a wall (DCP) charger is plugged in. bq2429x_ops has no
get_ibus, so the kernel jumps to NULL in charge_monitor_work and the tablet reboots / hangs /
powers off on every wall charger. Skip the 2A/1A probe when the charger IC can't measure IBUS."""
import sys
p = sys.argv[1]
s = open(p).read()
old = "if((di->input_current == di->core_data->iin_max)&&(di->charger_type == CHARGER_TYPE_STANDARD)) {"
new = "if((di->input_current == di->core_data->iin_max)&&(di->charger_type == CHARGER_TYPE_STANDARD)&&(NULL != di->ops->get_ibus)) {"
if new in s:
    print("already patched"); sys.exit(0)
if s.count(old) != 1:
    sys.exit("anchor not found exactly once")
s = s.replace(old, new)
open(p, "w").write(s)
print("patched", p)
