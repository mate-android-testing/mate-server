package org.mate.io;

import org.mate.util.Log;
import org.mate.util.Result;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ProcessRunner {
    private static final long PROCESS_TIMEOUT = 10; //Timeout of commands in seconds

    private ProcessRunner() {}

    public static boolean isWin = System.getProperty("os.name").startsWith("Windows");

    /**
     * The PID of the last background process that was started.
     */
    private static long lastBackgroundPID;

    /**
     * Returns the PID of the last background process.
     *
     * @return Returns the pid of the last background process.
     */
    public static long getLastBackgroundPID() {
        return lastBackgroundPID;
    }

    /**
     * Executes cmd and returns the output lines as a list of Strings
     * @param cmd cmd to be executed
     * @return If successful an Ok result of output lines as a list of Strings. Err result with the error message as a
     * single String in case of an error.
     */
    public static Result<List<String>, String> runProcess(List<String> cmd){
        return runProcess(null, null, cmd);
    }

    /**
     * Executes cmd and returns the output lines as a list of Strings
     * @param cmd cmd to be executed
     * @return If successful an Ok result of output lines as a list of Strings. Err result with the error message as a
     * single String in case of an error.
     */
    public static Result<List<String>, String> runProcess(String... cmd) {
        return runProcess(null, null, List.of(cmd));
    }

    /**
     * Executes cmd, streams input to stdin of the cmd if given and writes output to the given output file or returns
     * the output lines as a list of Strings
     * @param outputFile Path of the output file. Output lines will be returned as a list of Strings if null is given.
     * @param input will be streamed into stdin of the executed cmd if not null
     * @param cmd cmd to be executed
     * @return If successful an Ok result of output lines as a list of Strings or null, depending on whether an
     * {@code outputFile} was given. Err result with the error message as a single String in case of an error.
     */
    public static Result<List<String>, String> runProcess(Path outputFile, String input, List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            if (input != null) {
                BufferedWriter bufferedWriter = new BufferedWriter(
                        new OutputStreamWriter(p.getOutputStream(),StandardCharsets.UTF_8));
                bufferedWriter.write(input);
                bufferedWriter.close();
            }
            List<String> output = null;
            if (outputFile != null) {
                FileOutputStream fileOutputStream = new FileOutputStream(outputFile.toFile());
                p.getInputStream().transferTo(fileOutputStream);
                fileOutputStream.close();
            } else {
                output = new BufferedReader(new InputStreamReader(p.getInputStream()))
                        .lines()
                        .collect(Collectors.toList());
            }
            if (!p.waitFor(PROCESS_TIMEOUT, TimeUnit.SECONDS)) {
                var errMsg = "execution timeout for cmd " + cmd + " reached (" + PROCESS_TIMEOUT + " seconds)";
                Log.printError(errMsg);
                return Result.errOf(errMsg);
            }
            return Result.okOf(output);
        } catch (Exception e) {
            var errMsg = "unable to execute cmd " + cmd + " or redirect to file " + outputFile + ": " + e.getMessage()
                    + "\n" + e.fillInStackTrace();
            Log.printError(errMsg);
            return Result.errOf(errMsg);
        }
    }

    /**
     * Executes cmd, streams input to stdin of the cmd if given and writes output to the given output file or returns
     * the output lines as a list of Strings
     * @param outputFile Path of the output file. Output lines will be returned as a list of Strings if null is given.
     * @param input will be streamed into stdin of the executed cmd if not null
     * @param cmd cmd to be executed
     * @return If successful an Ok result of output lines as a list of Strings or null, depending on whether an
     * {@code outputFile} was given. Err result with the error message as a single String in case of an error.
     */
    public static Result<List<String>, String> runProcess(Path outputFile, String input, String... cmd) {
        return runProcess(outputFile, input, List.of(cmd));
    }

    public static Result<String, String> runBackgroundProcess(String... cmd) {
        return runBackgroundProcess(List.of(cmd));
    }

    public static Result<String, String> runBackgroundProcess(List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process p;

        try {
            p = pb.start();
            lastBackgroundPID = p.pid();
        } catch (Exception e) {
            var errMsg = "unable to execute cmd " + cmd + ": " + e.getMessage()
                    + "\n" + e.fillInStackTrace();
            Log.printError(errMsg);
            return Result.errOf(errMsg);
        }

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Log.println("Waiting for background process status");
        }

        if (p.isAlive()) {
            // Process is still in background, return an empty OK result
            return Result.okOf("");
        }

        // process has finished very shortly, this is probably an error.
        // return process output for analysis
        String output = new BufferedReader(new InputStreamReader(p.getInputStream()))
                .lines()
                .collect(Collectors.joining());

        return Result.errOf(output);
    }
}
