#!/usr/bin/python3

import sys
import subprocess
import xml.etree.ElementTree as ET
from os import listdir
from os.path import isfile, join
from replaceEmptyCoverageFiles import replaceEmptyCoverageFiles
from coverageReport import genCoverageReport


package = sys.argv[1]
chromosome = sys.argv[2]
lines = input().strip()
src_dir = package + ".src"
report_file = "jacocoTestReport.xml"
coverage_path = package + ".coverage/" + chromosome
listOfFiles = [coverage_path + "/" + f for f in listdir(coverage_path) if isfile(join(coverage_path, f))]

replaceEmptyCoverageFiles(package)
genCoverageReport(listOfFiles, src_dir)

def line_covered(l):
    return int(l.get("ci")) > 0

def el_covered(el):
    instruction_element = el.findall("./counter[@type='INSTRUCTION']")[0]
    covered_instructions = int(instruction_element.get('covered'))
    return covered_instructions > 0


tree = ET.parse(report_file)

for line in lines.split("*"):
    [pkg_name, src_name, line_nr] = line.split("+")
    coverage_percentage = 0.0
    pkg_el = tree.getroot().findall("./package[@name='" + pkg_name + "']")[0]
    src_el = pkg_el.findall("./sourcefile[@name='" + src_name + "']")[0]
    line_elements = src_el.findall("./line")

    before_target = False
    after_target = False
    reached_target = False
    target_covered = False
    for (idx, line_el) in enumerate(line_elements):
        lc = line_covered(line_el)
        if line_el.get("nr") == line_nr:
            reached_target = True
            target_covered = lc
            target_idx = idx
            if target_covered:
                break
        elif not reached_target and lc:
            before_target = idx
        elif reached_target and lc:
            after_target = idx
            break

    if target_covered:
        print(1.0)
    else:
        if el_covered(pkg_el):
            coverage_percentage += 0.25
        if el_covered(src_el):
            coverage_percentage += 0.25
        elements_before = target_idx
        elements_after = len(line_elements) - target_idx - 1

        factor = max(elements_before, elements_after)

        if before_target != False:
            before_target = target_idx - before_target
            if after_target == False:
                coverage_percentage += (factor - before_target) / (2 * factor)
        if after_target != False:
            after_target = after_target - target_idx
            if before_target == False:
                coverage_percentage += (factor - after_target) / (2 * factor)
        if before_target != False and after_target != False:
                coverage_percentage += (factor - min(before_target, after_target)) / (2 * factor)
        print(coverage_percentage)
