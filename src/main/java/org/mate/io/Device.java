package org.mate.io;

import org.mate.pdf.Report;
import org.mate.util.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Device {

    public static Hashtable<String, Device> devices;
    private final AndroidEnvironment androidEnvironment;

    private String deviceID;
    private String packageName;
    private boolean busy;
    private final int apiVersion;
    private String currentScreenShotLocation;

    /**
     * The set of covered test cases, i.e. for which traces have been dumped.
     */
    private final Set<String> coveredTestCases = new HashSet<>();

    // defines where the apps, in particular the APKs are located
    public static Path appsDir;

    public Device(String deviceID, AndroidEnvironment androidEnvironment) {
        this.deviceID = deviceID;
        this.androidEnvironment = androidEnvironment;
        this.packageName = "";
        this.busy = false;
        this.apiVersion = getAPIVersionFromADB();
    }

    public String getCurrentScreenShotLocation() {
        return currentScreenShotLocation;
    }

    public void setCurrentScreenShotLocation(String currentScreenShotLocation) {
        this.currentScreenShotLocation = currentScreenShotLocation;
    }

    public String getDeviceID() {
        return deviceID;
    }

    public void setDeviceID(String deviceID) {
        this.deviceID = deviceID;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
    }

    public boolean isBusy() {
        return this.busy;
    }

    public int getApiVersion() {
        return apiVersion;
    }

    private int getAPIVersionFromADB() {
        List<String> result = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID,
                "shell", "getprop", "ro.build.version.sdk").getOk();
        if (result != null && result.size() > 0) {
            Log.println("API version: " + result.get(0));
            return Integer.parseInt(result.get(0));
        } else {
            throw new IllegalStateException("Couldn't derive emulator API version via ADB!");
        }
    }

    /**
     * Fetches a serialized test case from the internal storage.
     * Afterwards, the test case file is erased from the emulator.
     *
     * @param testCaseDir The test case directory on the emulator.
     * @param testCase The name of the test case file.
     * @return Returns {@code true} if the test case file could be fetched,
     *         otherwise {@code false} is returned.
     */
    public boolean fetchTestCase(String testCaseDir, String testCase) {

        // TODO: check whether on Windows the leading slash needs to be removed (it seems as it is not necessary)

        // check whether the test case file exists
        Result<List<String>, String> result = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(),
                "-s", deviceID, "shell", "ls", testCaseDir);

        if (result.isErr() || !result.getOk().stream().anyMatch(str -> str.trim().equals(testCase))) {

            // the test case file couldn't be found, retry once
            Log.println("Couldn't locate test case file: " + result);
            Util.sleep(3);

            List<String> files = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID,
                    "shell", "ls", testCaseDir).getOk();

            if (!files.stream().anyMatch(str -> str.trim().equals(testCase))) {
                Log.println("Couldn't locate test case file: " + files);
                return false;
            }
        }

        File appDir = new File(appsDir.toFile(), packageName);
        File testCasesDir = new File(appDir, "test-cases");
        File testCaseFile = new File(testCasesDir, testCase);

        // create local test-cases directory if not present
        if (!testCasesDir.exists()) {
            Log.println("Creating test-cases directory: " + testCasesDir.mkdirs());
        }

        // fetch the test case file
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "pull",
                testCaseDir + "/" + testCase, String.valueOf(testCaseFile));

        if (!testCaseFile.exists()) {
            Log.println("Pulling test case file " + testCaseFile + " failed!");
        }

        // remove test case file from emulator
        var removeOp = ProcessRunner.runProcess(
                androidEnvironment.getAdbExecutable(), "-s", deviceID, "shell",
                "rm", "-f", testCaseDir + "/" + testCase);

        Log.println("Removal of test case file succeeded: " + removeOp.isOk());
        return testCaseFile.exists();
    }

    /**
     * Fetches an espresso test from the internal storage. Afterwards, the espresso test is erased from the emulator.
     *
     * @param espressoDir The espresso tests directory on the emulator.
     * @param testCase The name of the espresso test.
     * @return Returns {@code true} if the test case file could be fetched,
     *          otherwise {@code false} is returned.
     */
    public boolean fetchEspressoTest(String espressoDir, String testCase) {

        // look up the existing espresso tests in the espresso dir
        List<String> files = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID,
                "shell", "ls", espressoDir).getOk();

        // check whether the specified espresso test exists
        if (!files.stream().anyMatch(str -> str.trim().equals(testCase))) {
            return false;
        }

        File appDir = new File(appsDir.toFile(), packageName);
        File espressoTestsDir = new File(appDir, "espresso-tests");
        File testCaseFile = new File(espressoTestsDir, testCase);

        // create local test-cases directory if not present
        if (!espressoTestsDir.exists()) {
            Log.println("Creating espresso-tests directory: " + espressoTestsDir.mkdirs());
        }

        // fetch the espresso test
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "pull",
                espressoDir + "/" + testCase, String.valueOf(testCaseFile));

        if (!testCaseFile.exists()) {
            Log.println("Pulling espresso test " + testCaseFile + " failed!");
        }

        // remove espresso test from emulator
        var removeOp = ProcessRunner.runProcess(
                androidEnvironment.getAdbExecutable(), "-s", deviceID, "shell",
                "rm", "-f", espressoDir + "/" + testCase);

        Log.println("Removal of espresso test succeeded: " + removeOp.isOk());
        return testCaseFile.exists();
    }

    /**
     * Grants read and write permission for external storage to the given app.
     *
     * @param packageName The package name of the app.
     * @return Returns {@code true} if the permissions could be granted,
     *         otherwise {@code false}.
     */
    public boolean grantPermissions(String packageName) {

        List<String> responseRead = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "shell", "pm", "grant", packageName, "android.permission.READ_EXTERNAL_STORAGE").getOk();
        List<String> responseWrite = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "shell", "pm", "grant", packageName, "android.permission.WRITE_EXTERNAL_STORAGE").getOk();

        // empty response should signal no failure
        return responseRead.isEmpty() && responseWrite.isEmpty();
    }

    /**
     * Broadcasts the notification of a system event to a given receiver.
     *
     * @param packageName The package name of the AUT.
     * @param receiver The broadcast receiver listening for the system event notification.
     * @param action The actual system event.
     * @param dynamicReceiver Whether the receiver is a dynamic one.
     * @return Returns {@code true} if the system event notification could be successfully
     *         broad-casted, otherwise {@code false}.
     */
    public boolean executeSystemEvent(String packageName, String receiver, String action, boolean dynamicReceiver) {

        // the inner class seperator '$' needs to be escaped
        receiver = receiver.replaceAll("\\$", Matcher.quoteReplacement("\\$"));

        String tag;
        String component;

        if (dynamicReceiver) {
            // we can't specify the full component name (solely package) -> dynamic receivers can't be triggered by explicit intents
            tag = "-p";
            component = packageName;
        } else {
            tag = "-n";
            component = packageName + "/" + receiver;
        }

        var response = ProcessRunner.runProcess(
                androidEnvironment.getAdbExecutable(),
                "-s",
                deviceID,
                "shell",
                "su",
                "root",
                "am",
                "broadcast",
                "-a",
                action,
                tag,
                component);

        if (response.isErr()) {
            return false;
        } else {
            return response.getOk().contains("Broadcast completed: result=0");
        }
    }

    /**
     * Pushes dummy files for various data types onto the
     * external storage.
     *
     * @return Returns whether pushing files succeeded.
     */
    public boolean pushDummyFiles() {

        var f1 = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestBmp.bmp", "sdcard/mateTestBmp.bmp");
        var f2 = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestGif.gif", "sdcard/mateTestGif.gif");
        var f3 = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestJpg.jpg", "sdcard/mateTestJpg.jpg");
        var f4 = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestJson.json", "sdcard/mateTestJson.json");
        var f5 = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestMid.mid", "sdcard/mateTestMid.mid");
        var f6 = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestPdf.pdf", "sdcard/mateTestPdf.pdf");
        var f7 = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestPng.png", "sdcard/mateTestPng.png");
        var f8 = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestTiff.tiff", "sdcard/mateTestTiff.tiff");
        var f9 = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestTxt.txt", "sdcard/mateTestTxt.txt");
        var f10 = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestWav.wav", "sdcard/mateTestWav.wav");
        var f11 = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestCsv.csv", "sdcard/mateTestCsv.csv");
        var f12 = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestXml.xml", "sdcard/mateTestXml.xml");
        var f13 = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestOgg.ogg", "sdcard/mateTestOgg.ogg");
        var f14 = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestMp3.mp3", "sdcard/mateTestMp3.mp3");

        if (f1.isErr() || f2.isErr() || f3.isErr() || f4.isErr() || f5.isErr() || f6.isErr()
                || f7.isErr() || f8.isErr() || f9.isErr() || f10.isErr() || f11.isErr() || f12.isErr()
                || f13.isErr() || f14.isErr()) {
            return false;
        } else {
            final String success = "1 file pushed";
            return f1.getOk().get(0).contains(success) && f2.getOk().get(0).contains(success)
                    && f3.getOk().get(0).contains(success) && f4.getOk().get(0).contains(success)
                    && f5.getOk().get(0).contains(success) && f6.getOk().get(0).contains(success)
                    && f7.getOk().get(0).contains(success) && f8.getOk().get(0).contains(success)
                    && f9.getOk().get(0).contains(success) && f10.getOk().get(0).contains(success)
                    && f11.getOk().get(0).contains(success) && f12.getOk().get(0).contains(success)
                    && f13.getOk().get(0).contains(success) && f14.getOk().get(0).contains(success);
        }
    }

    /**
     * Checks whether writing the collected traces onto the external storage has been completed, i.e. if the info.txt
     * has been written to the external storage as well.
     *
     * @param externalStorage The path to the external storage.
     * @return Returns {@code true} if the writing process has been finished, otherwise {@code false}.
     */
    private boolean completedWritingTraces(final String externalStorage) {
        List<String> files = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID,
                "shell", "ls", externalStorage).getOk();
        Log.println("Files: " + files);

        return files.stream().anyMatch(str -> str.trim().equals("info.txt"));
    }

    /**
     * Checks whether the traces.txt and info.txt files exist on the external storage of the emulator.
     *
     * @return Returns a pair indicating whether the traces.txt and info.txt file exists.
     */
    private Pair<Boolean, Boolean> doTracesAndInfoFileExist() {

        final String tracesDir = "storage/emulated/0";
        final var query =
                ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "shell", "ls", tracesDir);

        if (query.isOk()) {
            final var files = query.getOk();
            final var tracesFileExists = files.stream()
                    .anyMatch(str -> str.trim().equals("traces.txt"));
            final var infoFileExists = files.stream()
                    .anyMatch(str -> str.trim().equals("info.txt"));
            return new Pair<>(tracesFileExists, infoFileExists);
        } else {
            throw new RuntimeException("Cannot get status of traces file: '" + query.getErr() + "'");
        }
    }

    /**
     * Requests the tracer to dump its traces to the external storage of the emulator.
     */
    public void getTracesFromTracer() {

        var traceFilesExists = doTracesAndInfoFileExist();
        var traceFileExists = traceFilesExists.fst();
        var infoFileExists = traceFilesExists.snd();

        if (infoFileExists && !traceFileExists) {
            throw new IllegalStateException("info.txt exists, but not traces.txt, this should not happen.");
        }

        if (traceFileExists && !infoFileExists) {

            /*
             * There are two possible states here:
             *
             *     1) The tracer is currently dumping its traces, and we just need to wait for it to finish.
             *     2) The tracer dumped the traces because its cache got full. In that case we need to call the tracer
             *        to get the remaining traces.
             *
             * We have no clear method of determining in which state we are in, so we have to wait for a while to have
             * the tracer potentially finish dumping its traces and re-check for the info.txt.
             */
            final int maxWaitTimeInSeconds = 30;
            for (int i = 1; i < maxWaitTimeInSeconds; ++i) {
                Util.sleep(1);
                traceFilesExists = doTracesAndInfoFileExist();
                traceFileExists = traceFilesExists.fst();
                infoFileExists = traceFilesExists.snd();
                if (traceFileExists && infoFileExists) {
                    // We were in case 1), now the tracer has finished dumping the traces, so we can continue.
                    break;
                }
            }

            if (infoFileExists && !traceFileExists) {
                throw new IllegalStateException("info.txt exists, but not traces.txt, this should not happen.");
            }
        }

        if (!infoFileExists) {
            /*
             * We assume that the tracer is not writing traces here. So either the traces.txt exists (because of a dump
             * of the tracer when it has hit its cache limit) or it does not. In either case we need to call the tracer
             * to dump its remaining traces.
             */
            var broadcastOperation =
                    ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(),
                            "-s",
                            deviceID,
                            "shell",
                            "am",
                            "broadcast",
                            "-a",
                            "STORE_TRACES",
                            "-n",
                            packageName + "/de.uni_passau.fim.auermich.tracer.Tracer"
                    );

            if (broadcastOperation.isErr()) {
                throw new IllegalStateException("Couldn't send broadcast!");
            }
        }
    }

    /**
     * Pulls the traces.txt file from the external storage (sd card) if present.
     *
     * @param chromosome Identifies either a test case or test suite.
     * @param entity If chromosome identifies a test suite, entity identifies the test case, otherwise {@code null}.
     */
    public void pullTraceFile(String chromosome, String entity) {

        Log.println("Chromosome: " + chromosome);
        Log.println("Entity: " + entity);

        String testCase = entity == null ? chromosome : entity;

        if (coveredTestCases.contains(testCase)) {
            // We have already dumped the traces for the given test case. We don't want to overwrite (corrupt) them.
            return;
        }

        // traces are stored on the sd card (external storage)
        String tracesDir = "storage/emulated/0";

        // check whether writing traces has been completed yet
        while (!completedWritingTraces(tracesDir)) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Log.println("Waiting for info.txt failed!");
                throw new IllegalStateException(e);
            }
        }

        // get number of traces from info.txt
        Result<List<String>, String> content = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(),
                "-s", deviceID, "shell", "cat", tracesDir + "/info.txt");

        if (content.isErr()) {
            throw new IllegalStateException("Couldn't read info.txt from emulator: " + content);
        }

        // request files from external storage (sd card)
        Result<List<String>, String> files = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(),
                "-s", deviceID, "shell", "ls", tracesDir);

        if (files.isErr()) {
            throw new IllegalStateException("Couldn't locate any file on external storage: " + files);
        }

        // check whether there is some traces file
        if (!files.getOk().stream().anyMatch(str -> str.trim().equals("traces.txt"))) {
            throw new IllegalStateException("Couldn't locate the traces.txt file!");
        }

        File appDir = new File(appsDir.toFile(), packageName);
        File baseTracesDir = new File(appDir, "traces");

        // create base traces directory if not yet present
        if (!baseTracesDir.exists()) {
            Log.println("Creating base traces directory: " + baseTracesDir.mkdirs());
        }

        File tracesFile = new File(baseTracesDir, chromosome);

        if (entity != null) {

            // traces file refers to a directory
            if (!tracesFile.exists()) {
                Log.println("Creating traces directory: " + tracesFile.mkdirs());
            }

            // we deal with test suites, thus entity refers to the test case name
            tracesFile = new File(tracesFile, entity);
        }

        Log.println("Traces File: " + tracesFile);

        var pullOperation = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(),
                "-s", deviceID, "pull", tracesDir + "/traces.txt", String.valueOf(tracesFile));

        var pullError = pullOperation.isErr()
                || (pullOperation.getOk().stream().anyMatch(s -> s.contains("adb"))
                && pullOperation.getOk().stream().anyMatch(s -> s.contains("error")));

        if (pullError) {
            throw new IllegalStateException("Couldn't pull traces.txt from emulator: " + pullOperation);
        } else {
            Log.println("Pull Operation: " + pullOperation.getOk());
        }

        // We successfully pulled the traces, no need to pull them again if the same request is sent again.
        coveredTestCases.add(testCase);

        // check whether there is a mismatch between info.txt and traces.txt
        try {
            long numberOfLines = Files.lines(tracesFile.toPath()).count();
            Log.println("Number of traces according to traces.txt: " + numberOfLines);

            int numberOfTraces = Integer.parseInt(content.getOk().get(0).trim());
            Log.println("Number of traces according to info.txt: " + numberOfTraces);
        } catch (IOException e) {
            Log.println("Couldn't count lines in traces.txt:", e);
        } catch (NumberFormatException e) {
            // in very rare cases, the info.txt seems to be corrupted
            Log.println("Couldn't read number of traces from info.txt:", e);
        }

        // remove trace file from emulator
        var removeTraceFileOp = ProcessRunner.runProcess(
                androidEnvironment.getAdbExecutable(), "-s", deviceID, "shell",
                "rm", "-f", tracesDir + "/traces.txt");

        // remove info file from emulator
        var removeInfoFileOp = ProcessRunner.runProcess(
                androidEnvironment.getAdbExecutable(), "-s", deviceID, "shell",
                "rm", "-f", tracesDir + "/info.txt");

        var removeTracesError = removeTraceFileOp.isErr()
                || (removeTraceFileOp.getOk().stream().anyMatch(s -> s.contains("adb"))
                && removeTraceFileOp.getOk().stream().anyMatch(s -> s.contains("error")));

        if (removeTracesError) {
            throw new IllegalStateException("Couldn't remove traces.txt from emulator: " + removeTraceFileOp);
        } else {
            Log.println("Remove Trace File Operation: " + removeTraceFileOp.getOk());
        }

        var removeInfoError = removeInfoFileOp.isErr()
                || (removeInfoFileOp.getOk().stream().anyMatch(s -> s.contains("adb"))
                && removeInfoFileOp.getOk().stream().anyMatch(s -> s.contains("error")));

        if (removeInfoError) {
            throw new IllegalStateException("Couldn't remove info.txt from emulator: " + removeInfoFileOp);
        } else {
            Log.println("Remove Info File Operation: " + removeInfoFileOp.getOk());
        }
    }

    /**
     * Returns the name of the currently visible activity or
     * 'unknown' if the activity name couldn't be extracted.
     * If the AUT crashed, it can happen that no activity
     * appears to be in foreground.
     *
     * @return Returns the name of the currently visible activity.
     */
    public String getCurrentActivity() {

        String response = "unknown";
        String cmd = androidEnvironment.getAdbExecutable() + " -s " + deviceID + " shell dumpsys activity activities | grep mFocusedActivity | cut -d \" \" -f 6";
        if (getApiVersion() == 23 || getApiVersion() == 25) {
            if (ProcessRunner.isWin) {
                cmd = "$focused = " + androidEnvironment.getAdbExecutable() + " -s " + deviceID + " shell dumpsys activity activities "
                        + "| select-string mFocusedActivity ; \"$focused\".Line.split(\" \")[5]";
            } else {
                cmd = androidEnvironment.getAdbExecutable() + " -s " + deviceID + " shell dumpsys activity activities | grep mFocusedActivity | cut -d \" \" -f 6";
            }
        }

        if (getApiVersion() == 26 || getApiVersion() == 27) {
            if (ProcessRunner.isWin) {
                cmd = "$focused = " + androidEnvironment.getAdbExecutable() + " -s " + deviceID + " shell dumpsys activity activities "
                        + "| select-string mFocusedActivity ; \"$focused\".Line.split(\" \")[7]";
            } else {
                cmd = androidEnvironment.getAdbExecutable() + " -s " + deviceID + " shell dumpsys activity activities | grep mResumedActivity | cut -d \" \" -f 8";
            }
        }

        /*
         * 27.04.2019
         *
         * The record 'mFocusedActivity' is not available anymore under Windows for API Level 28 (tested on Nexus5 and PixelC),
         * although it is available still under Linux (tested on Nexus5), which is somewhat strange.
         * Instead, we need to search for the 'realActivity' record, pick the second one (seems to be the current active Activity)
         * and split on '='.
         */
        if (getApiVersion() == 28) {
            if (ProcessRunner.isWin) {
                cmd = "$activity = " + androidEnvironment.getAdbExecutable() + " -s " + deviceID + " shell dumpsys activity activities "
                        + "| select-string \"realActivity\" ; $focused = $activity[1] ; $final = $focused -split '=' ; echo $final[1]";
                // Alternatively use: "$focused.Line.split(=)[1] \"";
            } else {
                cmd = androidEnvironment.getAdbExecutable() + " -s " + deviceID + " shell dumpsys activity activities | grep mResumedActivity | cut -d \" \" -f 8";
            }
        }

        if (getApiVersion() == 29 || getApiVersion() == 30) {
            if (ProcessRunner.isWin) {
                cmd = "$focused = " + androidEnvironment.getAdbExecutable() + " -s " + deviceID
                        + " shell dumpsys activity activities "
                        + "| select-string mResumedActivity ; \"$focused\".Line.split(\" \")[7]";
            } else {
                cmd = androidEnvironment.getAdbExecutable() + " -s " + deviceID
                        + " shell dumpsys activity activities | grep mResumedActivity | cut -d \" \" -f 8";
            }
        }

        List<String> result;

        if (ProcessRunner.isWin) {
            result = ProcessRunner.runProcess("powershell", "-command", cmd).getOk();
        } else {
            result = ProcessRunner.runProcess("bash", "-c", cmd).getOk();
        }

        if (result != null && result.size() > 0) {
            response = result.get(0);
            Log.println("Activity: " + response);
        } else {
            Log.printError("Failed to retrieve current activity: " + result);
        }

        return response;
    }

    /**
     * Returns the activity names belonging to the AUT.
     *
     * @return Returns the activities of the AUT.
     */
    public List<String> getActivities() {

        List<String> activities = new ArrayList<>();

        String apkPath = appsDir + File.separator + packageName + ".apk";

        var lines = ProcessRunner.runProcess(androidEnvironment.getAaptExecutable(),
                "dump",
                "xmltree",
                apkPath,
                "AndroidManifest.xml").getOk();
        var foundPackage = false;
        var foundAct = false;
        String whitePrefix = null;
        String pkgName = null;

        var rawPattern = Pattern.compile("\\(Raw: \"([^\"]*)\"");

        for (String line : lines) {
            if (foundPackage) {
                if (foundAct) {
                    if (line.startsWith(whitePrefix + "A:")
                            && line.contains("android:name")
                            && line.contains("(Raw: ")) {
                        foundAct = false;
                        Matcher matcher = rawPattern.matcher(line);
                        matcher.find();
                        String act = matcher.group(1);
                        if (act.startsWith(pkgName)) {
                            activities.add(act.substring(0, pkgName.length()) + "/" + act.substring(pkgName.length()));
                        } else {
                            activities.add(packageName + "/" + act);
                        }
                    }
                } else {
                    if (line.stripLeading().startsWith("E: activity ")) {
                        foundAct = true;
                        whitePrefix = " ".repeat(line.length() - line.stripLeading().length() + 2);
                    }
                }
            } else {
                if (line.stripLeading().startsWith("A: package=\"") && line.contains("(Raw: ")) {
                    foundPackage = true;
                    Matcher matcher = rawPattern.matcher(line);
                    matcher.find();
                    pkgName = matcher.group(1);
                }
            }
        }

        Log.println("Activities:");
        for (String activity : activities) {
            Log.println("\t" + activity);
        }
        return activities;
    }

    /**
     * Lists the devices according to the output of 'adb devices'.
     *
     * @param androidEnvironment A reference to the android environment, e.g. access to adb.
     */
    public static void listDevices(AndroidEnvironment androidEnvironment) {
        List<String> resultDevices = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "devices").getOk();
        Log.println("Devices: ");
        for (String deviceStr : resultDevices) {
            if (deviceStr.contains("device") && !deviceStr.contains("attached")) {
                Log.println(deviceStr);
            }
        }
    }

    /**
     * Allocates emulator instances that are attached according to 'adb devices'.
     *
     * @param androidEnvironment A reference to the android environment, e.g. access to adb.
     */
    public static void loadActiveDevices(AndroidEnvironment androidEnvironment) {
        if (devices == null)
            devices = new Hashtable<String, Device>();
        List<String> resultDevices = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "devices").getOk();
        for (String deviceStr : resultDevices) {
            String devID = "";
            if (deviceStr.contains("device") && !deviceStr.contains("attached")) {
                devID = deviceStr.replace("device", "");
                devID = devID.replace(" ", "");
                devID = devID.trim();
                if (devID.length() > 13)
                    devID = devID.substring(0, devID.length() - 1);
                if (devices.get(devID) == null) {
                    Device device = new Device(devID, androidEnvironment);
                    devices.put(devID, device);
                }
            }
        }
    }

    /**
     * Prints the discovered emulators and its running AUT's package name.
     */
    public static void listActiveDevices() {
        for (String devID : devices.keySet()) {
            Device device = devices.get(devID);
            Log.println(device.getDeviceID() + " - " + device.isBusy() + ": " + device.getPackageName());
        }
    }

    /**
     * Requests the name of the emulator that is running the AUT specified through the
     * given package name.
     *
     * @param androidEnvironment A reference to the android environment, e.g. access to adb.
     * @return Returns the name of the emulator.
     */
    public static String allocateDevice(String packageName, AndroidEnvironment androidEnvironment) {

        // check which emulator is running the AUT
        String deviceID = getDeviceRunningPackage(packageName, androidEnvironment);
        Device device = devices.get(deviceID);
        if (device != null) {
            device.setPackageName(packageName);
            device.setBusy(true);
        }

        Report.createHeader(deviceID, packageName);

        return deviceID;
    }

    /**
     * Returns the emulator name running the given package name.
     *
     * @param packageName The package name of the AUT.
     * @param androidEnvironment A reference to the android environment, e.g. access to adb.
     * @return Returns the name of the emulator running the given app. If no such emulator is
     *         found, the emtpy string is returned.
     */
    public static String getDeviceRunningPackage(String packageName, AndroidEnvironment androidEnvironment) {
        // FIXME: if multiple emulators are running the same app, we always return the first emulator match
        for (String key : devices.keySet()) {
            // prints the pid of the app process or an empty string if no process is executing the app
            // see for recent change: https://stackoverflow.com/questions/16691487/how-to-detect-running-app-using-adb-command
            List<String> result = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", key, "shell", "pidof", packageName).getOk();
            for (String res : result) {
                Log.println("PID: " + res);
                // non empty response indicates that emulator is running app
                if (!res.isEmpty())
                    return key;
            }
        }
        return "";
    }

    /**
     * Marks the emulator as released.
     *
     * @return Returns the string 'released' if the operation succeeded,
     *         otherwise an empty string is returned.
     */
    public String releaseDevice() {
        setPackageName("");
        setBusy(false);
        return "released";
    }

    /**
     * Returns the emulator with the given emulator id.
     *
     * @param deviceId The id of the emulator.
     * @return Returns the emulator associated with the given id.
     */
    public static Device getDevice(String deviceId) {
        return devices.get(deviceId);
    }
}
