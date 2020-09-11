package org.mate.io;

import org.mate.pdf.Report;
import org.mate.Server;
import org.mate.accessibility.ImageHandler;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;
import org.mate.util.Result;

import java.io.*;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Device {

    public static Hashtable<String, Device> devices;
    private final AndroidEnvironment androidEnvironment;

    private String deviceID;
    private String packageName;
    private boolean busy;
    private int APIVersion;
    private String currentScreenShotLocation;

    public Device(String deviceID, AndroidEnvironment androidEnvironment) {
        this.deviceID = deviceID;
        this.androidEnvironment = androidEnvironment;
        this.packageName = "";
        this.busy = false;
        APIVersion = this.getAPIVersionFromADB();
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

    public int getAPIVersion() {
        return APIVersion;
    }

    private int getAPIVersionFromADB() {
        List<String> result = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "shell", "getprop", "ro.build.version.sdk").getOk();
        if (result != null && result.size() > 0) {
            System.out.println("API consulta: " + result.get(0));
            return Integer.valueOf(result.get(0));
        }
        return 23;
    }

    /**
     * Grants read and write permission for external storage to the given app.
     *
     * @param packageName The package name of the app.
     * @return Returns {@code true} if the permissions could be granted,
     * otherwise {@code false}.
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
     * @param packageName     The package name of the AUT.
     * @param receiver        The broadcast receiver listening for the system event notification.
     * @param action          The actual system event.
     * @param dynamicReceiver Whether the receiver is a dynamic one.
     * @return Returns {@code true} if the system event notification could be successfully
     * broad-casted, otherwise {@code false}.
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

        List<String> response = ProcessRunner.runProcess(
                "adb",
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
                component).getOk();

        System.out.println("Response: " + response);
        return true;
    }

    /**
     * Pushes dummy files for various data types onto the
     * external storage.
     *
     * @return Returns whether pushing files succeeded.
     */
    public boolean pushDummyFiles() {

        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "root");
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestBmp.bmp", "sdcard/mateTestBmp.bmp");
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestGif.gif", "sdcard/mateTestGif.gif");
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestJpg.jpg", "sdcard/mateTestJpg.jpg");
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestJson.json", "sdcard/mateTestJson.json");
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestMid.mid", "sdcard/mateTestMid.mid");
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestPdf.pdf", "sdcard/mateTestPdf.pdf");
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestPng.png", "sdcard/mateTestPng.png");
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestTiff.tiff", "sdcard/mateTestTiff.tiff");
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestTxt.txt", "sdcard/mateTestTxt.txt");
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestWav.wav", "sdcard/mateTestWav.wav");
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestCsv.csv", "sdcard/mateTestCsv.csv");
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestXml.xml", "sdcard/mateTestXml.xml");
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestOgg.ogg", "sdcard/mateTestOgg.ogg");
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "push", "mediafiles/mateTestMp3.mp3", "sdcard/mateTestMp3.mp3");
        return true;
    }

    /**
     * Pulls the traces.txt file from the external storage (sd card) if present.
     * The file is stored in the working directory, that is the mate-commander directory by default.
     *
     * @return Returns {@code true} if the trace file could be pulled,
     * otherwise {@code false}.
     */
    // TODO: can be removed and replaced with pullTraceFile(String fileName)
    public boolean pullTraceFile() {

        // traces are stored on the sd card (external storage)
        String tracesDir = "storage/emulated/0"; // + packageName;
        // String checkFileCmd = "adb -s " + deviceID + " shell " + "\"run-as " + packageName + " ls\"";

        List<String> files = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "shell", "ls", tracesDir).getOk();

        // check whether there is some traces file
        if (!files.stream().anyMatch(str -> str.trim().equals("traces.txt"))) {
            return false;
        }

        // use the working directory (MATE-COMMANDER HOME) as output directory for trace file
        String workingDir = System.getProperty("user.dir");

        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "pull", tracesDir + "/traces.txt", workingDir + File.separator + "traces.txt");

        return true;
    }

    /**
     * Pulls the traces.txt file from the external storage (sd card) if present.
     * The file is stored in the working directory, that is the mate-commander directory by default.
     *
     * @param fileName The name of the traces file.
     * @return Returns the path to the traces file.
     */
    public File pullTraceFile(String fileName) {

        // traces are stored on the sd card (external storage)
        String tracesDir = "storage/emulated/0"; // + packageName;

        Result<List<String>, String> files = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(),
                "-s", deviceID, "shell", "ls", tracesDir);

        if (files.isErr()) {
            throw new IllegalStateException("Couldn't locate any file on external storage!");
        }

        // check whether there is some traces file
        if (!files.getOk().stream().anyMatch(str -> str.trim().equals("traces.txt"))) {
            throw new IllegalStateException("Couldn't locate the traces.txt file!");
        }

        // use the working directory (MATE-COMMANDER HOME) as output directory for trace file
        String workingDir = System.getProperty("user.dir");
        File appDir = new File(workingDir, packageName);
        File localTracesDir = new File(appDir, "traces");

        // create local traces directory if not yet present
        if (!localTracesDir.exists()) {
            Log.println("Creating local traces directory: " + localTracesDir.mkdirs());
        }

        var pullOperation = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(),
                "-s", deviceID, "pull", tracesDir + "/traces.txt", localTracesDir + File.separator + fileName);

        if (pullOperation.isErr()) {
            throw new IllegalStateException("Couldn't pull traces.txt file from emulator's external storage!");
        }

        return new File(localTracesDir, fileName);
    }

    public String getCurrentActivity() {

        String response = "unknown";
        String cmd = androidEnvironment.getAdbExecutable() + " -s " + deviceID + " shell dumpsys activity activities | grep mFocusedActivity | cut -d \" \" -f 6";
        if (getAPIVersion() == 23 || getAPIVersion() == 25) {
            if (ProcessRunner.isWin) {
                cmd = "$focused = " + androidEnvironment.getAdbExecutable() + " -s " + deviceID + " shell dumpsys activity activities "
                        + "| select-string mFocusedActivity ; \"$focused\".Line.split(\" \")[5]";
                System.out.println(cmd);
            } else {
                cmd = androidEnvironment.getAdbExecutable() + " -s " + deviceID + " shell dumpsys activity activities | grep mFocusedActivity | cut -d \" \" -f 6";
            }
        }

        if (getAPIVersion() == 26 || getAPIVersion() == 27) {
            if (ProcessRunner.isWin) {
                cmd = "$focused = " + androidEnvironment.getAdbExecutable() + " -s " + deviceID + " shell dumpsys activity activities "
                        + "| select-string mFocusedActivity ; \"$focused\".Line.split(\" \")[7]";
                System.out.println(cmd);
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
        if (getAPIVersion() == 28) {
            if (ProcessRunner.isWin) {
                cmd = "$activity = " + androidEnvironment.getAdbExecutable() + " -s " + deviceID + " shell dumpsys activity activities "
                        + "| select-string \"realActivity\" ; $focused = $activity[1] ; $final = $focused -split '=' ; echo $final[1]";
                // Alternatively use: "$focused.Line.split(=)[1] \"";
                System.out.println(cmd);
            } else {
                cmd = androidEnvironment.getAdbExecutable() + " -s " + deviceID + " shell dumpsys activity activities | grep mResumedActivity | cut -d \" \" -f 8";
            }
        }

        List<String> result;
        if (ProcessRunner.isWin) {
            result = ProcessRunner.runProcess("powershell", "-command", cmd).getOk();
        } else {
            result = ProcessRunner.runProcess("bash", "-c", cmd).getOk();
        }
        if (result != null && result.size() > 0)
            response = result.get(0);
        System.out.println("activity: " + response);

        return response;
    }

    public List<String> getActivities() {
        String cmd = "";
        List<String> activities = new ArrayList<>();
        var lines = ProcessRunner.runProcess(androidEnvironment.getAaptExecutable(),
                "dump",
                "xmltree",
                packageName + ".apk",
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

        System.out.println("activities:");
        for (String activity : activities) {
            System.out.println("\t" + activity);
        }
        return activities;
    }

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

    public static void listActiveDevices() {
        for (String devID : devices.keySet()) {
            Device device = devices.get(devID);
            System.out.println(device.getDeviceID() + " - " + device.isBusy() + ": " + device.getPackageName());
        }
    }

    public static String allocateDevice(String cmdStr, ImageHandler imageHandler, AndroidEnvironment androidEnvironment) {
        String parts[] = cmdStr.split(":");
        String packageName = parts[1];

        if (Server.emuName != null) {
            Device device = devices.get(Server.emuName);
            device.setPackageName(packageName);
            device.setBusy(true);
            return Server.emuName;
        }

        String deviceID = getDeviceRunningPackage(packageName, androidEnvironment);
        Device device = devices.get(deviceID);
        if (device != null) {
            device.setPackageName(packageName);
            device.setBusy(true);
        }
/*
        if (deviceID.equals("")){
            int i=0;
            boolean emulatorFound = false;
            Enumeration<String> keys = emulatorsAllocated.keys();
            while (keys.hasMoreElements() && !emulatorFound){
                String key = keys.nextElement();
                boolean allocated = emulatorsAllocated.get(key);
                if (!allocated){
                    response = key;
                    emulatorFound=true;
                    emulatorsAllocated.put(response, Boolean.TRUE);
                    emulatorsPackage.put(response,packageName);
                    System.out.println("found: " + response);
                }
            }
            if (!emulatorFound)
                response="";
        }*/
        //response = "4f60d1bb";

        Report.createHeader(deviceID, packageName);
        imageHandler.createPicturesFolder(deviceID, packageName);

        return deviceID;
    }

    public static String getDeviceRunningPackage(String packageName, AndroidEnvironment androidEnvironment) {
        for (String key : devices.keySet()) {
            List<String> result = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", key, "shell", "ps", packageName).getOk();
            for (String res : result) {
                System.out.println(res);
                if (res.contains(packageName))
                    return key;
            }
        }
        return "";
    }

    public static String releaseDevice(String cmdStr) {
        String response = "";
        String[] parts = cmdStr.split(":");
        if (parts.length > 0) {
            String deviceID = parts[1];
            Device device = devices.get(deviceID);
            if (device != null) {
                device.setPackageName("");
                device.setBusy(false);
                response = "released";
            }
        }
        return response;
    }

    public static Device getDevice(String deviceId) {
        return devices.get(deviceId);
    }
}
