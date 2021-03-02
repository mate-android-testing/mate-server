package org.mate.endpoints;

import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.network.message.Messages;
import org.mate.util.AndroidEnvironment;
import org.mate.util.FitnessFunction;
import org.mate.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Handles requests related to storing and copying of fitness data.
 */
public class FitnessEndpoint implements Endpoint {

    private final AndroidEnvironment androidEnvironment;
    private final Path resultsPath;
    private final Path appsDir;

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
        }
        throw new IllegalArgumentException("Message request with subject: "
                + request.getSubject() + " can't be handled by FitnessEndpoint!");
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
                return copyBasicBlockFitnessData(request);
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
                return storeBasicBlockFitnessData(request);
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
