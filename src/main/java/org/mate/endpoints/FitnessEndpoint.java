package org.mate.endpoints;

import org.apache.commons.io.FileUtils;
import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.network.message.Messages;
import org.mate.util.AndroidEnvironment;
import org.mate.util.FitnessFunction;
import org.mate.util.Log;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handles requests related to storing and copying of fitness data.
 */
public class FitnessEndpoint implements Endpoint {

    private final AndroidEnvironment androidEnvironment;
    private final Path resultsPath;
    private final Path appsDir;

    private static final String BLOCKS_FILE = "blocks.txt";
    private static final String BRANCHES_FILE = "branches.txt";

    public FitnessEndpoint(AndroidEnvironment androidEnvironment, Path resultsPath, Path appsDir) {
        this.androidEnvironment = androidEnvironment;
        this.resultsPath = resultsPath;
        this.appsDir = appsDir;
    }

    @Override
    public Message handle(Message request) {

        if (request.getSubject().startsWith("/fitness/store_fitness_data")) {
            return storeFitnessData(request);
        } else if (request.getSubject().startsWith("/fitness/copy_fitness_data")) {
            return copyFitnessData(request);
        } else if (request.getSubject().startsWith("/fitness/get_branches")) {
            return getBranches(request);
        } else if (request.getSubject().startsWith("/fitness/get_basic_blocks")) {
            return getBasicBlocks(request);
        } else if (request.getSubject().startsWith("/fitness/get_basic_block_fitness_vector")) {
            return getBasicBlockFitnessVector(request);
        }
        throw new IllegalArgumentException("Message request with subject: "
                + request.getSubject() + " can't be handled by FitnessEndpoint!");
    }

    /**
     * Returns the branches of the AUT in the order they were recorded in the branches.txt file.
     *
     * @param request The message request.
     * @return Returns the branches of the AUT encapsulated in a message.
     */
    private Message getBranches(Message request) {

        String packageName = request.getParameter("packageName");
        Path appDir = appsDir.resolve(packageName);
        File branchesFile = appDir.resolve(BRANCHES_FILE).toFile();

        List<String> branches = new ArrayList<>();

        try (Stream<String> stream = Files.lines(branchesFile.toPath(), StandardCharsets.UTF_8)) {
            // hopefully this preserves the order (remove blank line at end)
            branches.addAll(stream.filter(line -> line.length() > 0).collect(Collectors.toList()));
        } catch (IOException e) {
            Log.printError("Reading branches.txt failed!");
            throw new IllegalStateException(e);
        }

        return new Message.MessageBuilder("/fitness/get_branches")
                .withParameter("branches", String.join("+", branches))
                .build();
    }

    /**
     * Returns the basic blocks of the AUT in the order they were recorded in the blocks.txt file.
     *
     * @param request The message request.
     * @return Returns the basic blocks of the AUT encapsulated in a message.
     */
    private Message getBasicBlocks(Message request) {

        String packageName = request.getParameter("packageName");
        Path appDir = appsDir.resolve(packageName);
        File basicBlocksFile = appDir.resolve(BLOCKS_FILE).toFile();

        List<String> basicBlocks = new ArrayList<>();

        try (var blocksReader = new BufferedReader(new InputStreamReader(new FileInputStream(basicBlocksFile)))) {

            // an entry looks as follows: class name -> method name -> block id -> block size -> isBranch
            // where the entries are ordered per method based on the block id
            String line;
            while ((line = blocksReader.readLine()) != null) {

                final String[] tokens = line.split("->");
                final String clazz = tokens[0];
                final String method = tokens[1];
                final String basicBlockID = tokens[2];

                final String basicBlock = clazz + "->" + method + "->" + basicBlockID;
                basicBlocks.add(basicBlock);
            }
        } catch (IOException e) {
            Log.printError("Can't read from basic blocks file!");
            throw new IllegalArgumentException(e);
        }

        String blocks = String.join("+", basicBlocks);
        return new Message.MessageBuilder("/fitness/get_basic_blocks")
                .withParameter("blocks", blocks)
                .build();
    }

