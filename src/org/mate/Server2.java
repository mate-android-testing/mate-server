package org.mate;

import com.itextpdf.text.*;
import com.itextpdf.text.Font;
import com.itextpdf.text.Image;
import com.itextpdf.text.pdf.PdfWriter;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.List;

/**
 * Created by marceloeler on 14/03/17.
 */
public class Server2 {

    public static boolean generatePDFReport;
    public static boolean showImagesOnTheFly;
    public static long timeout;
    public static long length;

    public static void main(String[] args) throws DocumentException {

        showImagesOnTheFly = true;

        //Check OS (windows or linux)
        boolean isWin = false;
        generatePDFReport = false;
        String os = System.getProperty("os.name");
        if (os!=null && os.startsWith("Windows"))
            isWin = true;
        ADB.isWin = isWin;

        //read arguments and set default values otherwise
        timeout = 1;
        length = 1000;
        if (args!=null && args.length==2) {
            String timeoutstr = args[0];
            timeout = Long.valueOf(timeoutstr);
            String lenghStr = args[1];
            length = Long.valueOf(lenghStr);
        }
        ImageHandler.screenShotDir = "";

        Device.loadActiveDevices();

        //ProcessRunner.runProcess(isWin, "rm *.png");
        try {
            ServerSocket server = new ServerSocket(12345, 5000);
            Socket client = null;
            while (true) {

                Device.listActiveDevices();
                System.out.println("ACCEPT: " + new Date().toGMTString());
                client = server.accept();

                Scanner cmd = new Scanner(client.getInputStream());
                String cmdStr = cmd.nextLine();
                String response = handleRequest(cmdStr);

                PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                out.println(response);
                out.close();

                client.close();
                cmd.close();
            }

        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    private static String handleRequest(String cmdStr) {
        System.out.println(cmdStr);

        if (cmdStr.contains("getActivity"))
            return getActivity(cmdStr);

        if (cmdStr.contains("getEmulator"))
            return Device.allocateDevice(cmdStr);

        if (cmdStr.contains("releaseEmulator"))
            return Device.releaseDevice(cmdStr);

        //format commands
        if (cmdStr.contains("screenshot"))
            return ImageHandler.takeScreenshot(cmdStr);

        if(cmdStr.contains("mark-image") && generatePDFReport)
            return ImageHandler.markImage(cmdStr);

        if (cmdStr.contains("contrastratio"))
            return ImageHandler.calculateConstratRatio(cmdStr);

        if (cmdStr.contains("rm emulator"))
            return "";

        List<String> result = ADB.runCommand(cmdStr);
        String response = "";

        if (cmdStr.contains("density")) {
            response = "0";
            if (result != null && result.size() > 0)
                response = result.get(0).replace("Physical density: ", "");
            System.out.println("NH: Density: " + response);
        }

        if (cmdStr.contains("clear")) {
            response = "clear";
            System.out.println("NH:  clear: app data deleted");
        }

        if (cmdStr.contains("rm -rf")) {
            response = "delete";
            System.out.println("NH:  pngs deleted");
        }

        if (cmdStr.contains("screencap")) {
            response = "NH: screenshot";
        }

        if (cmdStr.contains("timeout"))
            response = String.valueOf(timeout);

        if (cmdStr.contains("randomlength"))
            response = String.valueOf(length);

        if(cmdStr.contains("FINISH") && generatePDFReport) {
            try {
                Report.generateReport(cmdStr);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return response;
    }

    public static String getActivity(String cmdStr){
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return device.getCurrentActivity();
    }


}
