package org.mate.coverage;

import org.jacoco.core.analysis.*;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;
import org.mate.io.ProcessRunner;
import org.mate.network.message.Message;
import org.mate.network.message.Messages;
import org.mate.util.AndroidEnvironment;
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

/**
 * Handles requests related to line coverage.
 */
public class LineCoverageManager {

    public static class Line {
        private final String packageName;
        private final String className;
        private final int lineNr;

        public Line(String packageName, String className, int lineNr) {
            this.packageName = packageName;
            this.className = className;
            this.lineNr = lineNr;
        }

        public static Line valueOf(String line) {
            String[] components = line.split("\\+");
            if (components.length != 3) {
                throw new IllegalArgumentException("illegal line format: " + line);
            }
            int lineNr;
            try {
                lineNr = Integer.parseInt(components[2]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("line number is not an integer: " + components[2]);
            }
            return new Line(components[0], components[1], lineNr);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Line line = (Line) o;
            return lineNr == line.lineNr &&
                    packageName.equals(line.packageName) &&
                    className.equals(line.className);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName, className, lineNr);
        }
    }

    /**
     * Returns the line coverage percentage metric.
     *
     * @param appsDir The apps directory.
     * @param packageName The package name of the AUT.
     * @param chromosomes Identifies the chromosomes for which the line coverage percentage metric should be derived.
     * @return Returns the line coverage percentage metric.
     */
    public static Message getLineCoveredPercentages(final Path appsDir, final String packageName, final String chromosomes) {

        Result<List<String>, String> lines = getLines(appsDir, packageName);

        if (lines.isErr()) {
            return Messages.errorMessage(lines.getErr());
        }

        var sourceLines = lines.getOk().stream()
                .map(LineCoverageManager.Line::valueOf)
                .collect(Collectors.toList());

        Path baseCoverageDir = getCoverageBaseDir(appsDir, packageName);

        final var execFiles = getExecFiles(chromosomes, baseCoverageDir);
        if (execFiles.isErr()) {
            return Messages.errorMessage(execFiles.getErr());
        }

        var lineCoveredPercentages = getLineCoveredPercentages(
                execFiles.getOk(),
                appsDir.resolve(packageName).resolve("src").resolve("classes"),
                sourceLines);

        return new Message.MessageBuilder("/coverage/lineCoveredPercentages")
                .withParameter("coveragePercentages", lineCoveredPercentages.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining("\n")))
                .build();
    }

    private static List<Double> getLineCoveredPercentages(List<Path> execFilePaths, Path classesDirPath, List<Line> lines) {
        Map<String, Map<String, Set<Integer>>> requestedLines = new HashMap<>();
        for (Line line : lines) {
            requestedLines
                    .computeIfAbsent(line.packageName, k -> new HashMap<>())
                    .computeIfAbsent(line.className, k -> new HashSet<>())
                    .add(line.lineNr);
        }

        IBundleCoverage bundle = generateBundleCoverage(execFilePaths, classesDirPath);
        Map<Line, Double> coveredPercentage = new HashMap<>();
        for (IPackageCoverage packageCoverage : bundle.getPackages()) {
            String packageName = packageCoverage.getName();
            if (!requestedLines.containsKey(packageName)) {
                continue;
            }
            for (Map.Entry<String, Set<Integer>> classLineNrsEntry : requestedLines.get(packageName).entrySet()) {
                for (Integer lineNr : classLineNrsEntry.getValue()) {
                    coveredPercentage.put(new Line(packageName, classLineNrsEntry.getKey(), lineNr), 0.25);
                }
            }

            for (IClassCoverage classCoverage : packageCoverage.getClasses()) {
                String className = classCoverage.getName();
                if (!requestedLines.get(packageName).containsKey(className)) {
                    continue;
                }
                //counter of how many lines the nearest covered line before the respective line of the key is await
                var lastCoveredBeforeLine = new HashMap<Integer, Optional<Integer>>();
                //counter of how many lines the nearest covered line after the respective line of the key is await
                var nextCoveredAfterLine = new HashMap<Integer, Optional<Integer>>();
                //counter of how many non empty lines are before the line of the respective key
                var linesBeforeLine = new HashMap<Integer, Integer>();
                //counter of how many non empty lines are after the line of the respective key
                var linesAfterLine = new HashMap<Integer, Integer>();
                for (Integer lineNr : requestedLines.get(packageName).get(className)) {
                    lastCoveredBeforeLine.put(lineNr, Optional.empty());
                    nextCoveredAfterLine.put(lineNr, Optional.empty());
                    linesBeforeLine.put(lineNr, 0);
                    linesAfterLine.put(lineNr, 0);
                }

                for (int i = classCoverage.getFirstLine(); i <= classCoverage.getLastLine(); i++) {
                    int status = classCoverage.getLine(i).getStatus();
                    if (status == ICounter.EMPTY) {
                        continue;
                    }
                    for (Integer lineNr : requestedLines.get(packageName).get(className)) {
                        if (i < lineNr) {
                            linesBeforeLine.compute(lineNr, (k, v) -> v + 1);
                        } else if (i > lineNr) {
                            linesAfterLine.compute(lineNr, (k, v) -> v + 1);
                        }
                        if (status == ICounter.PARTLY_COVERED || status == ICounter.FULLY_COVERED) {
                            if (i < lineNr) {
                                lastCoveredBeforeLine.put(lineNr, Optional.of(1));
                            } else if (i > lineNr) {
                                nextCoveredAfterLine.compute(lineNr, (k, v) -> v.or(() -> Optional.of(linesAfterLine.get(lineNr))));
                            } else {
                                lastCoveredBeforeLine.put(lineNr, Optional.of(0));
                                nextCoveredAfterLine.put(lineNr, Optional.of(0));
                            }
                        } else {
                            if (i < lineNr) {
                                lastCoveredBeforeLine.compute(lineNr, (k, v) -> v.map(x -> x + 1));
                            }
                        }
                    }
                }

                for (Integer lineNr : requestedLines.get(packageName).get(className)) {
                    double lineCoveredPercentage = 0.5;
                    if (lastCoveredBeforeLine.get(lineNr).map(x -> x == 0).orElse(false)
                            && nextCoveredAfterLine.get(lineNr).map(x -> x == 0).orElse(false)) {
                        lineCoveredPercentage = 1.0;
                    } else {
                        int factor = Math.max(linesBeforeLine.get(lineNr), linesAfterLine.get(lineNr));
                        if (factor != 0) {
                            Optional<Integer> closestCoveredLine = Optional.empty();
                            if (lastCoveredBeforeLine.get(lineNr).isPresent()) {
                                if (nextCoveredAfterLine.get(lineNr).isPresent()) {
                                    closestCoveredLine = Optional.of(
                                            Math.min(
                                                    lastCoveredBeforeLine.get(lineNr).get(),
                                                    nextCoveredAfterLine.get(lineNr).get()));
                                } else {
                                    closestCoveredLine = lastCoveredBeforeLine.get(lineNr);
                                }
                            } else {
                                if (nextCoveredAfterLine.get(lineNr).isPresent()) {
                                    closestCoveredLine = nextCoveredAfterLine.get(lineNr);
                                }
                            }
                            lineCoveredPercentage += closestCoveredLine.map(x -> (factor - x) / (2.0 * factor)).orElse(0.0);
                        }
                    }
                    coveredPercentage.put(new Line(packageName, className, lineNr), lineCoveredPercentage);
                }
            }
        }
        return lines.stream().map(line -> coveredPercentage.getOrDefault(line, 0.0)).collect(Collectors.toList());
    }

    /**
     * Gets the coverage files corresponding to the chromosomes.
     *
     * @param chromosomes The chromosomes for which coverage should be derived.
     * @param baseCoverageDir The path of the coverage base directory.
     * @return Returns the coverage files corresponding, or an error message if something went wrong.
     */
    private static Result<List<Path>, String> getExecFiles(String chromosomes, Path baseCoverageDir) {

        List<Path> execFiles;

        // get the coverage of all chromosomes if none are specified
        if (chromosomes == null) {
            try {
                execFiles = Files.walk(baseCoverageDir)
                        .filter(Files::isRegularFile)
                        .collect(Collectors.toList());
            } catch (IOException e) {
                final var errorMsg = "Failed to get combined coverage: " + e + "\n" + e.fillInStackTrace();
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
                    final var errorMsg = "Failed to get combined coverage: " + e + "\n" + e.fillInStackTrace();
                    Log.printError(errorMsg);
                    return Result.errOf(errorMsg);
                }
            }
        }
        return Result.okOf(execFiles);
    }

    /**
     * Computes the line coverage of a single test case within a test suite.
     *
     * @param appsDir The apps directory.
     * @param packageName The package name of the AUT.
     * @param testSuiteId The id of the test suite.
     * @param testCaseId The id of the test case.
     * @return Returns the line coverage for the given test case.
     */
    public static Message getCoverage(Path appsDir, String packageName, String testSuiteId, String testCaseId) {

        Path coverageChromosomeDir = getCoverageChromosomeDir(appsDir, packageName, testSuiteId);
        Path coverageFile = coverageChromosomeDir.resolve(testCaseId);

        if (!coverageFile.toFile().exists()) {
            return Messages.errorMessage("Failed to get coverage: " + coverageFile + " not existent!");
        }

        Path classesDirPath = appsDir.resolve(packageName).resolve("src").resolve("classes");
        /*
         * We can't use here Lists.newArrayList(coverageFile) to hand over the coverage file in a list.
         * This is due to the fact that a 'Path' object is iterable and Lists.newArrayList() would construct
         * a list where each element is a sub path in this context due to overloading!
         */
        IBundleCoverage bundle = generateBundleCoverage(Collections.singletonList(coverageFile), classesDirPath);
        ICounter counter = bundle.getLineCounter();

        return new Message.MessageBuilder("/coverage/get")
                .withParameter("line_coverage", String.valueOf(counter.getCoveredRatio() * 100))
                .build();
    }

    /**
     * Gets the combined line coverage for the given chromosomes.
     *
     * @param appsDir The apps directory.
     * @param packageName The package name of the AUT.
     * @param chromosomes The chromosomes for which coverage should be derived.
     * @return Returns a message containing the coverage information or an error message on failure.
     */
    public static Message getCombinedCoverage(final Path appsDir, final String packageName, final String chromosomes) {

        Path baseCoverageDir = getCoverageBaseDir(appsDir, packageName);

        final var execFiles = getExecFiles(chromosomes, baseCoverageDir);
        if (execFiles.isErr()) {
            return Messages.errorMessage(execFiles.getErr());
        }

        Path classesDirPath = appsDir.resolve(packageName).resolve("src").resolve("classes");
        IBundleCoverage bundle = generateBundleCoverage(execFiles.getOk(), classesDirPath);
        ICounter counter = bundle.getLineCounter();

        return new Message.MessageBuilder("/coverage/combined")
                .withParameter("line_coverage", String.valueOf(counter.getCoveredRatio() * 100))
                .build();
    }

    private static IBundleCoverage generateBundleCoverage(List<Path> execFilePaths, Path classesDirPath) {

        CoverageBuilder coverageBuilder = new CoverageBuilder();
        ExecutionDataStore executionDataStore = new ExecutionDataStore();
        for (Path execFilePath : execFilePaths) {
            ExecFileLoader execFileLoader = new ExecFileLoader();
            try {
                execFileLoader.load(execFilePath.toFile());
            } catch (IOException e) {
                Log.println("Failed to load coverage file: " + execFilePath);
                e.printStackTrace();
            }
            for (ExecutionData data : execFileLoader.getExecutionDataStore().getContents()) {
                executionDataStore.visitClassExecution(data);
            }
        }
        Analyzer analyzer = new Analyzer(executionDataStore, coverageBuilder);
        try {
            analyzer.analyzeAll(classesDirPath.toFile());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return coverageBuilder.getBundle("unnamed coverage bundle");
    }

    /**
     * Returns the coverage base directory.
     *
     * @param appsDir The apps directory.
     * @param packageName The package name of the AUT.
     * @return Returns the path to the coverage base directory.
     */
    private static Path getCoverageBaseDir(final Path appsDir, final String packageName) {
        return appsDir.resolve(packageName).resolve("coverage");
    }

    /**
     * Returns the chromosome base directory.
     *
     * @param appsDir The apps directory.
     * @param packageName The package name of the AUT.
     * @param chromosome The identifier of the chromosome.
     * @return Returns the path to the chromosome base directory.
     */
    private static Path getCoverageChromosomeDir(final Path appsDir, final String packageName, final String chromosome) {
        return getCoverageBaseDir(appsDir, packageName).resolve(chromosome);
    }

    /**
     * Stores the coverage data.
     *
     * @param androidEnvironment Defines the location of the adb/aapt binary.
     * @param appsDir The apps directory.
     * @param deviceID The name of the emulator, e.g. emulator-5554.
     * @param packageName The package name of the AUT.
     * @param chromosome Identifies either a test suite or a test case.
     * @param entity Identifies the test case when chromosome refers to the test suite or {@code null}.
     * @return Returns an empty message in case of success, otherwise a error message is returned.
     */
    public static Message storeCoverageData(AndroidEnvironment androidEnvironment, final Path appsDir,
                                            String deviceID, String packageName, String chromosome, String entity) {

        Path coverageDir = getCoverageChromosomeDir(appsDir, packageName, chromosome);

        coverageDir.toFile().mkdirs();
        Path coverageFile;
        if (entity == null) {
            try {
                coverageFile = Files.createTempFile(coverageDir, null, null);
            } catch (IOException e) {
                final var errorMsg = "Failed to create coverage file: " + e + "\n" + e.fillInStackTrace();
                Log.printError(errorMsg);
                return Messages.errorMessage(errorMsg);
            }
        } else {
            coverageFile = coverageDir.resolve(entity);
        }

        // close app in order to start coverage write to internal app storage
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(),
                "-s",
                deviceID,
                "shell",
                "input",
                "keyevent",
                "3");

        // start app to restore original state of the emulator
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(),
                "-s",
                deviceID,
                "shell",
                "monkey",
                "-p",
                packageName,
                "1");

        // extract coverage from internal app storage to local coverage file
        if (!ProcessRunner.runProcess(coverageFile,
                null,
                androidEnvironment.getAdbExecutable(),
                "-s",
                deviceID,
                "exec-out",
                "run-as",
                packageName,
                "cat",
                "files/coverage.exec").isOk()) {
            final var errorMsg = "Failed to extract coverage file from internal app storage";
            Log.printError(errorMsg);
            return Messages.errorMessage(errorMsg);
        }

        return new Message("/coverage/store");
    }

    /**
     * Copies the coverage data from the chromosome source directory to the chromosome target directory.
     *
     * @param appsDir The apps directory.
     * @param packageName The package name of the AUT.
     * @param chromosomeSrc The chromosome source directory.
     * @param chromosomeTarget The chromosome target directory.
     * @param entities The identifiers of the individual chromosomes / coverage files.
     * @return Returns an empty message in case of success, otherwise a error message is returned.
     */
    public static Message copyLineCoverageData(final Path appsDir, final String packageName, final String chromosomeSrc,
                                               final String chromosomeTarget, final String[] entities) {

        Path srcDir = getCoverageChromosomeDir(appsDir, packageName, chromosomeSrc);
        Path targetDir = getCoverageChromosomeDir(appsDir, packageName, chromosomeTarget);

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

        return new Message("/coverage/copy");
    }

    private static Result<List<String>, String> getLines(final Path appsDir, final String packageName) {

        Path reportFile = appsDir.resolve(packageName).resolve(packageName + ".report");
        String separator = "+";

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

        // ignore dtd file
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

        List<String> sourceLines = new ArrayList<>();
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

        return Result.okOf(sourceLines);
    }

    /**
     * Retrieves the source lines of the app under test.
     *
     * @param appsDir The apps directory.
     * @param packageName The package name of the AUT.
     * @return Returns a message containing the source lines in case of success, otherwise a error message is returned.
     */
    public static Message getSourceLines(final Path appsDir, final String packageName) {

        Result<List<String>, String> sourceLines = getLines(appsDir, packageName);

        if (sourceLines.isErr()) {
            return Messages.errorMessage(sourceLines.getErr());
        } else {
            return new Message.MessageBuilder("/coverage/getSourceLines")
                    .withParameter("lines", String.join("\n", sourceLines.getOk()))
                    .build();
        }
    }

    /**
     * Retrieves the number of source lines of the app under test.
     *
     * @param appsDir The apps directory.
     * @param packageName The package name of the AUT.
     * @return Returns a message containing the number of source lines in case of success, otherwise a error message is
     *         returned.
     */
    public static Message getNumberOfSourceLines(final Path appsDir, final String packageName) {

        Result<List<String>, String> sourceLines = getLines(appsDir, packageName);

        if (sourceLines.isErr()) {
            return Messages.errorMessage(sourceLines.getErr());
        } else {
            return new Message.MessageBuilder("/coverage/getNumberOfSourceLines")
                    .withParameter("lines", String.valueOf(sourceLines.getOk().size()))
                    .build();
        }
    }
}
