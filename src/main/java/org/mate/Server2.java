package org.mate;

import com.itextpdf.text.*;
import de.uni_passau.fim.auermich.Main;
import de.uni_passau.fim.auermich.graphs.Vertex;
import de.uni_passau.fim.auermich.graphs.cfg.BaseCFG;
import de.uni_passau.fim.auermich.statement.BasicStatement;
import de.uni_passau.fim.auermich.statement.BlockStatement;
import org.mate.graphs.CFG;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    public static CFG graph = null;

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

        if (cmdStr.startsWith("getBranchCoverage"))
            return getBranchCoverage(cmdStr);

        if (cmdStr.startsWith("getBranches"))
            return getBranches(cmdStr);

        if (cmdStr.startsWith("getBranchDistanceVector"))
            return getBranchDistanceVector(cmdStr);

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

    /**
     * Returns the list of branches contained in the CFG,
     * where each branch is composed of class->method->branchID.
     *
     * @param cmdStr The command string.
     * @return Returns the string representation of the branches.
     */
    public static String getBranches(String cmdStr) {

        List<String> branchIDs = new LinkedList<>();

        if (graph != null) {

            List<Vertex> branches = graph.getBranches();

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

    /**
     * Returns the branch coverage either for a given test case,
     * or if no test case is specified in the command string, the
     * global branch coverage is returned.
     *
     * @param cmdStr The command string specifying the branch coverage
     *               either for a single test case, or the global one.
     * @return Returns the branch coverage.
     */
    public static String getBranchCoverage(String cmdStr) {

        double branchCoverage;

        if (cmdStr.contains(":")) {
            String testCase = cmdStr.split(":", 2)[1];
            branchCoverage = graph.getBranchCoverage(testCase);
        } else {
            branchCoverage = graph.getBranchCoverage();
        }

        return String.valueOf(branchCoverage);
    }

    /**
     * Computes the branch distance vector for a given test case
     * and a given list of branches.
     *
     * @param cmdStr The command string containing a test case.
     * @return Returns the branch distance vector.
     */
    public static String getBranchDistanceVector(String cmdStr) {

        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        String testCase = parts[2];

        String tracesDir = System.getProperty("user.dir");
        File traces = new File(tracesDir, "traces.txt");

        Device device = Device.devices.get(deviceID);

        if (!device.pullTraceFile() || !traces.exists()) {
            // traces.txt was not accessible/found on emulator
            System.out.println("Couldn't find traces.txt!");
            return null;
            /*
            List<String> maxDistanceVector
                    = Collections.nCopies(interCFG.getBranches().size(), "0");
            return String.join("\n", maxDistanceVector);
            */
        }

        List<String> executionPath = new ArrayList<>();

        try (Stream<String> stream = Files.lines(traces.toPath(), StandardCharsets.UTF_8)) {
            executionPath = stream.collect(Collectors.toList());
        }
        catch (IOException e) {
            System.out.println("Reading traces.txt failed!");
            e.printStackTrace();
            return null;
        }

        // remove first line, since it contains some time stamp information
        executionPath.remove(0);

        System.out.println("Visited Vertices: " + executionPath);

        // we need to mark vertices we visit
        Set<Vertex> visitedVertices = new HashSet<>();

        // we need to track covered branch vertices for branch coverage
        Set<Vertex> coveredBranches = new HashSet<>();

        Set<Vertex> vertices = graph.getVertices();

        // look up each pathNode (vertex) in the CFG
        for (String pathNode : executionPath) {

            // get full-qualified method name + type (entry,exit,instructionID)
            int index = pathNode.lastIndexOf("->");
            String method = pathNode.substring(0, index);
            String type = pathNode.substring(index+2);

            System.out.println("PathNode: " + pathNode);

            if (type.equals("entry")) {
                Vertex entry = vertices.stream().filter(v -> v.isEntryVertex()
                        && v.getMethod().equals(method)).findFirst().get();
                visitedVertices.add(entry);
            } else if (type.equals("exit")) {
                Vertex exit = vertices.stream().filter(v -> v.isExitVertex()
                        && v.getMethod().equals(method)).findFirst().get();
                visitedVertices.add(exit);
            } else {
                // must be the instruction id of a branch
                int id = Integer.parseInt(type);
                Vertex branch = vertices.stream().filter(v ->
                        v.containsInstruction(method,id)).findFirst().get();
                visitedVertices.add(branch);
                coveredBranches.add(branch);
            }
        }

        // evaluate against all branches
        List<Vertex> branches = graph.getBranches();

        // track covered branches for coverage measurement
        graph.addCoveredBranches(coveredBranches);
        graph.addCoveredBranches(testCase, coveredBranches);

        // stores the fitness value for a given test case (execution path) evaluated against each branch
        List<String> branchDistanceVector = new LinkedList<>();

        for (Vertex branch : branches) {

            // the minimal distance between a execution path and a given branch
            int min = Integer.MAX_VALUE;

            // the execution path (visited vertices) represent a single test case
            for (Vertex visitedVertex : visitedVertices) {
                // find the shortest distance between a branch and the execution path
                int distance = graph.getShortestDistance(visitedVertex, branch);
                System.out.println("Distance: " + distance);
                if (distance < min && distance != -1) {
                    // found shorter path
                    min = distance;
                }
            }

            // we need to normalise approach level / branch distance to the range [0,1] where 1 is best
            if (min == Integer.MAX_VALUE) {
                // branch not reachable by execution path
                branchDistanceVector.add(String.valueOf(0));
            } else {
                branchDistanceVector.add(String.valueOf(1 - ((double) min / (min + 1))));
            }
        }

        System.out.println("Branch Distance Vector: " + branchDistanceVector);
        return String.join("\n", branchDistanceVector);
    }

    /**
     * Initialises the CFG based on given APK file.
     *
     * @param cmdStr The command string containing the APK path.
     * @return Returns the string {@code true} if the CFG
     *      has been initialised successfully, otherwise {@code false}.
     */
    public static String initCFG(String cmdStr) {

        // limit is required to avoid splitting a Windows path
        String[] parts = cmdStr.split(":", 2);
        String apkPath = parts[1];

        System.out.print("APK path: " + apkPath);

        try {
            graph = new CFG(Main.computeInterCFGWithBasicBlocks(apkPath));
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
