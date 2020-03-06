package org.mate.io;

import org.mate.pdf.Report;
import org.mate.Server;
import org.mate.accessibility.ImageHandler;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;

public class Device {

    public static Hashtable<String,Device> devices;

    private String deviceID;
    private String packageName;
    private boolean busy;
    private int APIVersion;
    private String currentScreenShotLocation;

    public Device(String deviceID){
        this.deviceID = deviceID;
        this.packageName = "";
        this.busy = false;
        APIVersion = this.getAPIVersionFromADB();
    }

    public String getCurrentScreenShotLocation(){
        return currentScreenShotLocation;
    }

    public void setCurrentScreenShotLocation(String currentScreenShotLocation){
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

    public void setBusy(boolean busy){
        this.busy = busy;
    }

    public boolean isBusy(){
        return this.busy;
    }

    public int getAPIVersion() {
        return APIVersion;
    }

    private int getAPIVersionFromADB(){
        List<String> result = ProcessRunner.runProcess("adb", "-s", deviceID, "shell", "getprop", "ro.build.version.sdk");
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
     *          otherwise {@code false}.
     */
    public boolean grantPermissions(String packageName) {

        List<String> responseRead = ProcessRunner.runProcess("adb", "-s", deviceID, "shell", "pm", "grant", packageName, "android.permission.READ_EXTERNAL_STORAGE");
        List<String> responseWrite = ProcessRunner.runProcess("adb", "-s", deviceID, "shell", "pm", "grant", packageName, "android.permission.WRITE_EXTERNAL_STORAGE");

        // empty repsonse should signal no failure
        return responseRead.isEmpty() && responseWrite.isEmpty();
    }

    /**
     * Pulls the traces.txt file from the external storage (sd card) if present.
     * The file is stored in the working directory, that is the mate-commander directory by default.
     *
     * @return Returns {@code true} if the trace file could be pulled,
     *              otherwise {@code false}.
     */
    public boolean pullTraceFile() {

        // traces are stored on the sd card (external storage)
        String tracesDir = "storage/emulated/0"; // + packageName;
        // String checkFileCmd = "adb -s " + deviceID + " shell " + "\"run-as " + packageName + " ls\"";

        List<String> files = ProcessRunner.runProcess("adb", "-s", deviceID, "shell", "ls", tracesDir);

        // check whether there is some traces file
        if (!files.stream().anyMatch(str -> str.trim().equals("traces.txt"))) {
            return false;
        }

        // use the working directory (MATE-COMMANDER HOME) as output directory for trace file
        String workingDir = System.getProperty("user.dir");

        ProcessRunner.runProcess("adb", "-s", deviceID, "pull", tracesDir+"/traces.txt", workingDir + File.separator + "traces.txt");

        return true;
    }

    public String getCurrentActivity(){

        String response="unknown";
        String cmd = "adb -s " + deviceID +" shell dumpsys activity activities | grep mFocusedActivity | cut -d \" \" -f 6";
        if (getAPIVersion()==23 || getAPIVersion()==25){
            if (ProcessRunner.isWin) {
                cmd = "$focused = adb -s " + deviceID + " shell dumpsys activity activities "
                        + "| select-string mFocusedActivity ; \"$focused\".Line.split(\" \")[5]";
                System.out.println(cmd);
            } else {
                cmd = "adb -s " + deviceID + " shell dumpsys activity activities | grep mFocusedActivity | cut -d \" \" -f 6";
            }
        }

        if (getAPIVersion()==26 || getAPIVersion()==27){
            if (ProcessRunner.isWin) {
                cmd = "$focused = adb -s " + deviceID + " shell dumpsys activity activities "
                        + "| select-string mFocusedActivity ; \"$focused\".Line.split(\" \")[7]";
                System.out.println(cmd);
            } else {
                cmd = "adb -s " + deviceID + " shell dumpsys activity activities | grep mResumedActivity | cut -d \" \" -f 8";
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
                cmd = "$activity = adb -s " + deviceID + " shell dumpsys activity activities "
                       + "| select-string \"realActivity\" ; $focused = $activity[1] ; $final = $focused -split '=' ; echo $final[1]";
                        // Alternatively use: "$focused.Line.split(=)[1] \"";
                System.out.println(cmd);
            } else {
                cmd = "adb -s " + deviceID + " shell dumpsys activity activities | grep mResumedActivity | cut -d \" \" -f 8";
            }
        }

        List<String> result;
        if (ProcessRunner.isWin) {
            result = ProcessRunner.runProcess("powershell", "-command", cmd);
        } else {
            result = ProcessRunner.runProcess("bash", "-c", cmd);
        }
        if (result != null && result.size() > 0)
            response = result.get(0);
        System.out.println("activity: " + response);

        return response;
    }

    public List<String> getActivities() {
        String cmd = "";
        List<String> response;
        if (ProcessRunner.isWin) {
            System.out.println("Running windows get source lines command!");
            cmd = "aapt dump xmltree " + packageName + ".apk AndroidManifest.xml | python getActivityNames.py" + "";
            response = ProcessRunner.runProcess("powershell", "-command", cmd);
        } else {
            cmd = "aapt dump xmltree " + packageName + ".apk AndroidManifest.xml | ./getActivityNames.py";
            response = ProcessRunner.runProcess("bash", "-c", cmd);
        }
        System.out.println("activities:");
        for (String activity : response) {
            System.out.println("\t" + activity);
        }
        return response;
    }

    public List<String> getSourceLines() {
        String cmd = "";
        if (ProcessRunner.isWin) {
            System.out.println("Running windows get source lines command!");
            cmd = "python3 getSourceLines.py " + packageName;
            return ProcessRunner.runProcess("powershell", "-command", cmd);
        } else {
            cmd = "./getSourceLines.py " + packageName;
            return ProcessRunner.runProcess("bash", "-c", cmd);
        }
    }

    public String clearApp() {
        String cmd = "";
        if (ProcessRunner.isWin) {
            System.out.println("Running windows clear app command!");
            cmd = "python3 clearApp.py " + deviceID + " " + packageName;
        } else {
            cmd = "./clearApp.py " + deviceID + " " + packageName;
        }
        List<String> response;
        if (ProcessRunner.isWin) {
            response = ProcessRunner.runProcess("powershell", "-command", cmd);
        } else {
            response = ProcessRunner.runProcess("bash", "-c", cmd);
        }
        return String.join("\n", response);
    }

    public String storeCurrentTraceFile() {
        System.out.println("Storing current Trace file!");
        String cmd = "";
        if (ProcessRunner.isWin) {
            cmd = "python3 storeCurrentTraceFile.py" + " " + deviceID + " " + packageName;
        } else {
            cmd = "./storeCurrentTraceFile.py " + deviceID + " " + packageName;
        }
        List<String> response;
        if (ProcessRunner.isWin) {
            response = ProcessRunner.runProcess("powershell", "-command", cmd);
        } else {
            response = ProcessRunner.runProcess("bash", "-c", cmd);
        }
        return String.join("\n", response);
    }

    public String storeCoverageData(String chromosome, String entity) {
        System.out.println("Storing coverage data");
        String cmd = "";
        if (ProcessRunner.isWin) {
            System.out.println("Running windows storing coverage command!");
            cmd = "python3 storeCoverageData.py " + deviceID + " " + packageName + " " + chromosome;
        } else {
            cmd = "./storeCoverageData.py " + deviceID + " " + packageName + " " + chromosome;
        }
        if (entity != null) {
            cmd += " " + entity;
        }
        List<String> response;
        if (ProcessRunner.isWin) {
            response = ProcessRunner.runProcess("powershell", "-command", cmd);
        } else {
            response = ProcessRunner.runProcess("bash", "-c", cmd);
        }
        return String.join("\n", response);
    }

    public String copyCoverageData(String chromosome_source, String chromosome_target, String entities) {
        System.out.println("Copying coverage data");
        String cmd = "";
        if (ProcessRunner.isWin) {
            System.out.println("Running windows copy coverage command!");
            cmd = "python3 copyCoverageData.py " + packageName + " " + chromosome_source
                    + " " + chromosome_target + " " + entities;
        } else {
            cmd = "./copyCoverageData.py " + packageName + " " + chromosome_source + " " + chromosome_target + " " + entities;
        }
        List<String> response;
        if (ProcessRunner.isWin) {
            response = ProcessRunner.runProcess("powershell", "-command", cmd);
        } else {
            response = ProcessRunner.runProcess("bash", "-c", cmd);
        }
        return String.join("\n", response);
    }


    public String getCoverage(String chromosome) {
        String response="unknown";
        String cmd = "";
        if (ProcessRunner.isWin) {
            System.out.println("Running windows get coverage command!");
            cmd = "python3 getCoverage.py " + packageName + " " + chromosome;
        } else {
            cmd = "./getCoverage.py " + packageName + " " + chromosome;
        }
        List<String> result;
        if (ProcessRunner.isWin) {
            result = ProcessRunner.runProcess("powershell", "-command", cmd);
        } else {
            result = ProcessRunner.runProcess("bash", "-c", cmd);
        }
        if (result != null && result.size() > 0)
            response = result.get(result.size() - 1);
        System.out.println("coverage: " + response);

        return response;
    }

    public List<String> getLineCoveredPercentage(String chromosome, String line) {
        String cmd = "";
        if (ProcessRunner.isWin) {
            System.out.println("Running windows get line coverage command!");
            cmd = "python3 getLineCoveredPercentage.py " + packageName + " " + chromosome;
        } else {
            cmd = "./getLineCoveredPercentage.py " + packageName + " " + chromosome;
        }
        try {
            // TODO: refactor and use ProcessRunner.runProces() (no Windows support yet)
            ProcessBuilder pb = new ProcessBuilder(Arrays.asList("bash", "-c", cmd));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedWriter br = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
            br.write(line);
            br.flush();
            br.close();
            BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String _temp;
            List<String> result = new ArrayList<>();
            while ((_temp = in.readLine()) != null) {
                result.add(_temp);
            }

            System.out.println("result after command: " + result);
            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public String getCombinedCoverage(String chromosomes) {
        String response="unknown";
        String cmd = "";
        if (ProcessRunner.isWin) {
            System.out.println("Running windows get combined coverage command!");
            cmd = "python3 getCombinedCoverage.py " + packageName + " " + chromosomes;
        } else {
            cmd = "./getCombinedCoverage.py " + packageName + " " + chromosomes;
        }
        List<String> result;
        if (ProcessRunner.isWin) {
            result = ProcessRunner.runProcess("powershell", "-command", cmd);
        } else {
            result = ProcessRunner.runProcess("bash", "-c", cmd);
        }
        if (result != null && result.size() > 0)
            response = result.get(result.size() - 1);
        System.out.println("combined coverage: " + response);

        return response;
    }

    public static void loadActiveDevices(){
        if (devices==null)
            devices = new Hashtable<String,Device>();
        List<String> resultDevices = ProcessRunner.runProcess("adb", "devices");
        for (String deviceStr:resultDevices){
            String devID="";
            if (deviceStr.contains("device") && !deviceStr.contains("attached")) {
                devID = deviceStr.replace("device", "");
                devID = devID.replace(" ", "");
                devID = devID.trim();
                if (devID.length() > 13)
                    devID = devID.substring(0, devID.length() - 1);
                if (devices.get(devID) == null) {
                    Device device = new Device(devID);
                    devices.put(devID, device);
                }
            }
        }
    }

    public static void listActiveDevices() {
        for (String devID: devices.keySet()) {
            Device device = devices.get(devID);
            System.out.println(device.getDeviceID()+ " - " + device.isBusy()+ ": " + device.getPackageName());
        }
    }

    public static String allocateDevice(String cmdStr){
        String parts[] = cmdStr.split(":");
        String packageName = parts[1];

        if (Server.emuName != null) {
            Device device = devices.get(Server.emuName);
            device.setPackageName(packageName);
            device.setBusy(true);
            return Server.emuName;
        }

        String deviceID = getDeviceRunningPackage(packageName);
        Device device = devices.get(deviceID);
        if (device!=null) {
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

        Report.createHeader(deviceID,packageName);
        ImageHandler.createPicturesFolder(deviceID,packageName);

        return deviceID;
    }

    public static String getDeviceRunningPackage(String packageName){
        for (String key: devices.keySet()){
            List<String> result = ProcessRunner.runProcess("adb", "-s", key, "shell", "ps", packageName);
            for (String res: result){
                System.out.println(res);
                if (res.contains(packageName))
                    return key;
            }
        }
        return "";
    }

    public static String releaseDevice(String cmdStr){
        String response = "";
        String[] parts = cmdStr.split(":");
        if (parts!=null){
            if (parts.length>0) {
                String deviceID = parts[1];
                Device device = devices.get(deviceID);
                if (device!=null) {
                    device.setPackageName("");
                    device.setBusy(false);
                    response = "released";
                }
            }
        }
        return response;
    }

    public static Device getDevice(String deviceId){
        return devices.get(deviceId);
    }


}
