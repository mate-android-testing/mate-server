#!/usr/bin/python3

import sys
import re

found_package = False
found_act = False
white_prefix = None
pkg_name = None

for line in sys.stdin:
    if found_package:
        if found_act:
            if (line.startswith(white_prefix + "A:")
                    and "android:name" in line
                    and "(Raw: " in line):
                found_act = False
                act = (re.search('\\(Raw: "([^"]*)"', line).group(1))
                if act.startswith(pkg_name):
                    l = len(pkg_name)
                    print(act[:l] + "/" + act[l:])
                else:
                    print(pkg_name + "/" + act)
        else:
            if line.lstrip().startswith("E: activity "):
                found_act = True
                white_prefix = " " * (len(line) - len(line.lstrip()) + 2)
    else:
        if (line.lstrip().startswith("A: package=\"")
                and "(Raw: " in line):
            found_package = True
            pkg_name = re.search('\\(Raw: "([^"]*)"', line).group(1)
