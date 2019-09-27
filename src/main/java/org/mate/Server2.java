package org.mate;

import com.itextpdf.text.*;
import de.uni_passau.fim.auermich.Main;
import de.uni_passau.fim.auermich.graphs.Vertex;
import de.uni_passau.fim.auermich.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.statement.BasicStatement;
import de.uni_passau.fim.auermich.statement.BlockStatement;

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

    // graph instance needed for branch distance computation
    public static BaseCFG interCFG = null;

    public static void main(String[] args) throws DocumentException {

        createFolders();

        showImagesOnTheFly = true;

        //Check OS (windows or linux)
        boolean isWin = false;
        generatePDFReport = false;
        String os = System.getProperty("os.name");
        if (os != null && os.startsWith("Windows"))
            isWin = true;
        ADB.isWin = isWin;

        //read arguments and set default values otherwise
        timeout = 5;
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

    private static void createFolders() {
        String workingDir = System.getProperty("user.dir");
        // System.out.println(workingDir);
        try {
            new File(workingDir+"/csvs").mkdir();
        } catch(Exception e){

        }

        try {
            new File(workingDir+"/pictures").mkdir();
        } catch(Exception e){
        }

        ImageHandler.screenShotDir = workingDir+"/pictures/";
        Report.reportDir = workingDir+"/csvs/";
    }

    private static String handleRequest(String cmdStr) {
        System.out.println();
        System.out.println(cmdStr);

        if (cmdStr.startsWith("reportFlaw")){
            return Report.addFlaw(cmdStr);
        }

        if (cmdStr.startsWith("clearApp"))
            return clearApp(cmdStr);

        if (cmdStr.startsWith("getActivity"))
            return getActivity(cmdStr);

        if (cmdStr.startsWith("getSourceLines"))
            return getSourceLines(cmdStr);

        if (cmdStr.startsWith("storeCurrentTraceFile"))
            return storeCurrentTraceFile(cmdStr);

        if (cmdStr.startsWith("storeCoverageData"))
            return storeCoverageData(cmdStr);

        if (cmdStr.startsWith("copyCoverageData"))
            return copyCoverageData(cmdStr);

        if (cmdStr.startsWith("getActivities"))
            return getActivities(cmdStr);

        if (cmdStr.startsWith("getEmulator"))
            return Device.allocateDevice(cmdStr);

        if (cmdStr.startsWith("releaseEmulator"))
            return Device.releaseDevice(cmdStr);

        if (cmdStr.startsWith("getCoverage"))
            return getCoverage(cmdStr);

        if (cmdStr.startsWith("getLineCoveredPercentage"))
            return getLineCoveredPercentage(cmdStr);

        if (cmdStr.startsWith("getCombinedCoverage"))
            return getCombinedCoverage(cmdStr);

        if (cmdStr.startsWith("initCFG"))
            return initCFG(cmdStr);

        if (cmdStr.startsWith("getBranches"))
            return getBranches(cmdStr);

        //format commands
        if (cmdStr.startsWith("screenshot"))
            return ImageHandler.takeScreenshot(cmdStr);

        if (cmdStr.startsWith("mark-image") && generatePDFReport)
            return ImageHandler.markImage(cmdStr);

        if (cmdStr.startsWith("contrastratio"))
            return ImageHandler.calculateConstratRatio(cmdStr);

        if (cmdStr.startsWith("rm emulator"))
            return "";

        if (cmdStr.startsWith("timeout"))
            return String.valueOf(timeout);

        if (cmdStr.startsWith("randomlength"))
            return String.valueOf(length);

        if (cmdStr.startsWith("FINISH") && generatePDFReport) {
            try {
                Report.generateReport(cmdStr);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "Finished PDF report";
        }

        if (cmdStr.startsWith("reportAccFlaw")){

            return "ok";
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

    public static String getBranches(String cmdStr) {

        List<String> branchIDs = new LinkedList<>();

        if (interCFG != null) {

            List<Vertex> branches = interCFG.getBranches();

            for (Vertex branch : branches) {
                Integer branchID = null;
                if (branch.getStatement() instanceof BasicStatement) {
                    branchID = ((BasicStatement) branch.getStatement()).getInstructionIndex();
                } else if (branch.getStatement() instanceof BlockStatement) {
                    branchID = ((BasicStatement) ((BlockStatement) branch.getStatement()).getFirstStatement()).getInstructionIndex();
                }

                if (branchID != null) {
                    branchIDs.add(branch.getMethod() + "->" + branchID);
                }
            }
        }
        return String.join("\n", branchIDs);
    }

    public static String initCFG(String cmdStr) {

        // limit is required to avoid splitting a Windows path
        String[] parts = cmdStr.split(":", 2);
        String apkPath = parts[1];

        System.out.print("APK path: " + apkPath);

        try {
            interCFG = Main.computeInterCFGWithBasicBlocks(apkPath);
        } catch (IOException e) {
            e.printStackTrace();
            return "false";
        }
        return "true";
    }

    public static String getActivity(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return device.getCurrentActivity();
    }

    public static String storeCurrentTraceFile(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return device.storeCurrentTraceFile();
    }

    public static String storeCoverageData(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        String chromosome = parts[2];
        String entity = null;
        if (parts.length > 3) {
            entity = parts[3];
        }
        Device device = Device.devices.get(deviceID);
        return device.storeCoverageData(chromosome, entity);
    }

    public static String copyCoverageData(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        String chromosome_source = parts[2];
        String chromosome_target = parts[3];
        String entities = parts[4];
        Device device = Device.devices.get(deviceID);
        return device.copyCoverageData(chromosome_source, chromosome_target, entities);
    }


    public static String getActivities(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return String.join("\n", device.getActivities());
    }

    public static String getSourceLines(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return String.join("\n", device.getSourceLines());
    }

    public static String getCoverage(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        String chromosome = parts[2];
        Device device = Device.devices.get(deviceID);
        return device.getCoverage(chromosome);
    }

    public static String getLineCoveredPercentage(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        String chromosome = parts[2];
        String line = parts[3];
        Device device = Device.devices.get(deviceID);
        return String.join("\n", device.getLineCoveredPercentage(chromosome, line));
    }

    public static String getCombinedCoverage(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        String chromosomes = "all";
        if (parts.length > 2) {
            chromosomes = parts[2];
        }
        return device.getCombinedCoverage(chromosomes);
    }

    public static String clearApp(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return device.clearApp();
    }
}
