#!/usr/bin/python3
import sys
import subprocess
import pathlib
from shutil import copyfile

package = sys.argv[1]
chromosome_source = sys.argv[2]
chromosome_target = sys.argv[3]
entities = sys.argv[4].split(",")

coverage_source_dir = package + ".coverage/" + chromosome_source
coverage_target_dir = package + ".coverage/" + chromosome_target

pathlib.Path(coverage_target_dir).mkdir(parents=True, exist_ok=True)

for entity in entities:
    copyfile(coverage_source_dir + "/" + entity, coverage_target_dir + "/" + entity)
