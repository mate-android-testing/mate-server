package org.mate.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;

public class Log {
    private final boolean logToFile;
    private final boolean logToStdout;
    private final String logFilePath;
    private final BufferedWriter logFileWriter;
    private boolean log;
    private String buffer;

    private static Log logger = null;

    public static void registerLogger(Log logger) {
        Log.logger = logger;
    }

    public Log() {
        logToFile = false;
        logToStdout = true;
        logFilePath = null;
        logFileWriter = null;
        log = true;
        buffer = "";
    }

    public Log(String filePath) throws IOException {
        this(true, filePath);
    }

    public Log(boolean logToStdout, String filePath) throws IOException {
        logToFile = true;
        this.logToStdout = logToStdout;
        logFilePath = filePath;
        logFileWriter = new BufferedWriter(new FileWriter(filePath));
        log = true;
        buffer = "";
    }

    public void doLog() {
        log = true;
        writeToLog(buffer);
        buffer = "";
    }

    public void doNotLog() {
        log = false;
    }

    public static void printWarning(String message) {
        printWithTag("WARNING", message);
    }

    public static void printError(String message) {
        printWithTag("ERROR", message);
    }

    private static void printWithTag(String tag, String message) {
        if (logger == null) {
            throw new IllegalStateException("No logger registered");
        }
        StringBuilder output = new StringBuilder();
        for (String line : message.split("\n")) {
            output.append(tag)
                    .append(": ")
                    .append(line)
                    .append("\n");
        }
        output.setLength(output.length() - 1);
        println(output.toString());
    }

    public static void println(String message) {
        if (logger == null) {
            throw new IllegalStateException("No logger registered");
        }
        StringBuilder output = new StringBuilder();
        for (String line : message.split("\n")) {
            output.append(LocalDateTime.now())
                    .append(" ")
                    .append(line)
                    .append("\n");
        }

        if (logger.log) {
            logger.writeToLog(output.toString());
        } else {
            logger.buffer += output.toString();
        }
    }

    private void writeToLog(String output) {
        if (logToFile) {
            try {
                logFileWriter.append(output);
            } catch (IOException e) {
                System.err.println("Unexpected IO error when trying to write to log file (" + logFilePath + "):\n" +
                        e + "\n" + e.fillInStackTrace());
            }
        }
        if (logToStdout) {
            System.out.print(output);
        }
    }
}
