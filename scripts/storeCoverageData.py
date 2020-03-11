#!/usr/bin/python3

import sys
import subprocess
import pathlib
import tempfile

def execCmd(cmd):
    if platform.system() == "Windows":
        subprocess.run(["powershell", "-command", cmd], stdout=subprocess.PIPE)
    else:
        subprocess.run(["bash", "-c", cmd], stdout=subprocess.PIPE)

device = sys.argv[1]
package = sys.argv[2]
chromosome = sys.argv[3]
entity = False
if len(sys.argv) > 4:
    entity = sys.argv[4]

coverage_dir = package + ".coverage/" + chromosome
pathlib.Path(coverage_dir).mkdir(parents=True, exist_ok=True)

store_in_app_command = "adb -s " + device + " shell input keyevent 3; adb -s " + device + " shell monkey -p " + package + " 1"
execCmd(store_in_app_command)

if entity != False:
    coverage_file = coverage_dir + "/" + entity
else:
    (fd, coverage_file) = tempfile.mkstemp(dir=coverage_dir)

print("Created new coverage file: "  + coverage_file)

pull_file_command = "adb -s " + device + " exec-out run-as " + package + " cat files/coverage.exec > " + coverage_file
execCmd(pull_file_command)
