package org.mate.coverage;

import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.mate.io.Device;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles requests where multiple coverage criteria are required, i.e. a combination of method, branch and line coverage.
 */
public class AllCoverageManager {

    /**
     * The name of the file that contains all the instrumented blocks.
     */
    private static final String BLOCKS_FILE = "blocks.txt";

    /**
     * The name of the directory where the traces have been stored.
     */
    private static final String TRACES_DIR = "traces";

    /**
     * The total number of instructions.
     */
    private static Integer numberOfInstructions = null;

    /**
     * The total number of branches.
     */
    private static Integer numberOfBranches = null;

    /**
     * The total number of methods.
     */
    private static Integer numberOfMethods = null;

    /**
     * Stores the 'all coverage' data for a chromosome, which can be either a test case or a test suite.
     * <p>
     * First, a broadcast is sent to the AUT in order to write out a traces.txt file on the external storage.
     * Second, this traces.txt is pulled from the emulator and saved on a pre-defined location (app directory/traces).
     *
     * @param androidEnvironment Defines the location of the adb/aapt binary.
     * @param deviceID The id of the emulator, e.g. emulator-5554.
     * @param chromosome Identifies either a test case or a test suite.
     * @param entity Identifies a test case if chromosome refers to
     *         a test suite, otherwise {@code null}.
     * @return Returns an empty message.
     */
    public static Message storeCoverageData(AndroidEnvironment androidEnvironment, String deviceID,
                                            String chromosome, String entity) {

        Device device = Device.devices.get(deviceID);
        device.getTracesFromTracer();
        device.pullTraceFile(chromosome, entity);
        return new Message("/coverage/store");
    }

    /**
     * Computes the (combined) 'all coverage' for a set of test cases/test suites.
     *
     * @param packageName The package name of the AUT.
     * @param chromosomes A list of chromosomes separated by '+'.
     * @return Returns the (combined) coverage for a set of chromosomes.
     */
    public static Message getCombinedCoverage(Path appsDir, String packageName, String chromosomes) {

        // get list of traces file
        File appDir = new File(appsDir.toFile(), packageName);
        File tracesDir = new File(appDir, TRACES_DIR);

        // the blocks.txt should be located within the app directory
        File blocksFile = new File(appDir, BLOCKS_FILE);

        // only consider the traces files described by the chromosome ids
        List<File> tracesFiles = getTraceFiles(tracesDir, chromosomes);

        // evaluate 'all coverage' over all traces files
        CoverageDTO coverage = evaluateCoverage(blocksFile, tracesFiles);

        return new Message.MessageBuilder("/coverage/combined")
                .withParameter("method_coverage", String.valueOf(coverage.getMethodCoverage()))
                .withParameter("branch_coverage", String.valueOf(coverage.getBranchCoverage()))
                .withParameter("line_coverage", String.valueOf(coverage.getLineCoverage()))
                .build();
    }

    /**
     * Copies the coverage data, i.e. traces of test cases, specified through the list of entities
     * from the source chromosome (test suite) to the target chromosome (test suite).
     *
     * @param appsDir The apps directory containing all app directories.
     * @param packageName The package name of the AUT.
     * @param sourceChromosome The source chromosome (test suite).
     * @param targetChromosome The target chromosome (test suite).
     * @param entities A list of test cases.
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
            throw new IllegalStateException(errorMsg);
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
                throw new IllegalStateException(errorMsg, e);
            }
        }
        return new Message("/coverage/copy");
    }

    /**
     * Computes the 'all coverage' of a single test case within a test suite.
     *
     * @param appsDir The apps directory.
     * @param packageName The package name of the AUT.
     * @param testSuiteId The id of the test suite.
     * @param testCaseId The id of the test case.
     * @return Returns the 'all coverage' for the given test case.
     */
    public static Message getCoverage(Path appsDir, String packageName, String testSuiteId, String testCaseId) {

        // get list of traces file
        File appDir = new File(appsDir.toFile(), packageName);
        File tracesDir = new File(appDir, TRACES_DIR);

        // the blocks.txt should be located within the app directory
        File blocksFile = new File(appDir, BLOCKS_FILE);

        // the trace file corresponding to the test case within the given test suite
        File traceFile = tracesDir.toPath().resolve(testSuiteId).resolve(testCaseId).toFile();

        // evaluate 'all coverage' over all traces files
        CoverageDTO coverage = evaluateCoverage(blocksFile, Lists.newArrayList(traceFile));

        return new Message.MessageBuilder("/coverage/get")
                .withParameter("method_coverage", String.valueOf(coverage.getMethodCoverage()))
                .withParameter("branch_coverage", String.valueOf(coverage.getBranchCoverage()))
                .withParameter("line_coverage", String.valueOf(coverage.getLineCoverage()))
                .build();
    }

