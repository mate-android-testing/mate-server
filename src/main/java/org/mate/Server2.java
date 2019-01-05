package org.mate;

import com.itextpdf.text.*;

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
    public static String emuName;
    public static int port;

    public static void main(String[] args) throws DocumentException {

        showImagesOnTheFly = true;

        //Check OS (windows or linux)
        boolean isWin = false;
        generatePDFReport = false;
        String os = System.getProperty("os.name");
        if (os != null && os.startsWith("Windows"))
            isWin = true;
        ADB.isWin = isWin;

        //read arguments and set default values otherwise
        timeout = 1;
        length = 1000;
        port = 12345;
        if (args.length > 0) {
            timeout = Long.valueOf(args[0]);
        }
        if (args.length > 1) {
            length = Long.valueOf(args[1]);
        }
        if (args.length > 2) {
            port = Integer.valueOf(args[2]);
        }
        if (args.length > 3) {
            emuName = args[3];
        }
        ImageHandler.screenShotDir = "";

        //ProcessRunner.runProcess(isWin, "rm *.png");
        try {
            ServerSocket server = new ServerSocket(port, 100000000);
            if (port == 0) {
                System.out.println(server.getLocalPort());
            }
            Socket client;

            Device.loadActiveDevices();

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

        if (cmdStr.contains("removeCoverageData"))
            return removeCoverageData(cmdStr);

        if (cmdStr.contains("storeCoverageData"))
            return storeCoverageData(cmdStr);

        if (cmdStr.contains("getActivities"))
            return getActivities(cmdStr);

        if (cmdStr.contains("getEmulator"))
            return Device.allocateDevice(cmdStr);

        if (cmdStr.contains("releaseEmulator"))
            return Device.releaseDevice(cmdStr);

        if (cmdStr.contains("getCoverage"))
            return getCoverage(cmdStr);

        //format commands
        if (cmdStr.contains("screenshot"))
            return ImageHandler.takeScreenshot(cmdStr);

        if (cmdStr.contains("mark-image") && generatePDFReport)
            return ImageHandler.markImage(cmdStr);

        if (cmdStr.contains("contrastratio"))
            return ImageHandler.calculateConstratRatio(cmdStr);

        if (cmdStr.contains("rm emulator"))
            return "";

        if (cmdStr.contains("timeout"))
            return String.valueOf(timeout);

        if (cmdStr.contains("randomlength"))
            return String.valueOf(length);

        if (cmdStr.contains("FINISH") && generatePDFReport) {
            try {
                Report.generateReport(cmdStr);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "Finished PDF report";
        }

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

        return response;
    }

    public static String getActivity(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return device.getCurrentActivity();
    }

    public static String removeCoverageData(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return device.removeCoverageData();
    }

    public static String storeCoverageData(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return device.storeCoverageData();
    }


    public static String getActivities(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return String.join("\n", device.getActivities());
    }

    public static String getCoverage(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return device.getCoverage();
    }
}
