package org.mate.io;

import org.mate.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AndroidRunner {
    private static final long PROCESS_TIMEOUT = 10; //Timeout of android commands in seconds
    private final Path androidSdkPath;
    private final Path adbCmdPath;
    private final Path aaptCmdPath;

    public AndroidRunner() {
        var sdkPath = Optional.ofNullable(System.getenv("ANDROID_HOME"));
        sdkPath = sdkPath.or(() -> Optional.ofNullable(System.getenv("ANDROID_SDK_ROOT")));

        //If not set try to look for installations at common locations
        androidSdkPath = sdkPath.map(Paths::get).or(() -> {
            Optional<String> home;
            if (System.getProperty("os.name").startsWith("Windows")) {
                home = Optional.ofNullable(System.getenv("LOCALAPPDATA"));
            } else {
                home = Optional.ofNullable(System.getenv("HOME"));
            }
            return home.flatMap(path -> {
                Path androidSdkPath = Paths.get(path).resolve("Android/sdk");
                if (Files.exists(androidSdkPath) && Files.isDirectory(androidSdkPath)) {
                    return Optional.of(androidSdkPath);
                }
                return Optional.empty();
            });
        }).orElse(null);

        if (androidSdkPath == null) {
            Log.printWarning("unable to locate android installation");
        }

        if (existsInPath("adb")) {
            adbCmdPath = Paths.get("adb");
        } else {
            adbCmdPath = Optional.ofNullable(androidSdkPath).flatMap(path -> {
                var adbCmdPath = path.resolve("platform-tools/adb");
                if (Files.isExecutable(adbCmdPath)) {
                    return Optional.of(adbCmdPath);
                }
                return Optional.empty();
            }).orElse(null);
        }

        if (adbCmdPath == null) {
            Log.printWarning("unable to find adb executable in PATH or android installation");
        }

        if (existsInPath("aapt")) {
            aaptCmdPath = Paths.get("aapt");
        } else {
            aaptCmdPath = Optional.ofNullable(androidSdkPath).flatMap(path -> {
                var buildToolsPath = path.resolve("build-tools");
                Optional<Path> latestBuildToolsPath = Optional.empty();
                try {
                     latestBuildToolsPath = Files.walk(buildToolsPath, 1)
                             .filter(Files::isDirectory)
                             .max(Comparator.comparing(Path::toString));
                } catch (IOException e) {
                    Log.printWarning(e.getMessage() + "\n" + e.fillInStackTrace());
                }
                return latestBuildToolsPath.flatMap((p) -> {
                    var aaptCmdPath = p.resolve("aapt");
                    if (Files.isExecutable(aaptCmdPath)) {
                        return Optional.of(aaptCmdPath);
                    }
                    return Optional.empty();
                });
            }).orElse(null);
        }

        if (aaptCmdPath == null) {
            Log.printWarning("unable to find aapt executable in PATH or android installation");
        }
    }

    private List<String> runProcess(List<String> cmd){
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            var output = new BufferedReader(new InputStreamReader(p.getInputStream()))
                    .lines()
                    .collect(Collectors.toList());
            if (!p.waitFor(PROCESS_TIMEOUT, TimeUnit.SECONDS)) {
                Log.printError("execution timeout for cmd " + cmd + " reached (" + PROCESS_TIMEOUT + " seconds)");
            }
            return output;
        } catch (Exception e) {
            Log.printError("unable to execute cmd " + cmd + ": " + e.getMessage() + "\n" + e.fillInStackTrace());
        }
        return null;
    }

    public List<String> runAdbCmd(List<String> cmd) {
        if (adbCmdPath == null) {
            throw new IllegalStateException("cannot execute adb command as adb was not found in PATH or android installation");
        }
        var adbCmd = Arrays.asList(adbCmdPath.toString());
        adbCmd.addAll(cmd);
        return runProcess(adbCmd);
    }

    public List<String> runAaptCmd(List<String> cmd) {
        if (aaptCmdPath == null) {
            throw new IllegalStateException("cannot execute aapt command as aapt was not found in PATH or android installation");
        }
        var aaptCmd = Arrays.asList(aaptCmdPath.toString());
        aaptCmd.addAll(cmd);
        return runProcess(aaptCmd);
    }

    public boolean outputAdbCmdToFile(List<String> cmd, Path outputFilePath) {
        var encounteredError = false;
        var adbCmd = Arrays.asList(adbCmdPath.toString());
        adbCmd.addAll(cmd);
        try {
        ProcessBuilder pb = new ProcessBuilder(adbCmd);
        pb.redirectOutput(outputFilePath.toFile());
            var p = pb.start();
            var errOutput = new BufferedReader(new InputStreamReader(p.getErrorStream()))
                    .lines()
                    .collect(Collectors.joining("\n"));
            if (!p.waitFor(PROCESS_TIMEOUT, TimeUnit.SECONDS)) {
                encounteredError = true;
                Log.printError("execution timeout for cmd " + adbCmd + " reached (" + PROCESS_TIMEOUT + " seconds)");
            }
            if (!errOutput.isBlank()) {
                encounteredError = true;
                Log.printError("stderr of cmd "+ adbCmd + ":" + "\n" + errOutput);
            }
        } catch (Exception e) {
            encounteredError = true;
            Log.printError("unable to execute cmd " + adbCmd + " or redirect output to file \"" + outputFilePath + "\": " + e.getMessage() + "\n" + e.fillInStackTrace());
        }
        return encounteredError;
    }

    private static boolean existsInPath(String cmd) {
        return Stream.of(System.getenv("PATH").split(Pattern.quote(File.pathSeparator)))
                .map(Paths::get)
                .anyMatch(path -> Files.exists(path.resolve(cmd)));
    }
}
