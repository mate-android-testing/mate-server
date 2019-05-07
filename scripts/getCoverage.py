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
src_dir = package + ".src"
report_path = "jacocoTestReport.xml"
coverage_path = package + ".coverage/" + chromosome
listOfFiles = [coverage_path + "/" + f for f in listdir(coverage_path) if isfile(join(coverage_path, f))]

replaceEmptyCoverageFiles(package)

genCoverageReport(listOfFiles, src_dir)

tree = ET.parse(report_path)
instruction_element = tree.getroot().findall("./counter[@type='LINE']")[0]
missed_instructions = float(instruction_element.get('missed'))
covered_instructions = float(instruction_element.get('covered'))
print("Missed instructions: " + str(missed_instructions))
print("Covered instructions: " + str(covered_instructions))
total = missed_instructions + covered_instructions
coverage_result = covered_instructions / total
print("Total coverage:")
print(coverage_result)