    private Message getBasicBlockFitnessVector(Message request) {

        String packageName = request.getParameter("packageName");
        String chromosomes = request.getParameter("chromosomes");

        Path appDir = appsDir.resolve(packageName);
        File basicBlocksFile = appDir.resolve(BLOCKS_FILE).toFile();

        // a linked hashset maintains insertion order and contains is in O(1)
        Set<String> basicBlocks = new LinkedHashSet<>();

        try (Stream<String> stream = Files.lines(basicBlocksFile.toPath(), StandardCharsets.UTF_8)) {
            // hopefully this preserves the order
            basicBlocks.addAll(stream.collect(Collectors.toList()));
        } catch (IOException e) {
            Log.printError("Reading blocks.txt failed!");
            throw new IllegalStateException(e);
        }

        // collect the traces files described by the chromosomes
        Path tracesDir = appDir.resolve("traces");
        List<File> tracesFiles = getTraceFiles(tracesDir.toFile(), chromosomes);

        Set<String> traces = readTraces(tracesFiles);
        List<String> basicBlockFitnessVector = new ArrayList<>(basicBlocks.size());

        for (String basicBlock : basicBlocks) {
            if (traces.contains(basicBlock)) {
                // covered basic block
                basicBlockFitnessVector.add(String.valueOf(1));
            } else {
                // uncovered basic block
                basicBlockFitnessVector.add(String.valueOf(0));
            }
        }

        Log.println("Basic Block Fitness Vector: " + basicBlockFitnessVector);

        return new Message.MessageBuilder("/fitness/get_basic_block_fitness_vector")
                .withParameter("basic_block_fitness_vector", String.join("+", basicBlockFitnessVector))
                .build();
    }

    /**
     * Reads the traces from the given list of traces files.
     *
     * @param tracesFiles A list of traces files.
     * @return Returns the unique traces contained in the given traces files.
     */
    private Set<String> readTraces(List<File> tracesFiles) {

        // read traces from trace file(s)
        long start = System.currentTimeMillis();

        Set<String> traces = new HashSet<>();

        for (File traceFile : tracesFiles) {
            try (Stream<String> stream = Files.lines(traceFile.toPath(), StandardCharsets.UTF_8)) {
                traces.addAll(stream.collect(Collectors.toList()));
            } catch (IOException e) {
                Log.println("Reading traces.txt failed!");
                throw new IllegalStateException(e);
            }
        }

        long end = System.currentTimeMillis();
        Log.println("Reading traces from file(s) took: " + (end - start) + " seconds");

        Log.println("Number of collected traces: " + traces.size());
        return traces;
    }

