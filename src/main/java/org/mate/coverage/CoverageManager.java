package org.mate.coverage;

import org.jacoco.core.analysis.*;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class CoverageManager {
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

    public static List<Double> getLineCoveredPercentages(List<Path> execFilePaths, Path classesDirPath, List<Line> lines) {
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

                int coveredLinesInClass = 0;
                int totalLinesInClass = 0;
                for (int i = classCoverage.getFirstLine(); i <= classCoverage.getLastLine(); i++) {
                    int status = classCoverage.getLine(i).getStatus();
                    if (status == ICounter.PARTLY_COVERED || status == ICounter.FULLY_COVERED) {
                        totalLinesInClass++;
                        coveredLinesInClass++;
                    } else if (status != ICounter.EMPTY) {
                        totalLinesInClass++;
                    }
                }
                double lineCoveredPercentageInClass = 0.5;
                // sanity check, should always be true
                if (totalLinesInClass != 0) {
                    lineCoveredPercentageInClass += (coveredLinesInClass / (totalLinesInClass * 2.0));
                }


                for (Integer lineNr : requestedLines.get(packageName).get(className)) {
                    coveredPercentage.put(new Line(packageName, className, lineNr), lineCoveredPercentageInClass);
                }
            }
        }
        return lines.stream().map(line -> coveredPercentage.getOrDefault(line, 0.0)).collect(Collectors.toList());
    }

    public static Double getCombinedCoverage(List<Path> execFilePaths, Path classesDirPath) {
        IBundleCoverage bundle = generateBundleCoverage(execFilePaths, classesDirPath);
        ICounter counter = bundle.getLineCounter();
        return counter.getCoveredRatio();
    }

    public static IBundleCoverage generateBundleCoverage(List<Path> execFilePaths, Path classesDirPath) {
        CoverageBuilder coverageBuilder = new CoverageBuilder();
        ExecutionDataStore executionDataStore = new ExecutionDataStore();
        for (Path execFilePath : execFilePaths) {
            ExecFileLoader execFileLoader = new ExecFileLoader();
            try {
                execFileLoader.load(execFilePath.toFile());
            } catch (IOException e) {
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
}
