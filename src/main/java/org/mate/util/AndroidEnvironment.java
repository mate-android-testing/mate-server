package org.mate.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class AndroidEnvironment {
    private final Path androidSdkPath;
    private final Path adbCmdPath;
    private final Path aaptCmdPath;

    public AndroidEnvironment() {
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
                Path androidSdkPath = Path.of(path, "Android", "Sdk");
                if (Files.exists(androidSdkPath) && Files.isDirectory(androidSdkPath)) {
                    return Optional.of(androidSdkPath);
                }
                return Optional.empty();
            });
        }).orElse(null);

        if (androidSdkPath == null) {
            Log.printWarning("unable to locate android installation");
        }

        if (System.getProperty("os.name").startsWith("Windows")) {
            if (existsInPath("adb.exe")) {
                adbCmdPath = Path.of("adb.exe");
            } else {
                adbCmdPath = Optional.ofNullable(androidSdkPath).flatMap(path -> {
                    var adbCmdPath = path.resolve("platform-tools/adb.exe");
                    if (Files.isExecutable(adbCmdPath)) {
                        return Optional.of(adbCmdPath);
                    }
                    return Optional.empty();
                }).orElse(null);
            }
        } else {
            if (existsInPath("adb")) {
                adbCmdPath = Path.of("adb");
            } else {
                adbCmdPath = Optional.ofNullable(androidSdkPath).flatMap(path -> {
                    var adbCmdPath = path.resolve("platform-tools/adb");
                    if (Files.isExecutable(adbCmdPath)) {
                        return Optional.of(adbCmdPath);
                    }
                    return Optional.empty();
                }).orElse(null);
            }
        }

        if (adbCmdPath == null) {
            Log.printWarning("unable to find adb executable in PATH or android installation");
        }

        if (System.getProperty("os.name").startsWith("Windows")) {
            if (existsInPath("aapt.exe")) {
                aaptCmdPath = Path.of("aapt.exe");
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
                        var aaptCmdPath = p.resolve("aapt.exe");
                        if (Files.isExecutable(aaptCmdPath)) {
                            return Optional.of(aaptCmdPath);
                        }
                        return Optional.empty();
                    });
                }).orElse(null);
            }
        } else {
            if (existsInPath("aapt")) {
                aaptCmdPath = Path.of("aapt");
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
        }

        if (aaptCmdPath == null) {
            Log.printWarning("unable to find aapt executable in PATH or android installation");
        }
    }


    public String getAdbExecutable() {
        if (adbCmdPath == null) {
            throw new IllegalStateException("cannot get adb executable as adb was not found in PATH or android installation");
        }
        return adbCmdPath.toString();
    }

    public String getAaptExecutable() {
        if (aaptCmdPath == null) {
            throw new IllegalStateException("cannot get aapt executable as aapt was not found in PATH or android installation");
        }
        return aaptCmdPath.toString();
    }

    private static boolean existsInPath(String cmd) {
        return Stream.of(System.getenv("PATH").split(Pattern.quote(File.pathSeparator)))
                .map(Paths::get)
                .anyMatch(path -> Files.exists(path.resolve(cmd)));
    }
}
