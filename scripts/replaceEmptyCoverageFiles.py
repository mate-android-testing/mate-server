import subprocess

def replaceEmptyCoverageFiles(pkg_name):
    cmd = "find " + pkg_name + ".coverage -type f -empty"
    result = subprocess.run(["bash", "-c", cmd], stdout=subprocess.PIPE).stdout.decode()
    if result.strip() != "":
        for f in result.strip().split("\n"):
            cmd = "cp " + pkg_name + ".coverage.empty " + f
            subprocess.run(["bash", "-c", cmd], stdout=subprocess.PIPE)
