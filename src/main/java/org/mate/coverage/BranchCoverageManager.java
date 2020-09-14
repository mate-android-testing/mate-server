package org.mate.coverage;

import com.google.common.collect.Lists;
import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public final class BranchCoverageManager {

    // stores for each test case the coverage
    private static Map<String, Double> coverageMap = new HashMap<>();

    /**
     * Stores the branch coverage for a given test case. First, a broadcast is sent to the AUT
     * in order to write out a traces.txt file on the external storage. Second, this traces.txt is
     * pulled from the emulator and saved on a pre-defined location (app directory/traces).
     * Finally, the branch coverage is evaluated by comparing the visited branches with the
     * total number of branches.
     *
     * @param androidEnvironment Defines the location of the adb/aapt binary.
     * @param deviceID The id of the emulator, e.g. emulator-5554.
     * @param testCaseId The test case identifier.
     * @return Returns the branch coverage for the given test case.
     */
    public static Message storeCoverage(AndroidEnvironment androidEnvironment, String deviceID, String testCaseId) {
        // grant runtime permissions
        Device device = Device.devices.get(deviceID);
        String packageName = device.getPackageName();
        boolean granted = device.grantPermissions(packageName);

        if (!granted) {
            throw new IllegalStateException("Couldn't grant runtime permissions!");
        }

        // run adb as root in order to fire up broadcast
        var rootOperation =  ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "root");

        if (rootOperation.isErr()) {
            throw new IllegalStateException("Couldn't run ADB as root!");
        }

        // send broadcast in order to write out traces
        var broadcastOperation = ProcessRunner.runProcess(
                androidEnvironment.getAdbExecutable(),
                "-s",
                deviceID,
                "shell",
                "am",
                "broadcast",
                "-a",
                "STORE_TRACES",
                "-n",
                packageName + "/de.uni_passau.fim.auermich.branchcoverage.tracer.Tracer",
                "--es",
                "packageName",
                packageName);

        if (broadcastOperation.isErr()) {
            throw new IllegalStateException("Couldn't send broadcast!");
        }

        // fetch traces + store it at certain location with test case id name
        File tracesFile = device.pullTraceFile("traces-testcase-" + testCaseId);
        File branchesFile = new File(System.getProperty("user.dir"), "branches.txt");

        double branchCoverage = 0d;

        // compute branch coverage
        try {
            branchCoverage = evaluateBranchCoverage(branchesFile, List.of(tracesFile));
            // cache for later requests
            coverageMap.put(testCaseId, branchCoverage);
        } catch (IOException e) {
            Log.printError(e.getMessage());
            throw new IllegalStateException("Branch coverage couldn't be evaluated!");
        }

        return new Message.MessageBuilder("/coverage/store")
                .withParameter("coverage", String.valueOf(branchCoverage))
                .build();
    }

    /**
     * Gets the branch coverage for a given test case. Should be only called
     * after storeCoverage has been called for the given test case.
     *
     * @param testCaseId The test case identifier.
     * @return Returns the branch coverage for the given test case.
     */
    public static Message getCoverage(String testCaseId) {
        /*
        * TODO: ensure that storeCoverage was previously called,
        *  otherwise coverageMap doesn't contain a valid entry.
         */
        return new Message.MessageBuilder("/coverage/get")
                .withParameter("coverage", String.valueOf(coverageMap.get(testCaseId)))
                .build();
    }

    /**
     * Gets the combined branch coverage.
     *
     * @param packageName The package name of the AUT. Must coincide with the
     *                    name of the app directory containing the traces subdirectory.
     * @param testcaseIds Determines which test cases should be considered for the combined
     *                    coverage computation. If {@code null}, all test cases are considered,
     *                    otherwise only a subset described by the test case ids is considered.
     * @return Returns a message encapsulating the combined branch coverage.
     */
    public static Message getCombinedCoverage(String packageName, String testcaseIds) {

        // TODO: check whether it is necessary to pull again the last traces file

        // get branches file
        File branchesFile = new File(System.getProperty("user.dir"), "branches.txt");

        // get list of traces file
        String workingDir = System.getProperty("user.dir");
        File appDir = new File(workingDir, packageName);
        File tracesDir = new File(appDir, "traces");

        if (!tracesDir.exists()) {
            throw new IllegalArgumentException("Traces directory " + tracesDir.getAbsolutePath() + " doesn't exist!");
        }

        List<File> tracesFiles = Lists.newArrayList(tracesDir.listFiles());

        if (testcaseIds != null) {
            // test case ids are concatenated by '+'
            List<String> testCases = Lists.newArrayList(testcaseIds.split("\\+"));

            tracesFiles = tracesFiles.stream().filter(traceFile
                    -> testCases.contains(traceFile.getName()
                    // remove file prefix
                    .replace("traces-testcase-", ""))).collect(Collectors.toList());
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
     * @param tracesFiles The set of traces file.
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

        double branchCoverage = 0d;

        // compute branch coverage per class
        for (String key : branches.keySet()) {

            float totalBranches = branches.get(key);
            float coveredBranches = visitedBranches.get(key);
            branchCoverage += coveredBranches / totalBranches * 100;

            Log.println("We have for the class " + key
                    + " a branch coverage of: " + coveredBranches / totalBranches * 100 + "%");
        }

        return branchCoverage;
    }

}
