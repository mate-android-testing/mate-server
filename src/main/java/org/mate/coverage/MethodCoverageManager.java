package org.mate.coverage;

import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.network.message.Message;
import org.mate.network.message.Messages;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class MethodCoverageManager {

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
        File tracesDir = new File(appDir, "traces");

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
        File tracesDir = new File(appDir, "traces");

        // the methods.txt should be located within the app directory
        File methodsFile = new File(appDir, "methods.txt");

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
                .withParameter("coverage", String.valueOf(methodCoverage))
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

        // TODO: check whether it is necessary to pull again the last traces file

        // get list of traces file
        File appDir = new File(appsDir.toFile(), packageName);
        File tracesDir = new File(appDir, "traces");

        // the methods.txt should be located within the app directory
        File methodsFile = new File(appDir, "methods.txt");

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

        // evaluate method coverage over all traces files
        double methodCoverage = 0d;

        try {
            methodCoverage = evaluateMethodCoverage(methodsFile, tracesFiles);
        } catch (IOException e) {
            Log.printError(e.getMessage());
            throw new IllegalStateException("Method coverage couldn't be evaluated!");
        }

        return new Message.MessageBuilder("/coverage/combined")
                .withParameter("coverage", String.valueOf(methodCoverage))
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
    private static double evaluateMethodCoverage(File methodsFile, List<File> tracesFiles) throws IOException {

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
}
