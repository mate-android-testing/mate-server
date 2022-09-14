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
        File targetDir = new File(screenshotDir.toFile(), packageName);

        if (!targetDir.exists()) {
            if (!targetDir.mkdirs()) {
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
                    "pull", "/sdcard/" + screenshotName, String.valueOf(targetDir) + "/" + screenshotName);
            if (pullSS.isErr()) {
                Log.printWarning("Pulling screenshot failed: " + pullSS.getErr());
            }
        }
        //device.setCurrentScreenShotLocation(targetFolder+"/"+imgPath);
    }

    /**
     * Moves the screenshots from the device to a certain target.
     *
     * @param deviceID The device on which the screenshots are saved.
     * @param source The directory, where the screenshots are initially saved.
     * @param target The directory, to which the screenshots are moved.
     * @return {@code true}, if the copying and removing of the screenshots were successful.
     *         Else, it returns {@code false}.
     */
    public boolean fetchScreenshots(String deviceID, String source, String target) {
        if (screenshotDir.toFile().exists()) {
            Device device = Device.devices.get(deviceID);
            File sourceDir = new File(screenshotDir.toFile(), source);
            File targetDir = new File(Device.appsDir.toFile(), device.getPackageName());
            targetDir = new File(targetDir, target);

            if (!sourceDir.exists()) {
                Log.println(sourceDir + " not found!");

                return false;
            }

            // Create directory if it doesn't exist
            if (!targetDir.exists()) {
                boolean success = targetDir.mkdirs();
                Log.println("Creating screenshot directory: " + success);
            }

            boolean copyingSuccess = ProcessRunner.runProcess("cp", "-r", String.valueOf(sourceDir), String.valueOf(targetDir)).isOk();

            boolean removeSuccess = ProcessRunner.runProcess("rm", "-r", String.valueOf(sourceDir)).isOk();

            Log.println("Copying of screenshots succeeded: " + copyingSuccess);
            Log.println("Removing of screenshots succeeded: " + removeSuccess);

            return removeSuccess && copyingSuccess;
        } else {
            Log.println(screenshotDir + " not found!");

            return false;
        }
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

    @Deprecated
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

    /**
     * Calculates the luminance of a widget based on a screenshot.
     *
     * @param packageName Identifies the directory containing the screenshot.
     * @param stateId Identifies the name of the screenshot.
     * @param x1 The x1 coordinate of the widget.
     * @param x2 The x2 coordinate of the widget.
     * @param y1 The y1 coordinate of the widget.
     * @param y2 The y2 coordinate of the widget.
     * @return Returns the luminance of the given widget.
     */
    public String calculateLuminance(String packageName, String stateId, int x1, int x2, int y1, int y2) {

        Path targetDir = screenshotDir.resolve(packageName);
        File screenshot = new File(targetDir.toFile(), stateId + ".png");

        if (!screenshot.exists()) {
            throw new IllegalStateException("Screenshot not present for calculating luminance!");
        }

        return AccessibilityUtils.getLuminance(screenshot.getPath(), x1, y1, x2, y2);
    }

    /**
     * Checks the surrounding color of a widget based on a screenshot.
     *
     * @param packageName Identifies the directory containing the screenshot.
     * @param stateId Identifies the name of the screenshot.
     * @param x1 The x1 coordinate of the widget.
     * @param x2 The x2 coordinate of the widget.
     * @param y1 The y1 coordinate of the widget.
     * @param y2 The y2 coordinate of the widget.
     * @return Returns a value indicating too which degree the surrounding color of
     *          the widget matches.
     */
    public double matchSurroundingColor(String packageName, String stateId, int x1, int x2, int y1, int y2) {

        Path targetDir = screenshotDir.resolve(packageName);
        File screenshot = new File(targetDir.toFile(), stateId + ".png");

        if (!screenshot.exists()) {
            throw new IllegalStateException("Screenshot not present for surrounding color check!");
        }

        return AccessibilityUtils.matchesSurroundingColor(screenshot.getPath(), x1, y1, x2, y2);
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
