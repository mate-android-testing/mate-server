package org.mate.coverage;

import org.mate.endpoints.CoverageEndpoint;
import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

import java.io.*;
import java.util.*;

public final class BranchCoverageManager {

    // stores for each test case the coverage
    private static Map<String, Double> coverageMap = new HashMap<>();

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
            branchCoverage = evaluateBranchCoverage(branchesFile, tracesFile);
            // cache for later requests
            coverageMap.put(testCaseId, branchCoverage);
        } catch (IOException e) {
            throw new IllegalStateException("Branch coverage couldn't be evaluated!");
        }

        return new Message.MessageBuilder("/coverage/store")
                .withParameter("coverage", String.valueOf(branchCoverage))
                .build();
    }

    public static Message getCoverage(String testCaseId) {
        return new Message.MessageBuilder("/coverage/get")
                .withParameter("coverage", String.valueOf(coverageMap.get(testCaseId)))
                .build();
    }

    public static Message getCombinedCoverage() {
        throw new UnsupportedOperationException("Get combined branch coverage not yet supported!");
        // TODO: get branches file
        // File branchesFile = new File(System.getProperty("user.dir"), "branches.txt");
        // TODO: get list of traces file
        // TODO: invoke evaluateBranchCoverage with list of traces file -> refactor if possible
        // TODO: check whether it is necessary to pull again the last traces file
        /*
        return new Message.MessageBuilder("/coverage/combined")
                .withParameter("coverage", String.valueOf(coverageMap.get(testCaseId)))
                .build();
         */
    }

    private static double evaluateBranchCoverage(File branchesFile, File tracesFile) throws IOException {

        Log.println("BranchesFile: " + branchesFile + "[" + branchesFile.exists() + "]");
        Log.println("TracesFile: " + tracesFile + "[" + tracesFile.exists() + "]");

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

        // second argument refers to traces.txt
        InputStream tracesInputStream = new FileInputStream(tracesFile);
        BufferedReader tracesReader = new BufferedReader(new InputStreamReader(tracesInputStream));
        Set<String> coveredTraces = new HashSet<>();

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