    /**
     * Evaluates the 'all coverage' for a given set of traces files. Reports coverage stats on a per class basis.
     *
     * @param basicBlocksFile The blocks.txt file listing for each class the basic blocks.
     * @param tracesFiles The set of traces file.
     * @return Returns a list containing the method, branch and line coverage.
     * @throws IOException Should never happen.
     */
    @SuppressWarnings("unused")
    private static CoverageDTO evaluateCoverageDetailed(File basicBlocksFile, List<File> tracesFiles) throws IOException {

        Log.println("BasicBlocksFile: " + basicBlocksFile + "[" + basicBlocksFile.exists() + "]");

        for (File tracesFile : tracesFiles) {
            Log.println("TracesFile: " + tracesFile + "[" + tracesFile.exists() + "]");
        }

        CoverageDTO coverage = new CoverageDTO();
        coverage.setMethodCoverage(evaluateMethodCoverage(basicBlocksFile, tracesFiles));
        coverage.setBranchCoverage(evaluateBranchCoverage(basicBlocksFile, tracesFiles));
        coverage.setLineCoverage(evaluateLineCoverage(basicBlocksFile, tracesFiles));
        return coverage;
    }

    /**
     * Reads the blocks.txt file and computes the total number of methods, branches and instructions.
     *
     * @param basicBlocksFile The blocks.txt file.
     */
    private static void readBlocksFile(File basicBlocksFile) {

        numberOfInstructions = 0;
        numberOfBranches = 0;
        numberOfMethods = 0;

        final Set<String> methods = new HashSet<>();

        try (var blocksReader = new BufferedReader(new InputStreamReader(new FileInputStream(basicBlocksFile)))) {

            // an entry looks as follows: class name -> method name -> block id -> block size -> isBranch
            String block;
            while ((block = blocksReader.readLine()) != null) {

                final String[] tuple = block.split("->");
                if (tuple.length == 5) {

                    final String method = tuple[0].trim() + "->" + tuple[1].trim();
                    final int blockSize = Integer.parseInt(tuple[3].trim());
                    final boolean isBranch = tuple[4].equals("isBranch");

                    if (methods.add(method)) {
                        // we deal with a new method
                        numberOfMethods++;
                    }

                    numberOfInstructions += blockSize;

                    if (isBranch) {
                        numberOfBranches++;
                    }
                } else {
                    Log.println("Malformed entry in " + basicBlocksFile + ": " + block);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't read from " + basicBlocksFile + "!", e);
        } finally {
            methods.clear(); // give free for garbage collection
        }
    }

    /**
     * Evaluates the 'all coverage' for a given set of traces files.
     *
     * @param basicBlocksFile The blocks.txt file listing for each class the basic blocks.
     * @param tracesFiles The set of traces file.
     * @return Returns a coverage dto containing method, branch and line coverage information.
     */
    private static CoverageDTO evaluateCoverage(File basicBlocksFile, List<File> tracesFiles) {

        Log.println(String.format("BasicBlocksFile: %s [Exists: %b]", basicBlocksFile, basicBlocksFile.exists()));

        for (File tracesFile : tracesFiles) {
            Log.println(String.format("TracesFile: %s [Exists: %b]", tracesFile, tracesFile.exists()));
        }

        if (numberOfInstructions == null) {
            // we only need to read the blocks.txt file once
            readBlocksFile(basicBlocksFile);
        }

        int numberOfCoveredMethods = 0;
        int numberOfCoveredBranches = 0;
        int numberOfCoveredInstructions = 0;

        final Set<String> coveredTraces = new HashSet<>();
        final Set<String> coveredMethods = new HashSet<>();

        for (var traceFile : tracesFiles) {
            try (var tracesReader = new BufferedReader(new InputStreamReader(new FileInputStream(traceFile)))) {

                // a trace looks as follows: class name -> method name -> block id -> block size -> isBranch
                String trace;
                while ((trace = tracesReader.readLine()) != null) {

                    if (coveredTraces.add(trace)) {
                        // we deal with a new trace

                        final String[] tuple = trace.split("->");
                        if (tuple.length == 5) {

                            final String method = tuple[0].trim() + "->" + tuple[1].trim();
                            final int blockSize = Integer.parseInt(tuple[3].trim());
                            final boolean isBranch = tuple[4].equals("isBranch");

                            numberOfCoveredInstructions += blockSize;

                            if (coveredMethods.add(method)) {
                                numberOfCoveredMethods++;
                            }

                            if (isBranch) {
                                numberOfCoveredBranches++;
                            }
                        } else {
                            Log.println("Malformed trace in " + traceFile + ": " + trace);
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't read from " + traceFile + "!", e);
            }
        }

        // give free for garbage collection
        coveredMethods.clear();
        coveredTraces.clear();

        CoverageDTO coverageDTO = new CoverageDTO();

        double methodCoverage = (double) numberOfCoveredMethods / numberOfMethods * 100;
        coverageDTO.setMethodCoverage(methodCoverage);
        Log.println("We have a total method coverage of " + methodCoverage + "%.");

        double branchCoverage = (double) numberOfCoveredBranches / numberOfBranches * 100;
        coverageDTO.setBranchCoverage(branchCoverage);
        Log.println("We have a total branch coverage of " + branchCoverage + "%");

        double lineCoverage = (double) numberOfCoveredInstructions / numberOfInstructions * 100;
        coverageDTO.setLineCoverage(lineCoverage);
        Log.println("We have a total line coverage of " + lineCoverage + "%");

        return coverageDTO;
    }

    /**
     * Evaluates the method coverage for a given set of traces files. Can be used to evaluate the method coverage for
     * a single test case as well as the combined coverage.
     *
     * @param basicBlocksFile The blocks.txt file listing for each class the number of branches.
     * @param tracesFiles The set of traces file.
     * @return Returns the method coverage for a single test case or the combined coverage.
     * @throws IOException Should never happen.
     */
    private static Double evaluateMethodCoverage(File basicBlocksFile, List<File> tracesFiles) throws IOException {

        final Map<String, Integer> totalMethodsPerClass = totalMethodsPerClass(basicBlocksFile);
        final Map<String, Integer> coveredMethodsPerClass = coveredMethodsPerClass(tracesFiles);

        double overallCoveredMethods = 0.0;
        double overallMethods = 0.0;

        for (String clazz : totalMethodsPerClass.keySet()) {

            // coverage per class
            double coveredMethods = coveredMethodsPerClass.getOrDefault(clazz, 0);
            double totalMethods = totalMethodsPerClass.get(clazz);

            if (coveredMethods > 0.0) {
                Log.println("We have for the class " + clazz + " a method coverage of "
                        + (coveredMethods / totalMethods * 100) + "%.");
            }

            // update total coverage
            overallCoveredMethods += coveredMethods;
            overallMethods += totalMethods;
        }

        // total method coverage
        double totalMethodCoverage = overallCoveredMethods / overallMethods * 100;
        Log.println("We have a total method coverage of " + totalMethodCoverage + "%.");
        return totalMethodCoverage;
    }

    /**
     * Evaluates the line coverage for a given set of traces files. Can be used to evaluate the line coverage for a
     * single test case as well as the combined coverage.
     *
     * @param basicBlocksFile The blocks.txt file listing for each class the number of branches.
     * @param tracesFiles The set of traces file.
     * @return Returns the line coverage for a single test case or the combined coverage.
     * @throws IOException Should never happen.
     */
    private static double evaluateLineCoverage(File basicBlocksFile, List<File> tracesFiles) throws IOException {

        final Map<String, Integer> totalInstructionsPerClass = totalInstructionsPerClass(basicBlocksFile);
        final Map<String, Integer> coveredInstructionsPerClass = coveredInstructionsPerClass(tracesFiles);

        for (final String key : totalInstructionsPerClass.keySet()) {
            final float coveredInstructions = coveredInstructionsPerClass.getOrDefault(key, 0);
            final float totalInstructions = totalInstructionsPerClass.get(key);
            if (coveredInstructions > 0) {
                Log.println("We have for the class " + key + " a line coverage of: "
                        + coveredInstructions / totalInstructions * 100 + "%");
            }
        }

        final int totalInstructions = totalInstructionsPerClass.values().stream().mapToInt(Integer::intValue).sum();
        final int coveredInstructions = coveredInstructionsPerClass.values().stream().mapToInt(Integer::intValue).sum();
        final double totalLineCoverage = (double) coveredInstructions / (double) totalInstructions * 100d;
        Log.println("Total line coverage: " + totalLineCoverage + "%");
        return totalLineCoverage;
    }

    /**
     * Evaluates the branch coverage for a given set of traces files. Can be used to evaluate the branch coverage for a
     * single test case as well as the combined coverage.
     *
     * @param basicBlocksFile The blocks.txt file listing for each class the number of branches.
     * @param tracesFiles The set of traces file.
     * @return Returns the branch coverage for a single test case or the combined coverage.
     * @throws IOException Should never happen.
     */
    private static double evaluateBranchCoverage(File basicBlocksFile, List<File> tracesFiles) throws IOException {

        final Map<String, Integer> totalBranchesPerClass = totalBranchesPerClass(basicBlocksFile);
        final Map<String, Integer> coveredBranchesPerClass = coveredBranchesPerClass(tracesFiles);

        for (final String key : totalBranchesPerClass.keySet()) {
            final float coveredBranches = coveredBranchesPerClass.getOrDefault(key, 0);
            final float totalBranches = totalBranchesPerClass.get(key);
            if (coveredBranches > 0) {
                Log.println("We have for the class " + key + " a branch coverage of: "
                        + coveredBranches / totalBranches * 100 + "%");
            }
        }

        final int totalBranches = totalBranchesPerClass.values().stream().mapToInt(Integer::intValue).sum();
        final int coveredBranches = coveredBranchesPerClass.values().stream().mapToInt(Integer::intValue).sum();
        final double totalBranchCoverage = (double) coveredBranches / (double) totalBranches * 100d;
        Log.println("Total branch coverage: " + totalBranchCoverage + "%");
        return totalBranchCoverage;
    }

    /**
     * Retrieves the total number of methods per class.
     *
     * @param blocksFile The blocks.txt file listing each basic block.
     * @return Returns the total number of methods per class.
     * @throws IOException Should never happen.
     */
    private static Map<String, Integer> totalMethodsPerClass(File blocksFile) throws IOException {

        final Map<String, Integer> totalMethodsPerClass = new HashMap<>();
        final Set<String> coveredMethods = new HashSet<>();

        try (var blocksReader = new BufferedReader(new InputStreamReader(new FileInputStream(blocksFile)))) {

            // an entry looks as follows: class name -> method name -> block id -> block size -> isBranch
            String block;
            while ((block = blocksReader.readLine()) != null) {

                final String[] tokens = block.split("->");
                final String clazz = tokens[0];
                final String method = tokens[1];
                final String methodSignature = clazz + "->" + method;

                if (!coveredMethods.contains(methodSignature)) {

                    coveredMethods.add(methodSignature);

                    // aggregate methods count per class
                    totalMethodsPerClass.merge(clazz, 1, Integer::sum);
                }
            }
        }
        return totalMethodsPerClass;
    }

    /**
     * Computes the covered methods per class by traversing the given trace files.
     *
     * @param tracesFiles A list of trace files.
     * @return Returns a mapping of class to covered methods count.
     * @throws IOException Should never happen.
     */
    private static Map<String, Integer> coveredMethodsPerClass(List<File> tracesFiles) throws IOException {

        final Map<String, Integer> coveredMethodsPerClass = new HashMap<>();
        final Set<String> coveredMethods = new HashSet<>();

        for (var traceFile : tracesFiles) {
            try (var tracesReader = new BufferedReader(new InputStreamReader(new FileInputStream(traceFile)))) {

                // a trace looks as follows: class name -> method name -> basic block id (instruction index)
                // -> number of instructions of block -> isBranch
                String trace;
                while ((trace = tracesReader.readLine()) != null) {
                    final String[] tuple = trace.split("->");
                    if (tuple.length == 5) {

                        final String clazz = tuple[0].trim();
                        final String method = tuple[1].trim();
                        final String methodSignature = clazz + "->" + method;

                        if (!coveredMethods.contains(methodSignature)) {

                            coveredMethods.add(methodSignature);

                            // aggregate methods count per class
                            coveredMethodsPerClass.merge(clazz, 1, Integer::sum);
                        }
                    } else {
                        Log.printWarning("Found incomplete line \"" + trace + "\" in traces file \""
                                + traceFile + "\"");
                    }
                }
            }
        }
        return coveredMethodsPerClass;
    }

    /**
     * Retrieves the total number of branches per class.
     *
     * @param blocksFile The blocks.txt file listing for each class the number of branches.
     * @return Returns the total number of branches per class.
     * @throws IOException Should never happen.
     */
    private static Map<String, Integer> totalBranchesPerClass(final File blocksFile) throws IOException {

        final Map<String, Integer> totalBranchesPerClass = new HashMap<>();

        try (var blocksReader = new BufferedReader(new InputStreamReader(new FileInputStream(blocksFile)))) {

            // an entry looks as follows: class name -> method name -> block id -> block size -> isBranch
            String block;
            while ((block = blocksReader.readLine()) != null) {

                final String[] tokens = block.split("->");
                final String clazz = tokens[0];
                boolean isBranch = tokens[4].equals("isBranch");

                // aggregate branches count per class
                if (isBranch) {
                    // add 1 to current count
                    totalBranchesPerClass.merge(clazz, 1, Integer::sum);
                }
            }
        }
        return totalBranchesPerClass;
    }

    /**
     * Computes the covered branches per class by traversing the given trace files.
     *
     * @param tracesFiles A list of trace files.
     * @return Returns a mapping of class to covered branches count.
     * @throws IOException Should never happen.
     */
    private static Map<String, Integer> coveredBranchesPerClass(final List<File> tracesFiles) throws IOException {

        // stores a mapping of class -> (method -> basic block id) where a basic block can only contain a single branch!
        final Map<String, Map<String, Set<Integer>>> coveredBranches = new HashMap<>();
        for (var traceFile : tracesFiles) {
            try (var tracesReader = new BufferedReader(new InputStreamReader(new FileInputStream(traceFile)))) {

                // a trace looks as follows: class name -> method name -> basic block id (instruction index)
                // -> number of instructions of block -> isBranch
                String trace;
                while ((trace = tracesReader.readLine()) != null) {
                    final String[] tuple = trace.split("->");
                    if (tuple.length == 5) {

                        final String clazz = tuple[0].trim();
                        final String method = tuple[1].trim();
                        final Integer blockId = Integer.parseInt(tuple[2].trim());
                        final boolean isBranch = tuple[4].trim().equals("isBranch");

                        if (isBranch) {
                            // ignore duplicate traces
                            coveredBranches.putIfAbsent(clazz, new HashMap<>());
                            coveredBranches.get(clazz).putIfAbsent(method, new HashSet<>());
                            coveredBranches.get(clazz).get(method).add(blockId);
                        }
                    } else {
                        Log.printWarning("Found incomplete line \"" + trace + "\" in traces file \""
                                + traceFile + "\"");
                    }
                }
            }
        }

        final Map<String, Integer> coveredBranchesPerClass = new HashMap<>();
        coveredBranches.keySet().forEach(clazz -> {
            final int count = coveredBranches.get(clazz).values().stream().mapToInt(Set::size).sum();
            coveredBranchesPerClass.put(clazz, count);
        });
        return coveredBranchesPerClass;
    }

    /**
     * Computes the covered instructions per class by traversing the given trace files.
     *
     * @param tracesFiles A list of trace files.
     * @return Returns a mapping of class to covered instruction count.
     * @throws IOException Should never happen.
     */
    private static Map<String, Integer> coveredInstructionsPerClass(final List<File> tracesFiles) throws IOException {

        // stores a mapping of class -> (method -> (basic block -> number of instructions of block))
        final Map<String, Map<String, Map<Integer, Integer>>> coveredInstructions = new HashMap<>();

        for (final var traceFile : tracesFiles) {
            try (var tracesReader = new BufferedReader(new InputStreamReader(new FileInputStream(traceFile)))) {

                // a trace looks as follows: class name -> method name -> basic block id (instruction index)
                // -> number of instructions of block -> isBranch
                String trace;
                while ((trace = tracesReader.readLine()) != null) {
                    final String[] tuple = trace.split("->");
                    if (tuple.length == 5) {

                        final String clazz = tuple[0].trim();
                        final String method = tuple[1].trim();
                        final Integer blockId = Integer.parseInt(tuple[2].trim());
                        final int count = Integer.parseInt(tuple[3].trim());

                        // ignore duplicate traces
                        coveredInstructions.putIfAbsent(clazz, new HashMap<>());
                        coveredInstructions.get(clazz).putIfAbsent(method, new HashMap<>());
                        coveredInstructions.get(clazz).get(method).putIfAbsent(blockId, count);
                    } else {
                        Log.printWarning("Found incomplete line \"" + trace + "\" in traces file \"" + traceFile.toString() + "\"");
                    }
                }
            }
        }

        // group the covered instructions per class
        final Map<String, Integer> coveredInstructionsPerClass = new HashMap<>();
        coveredInstructions.keySet().forEach(clazz -> {
            final int coveredInstructionsCount = coveredInstructions.get(clazz).entrySet().stream()
                    .flatMap(e -> e.getValue().entrySet().stream()).mapToInt(Map.Entry::getValue).sum();
            coveredInstructionsPerClass.put(clazz, coveredInstructionsCount);
        });

        return coveredInstructionsPerClass;
    }

    /**
     * Retrieves the total number of instructions per class.
     *
     * @param blocksFile The block.txt file containing per method the number of instructions and branches.
     * @return Returns the total number of instructions per class.
     * @throws IOException Should never happen.
     */
    private static Map<String, Integer> totalInstructionsPerClass(final File blocksFile) throws IOException {

        final Map<String, Integer> totalInstructionsPerClass = new HashMap<>();

        try (var blocksReader = new BufferedReader(new InputStreamReader(new FileInputStream(blocksFile)))) {

            // an entry looks as follows: class name -> method name -> block id -> block size -> isBranch
            String block;
            while ((block = blocksReader.readLine()) != null) {

                final String[] tokens = block.split("->");
                final String clazz = tokens[0];
                final int instructionCount = Integer.parseInt(tokens[3]);

                // update aggregation count per class
                final int recorded = totalInstructionsPerClass.getOrDefault(clazz, 0);
                totalInstructionsPerClass.put(clazz, recorded + instructionCount);
            }
        }
        return totalInstructionsPerClass;
    }

    /**
     * Gets the list of traces files specified by the given chromosomes.
     *
     * @param tracesDir The base directory containing the traces files.
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
                    throw new IllegalStateException("Couldn't retrieve traces files!", e);
                }
            }
        }

        Log.println("Number of considered traces files: " + tracesFiles.size());
        return tracesFiles;
    }
}
