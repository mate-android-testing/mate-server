import subprocess
import platform

def execCmd(cmd):
    if platform.system() == "Windows":
        return subprocess.run(["powershell", "-command", cmd], stdout=subprocess.PIPE)
    else:
        return subprocess.run(["bash", "-c", cmd], stdout=subprocess.PIPE)

def replaceEmptyCoverageFiles(pkg_name):
    cmd = "find " + pkg_name + ".coverage -type f -empty"
    result = execCmd(cmd).stdout.decode()
    if result.strip() != "":
        for f in result.strip().split("\n"):
            cmd = "cp " + pkg_name + ".coverage.empty " + f
            execCmd(cmd)