    /**
     * Gets the list of traces files specified by the given chromosomes.
     *
     * @param tracesDir   The base directory containing the traces files.
     * @param chromosomes Encodes a mapping to one or several traces files.
     * @return Returns the list of traces files described by the given chromosomes.
     */
    private List<File> getTraceFiles(File tracesDir, String chromosomes) {

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

    /**
     * Copies the fitness data from one chromosome to the other.
     *
     * @param request The request specifying the operation.
     * @return Returns a message describing the success/failure of the operation.
     */
    private Message copyFitnessData(Message request) {

        String fitnessFunction = request.getParameter("fitnessFunction");

        switch (FitnessFunction.valueOf(fitnessFunction)) {
            case BRANCH_COVERAGE:
            case BRANCH_DISTANCE:
            case BRANCH_DISTANCE_MULTI_OBJECTIVE:
                return copyBranchFitnessData(request);
            case LINE_COVERAGE:
            case LINE_PERCENTAGE_COVERAGE:
                return copyLineFitnessData(request);
            case BASIC_BLOCK_LINE_COVERAGE:
            case BASIC_BLOCK_BRANCH_COVERAGE:
            case BASIC_BLOCK_MULTI_OBJECTIVE:
                return copyBasicBlockFitnessData(request);
            case METHOD_COVERAGE:
                return copyMethodFitnessData(request);
            default:
                final String errorMsg = "Fitness function " + fitnessFunction + " not yet supported!";
                Log.printError(errorMsg);
                return Messages.errorMessage(errorMsg);
        }
    }

    /**
     * Copies the fitness data, i.e. traces of test cases, specified through the list of entities
     * from the source chromosome (test suite) to the target chromosome (test suite).
     *
     * @param request The request specifying the operation.
     * @return Returns a message describing the success/failure of the operation.
     */
    private Message copyMethodFitnessData(Message request) {

        String deviceID = request.getParameter("deviceId");
        String sourceChromosome = request.getParameter("chromosome_src");
        String targetChromosome = request.getParameter("chromosome_target");
        String[] entities = request.getParameter("entities").split(",");

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
        return new Message("/fitness/copy_fitness_data");
    }

    /**
     * Copies the fitness data, i.e. traces of test cases, specified through the list of entities
     * from the source chromosome (test suite) to the target chromosome (test suite).
     *
     * @param request The request specifying the operation.
     * @return Returns a message describing the success/failure of the operation.
     */
    private Message copyBasicBlockFitnessData(Message request) {

        String deviceID = request.getParameter("deviceId");
        String sourceChromosome = request.getParameter("chromosome_src");
        String targetChromosome = request.getParameter("chromosome_target");
        String[] entities = request.getParameter("entities").split(",");

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
        return new Message("/fitness/copy_fitness_data");
    }

    /**
     * Copies the fitness data, i.e. traces of test cases, specified through the list of entities
     * from the source chromosome (test suite) to the target chromosome (test suite).
     *
     * @param request The request specifying the operation.
     * @return Returns a message describing the success/failure of the operation.
     */
    private Message copyBranchFitnessData(Message request) {

        String deviceID = request.getParameter("deviceId");
        String sourceChromosome = request.getParameter("chromosome_src");
        String targetChromosome = request.getParameter("chromosome_target");
        String[] entities = request.getParameter("entities").split(",");

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
        return new Message("/fitness/copy_fitness_data");
    }

    /**
     * Copies the fitness data, i.e. coverage data of test cases, specified through the list of entities
     * from the source chromosome (test suite) to the target chromosome (test suite).
     *
     * @param request The request specifying the operation.
     * @return Returns a message describing the success/failure of the operation.
     */
    private Message copyLineFitnessData(Message request) {

        var deviceId = request.getParameter("deviceId");
        var packageName = Device.getDevice(deviceId).getPackageName();
        var chromosomeSrc = request.getParameter("chromosome_src");
        var chromosomeTarget = request.getParameter("chromosome_target");
        var entities = request.getParameter("entities").split(",");
        var srcDir = getCoverageChromosomeDir(packageName, chromosomeSrc);
        var targetDir = getCoverageChromosomeDir(packageName, chromosomeTarget);

        if (!targetDir.toFile().mkdirs() && !targetDir.toFile().isDirectory()) {
            final var errorMsg = "Chromosome copy failed: target directory could not be created.";
            Log.printError(errorMsg);
            return Messages.errorMessage(errorMsg);
        }
        for (String entity : entities) {

            if (targetDir.resolve(entity).toFile().exists()) {
                // line coverage data of chromosome have been already copied previously
                continue;
            }

            try {
                Files.copy(srcDir.resolve(entity), targetDir.resolve(entity));
            } catch (IOException e) {
                final var errorMsg = "Chromosome copy failed: entity " + entity + " could not be copied from "
                        + srcDir + " to " + targetDir;
                Log.printError(errorMsg);
                return Messages.errorMessage(errorMsg);
            }
        }
        return new Message("/fitness/copy_fitness_data");
    }

    /**
     * Stores the fitness data of the given chromosome.
     *
     * @param request The request specifying the operation.
     * @return Returns a message describing the success/failure of the operation.
     */
    private Message storeFitnessData(Message request) {

        String fitnessFunction = request.getParameter("fitnessFunction");

        switch (FitnessFunction.valueOf(fitnessFunction)) {
            case BRANCH_COVERAGE:
            case BRANCH_DISTANCE:
            case BRANCH_DISTANCE_MULTI_OBJECTIVE:
                return storeBranchFitnessData(request);
            case LINE_COVERAGE:
            case LINE_PERCENTAGE_COVERAGE:
                return storeLineFitnessData(request);
            case BASIC_BLOCK_BRANCH_COVERAGE:
            case BASIC_BLOCK_LINE_COVERAGE:
            case BASIC_BLOCK_MULTI_OBJECTIVE:
                return storeBasicBlockFitnessData(request);
            case METHOD_COVERAGE:
                return storeMethodFitnessData(request);
            default:
                final String errorMsg = "Fitness function " + fitnessFunction + " not yet supported!";
                Log.printError(errorMsg);
                return Messages.errorMessage(errorMsg);
        }
    }

    /**
     * Returns the coverage base dir residing within the apps directory. Only used in combination with
     * line coverage so far.
     *
     * @param packageName The package name of the AUT.
     * @return Returns the coverage base dir of the given app.
     */
    private Path getCoverageBaseDir(String packageName) {
        return appsDir.resolve(packageName).resolve("coverage");
    }

    /**
     * Returns the coverage directory of the given chromosome. Only used in combination with
     * line coverage so far.
     *
     * @param packageName The package name of the AUT.
     * @param chromosome A chromosome identifier.
     * @return Returns the coverage directory of the given chromosome.
     */
    private Path getCoverageChromosomeDir(String packageName, String chromosome) {
        return getCoverageBaseDir(packageName).resolve(chromosome);
    }

    /**
     * Stores the fitness data of the given chromosome.
     *
     * @param request The request specifying the operation.
     * @return Returns a message describing the success/failure of the operation.
     */
    private Message storeMethodFitnessData(Message request) {

        String deviceID = request.getParameter("deviceId");
        // TODO: use packageName of device
        String packageName = request.getParameter("packageName");
        String chromosome = request.getParameter("chromosome");
        String entity = request.getParameter("entity");

        // grant read/write permission on external storage
        Device device = Device.getDevice(deviceID);
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

        // fetch the traces from emulator
        device.pullTraceFile(chromosome, entity);
        return new Message("/fitness/store_fitness_data");
    }

    /**
     * Stores the fitness data of the given chromosome.
     *
     * @param request The request specifying the operation.
     * @return Returns a message describing the success/failure of the operation.
     */
    private Message storeBasicBlockFitnessData(Message request) {

        String deviceID = request.getParameter("deviceId");
        // TODO: use packageName of device
        String packageName = request.getParameter("packageName");
        String chromosome = request.getParameter("chromosome");
        String entity = request.getParameter("entity");

        // grant read/write permission on external storage
        Device device = Device.getDevice(deviceID);
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

        // fetch the traces from emulator
        device.pullTraceFile(chromosome, entity);
        return new Message("/fitness/store_fitness_data");
    }

    /**
     * Stores the fitness data of the given chromosome.
     *
     * @param request The request specifying the operation.
     * @return Returns a message describing the success/failure of the operation.
     */
    private Message storeLineFitnessData(Message request) {

        var deviceId = request.getParameter("deviceId");
        var packageName = Device.getDevice(deviceId).getPackageName();
        var chromosome = request.getParameter("chromosome");
        var entity = request.getParameter("entity");

        var coverageDir = getCoverageChromosomeDir(packageName, chromosome);
        coverageDir.toFile().mkdirs();
        Path coverageFile;
        if (entity == null) {
            try {
                coverageFile = Files.createTempFile(coverageDir, null, null);
            } catch (IOException e) {
                final var errorMsg = "Failed to create coverage file: " + e.toString() + "\n" + e.fillInStackTrace();
                Log.printError(errorMsg);
                return Messages.errorMessage(errorMsg);
            }
        } else {
            coverageFile = coverageDir.resolve(entity);
        }

        //Close app in order to start coverage write to internal app storage
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(),
                "-s",
                deviceId,
                "shell",
                "input",
                "keyevent",
                "3");
        //Start app to restore original state of the emulator
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(),
                "-s",
                deviceId,
                "shell",
                "monkey",
                "-p",
                packageName,
                "1");

        //Extract coverage from internal app storage to local coverage file
        if (!ProcessRunner.runProcess(coverageFile,
                null,
                androidEnvironment.getAdbExecutable(),
                "-s",
                deviceId,
                "exec-out",
                "run-as",
                packageName,
                "cat",
                "files/coverage.exec").isOk()) {
            final var errorMsg = "Failed to extract coverage file from internal app storage";
            Log.printError(errorMsg);
            return Messages.errorMessage(errorMsg);
        }
        return new Message("/fitness/store_fitness_data");
    }

    /**
     * Stores the fitness data of the given chromosome.
     *
     * @param request The request specifying the operation.
     * @return Returns a message describing the success/failure of the operation.
     */
    private Message storeBranchFitnessData(Message request) {

        String deviceID = request.getParameter("deviceId");
        // TODO: use packageName of device
        String packageName = request.getParameter("packageName");
        String chromosome = request.getParameter("chromosome");
        String entity = request.getParameter("entity");

        // grant read/write permission on external storage
        Device device = Device.getDevice(deviceID);
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

        // fetch the traces from emulator
        device.pullTraceFile(chromosome, entity);
        return new Message("/fitness/store_fitness_data");
    }
}
