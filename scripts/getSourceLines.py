#!/usr/bin/python3

import sys
import xml.etree.ElementTree as ET

package = sys.argv[1]
report_file = package + ".report"
separator = "+"

tree = ET.parse(report_file)
pkg_elements = tree.getroot().findall("./package")
for pkg_el in pkg_elements:
    pkg_name = pkg_el.get("name")
    src_elements = pkg_el.findall("./sourcefile")
    for src_el in src_elements:
        src_name = src_el.get("name")
        line_elements = src_el.findall("./line")
        for line_el in line_elements:
            line_nr = line_el.get("nr")
            print(pkg_name + separator + src_name + separator + line_nr)
