package org.mate.accessibility;

import org.mate.accessibility.AccessibilityUtils;
import org.mate.io.ADB;

public class TestFlicker {

    public static void main(String[] args){
        String screenShotDir = "/home/marceloeler/"; //TODO: remove hardcoded file path
        String targetFolder = screenShotDir;
        System.out.println("target folder: " + targetFolder);

        String emulator = "emulator-5554";
        String imgPath = "screenshot-app.png";
        String originalImgPath = imgPath;
        int index = imgPath.lastIndexOf("_");
        //String packageName = parts[1].substring(0,index-1);

        String cmdStr = "";
        for (int i=0; i<50; i++) {
            imgPath = originalImgPath.replace(".png","_flicker_"+i+".png");
            cmdStr = "adb -s " + emulator + " shell screencap -p /sdcard/" + imgPath;
            //System.out.println(cmdStr);
            ADB.runCommand(cmdStr);
        }
        for (int i=0; i<50; i++) {
            imgPath = originalImgPath.replace(".png","_flicker_"+i+".png");
            cmdStr = "adb -s " + emulator + " pull /sdcard/" + imgPath + " " + targetFolder;
            //System.out.println(cmdStr);
            ADB.runCommand(cmdStr);
        }

         boolean flickering = AccessibilityUtils.checkFlickering(targetFolder,originalImgPath);
    }
}
