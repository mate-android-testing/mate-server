package org.mate.coverage;

import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.mate.io.Device;
import org.mate.network.message.Message;
import org.mate.network.message.Messages;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles requests related to branch coverage.
 */
public final class BranchCoverageManager {

    /**
     * The name of the file that contains all the instrumented branches.
     */
    private static final String BRANCHES_FILE = "branches.txt";

    /**
     * The name of the directory where the traces have been stored.
     */
    private static final String TRACES_DIR = "traces";

    /**
     * Copies the coverage data, i.e. traces of test cases, specified through the list of entities
     * from the source chromosome (test suite) to the target chromosome (test suite).
     *
     * @param appsDir          The apps directory containing all app directories.
     * @param packageName      The package name of the AUT.
     * @param sourceChromosome The source chromosome (test suite).
     * @param targetChromosome The target chromosome (test suite).
     * @param entities         A list of test cases.
     * @return Returns a message describing the success/failure of the operation.
     */
    public static Message copyCoverageData(Path appsDir, String packageName, String sourceChromosome,
                                           String targetChromosome, String[] entities) {

        File appDir = new File(appsDir.toFile(), packageName);
        File tracesDir = new File(appDir, TRACES_DIR);

        File srcDir = new File(tracesDir, sourceChromosome);
        File targetDir = new File(tracesDir, targetChromosome);

        if (!targetDir.mkdirs() && !targetDir.isDirectory()) {
            final var errorMsg = "Chromosome copy failed: target directory could not be created.";
            Log.printError(errorMsg);
            return Messages.errorMessage(errorMsg);
        }
        for (String entity : entities) {

            if (Path.of(targetDir.getPath(), entity).toFile().exists()) {
                // traces of chromosome have been already copied previously
                continue;
            }

            try {
                Files.copy(Path.of(srcDir.getPath(), entity), Path.of(targetDir.getPath(), entity));
            } catch (IOException e) {
                final var errorMsg = "Chromosome copy failed: entity " + entity + " could not be copied from "
                        + srcDir + " to " + targetDir;
                Log.printError(errorMsg);
                return Messages.errorMessage(errorMsg);
            }
        }
        return new Message("/coverage/copy");
    }

    /**
     * Stores the branch coverage data for a chromosome, which can be either a test case or a test suite.
     * <p>
     * First, a broadcast is sent to the AUT in order to write out a traces.txt file on the external storage.
     * Second, this traces.txt is pulled from the emulator and saved on a pre-defined location (app directory/traces).
     *
     * @param androidEnvironment Defines the location of the adb/aapt binary.
     * @param deviceID           The id of the emulator, e.g. emulator-5554.
     * @param packageName        The package name of the AUT.
     * @param chromosome         Identifies either a test case or a test suite.
     * @param entity             Identifies a test case if chromosome refers to
     *                           a test suite, otherwise {@code null}.
     * @return Returns a dummy message on success.
     */
    public static Message storeCoverageData(AndroidEnvironment androidEnvironment, String deviceID, String packageName,
                                            String chromosome, String entity) {
        // grant runtime permissions
        Device device = Device.devices.get(deviceID);
        boolean granted = device.grantPermissions(packageName);

        if (!granted) {
            throw new IllegalStateException("Couldn't grant runtime permissions!");
        }

        device.getTracesFromTracer();
        device.pullTraceFile(chromosome, entity);
        return new Message("/coverage/store");
    }

    /**
     * Computes the branch coverage of a single test case within a test suite.
     *
     * @param appsDir     The apps directory.
     * @param packageName The package name of the AUT.
     * @param testSuiteId The id of the test suite.
     * @param testCaseId  The id of the test case.
     * @return Returns the branch coverage for the given test case.
     */
    public static Message getCoverage(Path appsDir, String packageName, String testSuiteId, String testCaseId) {

        // get list of traces file
        File appDir = new File(appsDir.toFile(), packageName);
        File tracesDir = new File(appDir, TRACES_DIR);

        // the branches.txt should be located within the app directory
        File branchesFile = new File(appDir, BRANCHES_FILE);

        // the trace file corresponding to the test case within the given test suite
        File traceFile = tracesDir.toPath().resolve(testSuiteId).resolve(testCaseId).toFile();

        double branchCoverage = 0d;

        try {
            branchCoverage = evaluateBranchCoverage(branchesFile, Lists.newArrayList(traceFile));
        } catch (IOException e) {
            Log.printError(e.getMessage());
            throw new IllegalStateException("Branch coverage couldn't be evaluated!");
        }

        return new Message.MessageBuilder("/coverage/get")
                .withParameter("branch_coverage", String.valueOf(branchCoverage))
                .build();
    }

