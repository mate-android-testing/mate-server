package org.mate.io;

import org.mate.util.Log;

import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ProcessRunner {
    private static final long PROCESS_TIMEOUT = 10; //Timeout of commands in seconds

    private ProcessRunner() {}

    public static boolean isWin = System.getProperty("os.name").startsWith("Windows");

    /**
     * Executes cmd and returns the output lines as a list of Strings
     * @param cmd cmd to be executed
     * @return cmd output lines as list of Strings
     */
    public static List<String> runProcess(List<String> cmd){
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

    /**
     * Executes cmd and returns the output lines as a list of Strings
     * @param cmd cmd to be executed
     * @return cmd output lines as list of Strings
     */
    public static List<String> runProcess(String... cmd) {
        return runProcess(List.of(cmd));
    }

    /**
     * Executes cmd and writes output to the given output file
     * @param outputFile Path of the output file
     * @param cmd cmd to be executed
     * @return true if successful otherwise false
     */
    public static boolean runProcess(Path outputFile, List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        boolean successful = true;
        try {
            Process p = pb.start();
            FileOutputStream fileOutputStream = new FileOutputStream(outputFile.toFile());
            p.getInputStream().transferTo(fileOutputStream);
            fileOutputStream.close();
        } catch (Exception e) {
            Log.printError("unable to execute cmd " + cmd + " or redirect to file " + outputFile + ": "
                    + e.getMessage() + "\n" + e.fillInStackTrace());
            successful = false;
        }
        return successful;

    }

    /**
     * Executes cmd and writes output to the given output file
     * @param outputFile Path of the output file
     * @param cmd cmd to be executed
     * @return true if successful otherwise false
     */
    public static boolean runProcess(Path outputFile, String... cmd) {
        return runProcess(outputFile, List.of(cmd));
    }
}
