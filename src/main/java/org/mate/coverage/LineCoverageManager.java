package org.mate.coverage;

import org.jacoco.core.analysis.*;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

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
