import subprocess
import tempfile
import platform

def execCmd(cmd):
    if platform.system() == "Windows":
        subprocess.run(["powershell", "-command", cmd], stdout=subprocess.PIPE)
    else:
        subprocess.run(["bash", "-c", cmd], stdout=subprocess.PIPE)


def chunks(l, n):
    """Yield successive n-sized chunks from l."""
    for i in range(0, len(l), n):
        yield l[i:i + n]

def crunsh(listOfFiles):
    lof = list()
    for files in chunks(listOfFiles, 100):
        (fd, crunsh_file) = tempfile.mkstemp(dir=".")
        crunsh_cmd = "java -jar bin/jacococli.jar merge " + " ".join(files) + " --destfile " + crunsh_file
        lof.append(crunsh_file)

        execCmd(crunsh_cmd)
    return lof

def genCoverageReport(listOfFiles, src_dir):
    while len(listOfFiles) > 100:
        listOfFiles = crunsh(listOfFiles)
    report_path = "jacocoTestReport.xml"

    coverage_files = " ".join(listOfFiles)

    generate_coverage_command = "java -jar bin/jacococli.jar report " + coverage_files + " --classfiles " + src_dir + "/classes --sourcefiles " + src_dir + "/java --xml " + report_path

    execCmd(generate_coverage_command)
