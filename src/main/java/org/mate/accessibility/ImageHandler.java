package org.mate.accessibility;

import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

public class ImageHandler {

    public static int contImg = 0;
    private final AndroidEnvironment androidEnvironment;
    private Path screenshotDir;

    public ImageHandler(AndroidEnvironment androidEnvironment) {
        this.androidEnvironment = androidEnvironment;
    }

    /**
     * Sets the screenshot directory.
     *
     * @param screenshotDir The new screenshot directory.
     */
    public void setScreenshotDir(Path screenshotDir) {
        this.screenshotDir = screenshotDir;
    }

    /**
     * Takes a screenshot of the given screen state.
     *
     * @param device The emulator instance.
     * @param packageName The package name corresponding to the screen state.
     * @param nodeId Identifies the screen state.
     */
    public void takeScreenshot(Device device, String packageName, String nodeId) {

        Path targetDir = screenshotDir.resolve(packageName);

        if (!targetDir.toFile().exists()) {
            if (!targetDir.toFile().mkdirs()) {
                Log.printError("Unable to create screenshot directory!");
                return;
            }
        }

        String screenshotName = nodeId + ".png";

        // take screenshot and pull it from emulator
        var takeSS = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", device.getDeviceID(),
                "shell", "screencap", "-p", "/sdcard/" + screenshotName);

        if (takeSS.isErr()) {
            Log.printWarning("Taking screenshot failed: " + takeSS.getErr());
        } else {
            // only pull if taking screenshot succeeded
            var pullSS = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", device.getDeviceID(),
                    "pull", "/sdcard/" + screenshotName, String.valueOf(targetDir.resolve(screenshotName)));
            if (pullSS.isErr()) {
                Log.printWarning("Pulling screenshot failed: " + pullSS.getErr());
            }
        }
        //device.setCurrentScreenShotLocation(targetFolder+"/"+imgPath);
    }

    /**
     * Checks whether there is a flickering observable between the given screenshot
     * and multiple samples of it.
     *
     * @param device The emulator instance.
     * @param packageName The package name identifies the location of the screenshot.
     * @param stateId Represents the name of the screenshot.
     * @return Returns {@code true} if flickering could be observed, otherwise
     *              {@code false} is returned.
     */
    public boolean checkForFlickering(Device device, String packageName, String stateId) {

        Path targetDir = screenshotDir.resolve(packageName);
        String screenshotName = stateId + ".png";

        // check whether original screenshot is present (the one we check for flickering)
        if (!targetDir.resolve(screenshotName).toFile().exists()) {
            throw new IllegalStateException("Screenshot to check for flickering is not present!");
        }

        List<String> samples = new ArrayList<>();

        // take 20 screenshot samples and pull them
        for (int i = 0; i < 20; i++) {

            String screenshotSampleName = screenshotName.replace(".png","_flicker_"+i+".png");
            samples.add(screenshotSampleName);

            ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", device.getDeviceID(),
                    "shell", "screencap", "-p", "/sdcard/" + screenshotSampleName);

            ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", device.getDeviceID(),
                    "pull", "/sdcard/" + screenshotSampleName, String.valueOf(targetDir.resolve(screenshotSampleName)));
        }

        return AccessibilityUtils.checkFlickering(targetDir, screenshotName, samples);
    }

    public String markImage(String originalImgPath,int x1, int y1, int x2, int y2,String flawType) {

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

    public String markImage(String cmdStr) {

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

    public String calculateLuminance(String cmdStr){
        String response = "0";
        try {

            System.out.println(cmdStr);
            String[] parts = cmdStr.split(":");
            String packageName = parts[1];

            String targetFolder = screenshotDir+packageName.split("_")[1];

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

    public String matchesSurroundingColor(String cmdStr){

        System.out.println(cmdStr);
        String response = "false";

        try {

            String[] parts = cmdStr.split(":");
            String packageName = parts[1];

            String targetFolder = screenshotDir + packageName.split("_")[1];

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

    /**
     * Calculates the contrast ratio.
     *
     * @param packageName Identifies the directory containing the screenshot.
     * @param stateId Identifies the name of the screenshot.
     * @param x1 The x1 coordinate of the widget.
     * @param x2 The x2 coordinate of the widget.
     * @param y1 The y1 coordinate of the widget.
     * @param y2 The y2 coordinate of the widget.
     * @return Returns the contrast ratio.
     */
    public double calculateContrastRatio(String packageName, String stateId, int x1, int x2, int y1, int y2) {

        Path targetDir = screenshotDir.resolve(packageName);
        File screenshot = new File(targetDir.toFile(), stateId + ".png");

        if (!screenshot.exists()) {
            throw new IllegalStateException("Screenshot not present for contrast ratio computation!");
        }

        double contrastRatio = AccessibilityUtils.getContrastRatio(screenshot.getPath(), x1, y1, x2, y2);
        Log.println("Contrast Ratio: " + contrastRatio);

        return contrastRatio;
    }
}
