package org.mate.coverage;

import org.apache.commons.io.FileUtils;
import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public final class BranchCoverageManager {

    /**
     * Stores the branch coverage data for a chromosome, which can be either a test case or a test suite.
     *
     * First, a broadcast is sent to the AUT in order to write out a traces.txt file on the external storage.
     * Second, this traces.txt is pulled from the emulator and saved on a pre-defined location (app directory/traces).
     * Finally, the branch coverage is evaluated by comparing the visited branches with the
     * total number of branches.
     *
     * @param androidEnvironment Defines the location of the adb/aapt binary.
     * @param deviceID           The id of the emulator, e.g. emulator-5554.
     * @param chromosome         Identifies either a test case or a test suite.
     * @param entity             Identifies a test case if chromosome refers to
     *                           a test suite, otherwise {@code null}.
     * @return Returns the branch coverage for the given test case.
     */
    public static Message storeCoverageData(AndroidEnvironment androidEnvironment, String deviceID,
                                            String chromosome, String entity) {
        // grant runtime permissions
        Device device = Device.devices.get(deviceID);
        String packageName = device.getPackageName();
        boolean granted = device.grantPermissions(packageName);

        if (!granted) {
            throw new IllegalStateException("Couldn't grant runtime permissions!");
        }

        // send broadcast in order to write out traces
        var broadcastOperation = ProcessRunner.runProcess(
                androidEnvironment.getAdbExecutable(),
                "-s",
                deviceID,
                "shell",
                "su",
                "root",
                "am",
                "broadcast",
                "-a",
                "STORE_TRACES",
                "-n",
                packageName + "/de.uni_passau.fim.auermich.tracer.Tracer",
                "--es",
                "packageName",
                packageName);

        if (broadcastOperation.isErr()) {
            throw new IllegalStateException("Couldn't send broadcast!");
        }

        device.pullTraceFile(chromosome, entity);
        return new Message("/coverage/store");
    }

    /**
     * Computes the (combined) coverage for a set of test cases/test suites.
     *
     * @param packageName The package name of the AUT.
     * @param chromosomes A list of chromosomes separated by '+'.
     * @return Returns the (combined) coverage for a set of chromosomes.
     */
    public static Message getCombinedCoverage(Path appsDir, String packageName, String chromosomes) {

        // TODO: check whether it is necessary to pull again the last traces file

        // get list of traces file
        File appDir = new File(appsDir.toFile(), packageName);
        File tracesDir = new File(appDir, "traces");

        // the branches.txt should be located within the app directory
        File branchesFile = new File(appDir, "branches.txt");

        List<File> tracesFiles = new ArrayList<>(FileUtils.listFiles(tracesDir, null, true));

        if (chromosomes != null) {

            // only consider the traces files described by the chromosome ids
            tracesFiles = new ArrayList<>();

            for (String chromosome : chromosomes.split("\\+")) {
                try {
                    tracesFiles.addAll(
                            Files.walk(tracesDir.toPath().resolve(chromosome))
                                    .filter(Files::isRegularFile)
                                    .map(Path::toFile)
                                    .collect(Collectors.toList()));
                } catch (IOException e) {
                    Log.printError("Couldn't retrieve traces files!");
                    throw new IllegalArgumentException(e);
                }
            }
        }

        // evaluate branch coverage over all traces files
        double branchCoverage = 0d;

        try {
            branchCoverage = evaluateBranchCoverage(branchesFile, tracesFiles);
        } catch (IOException e) {
            Log.printError(e.getMessage());
            throw new IllegalStateException("Branch coverage couldn't be evaluated!");
        }

        return new Message.MessageBuilder("/coverage/combined")
                .withParameter("coverage", String.valueOf(branchCoverage))
                .build();
    }

    /**
     * Evaluates the branch coverage for a given set of traces files. Can be used
     * to evaluate the branch coverage for a single test case as well as the combined coverage.
     *
     * @param branchesFile The branches.txt file listing for each class the number of branches.
     * @param tracesFiles  The set of traces file.
     * @return Returns the branch coverage for a single test case or the combined coverage.
     * @throws IOException Should never happen.
     */
    private static double evaluateBranchCoverage(File branchesFile, List<File> tracesFiles) throws IOException {

        Log.println("BranchesFile: " + branchesFile + "[" + branchesFile.exists() + "]");

        for (File tracesFile : tracesFiles) {
            Log.println("TracesFile: " + tracesFile + "[" + tracesFile.exists() + "]");
        }

        // tracks the number of total branches per class <class,#branches>
        Map<String, Integer> branches = new HashMap<>();

        // tracks the number of visited branches per class <class,#branches>
        Map<String, Integer> visitedBranches = new HashMap<>();

        // first argument refers to branches.txt
        InputStream branchesInputStream = new FileInputStream(branchesFile);
        BufferedReader branchesReader = new BufferedReader(new InputStreamReader(branchesInputStream));

        // read number of branches per class
        String line;
        while ((line = branchesReader.readLine()) != null) {
            // each line consists of className: #branches
            String[] tuple = line.split(":");
            branches.put(tuple[0], Integer.parseInt(tuple[1].trim()));
        }

        branchesReader.close();
        Set<String> coveredTraces = new HashSet<>();

        // second argument refers to traces.txt file(s)
        for (File tracesFile : tracesFiles) {
            InputStream tracesInputStream = new FileInputStream(tracesFile);
            BufferedReader tracesReader = new BufferedReader(new InputStreamReader(tracesInputStream));

            // read the traces
            String trace;
            while ((trace = tracesReader.readLine()) != null) {

                // each trace consists of className->methodName->branchID
                String[] triple = trace.split("->");

                if (triple.length != 3 || trace.contains(":")
                        || trace.endsWith("->exit") || trace.endsWith("->entry")) {
                    // ignore traces related to if statements or branch distance or virtual entry/exit vertices
                    continue;
                }

                if (visitedBranches.containsKey(triple[0])) {
                    // only new not yet covered branches are interesting
                    if (!coveredTraces.contains(trace)) {
                        // new covered branch for class, increase by one
                        int visitedBranchesOfClass = visitedBranches.get(triple[0]);
                        visitedBranches.put(triple[0], ++visitedBranchesOfClass);
                    }
                } else {
                    // it's a new entry for the given class
                    visitedBranches.put(triple[0], 1);
                }
                coveredTraces.add(trace);
            }
            tracesReader.close();
        }

        // compute branch coverage per class
        for (String key : branches.keySet()) {

            float totalBranches = branches.get(key);
            float coveredBranches = visitedBranches.getOrDefault(key, 0);

            // only log classes with coverage > 0
            if (coveredBranches != 0) {
                Log.println("We have for the class " + key
                        + " a branch coverage of: " + coveredBranches / totalBranches * 100 + "%");
            }
        }

        // compute total branch coverage
        double branchCoverage = 0d;
        double totalVisitedBranches = visitedBranches.values().stream().reduce(0, Integer::sum);
        double totalBranches = branches.values().stream().reduce(0, Integer::sum);
        branchCoverage = totalVisitedBranches / totalBranches * 100;

        Log.println("Total branch coverage: " + branchCoverage + "%");
        return branchCoverage;
    }

}
