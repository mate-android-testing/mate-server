#!/usr/bin/python3
import subprocess
import sys

device = sys.argv[1]
package = sys.argv[2]

cmd = "adb -s "+ device + " shell pm clear " + package

subprocess.run(["bash", "-c", cmd])

exec_str = str.encode('run-as ' + package + '\nmkdir -p files\ntouch files/coverage.exec\nexit\nexit\n', 'ascii')

cmd = ['adb', "-s", device, 'shell']
subprocess.run(cmd, input=exec_str)
