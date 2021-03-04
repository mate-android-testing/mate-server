package org.mate.endpoints;

import org.mate.coverage.BasicBlockCoverageManager;
import org.mate.coverage.BranchCoverageManager;
import org.mate.coverage.Coverage;
import org.mate.coverage.LineCoverageManager;
import org.mate.coverage.MethodCoverageManager;
import org.mate.network.message.Messages;
import org.mate.util.AndroidEnvironment;
import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.network.message.Message;
import org.mate.network.Endpoint;
import org.mate.util.Log;
import org.mate.util.Result;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class CoverageEndpoint implements Endpoint {
    private final AndroidEnvironment androidEnvironment;
    private final Path resultsPath;
    private final Path appsDir;

    public CoverageEndpoint(AndroidEnvironment androidEnvironment, Path resultsPath, Path appsDir) {
        this.androidEnvironment = androidEnvironment;
        this.resultsPath = resultsPath;
        this.appsDir = appsDir;
    }

    @Override
    public Message handle(Message request) {

        if (request.getSubject().startsWith("/coverage/store")) {
            return storeCoverageData(request);
        } else if (request.getSubject().startsWith("/coverage/combined")) {
            return getCombinedCoverage(request);
        } else if (request.getSubject().startsWith("/coverage/lineCoveredPercentages")) {
            return getLineCoveredPercentages(request);
        } else if (request.getSubject().startsWith("/coverage/copy")) {
            return copyCoverageData(request);
        } else if (request.getSubject().startsWith("/coverage/getSourceLines")) {
            final var result = getSourceLines(request);
            if (result.isOk()) {
                return new Message.MessageBuilder("/coverage/getSourceLines")
                        .withParameter("lines", result.getOk())
                        .build();
            } else {
                return Messages.errorMessage(result.getErr());
            }
        }
        throw new IllegalArgumentException("Message request with subject: "
                + request.getSubject() + " can't be handled by CoverageEndPoint!");
    }

    private Message copyCoverageData(Message request) {

        // get the coverage type, e.g. BRANCH_COVERAGE
        Coverage coverage = Coverage.valueOf(request.getParameter("coverage_type"));

        switch (coverage) {
            case LINE_COVERAGE:
                final var errorMsg = copyLineCoverageData(request);
                if (errorMsg == null) {
                    return new Message("/coverage/copy");
                } else {
                    return Messages.errorMessage(errorMsg);
                }
            case BRANCH_COVERAGE:
                return copyBranchCoverageData(request);
            case METHOD_COVERAGE:
                return copyMethodCoverageData(request);
            case BASIC_BLOCK_LINE_COVERAGE:
            case BASIC_BLOCK_BRANCH_COVERAGE:
                return copyBasicBlockCoverageData(request);
            default:
                throw new UnsupportedOperationException("Coverage type not yet supported!");
        }
    }

    private Message copyMethodCoverageData(Message request) {
        var deviceId = request.getParameter("deviceId");
        var chromosomeSrc = request.getParameter("chromosome_src");
        var chromosomeTarget = request.getParameter("chromosome_target");
        var entities = request.getParameter("entities").split(",");
        return MethodCoverageManager.copyCoverageData(appsDir, deviceId, chromosomeSrc, chromosomeTarget, entities);
    }

    private Message copyBranchCoverageData(Message request) {
        var deviceId = request.getParameter("deviceId");
        var chromosomeSrc = request.getParameter("chromosome_src");
        var chromosomeTarget = request.getParameter("chromosome_target");
        var entities = request.getParameter("entities").split(",");
        return BranchCoverageManager.copyCoverageData(appsDir, deviceId, chromosomeSrc, chromosomeTarget, entities);
    }

    private Message copyBasicBlockCoverageData(Message request) {
        var deviceId = request.getParameter("deviceId");
        var chromosomeSrc = request.getParameter("chromosome_src");
        var chromosomeTarget = request.getParameter("chromosome_target");
        var entities = request.getParameter("entities").split(",");
        return BasicBlockCoverageManager.copyCoverageData(appsDir, deviceId, chromosomeSrc, chromosomeTarget, entities);
    }

    private Message storeCoverageData(Message request) {

        // get the coverage type, e.g. BRANCH_COVERAGE
        Coverage coverage = Coverage.valueOf(request.getParameter("coverage_type"));

        switch (coverage) {
            case LINE_COVERAGE:
                final var errorMsg = storeLineCoverageData(request);
                if (errorMsg == null) {
                    return new Message("/coverage/store");
                } else {
                    return Messages.errorMessage(errorMsg);
                }
            case BRANCH_COVERAGE:
                return storeBranchCoverageData(request);
            case METHOD_COVERAGE:
                return storeMethodCoverageData(request);
            case BASIC_BLOCK_LINE_COVERAGE:
            case BASIC_BLOCK_BRANCH_COVERAGE:
                return storeBasicBlockCoverageData(request);
            default:
                throw new UnsupportedOperationException("Coverage type not yet supported!");
        }
    }

    private Message getCombinedCoverage(Message request) {

        // get the coverage type, e.g. BRANCH_COVERAGE
        Coverage coverage = Coverage.valueOf(request.getParameter("coverage_type"));

        switch (coverage) {
            case LINE_COVERAGE:
                return getCombinedLineCoverage(request);
            case BRANCH_COVERAGE:
                return getCombinedBranchCoverage(request);
            case METHOD_COVERAGE:
                return getCombinedMethodCoverage(request);
            case BASIC_BLOCK_LINE_COVERAGE:
                return getCombinedBasicBlockLineCoverage(request);
            case BASIC_BLOCK_BRANCH_COVERAGE:
                return getCombinedBasicBlockBranchCoverage(request);
            default:
                throw new UnsupportedOperationException("Coverage type not yet supported!");
        }
    }

    private Message getCombinedMethodCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testcaseIds = request.getParameter("chromosomes");
        return MethodCoverageManager.getCombinedCoverage(appsDir, packageName, testcaseIds);
    }

    private Message getCombinedBranchCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testcaseIds = request.getParameter("chromosomes");
        return BranchCoverageManager.getCombinedCoverage(appsDir, packageName, testcaseIds);
    }

    private Message getCombinedBasicBlockBranchCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testcaseIds = request.getParameter("chromosomes");
        return BasicBlockCoverageManager.getCombinedBranchCoverage(appsDir, packageName, testcaseIds);
    }

    private Message getCombinedBasicBlockLineCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testcaseIds = request.getParameter("chromosomes");
        return BasicBlockCoverageManager.getCombinedLineCoverage(appsDir, packageName, testcaseIds);
    }

    private Message storeMethodCoverageData(Message request) {
        String deviceID = request.getParameter("deviceId");
        String chromosome = request.getParameter("chromosome");
        String entity = request.getParameter("entity");
        return MethodCoverageManager.storeCoverageData(androidEnvironment, deviceID, chromosome, entity);
    }

    private Message storeBranchCoverageData(Message request) {
        String deviceID = request.getParameter("deviceId");
        String chromosome = request.getParameter("chromosome");
        String entity = request.getParameter("entity");
        return BranchCoverageManager.storeCoverageData(androidEnvironment, deviceID, chromosome, entity);
    }

    private Message storeBasicBlockCoverageData(Message request) {
        String deviceID = request.getParameter("deviceId");
        String chromosome = request.getParameter("chromosome");
        String entity = request.getParameter("entity");
        return BasicBlockCoverageManager.storeCoverageData(androidEnvironment, deviceID, chromosome, entity);
    }

    private Message getLineCoveredPercentages(Message request) {
        var deviceId = request.getParameter("deviceId");
        var packageName = Device.getDevice(deviceId).getPackageName();
        var chromosomes = request.getParameter("chromosomes");
        var lines = Arrays.stream(request.getParameter("lines").split("\\*"))
                .map(LineCoverageManager.Line::valueOf)
                .collect(Collectors.toList());
        var baseCoverageDir = getCoverageBaseDir(packageName);
        final var execFiles = getExecFiles(chromosomes, baseCoverageDir);
        if (execFiles.isErr()) {
            return Messages.errorMessage(execFiles.getErr());
        }

        var lineCoveredPercentages = LineCoverageManager.getLineCoveredPercentages(
                execFiles.getOk(),
                appsDir.resolve(packageName).resolve("src").resolve("classes"),
                lines);

        return new Message.MessageBuilder("/coverage/lineCoveredPercentages")
                .withParameter("coveragePercentages", lineCoveredPercentages.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining("\n")))
                .build();
    }

    private Message getCombinedLineCoverage(Message request) {
        var deviceId = request.getParameter("deviceId");
        var packageName = Device.getDevice(deviceId).getPackageName();
        var chromosomes = request.getParameter("chromosomes");
        var baseCoverageDir = getCoverageBaseDir(packageName);
        final var execFiles = getExecFiles(chromosomes, baseCoverageDir);
        if (execFiles.isErr()) {
            return Messages.errorMessage(execFiles.getErr());
        }

        Double combinedCoverage = LineCoverageManager.getCombinedCoverage(
                execFiles.getOk(),
                appsDir.resolve(packageName).resolve("src").resolve("classes"));

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

    private Path getCoverageBaseDir(String packageName) {
        return appsDir.resolve(packageName).resolve("coverage");
    }

    private Path getCoverageChromosomeDir(String packageName, String chromosome) {
        return getCoverageBaseDir(packageName).resolve(chromosome);
    }

    private String storeLineCoverageData(Message request) {
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
            return errorMsg;
        }
        return null;
    }

    private String copyLineCoverageData(Message request) {
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
            return errorMsg;
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
                return errorMsg;
            }
        }
        return null;
    }

    private Result<String, String> getSourceLines(Message request) {
        var deviceId = request.getParameter("deviceId");
        var packageName = Device.getDevice(deviceId).getPackageName();
        var reportFile = appsDir.resolve(packageName).resolve(packageName + ".report");
        var separator = "+";
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException e) {
            final var errorMsg = "Failed to get source lines: unable to set FEATURE_SECURE_PROCESSING for DocumentBuilderFactory";
            Log.printError(errorMsg);
            return Result.errOf(errorMsg);
        }
        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            final var errorMsg = "Failed to get source lines: unable to create document builder";
            Log.printError(errorMsg);
            return Result.errOf(errorMsg);
        }
        //ignore dtd file
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        Document document;
        try {
            document = builder.parse(reportFile.toFile());
        } catch (SAXException e) {
            final var errorMsg = "Failed to get source lines: unable to parse report file (" + reportFile + ")";
            Log.printError(errorMsg);
            return Result.errOf(errorMsg);
        } catch (IOException e) {
            e.printStackTrace();
            final var errorMsg = "Failed to get source lines: IOException while reading report file (" + reportFile + ")";
            Log.printError(errorMsg);
            return Result.errOf(errorMsg);
        }
        var sourceLines = new ArrayList<String>();
        NodeList packages = document.getDocumentElement().getElementsByTagName("package");
        for (int i = 0; i < packages.getLength(); i++) {
            var currentPackage = (Element) packages.item(i);
            var currentPackageName = currentPackage.getAttribute("name");
            NodeList sourceFiles = currentPackage.getElementsByTagName("sourcefile");
            for (int j = 0; j < sourceFiles.getLength(); j++) {
                var sourceFile = (Element) sourceFiles.item(j);
                String currentSrcName = sourceFile.getAttribute("name");
                NodeList lines = sourceFile.getElementsByTagName("line");
                for (int k = 0; k < lines.getLength(); k++) {
                    Element line = (Element) lines.item(k);
                    String lineNr = line.getAttribute("nr");
                    sourceLines.add(currentPackageName + separator + currentSrcName + separator + lineNr);
                }
            }
        }
        return Result.okOf(String.join("\n", sourceLines));
    }
}