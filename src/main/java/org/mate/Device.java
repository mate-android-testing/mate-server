package org.mate;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * Created by marceloeler on 14/09/18.
 */
public class Device {

    public static Hashtable<String,Device> devices;

    private String deviceID;
    private String packageName;
    private boolean busy;
    private int APIVersion;

    public Device(String deviceID){
        this.deviceID = deviceID;
        this.packageName = "";
        this.busy = false;
        APIVersion = this.getAPIVersionFromADB();
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
        String cmd = "adb -s " + deviceID + " shell getprop ro.build.version.sdk";
        System.out.println(cmd);
        List<String> result = ADB.runCommand(cmd);
        if (result != null && result.size() > 0) {
            System.out.println("API consulta: " + result.get(0));
            return Integer.valueOf(result.get(0));
        }
        return 23;
    }

    public String getCurrentActivity(){

        String response="unknown";
        String cmd = "adb -s " + deviceID +" shell dumpsys activity activities | grep mFocusedActivity | cut -d \" \" -f 6";
        if (getAPIVersion()==23 || getAPIVersion()==25){
            cmd = "adb -s " + deviceID +" shell dumpsys activity activities | grep mFocusedActivity | cut -d \" \" -f 6";
        }

        if (getAPIVersion()==27 || getAPIVersion()==28){
            cmd = "adb -s " + deviceID +" shell dumpsys activity activities | grep mResumedActivity | cut -d \" \" -f 8";
        }

        List<String> result = ADB.runCommand(cmd);
        if (result != null && result.size() > 0)
            response = result.get(0);
        System.out.println("activity: " + response);

        return response;
    }

    public List<String> getActivities() {
        String cmd = "aapt dump xmltree " + packageName + ".apk AndroidManifest.xml | ./getActivityNames.py";
        List<String> response = ADB.runCommand(cmd);
        System.out.println("activities:");
        for (String activity : response) {
            System.out.println("\t" + activity);
        }
        return response;
    }

    public String stopApp() {
        System.out.println("Stopping app");
        String cmd = "adb shell input keyevent 3; adb shell monkey -p " + packageName + " 1";
        List<String> response = ADB.runCommand(cmd);
        return String.join("\n", response);
    }


    public String getCoverage() {
        String response="unknown";
        String cmd = "./getCoverage.py " + deviceID + " " + packageName;
        List<String> result = ADB.runCommand(cmd);
        if (result != null && result.size() > 0)
            response = result.get(result.size() - 1);
        System.out.println("coverage: " + response);

        return response;
    }

    public static void loadActiveDevices(){
        if (devices==null)
            devices = new Hashtable<String,Device>();
        String cmd = "adb devices";
        List<String> resultDevices = ADB.runCommand(cmd);
        for (String deviceStr:resultDevices){
            String devID="";
            if (deviceStr.contains("device") && !deviceStr.contains("attached")) {
                devID = deviceStr.replace("device", "");
                devID = devID.replace(" ", "");
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

        if (Server2.emuName != null) {
            Device device = devices.get(Server2.emuName);
            device.setPackageName(packageName);
            device.setBusy(true);
            return Server2.emuName;
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
        return deviceID;
    }

    public static String getDeviceRunningPackage(String packageName){
        for (String key: devices.keySet()){
            String cmd = "adb -s " + key + " shell ps " + packageName;
            List<String> result = ADB.runCommand(cmd);
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
}
