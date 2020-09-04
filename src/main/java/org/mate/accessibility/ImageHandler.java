package org.mate.accessibility;

import org.mate.io.ProcessRunner;
import org.mate.io.Device;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;

public class ImageHandler {

    public static String screenShotDir;

    public static int contImg = 0;

    public static String takeScreenshot(String cmdStr) {

        String targetFolder = screenShotDir + cmdStr.split("_")[1];
        System.out.println("target folder: " + targetFolder);

        String[] parts = cmdStr.split(":");
        String emulator = parts[1];
        String imgPath = parts[2];
        int index = imgPath.lastIndexOf("_");

        ProcessRunner.runProcess("adb", "-s", emulator, "shell", "screencap", "-p", "/sdcard/" + imgPath);
        ProcessRunner.runProcess("adb", "-s", emulator, "pull", "/sdcard/"+parts[2], targetFolder);

        Device device = Device.getDevice(emulator);
        device.setCurrentScreenShotLocation(targetFolder+"/"+imgPath);

        return imgPath;
    }

    public static String takeFlickerScreenshot(String cmdStr) {

        String targetFolder = screenShotDir + cmdStr.split("_")[1];
        System.out.println("target folder: " + targetFolder);

        String[] parts = cmdStr.split(":");
        String emulator = parts[1];
        String imgPath = parts[2];
        String originalImgPath = imgPath;
        int index = imgPath.lastIndexOf("_");
        //String packageName = parts[1].substring(0,index-1);

        for (int i=0; i<20; i++) {
            imgPath = originalImgPath.replace(".png","_flicker_"+i+".png");
            ProcessRunner.runProcess("adb", "-s", emulator, "shell", "screencap", "-p", "/sdcard/" + imgPath);
        }
        for (int i=0; i<20; i++) {
            imgPath = originalImgPath.replace(".png","_flicker_"+i+".png");
            ProcessRunner.runProcess("adb", "-s", emulator, "pull", "/sdcard/" + imgPath, targetFolder);
        }

        boolean flickering = AccessibilityUtils.checkFlickering(targetFolder,originalImgPath);

        return imgPath;
    }

    public static String markImage(String originalImgPath,int x1, int y1, int x2, int y2,String flawType) {

        System.out.println("MARK IMAGE");
        contImg++;
        String newImagePath = originalImgPath.replace(".png","_"+contImg+".png");

        try {

            BufferedImage img = ImageIO.read(new File(originalImgPath));
            Graphics2D g2d = img.createGraphics();
            g2d.setColor(Color.RED);
            g2d.setStroke(new BasicStroke(5));
            g2d.drawRect(x1, y1, x2-x1, y2-y1);
            Font currentFont = g2d.getFont();
            Font newFont = currentFont.deriveFont(Font.PLAIN,40);
            g2d.setFont(newFont);
            g2d.drawString(flawType,img.getWidth()/6,img.getHeight()-100);
            ImageIO.write(img, "PNG", new File(newImagePath));
            g2d.dispose();
        }catch (Exception e){
            System.out.println("EXCEPTION --->" + e.getMessage());
        }

        return newImagePath;
    }

    public static String markImage(String cmdStr) {

        try {
            System.out.println(cmdStr);
            String[] parts = cmdStr.split(":");
            String imageName = parts[1];
            int x = Integer.parseInt(parts[2].split("-")[1]);
            int y = Integer.parseInt(parts[3].split("-")[1]);
            int width = Integer.parseInt(parts[4].split("-")[1]);
            int height = Integer.parseInt(parts[5].split("-")[1]);

            String fileName = imageName.split("_")[0] + imageName.split("_")[1] + ".txt";

            Writer output;
            output = new BufferedWriter(new FileWriter(fileName, true));

            output.append(imageName)
                    .append(",")
                    .append(parts[6])
                    .append(",")
                    .append(parts[7])
                    .append("\n");

            output.close();

            BufferedImage img = ImageIO.read(new File(imageName));
            Graphics2D g2d = img.createGraphics();
            g2d.setColor(Color.RED);
            g2d.setStroke(new BasicStroke(5));
            g2d.drawRect(x, y, width, height);
            ImageIO.write(img, "PNG", new File(imageName));
            g2d.dispose();
        }catch (Exception e){
            System.out.println("EXCEPTION --->" + e.getMessage());
        }
        System.out.println(cmdStr);

        return "";
    }

    public static String calculateLuminance(String cmdStr){
        String response = "0";
        try {

            System.out.println(cmdStr);
            String[] parts = cmdStr.split(":");
            String packageName = parts[1];

            String targetFolder = screenShotDir+packageName.split("_")[1];

            String stateId = parts[2];
            String coord = parts[3];

            String[] positions = coord.split(",");
            int x1 = Integer.valueOf(positions[0]);
            int y1 = Integer.valueOf(positions[1]);
            int x2 = Integer.valueOf(positions[2]);
            int y2 = Integer.valueOf(positions[3]);

            String fileName = targetFolder+ "/"+packageName + "_" + stateId + ".png";
            System.out.println(fileName);
            System.out.println(coord);
            String luminances = AccessibilityUtils.getLuminance(fileName, x1, y1, x2, y2);
            System.out.println("luminance: " + luminances);
            response = luminances;
        } catch (Exception e) {
            System.out.println("PROBLEMS CALCULATING LUMINANCE");
            response = "0,0";
        }
        return response;
    }

    public static String matchesSurroundingColor(String cmdStr){

        System.out.println(cmdStr);
        String response = "false";

        try {

            String[] parts = cmdStr.split(":");
            String packageName = parts[1];

            String targetFolder = screenShotDir + packageName.split("_")[1];

            String stateId = parts[2];
            String coord = parts[3];

            String[] positions = coord.split(",");
            int x1 = Integer.valueOf(positions[0]);
            int y1 = Integer.valueOf(positions[1]);
            int x2 = Integer.valueOf(positions[2]);
            int y2 = Integer.valueOf(positions[3]);

            String fileName = targetFolder + "/" + packageName + "_" + stateId + ".png";
            //System.out.println(fileName);
            //System.out.println(coord);
            double matchesBackground = AccessibilityUtils.matchesSurroundingColor(fileName, x1, y1, x2, y2);

            return String.valueOf(matchesBackground);
        }
        catch(Exception e){
            System.out.println("Problems matching color background");
            response = "false";
        }

        return response;
    }

    public static String calculateConstratRatio(String cmdStr) {

        String response = "21";
        try {


            System.out.println(cmdStr);
            String[] parts = cmdStr.split(":");
            String packageName = parts[1];

            String targetFolder = screenShotDir+packageName.split("_")[1];

            String stateId = parts[2];
            String coord = parts[3];



            String[] positions = coord.split(",");
            int x1 = Integer.valueOf(positions[0]);
            int y1 = Integer.valueOf(positions[1]);
            int x2 = Integer.valueOf(positions[2]);
            int y2 = Integer.valueOf(positions[3]);

            String fileName = targetFolder+ "/"+packageName + "_" + stateId + ".png";

            double contrastRatio = AccessibilityUtils.getContrastRatio(fileName, x1, y1, x2, y2);
            System.out.println("contrast ratio: " + contrastRatio);
            response = String.valueOf(contrastRatio);
        } catch (Exception e) {
            System.out.println("PROBLEMS CALCULATING CONTRAST RATIO");
            response = "21";
        }
        return response;
    }

    public static void createPicturesFolder(String deviceID, String packageName) {
        try {
            new File(screenShotDir+packageName).mkdir();
        } catch(Exception e){
        }

    }
}
