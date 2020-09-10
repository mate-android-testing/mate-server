package org.mate.endpoints;

import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.message.Message;
import org.mate.network.Endpoint;

import java.io.*;
import java.util.*;
import java.util.logging.Level;

public class CoverageEndPoint implements Endpoint {

    // stores for each test case the coverage
    private Map<String, Double> coverageMap = new HashMap<>();

    public enum Coverage {
        LINE_COVERAGE,
        STATEMENT_COVERAGE,
        BRANCH_COVERAGE,
        METHOD_COVERAGE,
    }

    public enum Operation {
        FETCH,
        STORE,
    }

    @Override
    public Message handle(Message request) {
        if (request.getSubject().startsWith("/coverage")) {

            String deviceID = request.getParameter("deviceId");
            Coverage coverage = Coverage.valueOf(request.getParameter("coverage_type"));
            Operation op = Operation.valueOf(request.getParameter("operation").toUpperCase());

            // TODO: convert to Integer
            String testCaseId = request.getParameter("testcaseId");

            if (testCaseId != null) {
                // get coverage of given test case
                return getCoverage(deviceID, coverage, op, testCaseId);
            } else {
                // get overall coverage
                return getOverallCoverage(coverage, op);
            }
        } else {
            return null;
        }
    }

    private Message getCoverage(String deviceID, Coverage coverage, Operation op, String testCaseId) {

        // TODO: handle fetch operation for yet unsupported coverage type
        if (op == Operation.FETCH) {
            // read coverage information from map
            return new Message.MessageBuilder("/coverage")
                    .withParameter("coverage", String.valueOf(coverageMap.get(testCaseId)))
                    .build();
        }

        // implies a store operation
        switch (coverage) {
            case LINE_COVERAGE:
                break;
            case BRANCH_COVERAGE:
                return storeBranchCoverage(deviceID, testCaseId);
            default:
                throw new UnsupportedOperationException("Coverage type not yet supported");
        }
        return null;
    }

    private Message getOverallCoverage(Coverage coverage, Operation op) {
        return null;
    }

    private Message storeBranchCoverage(String deviceID, String testCaseId) {

        // grant runtime permissions
        Device device = Device.devices.get(deviceID);
        String packageName = device.getPackageName();
        boolean granted = device.grantPermissions(packageName);

        if (!granted) {
            System.out.println("Couldn't grant runtime permissions!");
        }

        // run adb as root in order to fire up broadcast
        System.out.println("ADB root: " + ProcessRunner.runProcess("adb", "-s", deviceID, "root"));

        // send broadcast in order to write out traces
        List<String> result = ProcessRunner.runProcess(
                "adb",
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

        System.out.println(result);

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

        return new Message.MessageBuilder("/coverage")
                .withParameter("coverage", String.valueOf(branchCoverage))
                .build();
    }

    private double evaluateBranchCoverage(File branchesFile, File tracesFile) throws IOException {

        System.out.println("BranchesFile: " + branchesFile + "[" + branchesFile.exists() + "]");
        System.out.println("TracesFile: " + tracesFile + "[" + tracesFile.exists() + "]");

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

            System.out.println("We have for the class " + key
                    + " a branch coverage of: " + coveredBranches / totalBranches * 100 + "%");
        }

        return branchCoverage;
    }
}
