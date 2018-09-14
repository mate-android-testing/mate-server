package org.mate;

import java.util.List;

/**
 * Created by marceloeler on 14/09/18.
 */
public class ADB {

    public static boolean isWin;

    public static int getAPIVersion(){
        String cmd = "adb shell getprop ro.build.version.sdk";
        List<String> result = ProcessRunner.runProcess(isWin, cmd);
        if (result != null && result.size() > 0)
            return Integer.valueOf(result.get(0));
        return 23;
    }

    public static String getCurrentActivity(String emulator){
        String response="unknown";
        String cmd = "adb -s " + emulator+" shell dumpsys activity activities | grep mFocusedActivity | cut -d \" \" -f 6";
        if (getAPIVersion()==23 || getAPIVersion()==25){
            cmd = "adb -s " + emulator+" shell dumpsys activity activities | grep mFocusedActivity | cut -d \" \" -f 6";
        }

        if (getAPIVersion()==27 || getAPIVersion()==28){
            cmd = "adb -s " + emulator+" shell dumpsys activity activities | grep mResumedActivity | cut -d \" \" -f 8";
        }

        List<String> result = ProcessRunner.runProcess(isWin, cmd);
        if (result != null && result.size() > 0)
            response = result.get(0);
        System.out.println("activity: " + response);

        return response;
    }
}