    /**
     * Computes the (combined) coverage for a set of test cases/test suites.
     *
     * @param appsDir     The apps directory.
     * @param packageName The package name of the AUT.
     * @param chromosomes A list of chromosomes separated by '+'.
     * @return Returns the (combined) coverage for a set of chromosomes.
     */
    public static Message getCombinedCoverage(Path appsDir, String packageName, String chromosomes) {

        // get list of traces file
        File appDir = new File(appsDir.toFile(), packageName);
        File tracesDir = new File(appDir, TRACES_DIR);

        // the branches.txt should be located within the app directory
        File branchesFile = new File(appDir, BRANCHES_FILE);

        // only consider the traces files described by the chromosome ids
        List<File> tracesFiles = getTraceFiles(tracesDir, chromosomes);

        // evaluate branch coverage over all traces files
        double branchCoverage = 0d;

        try {
            branchCoverage = evaluateBranchCoverage(branchesFile, tracesFiles);
        } catch (IOException e) {
            Log.printError(e.getMessage());
            throw new IllegalStateException("Branch coverage couldn't be evaluated!");
        }

        return new Message.MessageBuilder("/coverage/combined")
                .withParameter("branch_coverage", String.valueOf(branchCoverage))
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

        // tracks the number of total branches per class and method
        Map<String, Map<String, Integer>> branches = new HashMap<>();

        // tracks the number of visited branches per class and method
        Map<String, Map<String, Integer>> visitedBranches = new HashMap<>();

        // first argument refers to branches.txt
        InputStream branchesInputStream = new FileInputStream(branchesFile);
        BufferedReader branchesReader = new BufferedReader(new InputStreamReader(branchesInputStream));

        // read number of branches per class (each entry is unique)
        String line;
        while ((line = branchesReader.readLine()) != null) {
            // each line consists of className->methodName->branchID
            String[] triple = line.split("->");

            if (triple.length == 3) {
                // ignore the blank line of the branches.txt at the end
                String clazz = triple[0];
                String method = triple[1];
                branches.putIfAbsent(clazz, new HashMap<>());
                branches.get(clazz).merge(method, 1, Integer::sum);
            }
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

                String clazz = triple[0];
                String method = triple[1];

                if (!coveredTraces.contains(trace)) {
                    // only new traces are interesting
                    coveredTraces.add(trace);

                    // sum up how many branches have been visited by method and class
                    visitedBranches.putIfAbsent(clazz, new HashMap<>());
                    visitedBranches.get(clazz).merge(method, 1, Integer::sum);
                }
            }
            tracesReader.close();
        }

        double overallCoveredBranches = 0.0;
        double overallBranches = 0.0;

        for (String clazz : branches.keySet()) {

            for (String method : branches.get(clazz).keySet()) {

                // coverage per method

                double coveredBranches = 0.0;

                if (visitedBranches.containsKey(clazz) && visitedBranches.get(clazz).containsKey(method)) {
                    coveredBranches = visitedBranches.get(clazz).get(method);
                }

                double totalBranches = branches.get(clazz).get(method);

                if (coveredBranches > 0.0) {
                    Log.println("We have for the method " + clazz + "->" + method + " a branch coverage of "
                            + (coveredBranches / totalBranches * 100) + "%.");
                }
            }

            // coverage per class

            double coveredBranches = 0.0;

            if (visitedBranches.containsKey(clazz)) {
                coveredBranches = visitedBranches.get(clazz).values().stream().reduce(0, Integer::sum);
            }

            double totalBranches = branches.get(clazz).values().stream().reduce(0, Integer::sum);

            if (coveredBranches > 0.0) {
                Log.println("We have for the class " + clazz + " a branch coverage of "
                        + (coveredBranches / totalBranches * 100) + "%.");
            }

            // update total coverage
            overallCoveredBranches += coveredBranches;
            overallBranches += totalBranches;
        }

        // total branch coverage
        double branchCoverage = overallCoveredBranches / overallBranches * 100;
        Log.println("We have a total branch coverage of " + branchCoverage + "%.");

        return branchCoverage;
    }

    /**
     * Gets the list of traces files specified by the given chromosomes.
     *
     * @param tracesDir   The base directory containing the traces files.
     * @param chromosomes Encodes a mapping to one or several traces files.
     * @return Returns the list of traces files described by the given chromosomes.
     */
    private static List<File> getTraceFiles(File tracesDir, String chromosomes) {

        // collect the relevant traces files
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

        Log.println("Number of considered traces files: " + tracesFiles.size());
        return tracesFiles;
    }

}
