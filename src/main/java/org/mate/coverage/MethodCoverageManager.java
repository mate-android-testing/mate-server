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
 * Handles requests related to method coverage.
 */
public class MethodCoverageManager {

    /**
     * The name of the file that contains all the instrumented methods.
     */
    private static final String METHODS_FILE = "methods.txt";

    /**
     * The name of the directory where the traces have been stored.
     */
    private static final String TRACES_DIR = "traces";

    /**
     * The total number of methods.
     */
    private static Integer numberOfMethods = null;

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
     * Stores the method coverage data for a chromosome, which can be either a test case or a test suite.
     *
     * @param androidEnvironment Defines the location of the adb/aapt binary.
     * @param deviceID           The id of the emulator, e.g. emulator-5554.
     * @param chromosome         Identifies either a test case or a test suite.
     * @param entity             Identifies a test case if chromosome refers to
     *                           a test suite, otherwise {@code null}.
     * @return Returns a dummy message on success.
     */
    public static Message storeCoverageData(AndroidEnvironment androidEnvironment, String deviceID,
                                            String chromosome, String entity) {

        Device device = Device.devices.get(deviceID);
        device.pullTraces(chromosome, entity);
        return new Message("/coverage/store");
    }

    /**
     * Computes the method coverage of a single test case within a test suite.
     *
     * @param appsDir     The apps directory.
     * @param packageName The package name of the AUT.
     * @param testSuiteId The id of the test suite.
     * @param testCaseId  The id of the test case.
     * @return Returns the method coverage for a set of chromosomes.
     */
    public static Message getCoverage(Path appsDir, String packageName, String testSuiteId, String testCaseId) {

        // get list of traces file
        File appDir = new File(appsDir.toFile(), packageName);
        File tracesDir = new File(appDir, TRACES_DIR);

        // the methods.txt should be located within the app directory
        File methodsFile = new File(appDir, METHODS_FILE);

        // the trace file corresponding to the test case within the given test suite
        File traceFile = tracesDir.toPath().resolve(testSuiteId).resolve(testCaseId).toFile();

        double methodCoverage = 0d;

        try {
            methodCoverage = evaluateMethodCoverage(methodsFile, Lists.newArrayList(traceFile));
        } catch (IOException e) {
            Log.printError(e.getMessage());
            throw new IllegalStateException("Method coverage couldn't be evaluated!");
        }

        return new Message.MessageBuilder("/coverage/get")
                .withParameter("method_coverage", String.valueOf(methodCoverage))
                .build();
    }

    /**
     * Computes the (combined) method coverage for a set of test cases/test suites.
     *
     * @param packageName The package name of the AUT.
     * @param chromosomes A list of chromosomes separated by '+'.
     * @return Returns the (combined) method coverage for a set of chromosomes.
     */
    public static Message getCombinedCoverage(Path appsDir, String packageName, String chromosomes) {

        // get list of traces file
        File appDir = new File(appsDir.toFile(), packageName);
        File tracesDir = new File(appDir, TRACES_DIR);

        // the methods.txt should be located within the app directory
        File methodsFile = new File(appDir, METHODS_FILE);

        // only consider the traces files described by the chromosome ids
        List<File> tracesFiles = getTraceFiles(tracesDir, chromosomes);

        // evaluate method coverage over all traces files
        double methodCoverage = 0d;

        try {
            methodCoverage = evaluateMethodCoverage(methodsFile, tracesFiles);
        } catch (IOException e) {
            Log.printError(e.getMessage());
            throw new IllegalStateException("Method coverage couldn't be evaluated!");
        }

        return new Message.MessageBuilder("/coverage/combined")
                .withParameter("method_coverage", String.valueOf(methodCoverage))
                .build();
    }

