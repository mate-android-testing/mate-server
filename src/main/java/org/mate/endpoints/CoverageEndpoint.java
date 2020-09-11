package org.mate.endpoints;

import org.mate.coverage.BranchCoverageManager;
import org.mate.coverage.Coverage;
import org.mate.coverage.CoverageManager;
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

    public CoverageEndpoint(AndroidEnvironment androidEnvironment, Path resultsPath) {
        this.androidEnvironment = androidEnvironment;
        this.resultsPath = resultsPath;
    }

    @Override
    public Message handle(Message request) {

        if (request.getSubject().startsWith("/coverage/store")) {
            return storeCoverageData(request);
        } else if (request.getSubject().startsWith("/coverage/get")) {
            return getCoverageData(request);
        } else if (request.getSubject().startsWith("/coverage/combined")) {
            return getCombinedCoverage(request);
        } else if (request.getSubject().startsWith("/coverage/lineCoveredPercentages")) {
            return getLineCoveredPercentages(request);
        } else if (request.getSubject().startsWith("/coverage/copy")) {
            final var errorMsg = copyCoverageData(request);
            if (errorMsg == null) {
                return new Message("/coverage/copy");
            } else {
                return Messages.errorMessage(errorMsg);
            }
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

    private Message storeCoverageData(Message request) {

        // get the coverage type, e.g. BRANCH_COVERAGE
        Coverage coverage = Coverage.valueOf(request.getParameter("coverage_type"));

        switch (coverage) {
            case LINE_COVERAGE:
                final var errorMsg = storeLineCoverageData(request);
                if (errorMsg == null) {
                    // TODO: add coverage parameter
                    return new Message("/coverage/store");
                } else {
                    return Messages.errorMessage(errorMsg);
                }
            case BRANCH_COVERAGE:
                return storeBranchCoverageData(request);
            default:
                throw new UnsupportedOperationException("Coverage type not yet supported!");
        }
    }

    private Message getCoverageData(Message request) {

        // get the coverage type, e.g. BRANCH_COVERAGE
        Coverage coverage = Coverage.valueOf(request.getParameter("coverage_type"));

        switch (coverage) {
            case BRANCH_COVERAGE:
                return getBranchCoverageData(request);
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
            default:
                throw new UnsupportedOperationException("Coverage type not yet supported!");
        }
    }

    private Message getCombinedBranchCoverage(Message request) {
        return BranchCoverageManager.getCombinedCoverage();
    }

    private Message storeBranchCoverageData(Message request) {
        String deviceID = request.getParameter("deviceId");
        String testCaseId = request.getParameter("testcaseId");
        return BranchCoverageManager.storeCoverage(androidEnvironment, deviceID, testCaseId);
    }

    private Message getBranchCoverageData(Message request) {
        String testCaseId = request.getParameter("testcaseId");
        return BranchCoverageManager.getCoverage(testCaseId);
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

    private Message getCombinedLineCoverage(Message request) {
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

    private Path getCoverageBaseDir(String packageName) {
        return resultsPath.resolve(packageName + ".coverage");
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

    private String copyCoverageData(Message request) {
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
        var reportFile = Path.of(packageName + ".report");
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