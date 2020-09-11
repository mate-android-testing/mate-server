package org.mate.accessibility;

import org.mate.io.ProcessRunner;
import org.mate.util.AndroidEnvironment;

public class TestFlicker {

    public static void main(String[] args){
        String screenShotDir = "/home/marceloeler/"; //TODO: remove hardcoded file path
        String targetFolder = screenShotDir;
        AndroidEnvironment androidEnvironment = new AndroidEnvironment();

        System.out.println("target folder: " + targetFolder);

        String emulator = "emulator-5554";
        String imgPath = "screenshot-app.png";
        String originalImgPath = imgPath;
        int index = imgPath.lastIndexOf("_");
        //String packageName = parts[1].substring(0,index-1);

        String cmdStr = "";
        for (int i=0; i<50; i++) {
            imgPath = originalImgPath.replace(".png","_flicker_"+i+".png");
            ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", emulator, "shell", "screencap", "-p", "/sdcard/" + imgPath);
        }
        for (int i=0; i<50; i++) {
            imgPath = originalImgPath.replace(".png","_flicker_"+i+".png");
            ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", emulator, "pull", "/sdcard/" + imgPath, targetFolder);
        }

         boolean flickering = AccessibilityUtils.checkFlickering(targetFolder,originalImgPath);
    }
}