    /**
     * Evaluates the method coverage for a given set of traces files. Can be used
     * to evaluate the method coverage for a single test case as well as the combined coverage.
     *
     * @param methodsFile The methods.txt file listing all the instrumented methods.
     * @param tracesFiles The set of traces file.
     * @return Returns the method coverage for a single test case or the combined coverage.
     * @throws IOException Should never happen.
     */
    @SuppressWarnings("unused")
    private static double evaluateMethodCoverageDetailed(File methodsFile, List<File> tracesFiles) throws IOException {

        Log.println("MethodsFile: " + methodsFile + "[" + methodsFile.exists() + "]");

        for (File tracesFile : tracesFiles) {
            Log.println("TracesFile: " + tracesFile + "[" + tracesFile.exists() + "]");
        }

        // tracks the number of methods per class
        Map<String, Integer> methods = new HashMap<>();

        // tracks the number of visited methods per class
        Map<String, Integer> visitedMethods = new HashMap<>();

        // first argument refers to methods.txt
        InputStream methodsInputStream = new FileInputStream(methodsFile);
        BufferedReader methodsReader = new BufferedReader(new InputStreamReader(methodsInputStream));

        // read number of methods per class (the entries are unique)
        String line;
        while ((line = methodsReader.readLine()) != null) {
            // each line consists of className->methodName
            String[] tuple = line.split("->");
            String clazz = tuple[0];
            methods.merge(clazz, 1, Integer::sum);
        }

        methodsReader.close();
        Set<String> coveredTraces = new HashSet<>();

        // second argument refers to traces.txt file(s)
        for (File tracesFile : tracesFiles) {
            InputStream tracesInputStream = new FileInputStream(tracesFile);
            BufferedReader tracesReader = new BufferedReader(new InputStreamReader(tracesInputStream));

            // read the traces
            String trace;
            while ((trace = tracesReader.readLine()) != null) {

                // each trace consists of className->methodName
                String[] tuple = trace.split("->");

                if (tuple.length == 2) {

                    if (!coveredTraces.contains(trace)) {
                        // only new traces are interesting
                        coveredTraces.add(trace);

                        String clazz = tuple[0];

                        // sum up how many methods have been visited per class
                        visitedMethods.merge(clazz, 1, Integer::sum);
                    }
                }
            }
            tracesReader.close();
        }

        double overallCoveredMethods = 0.0;
        double overallMethods = 0.0;

        for (String clazz : methods.keySet()) {

            // coverage per class
            double coveredMethods = visitedMethods.getOrDefault(clazz, 0);
            double totalMethods = methods.get(clazz);

            if (coveredMethods > 0.0) {
                Log.println("We have for the class " + clazz + " a method coverage of "
                        + (coveredMethods / totalMethods * 100) + "%.");
            }

            // update total coverage
            overallCoveredMethods += coveredMethods;
            overallMethods += totalMethods;
        }

        // total method coverage
        double methodCoverage = overallCoveredMethods / overallMethods * 100;
        Log.println("We have a total method coverage of " + methodCoverage + "%.");

        return methodCoverage;
    }

    /**
     * Evaluates the method coverage for a given set of traces files. Can be used
     * to evaluate the method coverage for a single test case as well as the combined coverage.
     *
     * @param methodsFile The methods.txt file listing all the instrumented methods.
     * @param tracesFiles The set of traces file.
     * @return Returns the method coverage for a single test case or the combined coverage.
     * @throws IOException Should never happen.
     */
    private static double evaluateMethodCoverage(File methodsFile, List<File> tracesFiles) throws IOException {

        Log.println("MethodsFile: " + methodsFile + "[" + methodsFile.exists() + "]");

        for (File tracesFile : tracesFiles) {
            Log.println("TracesFile: " + tracesFile + "[" + tracesFile.exists() + "]");
        }

        if (numberOfMethods == null) {
            // we only need to read the methods.txt file once
            numberOfMethods = (int) Files.lines(methodsFile.toPath()).count();
        }

        Set<String> coveredMethods = new HashSet<>();
        int numberOfCoveredMethods = 0;

        for (var traceFile : tracesFiles) {
            try (var tracesReader = new BufferedReader(new InputStreamReader(new FileInputStream(traceFile)))) {

                // a trace looks as follows: class name -> method name
                String trace;
                while ((trace = tracesReader.readLine()) != null) {
                    final String[] tuple = trace.split("->");
                    if (tuple.length == 2) { // prevent malformed traces
                        coveredMethods.add(trace);
                    }
                }
            }
        }

        numberOfCoveredMethods = coveredMethods.size();
        coveredMethods.clear();

        // total method coverage
        double methodCoverage = (double) numberOfCoveredMethods / numberOfMethods * 100;
        Log.println("We have a total method coverage of " + methodCoverage + "%.");
        return methodCoverage;
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
