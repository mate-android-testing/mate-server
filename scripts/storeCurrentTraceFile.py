#!/usr/bin/python3

import sys
import subprocess

device = sys.argv[1]
package = sys.argv[2]

store_in_app_command = "adb -s " + device + " shell input keyevent 3"
subprocess.run(["bash", "-c", store_in_app_command])


list_files_command = "adb -s " + device + " exec-out run-as " + package + " ls files"
p = subprocess.run(["bash", "-c", list_files_command])
out, err = p.stdout.decode("utf-8").strip(), p.stderr.decode("utf-8").strip()

trace_files = list(filter(lambda x: x.startswith("trace-"), out.split(" ")))

for trace_file in trace_files:
    pull_files_command = "adb -s " + device + " exec-out run-as " + package + " cat files/" + trace_file + " > " + trace_file
