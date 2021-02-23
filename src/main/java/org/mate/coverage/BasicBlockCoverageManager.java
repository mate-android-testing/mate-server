package org.mate.coverage;

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

public final class BasicBlockCoverageManager {

    /**
     * Copies the coverage data, i.e. traces of test cases, specified through the list of entities
     * from the source chromosome (test suite) to the target chromosome (test suite).
     *
     * @param appsDir The apps directory containing all app directories.
     * @param deviceID The emulator identifier.
     * @param sourceChromosome The source chromosome (test suite).
     * @param targetChromosome The target chromosome (test suite).
     * @param entities A list of test cases.
     * @return Returns a message describing the success/failure of the operation.
     */
    public static Message copyCoverageData(Path appsDir, String deviceID, String sourceChromosome,
                                           String targetChromosome, String[] entities) {

        Device device = Device.devices.get(deviceID);
        String packageName = device.getPackageName();

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
     * Computes the (combined) branch coverage for a set of test cases/test suites.
     *
     * @param packageName The package name of the AUT.
     * @param chromosomes A list of chromosomes separated by '+'.
     * @return Returns the (combined) coverage for a set of chromosomes.
     */
    public static Message getCombinedBranchCoverage(Path appsDir, String packageName, String chromosomes) {
        return getCombinedCoverage(appsDir, packageName, chromosomes, false);
    }

    /**
     * Computes the (combined) line coverage for a set of test cases/test suites.
     *
     * @param packageName The package name of the AUT.
     * @param chromosomes A list of chromosomes separated by '+'.
     * @return Returns the (combined) coverage for a set of chromosomes.
     */
    public static Message getCombinedLineCoverage(Path appsDir, String packageName, String chromosomes) {
        return getCombinedCoverage(appsDir, packageName, chromosomes, true);
    }

    private static Message getCombinedCoverage(Path appsDir, String packageName, String chromosomes, boolean lineCoverage) {
        // get list of traces file
        File appDir = new File(appsDir.toFile(), packageName);
        File tracesDir = new File(appDir, "traces");

        // the branches.txt should be located within the app directory
        File blocksFile = new File(appDir, "blocks.txt");

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
        double coverage = 0d;

        try {
            if(lineCoverage) {
                coverage = evaluateLineCoverage(blocksFile, tracesFiles);
            } else {
                coverage = evaluateBranchCoverage(blocksFile, tracesFiles);
            }
        } catch (IOException e) {
            Log.printError(e.getMessage());
            final String kind = lineCoverage ? "Line" :"Block";
            throw new IllegalStateException(kind + " coverage couldn't be evaluated!");
        }

        return new Message.MessageBuilder("/coverage/combined")
                .withParameter("coverage", String.valueOf(coverage))
                .build();
    }

    /**
     * Evaluates the line coverage for a given set of traces files. Can be used
     * to evaluate the line coverage for a single test case as well as the combined coverage.
     *
     * @param basicBlocksFile The blocks.txt file listing for each class the number of branches.
     * @param tracesFiles  The set of traces file.
     * @return Returns the branch coverage for a single test case or the combined coverage.
     * @throws IOException Should never happen.
     */
    private static double evaluateLineCoverage(File basicBlocksFile, List<File> tracesFiles) throws IOException {
        Log.println("BasicBlocksFile: " + basicBlocksFile + "[" + basicBlocksFile.exists() + "]");

        for (File tracesFile : tracesFiles) {
            Log.println("TracesFile: " + tracesFile + "[" + tracesFile.exists() + "]");
        }

        final Map<String, Integer> totalInstructionsPerClass = totalInstructionsPerClass(basicBlocksFile);
        final Map<String, Integer> coveredInstructionsPerClass = coveredInstructionsPerClass(tracesFiles);

        for (final String key : totalInstructionsPerClass.keySet()) {
            final float coveredInstructions = coveredInstructionsPerClass.getOrDefault(key, 0);
            final float totalInstructions = totalInstructionsPerClass.get(key);
            if(coveredInstructions > 0) {
                Log.println("We have for the class " + key + " a line coverage of: " + coveredInstructions / totalInstructions * 100 + "%");
            }
        }

        final int totalInstructions = totalInstructionsPerClass.values().stream().mapToInt(Integer::intValue).sum();
        final int coveredInstructions = coveredInstructionsPerClass.values().stream().mapToInt(Integer::intValue).sum();
        final double totalLineCoverage = (double) coveredInstructions / (double) totalInstructions * 100d;
        Log.println("Total line coverage: " + totalLineCoverage + "%");
        return totalLineCoverage;
    }

    private static Map<String, Integer> coveredInstructionsPerClass(final List<File> tracesFiles) throws IOException {
        // The same basic blocks can be executed multiple times during a run
        // But for the coverage we only need to count each block once, even if it is executed multiple times
        // Class name -> Method name -> Basic block id -> Instruction count
        final Map<String, Map<String, Map<Integer, Integer>>> instruction_count = new HashMap<>();

        for (final var path : tracesFiles) {
            try (var traceReader = new BufferedReader(new InputStreamReader(new FileInputStream(path)))) {

                // Class name -> method name -> id -> instructions count -> isBranch
                String line;
                while ((line = traceReader.readLine()) != null) {
                    final String[] tuple = line.split("->");
                    if (tuple.length == 5) {
                        final String clazz = tuple[0].trim();
                        final String method = tuple[1].trim();
                        final Integer blockId = Integer.parseInt(tuple[2].trim());
                        final int count = Integer.parseInt(tuple[3].trim());

                        instruction_count.putIfAbsent(clazz, new HashMap<>());
                        instruction_count.get(clazz).putIfAbsent(method, new HashMap<>());
                        instruction_count.get(clazz).get(method).putIfAbsent(blockId, count);
                    } else {
                        Log.printWarning("Found incomplete line \"" + line + "\" in traces file \"" + path.toString() + "\"");
                    }
                }
            }
        }

        final Map<String, Integer> coveredInstructionsPerClass = new HashMap<>();
        instruction_count.keySet().forEach(clazz -> {
            final int coveredInstructions = instruction_count.get(clazz).entrySet().stream().flatMap(e -> e.getValue().entrySet().stream()).mapToInt(Map.Entry::getValue).sum();
            coveredInstructionsPerClass.put(clazz, coveredInstructions);
        });

        return coveredInstructionsPerClass;
    }

        private static  Map<String, Integer> totalInstructionsPerClass(final File blocksFile) throws IOException {
            // Class name -> Instruction count
            final Map<String, Integer> totalInstructionsPerClass = new HashMap<>();

            // Assumes there are not duplicate lines in the file
            try (var blocksReader = new BufferedReader(new InputStreamReader(new FileInputStream(blocksFile)))) {

                // Class name -> method name -> instructions count -> branches count
                String line;
                while ((line = blocksReader.readLine()) != null) {
                    final String[] tuple = line.split("->");
                    final String clazz = tuple[0].trim();
                    final int instruction_count = Integer.parseInt(tuple[2].trim());
                    final int recorded = totalInstructionsPerClass.getOrDefault(clazz, 0);
                    totalInstructionsPerClass.put(clazz, recorded + instruction_count);
                }
            }
            return totalInstructionsPerClass;
        }
    /**
     * Evaluates the branch coverage for a given set of traces files. Can be used
     * to evaluate the branch coverage for a single test case as well as the combined coverage.
     *
     * @param basicBlocksFile The blocks.txt file listing for each class the number of branches.
     * @param tracesFiles  The set of traces file.
     * @return Returns the branch coverage for a single test case or the combined coverage.
     * @throws IOException Should never happen.
     */
    private static double evaluateBranchCoverage(File basicBlocksFile, List<File> tracesFiles) throws IOException {
        Log.println("BasicBlocksFile: " + basicBlocksFile + "[" + basicBlocksFile.exists() + "]");

        for (File tracesFile : tracesFiles) {
            Log.println("TracesFile: " + tracesFile + "[" + tracesFile.exists() + "]");
        }

        final Map<String, Integer> totalBranchesPerClass = totalBranchesPerClass(basicBlocksFile);
        final Map<String, Integer> coveredBranchesPerClass = coveredBranchesPerClass(tracesFiles);

        for (final String key : totalBranchesPerClass.keySet()) {
            final float coveredBranches = coveredBranchesPerClass.getOrDefault(key, 0);
            final float totalBranches = totalBranchesPerClass.get(key);
            if(coveredBranches > 0) {
                Log.println("We have for the class " + key + " a branch coverage of: " + coveredBranches / totalBranches * 100 + "%");
            }
        }

        final int totalBranches = totalBranchesPerClass.values().stream().mapToInt(Integer::intValue).sum();
        final int coveredBranches = coveredBranchesPerClass.values().stream().mapToInt(Integer::intValue).sum();
        final double totalBranchCoverage = (double) coveredBranches / (double) totalBranches * 100d;
        Log.println("Total branch coverage: " + totalBranchCoverage + "%");
        return totalBranchCoverage;
    }

    private static  Map<String, Integer> totalBranchesPerClass(final File blocksFile) throws IOException {
        //Class name -> branch count
        final Map<String, Integer> totalBranchesPerClass = new HashMap<>();

        // Assumes there are not duplicate lines in the file
        try (var blocksReader = new BufferedReader(new InputStreamReader(new FileInputStream(blocksFile)))) {

            // Class name -> method name -> instructions count -> branches count
            String line;
            while ((line = blocksReader.readLine()) != null) {
                final String[] tuple = line.split("->");
                final String clazz = tuple[0].trim();
                final int noBranches = Integer.parseInt(tuple[3].trim());
                final int count = totalBranchesPerClass.getOrDefault(clazz, 0);
                totalBranchesPerClass.put(clazz, count + noBranches);
            }
        }
        return totalBranchesPerClass;
    }

    private static Map<String, Integer> coveredBranchesPerClass(final List<File>  tracesFiles) throws IOException {
        // The same basic blocks can be executed multiple times during a run
        // But for the coverage we only need to count each block once, even if it is executed multiple times

        // Class name -> Method name -> Basic block id
        final Map<String, Map<String, Set<Integer>>> covered_branches = new HashMap<>();
        for(var traceFile : tracesFiles) {
            try (var branchesReader = new BufferedReader(new InputStreamReader(new FileInputStream(traceFile)))) {
                // Class name -> method name -> id -> instructions count -> isBranch
                String line;
                while ((line = branchesReader.readLine()) != null) {
                    final String[] tuple = line.split("->");
                    if (tuple.length == 5) {
                        final String clazz = tuple[0].trim();
                        final String method = tuple[1].trim();
                        final Integer blockId = Integer.parseInt(tuple[2].trim());
                        final boolean isBranch = tuple[4].trim().equals("isBranch");

                        if (isBranch) {
                            covered_branches.putIfAbsent(clazz, new HashMap<>());
                            covered_branches.get(clazz).putIfAbsent(method, new HashSet<>());
                            covered_branches.get(clazz).get(method).add(blockId);
                        }
                    } else {
                        Log.printWarning("Found incomplete line \"" + line + "\" in traces file \"" + traceFile.toString() + "\"");
                    }
                }
            }
        }

        final Map<String, Integer> coveredBranchesPerClass = new HashMap<>();
        covered_branches.keySet().forEach(clazz -> {
            final int count = covered_branches.get(clazz).values().stream().mapToInt(Set::size).sum();
            coveredBranchesPerClass.put(clazz, count);
        });
        return coveredBranchesPerClass;
    }
}
