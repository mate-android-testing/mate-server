package org.mate.endpoints;

import org.mate.coverage.CoverageManager;
import org.mate.network.message.Messages;
import org.mate.util.AndroidEnvironment;
import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.network.message.Message;
import org.mate.network.Endpoint;
import org.mate.util.Log;
import org.mate.util.Result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class CoverageEndpoint implements Endpoint {
    private final AndroidEnvironment androidEnvironment;
    private final Path resultsPath;

    public CoverageEndpoint(AndroidEnvironment androidEnvironment, Path resultsPath) {
        this.androidEnvironment = androidEnvironment;
        this.resultsPath = resultsPath;
    }

    @Override
    public Message handle(Message request) {
        if (request.getSubject().startsWith("/coverage/store")) {
            final var errorMsg = storeCoverageData(request);
            if (errorMsg == null) {
                return new Message("/coverage/store");
            } else {
                return Messages.errorMessage(errorMsg);
            }
        } else if (request.getSubject().startsWith("/coverage/combined")) {
            return getCombinedCoverage(request);
        } else if (request.getSubject().startsWith("/coverage/lineCoveredPercentages")) {
            return getLineCoveredPercentages(request);
        }
        return null;
    }

    private Message getLineCoveredPercentages(Message request) {
        var deviceId = request.getParameter("deviceId");
        var packageName = Device.getDevice(deviceId).getPackageName();
        var chromosomes = request.getParameter("chromosomes");
        var lines = Arrays.stream(request.getParameter("lines").split("\\*"))
                .map(CoverageManager.Line::valueOf)
                .collect(Collectors.toList());
        var baseCoverageDir = resultsPath.resolve(packageName + ".coverage");
        final var execFiles = getExecFiles(chromosomes, baseCoverageDir);
        if (execFiles.isErr()) {
            return Messages.errorMessage(execFiles.getErr());
        }

        var lineCoveredPercentages = CoverageManager.getLineCoveredPercentages(
                execFiles.getOk(),
                Path.of(packageName + ".src", "classes"),
                lines);

        return new Message.MessageBuilder("/coverage/lineCoveredPercentages")
                .withParameter("coveragePercentages", lineCoveredPercentages.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining("\n")))
                .build();
    }

    private Message getCombinedCoverage(Message request) {
        var deviceId = request.getParameter("deviceId");
        var packageName = Device.getDevice(deviceId).getPackageName();
        var chromosomes = request.getParameter("chromosomes");
        var baseCoverageDir = resultsPath.resolve(packageName + ".coverage");
        final var execFiles = getExecFiles(chromosomes, baseCoverageDir);
        if (execFiles.isErr()) {
            return Messages.errorMessage(execFiles.getErr());
        }

        Double combinedCoverage = CoverageManager.getCombinedCoverage(
                execFiles.getOk(),
                Path.of(packageName + ".src", "classes"));

        return new Message.MessageBuilder("/coverage/combined")
                .withParameter("coverage", String.valueOf(combinedCoverage))
                .build();
    }

    private Result<List<Path>, String> getExecFiles(String chromosomes, Path baseCoverageDir) {
        List<Path> execFiles;
        //get the coverage of all chromosomes if none are specified
        if (chromosomes == null) {
            try {
                execFiles = Files.walk(baseCoverageDir)
                        .filter(Files::isRegularFile)
                        .collect(Collectors.toList());
            } catch (IOException e) {
                final var errorMsg = "Failed to get combined coverage: " + e.toString() + "\n" + e.fillInStackTrace();
                Log.printError(errorMsg);
                return Result.errOf(errorMsg);
            }
        } else {
            execFiles = new ArrayList<>();
            for (String chromosome : chromosomes.split("\\+")) {
                try {
                    execFiles.addAll(
                            Files.walk(baseCoverageDir.resolve(chromosome))
                                    .filter(Files::isRegularFile)
                                    .collect(Collectors.toList()));
                } catch (IOException e) {
                    final var errorMsg = "Failed to get combined coverage: " + e.toString() + "\n" + e.fillInStackTrace();
                    Log.printError(errorMsg);
                    return Result.errOf(errorMsg);
                }
            }
        }
        return Result.okOf(execFiles);
    }

    private String storeCoverageData(Message request) {
        var deviceId = request.getParameter("deviceId");
        var packageName = Device.getDevice(deviceId).getPackageName();
        var chromosome = request.getParameter("chromosome");
        var entity = request.getParameter("entity");
        var coverageDir = resultsPath.resolve(packageName + ".coverage").resolve(chromosome);
        coverageDir.toFile().mkdirs();
        Path coverageFile;
        if (entity == null) {
            try {
                coverageFile = Files.createTempFile(coverageDir, null, null);
            } catch (IOException e) {
                final var errorMsg = "Failed to create coverage file: " + e.toString() + "\n" + e.fillInStackTrace();
                Log.printError(errorMsg);
                return errorMsg;
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
            return errorMsg;
        }
        return null;
    }
}
