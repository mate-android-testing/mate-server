package org.mate.endpoints;

import de.uni_passau.fim.auermich.Main;
import de.uni_passau.fim.auermich.graphs.Edge;
import de.uni_passau.fim.auermich.graphs.Vertex;
import de.uni_passau.fim.auermich.statement.BasicStatement;
import de.uni_passau.fim.auermich.statement.BlockStatement;
import de.uni_passau.fim.auermich.statement.ExitStatement;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.mate.accessibility.ImageHandler;
import org.mate.graphs.CFG;
import org.mate.io.ProcessRunner;
import org.mate.io.Device;
import org.mate.message.Message;
import org.mate.network.Endpoint;
import org.mate.pdf.Report;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LegacyEndpoint implements Endpoint {
    // graph instance needed for branch distance computation
    private CFG graph = null;
    private final long timeout;
    private final long length;
    private boolean generatePDFReport = false;

    public LegacyEndpoint(long timeout, long length) {
        this.timeout = timeout;
        this.length = length;
    }

    @Override
    public Message handle(Message request) {
        return new Message.MessageBuilder("/legacy")
                .withParameter("response", handleRequest(request.getParameter("cmd")))
                .build();
    }

    private String handleRequest(String cmdStr) {
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

        if (cmdStr.startsWith("grantPermissions"))
            return grantPermissions(cmdStr);

        if (cmdStr.startsWith("storeBranchCoverage"))
            return storeBranchCoverage(cmdStr);

        if (cmdStr.startsWith("initCFG"))
            return initCFG(cmdStr);

        if (cmdStr.startsWith("getBranchCoverage"))
            return getBranchCoverage(cmdStr);

        if (cmdStr.startsWith("getBranches"))
            return getBranches(cmdStr);

        if (cmdStr.startsWith("getBranchDistanceVector"))
            return getBranchDistanceVector(cmdStr);

        if (cmdStr.startsWith("getBranchDistance"))
            return getBranchDistance(cmdStr);

        //format commands
        if (cmdStr.startsWith("screenshot"))
            return ImageHandler.takeScreenshot(cmdStr);

        if (cmdStr.startsWith("flickerScreenshot"))
            return ImageHandler.takeFlickerScreenshot(cmdStr);

        if (cmdStr.startsWith("mark-image") && generatePDFReport)
            return ImageHandler.markImage(cmdStr);

        if (cmdStr.startsWith("contrastratio"))
            return ImageHandler.calculateConstratRatio(cmdStr);

        if (cmdStr.startsWith("luminance"))
            return ImageHandler.calculateLuminance(cmdStr);

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
        return null;
    }

    /**
     * Grants runtime permission to a given app.
     *
     * @param cmdStr The command string.
     * @return Returns the string "true" if the permissions
     *      could be granted, otherwise the string "false".
     */
    private String grantPermissions(String cmdStr) {

        String parts[] = cmdStr.split(":");
        String packageName = parts[1];
        String deviceID = parts[2];

        Device device = Device.devices.get(deviceID);
        boolean granted = device.grantPermissions(packageName);
        return String.valueOf(granted);
    }

    /**
     * Returns the list of branches contained in the CFG,
     * where each branch is composed of class->method->branchID.
     *
     * @param cmdStr The command string.
     * @return Returns the string representation of the branches.
     */
    private String getBranches(String cmdStr) {

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
     * Stores the branch coverage information into a file.
     *
     * @param cmdStr The command string.
     * @return Returns an empty response.
     */
    private String storeBranchCoverage(String cmdStr) {

        int lastDelimiter = cmdStr.lastIndexOf(':');
        String deviceID = cmdStr.substring(lastDelimiter + 1);
        String branchCoverage = getBranchCoverage(cmdStr.substring(0,lastDelimiter));
        String packageName = graph.getPackageName();

        // name of chromosome or 'total'
        String fileName = "total";

        if (cmdStr.chars().filter(ch -> ch == ':').count() > 1) {
            // the chromosome name
            fileName = cmdStr.split(":")[1];
        }

        // add timestamp to name
        fileName += Instant.now();
        // remove unallowed characters from file name
        fileName = fileName.replaceAll(":", "-");

        String workingDir = System.getProperty("user.dir");
        File dir = new File(workingDir, packageName + ".coverage");

        // create coverage directory
        if (!dir.exists()) {
            dir.mkdir();
        }

        File file = new File(dir, fileName);
        try {
            Files.write(file.toPath(), branchCoverage.getBytes());
        } catch (IOException e) {
            System.err.println("Couldn't write branch coverage information!");
            e.printStackTrace();
        }
        return "";
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
    private String getBranchCoverage(String cmdStr) {

        double branchCoverage;

        if (cmdStr.contains(":")) {
            String testCase = cmdStr.split(":", 2)[1];
            System.out.println("TestCase: " + testCase);
            branchCoverage = graph.getBranchCoverage(testCase);
        } else {
            branchCoverage = graph.getBranchCoverage();
        }

        return String.valueOf(branchCoverage);
    }

    /**
     * Returns the branch distance for a given test case evaluated
     * against a pre-defined target vertex. That is, we evaluate
     * a given execution path (sequence of vertices) against a single
     * pre-defined target vertex.
     *
     * @param cmdStr The command string specifying the test case.
     * @return Returns the branch distance for a given test case.
     */
    private String getBranchDistance(String cmdStr) {

        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        String testCase = parts[2];

        Device device = Device.devices.get(deviceID);

        // check whether writing traces has been completed yet
        while(!completedWritingTraces(deviceID)) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                System.out.println("sleeping failed!");
                e.printStackTrace();
                return null;
            }
        }

        String tracesDir = System.getProperty("user.dir");
        File traces = new File(tracesDir, "traces.txt");

        if (!device.pullTraceFile() || !traces.exists()) {
            // traces.txt was not accessible/found on emulator
            System.out.println("Couldn't find traces.txt!");
            return null;
        }

        List<String> executionPath = new ArrayList<>();

        long start = System.currentTimeMillis();

        try (Stream<String> stream = Files.lines(traces.toPath(), StandardCharsets.UTF_8)) {
            executionPath = stream.collect(Collectors.toList());
        }
        catch (IOException e) {
            System.out.println("Reading traces.txt failed!");
            e.printStackTrace();
            return null;
        }

        long end = System.currentTimeMillis();
        System.out.println("Reading traces from file took: " + (end-start));

        System.out.println("Number of visited vertices: " + executionPath.size());

        // we need to mark vertices we visit
        Set<Vertex> visitedVertices = Collections.newSetFromMap(new ConcurrentHashMap<Vertex, Boolean>());
        // Set<Vertex> visitedVertices = new HashSet<>();

        // we need to track covered branch vertices for branch coverage
        Set<Vertex> coveredBranches = Collections.newSetFromMap(new ConcurrentHashMap<Vertex, Boolean>());
        // Set<Vertex> coveredBranches = new HashSet<>();

        // we need to track entry vertices separately
        Set<Vertex> entryVertices = Collections.newSetFromMap(new ConcurrentHashMap<Vertex, Boolean>());

        Map<String, Vertex> vertexMap = graph.getVertexMap();

        // map trace to vertex
        executionPath.parallelStream().forEach(pathNode -> {

            Vertex visitedVertex = vertexMap.get(pathNode);

            if (visitedVertex == null) {
                System.out.println("Couldn't derive vertex for trace entry: " + pathNode);
            } else {

                visitedVertices.add(visitedVertex);

                if (visitedVertex.isEntryVertex()) {
                    entryVertices.add(visitedVertex);
                }

                if (!visitedVertex.isEntryVertex() && !visitedVertex.isExitVertex()) {
                    // must be a branch
                    coveredBranches.add(visitedVertex);
                }
            }
        });

        System.out.println("Marking intermediate path nodes now...");

        // mark the intermediate path nodes that are between branches we visited
        entryVertices.parallelStream().forEach(entry -> {
            Vertex exit = new Vertex(new ExitStatement(entry.getMethod()));
            if (visitedVertices.contains(entry) && visitedVertices.contains(exit)) {
                graph.markIntermediatePathVertices(entry, exit, visitedVertices);
            }
        });

        // track covered branches for coverage measurement
        graph.addCoveredBranches(coveredBranches);
        graph.addCoveredBranches(testCase, coveredBranches);

        // draw the graph if the size is not too big
        if (graph.getVertices().size() < 700) {
            System.out.println("Drawing graph now...");
            File output = new File(System.getProperty("user.dir"),
                    "graph" + graph.branchDistanceRetrievalCounter + ".png");
            graph.branchDistanceRetrievalCounter++;
            graph.drawGraph(visitedVertices, output);
        }

        // the minimal distance between a execution path and a chosen target vertex
        AtomicInteger min = new AtomicInteger(Integer.MAX_VALUE);

        // cache already computed branch distances
        Map<Vertex, Double> branchDistances = graph.getBranchDistances();

        // use bidirectional dijkstra
        ShortestPathAlgorithm<Vertex, Edge> dijkstra = graph.getDijkstra();
        // ShortestPathAlgorithm<Vertex, Edge> bfs = graph.getBFS();

        // we have a fixed target vertex
        Vertex targetVertex = graph.getTargetVertex();

        start = System.currentTimeMillis();

        visitedVertices.parallelStream().forEach(visitedVertex -> {

            System.out.println("Visited Vertex: " + visitedVertex + " " + visitedVertex.getMethod());

            int distance = -1;

            if (branchDistances.containsKey(visitedVertex)) {
                distance = branchDistances.get(visitedVertex).intValue();
            } else {
                GraphPath<Vertex, Edge> path = dijkstra.getPath(visitedVertex, targetVertex);
                if (path != null) {
                    distance = path.getLength();
                    // update branch distance map
                    branchDistances.put(visitedVertex, Double.valueOf(distance));
                } else {
                    // update branch distance map
                    branchDistances.put(visitedVertex, Double.valueOf(-1));
                }
            }

            // int distance = branchDistances.get(visitedVertex).intValue();
            if (distance < min.get() && distance != -1) {
                // found shorter path
                min.set(distance);
                System.out.println("Current min distance: " + distance);
            }
        });

        end = System.currentTimeMillis();
        System.out.println("Computing branch distances took: " + (end-start));

        // we maximise branch distance in contradiction to its meaning, that means a branch distance of 1 is the best
        String branchDistance = null;

        // we need to normalise approach level / branch distance to the range [0,1] where 1 is best
        if (min.get() == Integer.MAX_VALUE) {
            // branch not reachable by execution path
            branchDistance = String.valueOf(0);
        } else {
            branchDistance = String.valueOf(1 - ((double) min.get() / (min.get() + 1)));
        }

        System.out.println("Branch Distance: " + branchDistance);

        /*
        // update target vertex
        if (branchDistance.equals("1.0")) {
            graph.updateCoveredTargetVertices();
            graph.selectTargetVertex(false);
        }
        */

        return branchDistance;
    }

    /**
     * Returns the branch distance for a given test case evaluated
     * against a pre-defined target vertex. That is, we evaluate
     * a given execution path (sequence of vertices) against a single
     * pre-defined target vertex.
     *
     * @param cmdStr The command string specifying the test case.
     * @return Returns the branch distance for a given test case.
     */
    private String getBranchDistanceSequential(String cmdStr) {

        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        String testCase = parts[2];

        Device device = Device.devices.get(deviceID);

        // check whether writing traces has been completed yet
        while(!completedWritingTraces(deviceID)) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                System.out.println("sleeping failed!");
                e.printStackTrace();
                return null;
            }
        }

        String tracesDir = System.getProperty("user.dir");
        File traces = new File(tracesDir, "traces.txt");

        if (!device.pullTraceFile() || !traces.exists()) {
            // traces.txt was not accessible/found on emulator
            System.out.println("Couldn't find traces.txt!");
            return null;
        }

        List<String> executionPath = new ArrayList<>();

        long start = System.currentTimeMillis();

        try (Stream<String> stream = Files.lines(traces.toPath(), StandardCharsets.UTF_8)) {
            executionPath = stream.collect(Collectors.toList());
        }
        catch (IOException e) {
            System.out.println("Reading traces.txt failed!");
            e.printStackTrace();
            return null;
        }

        long end = System.currentTimeMillis();
        System.out.println("Reading traces from file took: " + (end-start));

        System.out.println("Number of visited vertices: " + executionPath.size());

        // we need to mark vertices we visit
        Set<Vertex> visitedVertices = new HashSet<>();

        // we need to track covered branch vertices for branch coverage
        Set<Vertex> coveredBranches = new HashSet<>();

        Map<String, Vertex> vertexMap = graph.getVertexMap();

        // map trace to vertex
        for (String pathNode : executionPath) {

            int index = pathNode.lastIndexOf("->");
            String type = pathNode.substring(index+2);

            Vertex visitedVertex = vertexMap.get(pathNode);
            visitedVertices.add(visitedVertex);

            if (!type.equals("entry") && !type.equals("exit")) {
                // must be a branch
                coveredBranches.add(visitedVertex);
            }
        }

        // track covered branches for coverage measurement
        graph.addCoveredBranches(coveredBranches);
        graph.addCoveredBranches(testCase, coveredBranches);

        // the minimal distance between a execution path and a chosen target vertex
        int min = Integer.MAX_VALUE;

        // cache already computed branch distances
        Map<Vertex, Double> branchDistances = graph.getBranchDistances();

        // use bidirectional dijkstra
        ShortestPathAlgorithm<Vertex, Edge> bfs = graph.getBFS();

        // we have a fixed target vertex
        Vertex targetVertex = graph.getTargetVertex();

        for (Vertex visitedVertex : visitedVertices) {

            int distance = -1;

            if (branchDistances.containsKey(visitedVertex)) {
                distance = branchDistances.get(visitedVertex).intValue();
            } else {
                GraphPath<Vertex, Edge> path = bfs.getPath(visitedVertex, targetVertex);
                if (path != null) {
                    distance = path.getLength();
                    // update branch distance map
                    branchDistances.put(visitedVertex, Double.valueOf(distance));
                } else {
                    // update branch distance map
                    branchDistances.put(visitedVertex, Double.valueOf(-1));
                }
            }

            // int distance = branchDistances.get(visitedVertex).intValue();
            if (distance < min && distance != -1) {
                // found shorter path
                min = distance;
                System.out.println("Current min distance: " + distance);
            }
        }

        // we maximise branch distance in contradiction to its meaning, that means a branch distance of 1 is the best
        String branchDistance = null;

        // we need to normalise approach level / branch distance to the range [0,1] where 1 is best
        if (min == Integer.MAX_VALUE) {
            // branch not reachable by execution path
            branchDistance = String.valueOf(0);
        } else {
            branchDistance = String.valueOf(1 - ((double) min / (min + 1)));
        }

        System.out.println("Branch Distance: " + branchDistance);

        /*
        // update target vertex
        if (branchDistance.equals("1.0")) {
            graph.updateCoveredTargetVertices();
            graph.selectTargetVertex(false);
        }
        */

        return branchDistance;
    }

    /**
     * Checks whether writing the collected traces onto the external storage has been completed.
     * This is done by checking if an info.txt file exists in the app-internal storage.
     *
     * @param deviceID The id of the emulator.
     * @return Returns {@code true} if the writing process has been finished,
     *          otherwise {@code false}.
     */
    private boolean completedWritingTraces(String deviceID) {
        List<String> files = ProcessRunner.runProcess("adb", "-s", deviceID, "shell", "run-as", graph.getPackageName(), "ls");
        System.out.println("Files: " + files);

        return files.stream().anyMatch(str -> str.trim().equals("info.txt"));
    }

    /**
     * Computes the branch distance vector for a given test case
     * and a given list of branches.
     *
     * @param cmdStr The command string containing a test case.
     * @return Returns the branch distance vector.
     */
    private String getBranchDistanceVector(String cmdStr) {

        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        String testCase = parts[2];

        Device device = Device.devices.get(deviceID);

        // check whether writing traces has been completed yet
        while(!completedWritingTraces(deviceID)) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                System.out.println("sleeping failed!");
                e.printStackTrace();
                return null;
            }
        }

        String tracesDir = System.getProperty("user.dir");
        File traces = new File(tracesDir, "traces.txt");


        if (!device.pullTraceFile() || !traces.exists()) {
            // traces.txt was not accessible/found on emulator
            System.out.println("Couldn't find traces.txt!");
            return null;
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

        System.out.println("Visited Vertices: " + executionPath);

        // we need to mark vertices we visit
        Set<Vertex> visitedVertices = new HashSet<>();

        // we need to track covered branch vertices for branch coverage
        Set<Vertex> coveredBranches = new HashSet<>();

        Map<String, Vertex> vertexMap = graph.getVertexMap();

        // map trace to vertex
        for (String pathNode : executionPath) {

            int index = pathNode.lastIndexOf("->");
            String type = pathNode.substring(index+2);

            Vertex visitedVertex = vertexMap.get(pathNode);
            visitedVertices.add(visitedVertex);

            if (!type.equals("entry") && !type.equals("exit")) {
                // must be a branch
                coveredBranches.add(visitedVertex);
            }
        }

        // evaluate against all branches
        List<Vertex> branches = graph.getBranches();

        // track covered branches for coverage measurement
        graph.addCoveredBranches(coveredBranches);
        graph.addCoveredBranches(testCase, coveredBranches);

        // stores the fitness value for a given test case (execution path) evaluated against each branch
        List<String> branchDistanceVector = new LinkedList<>();

        // use bidirectional dijkstra
        ShortestPathAlgorithm<Vertex, Edge> bfs = graph.getBFS();

        // evaluate fitness value for each single branch
        for (Vertex branch : branches) {

            // the minimal distance between a execution path and a given branch
            int min = Integer.MAX_VALUE;

            // find the shortest distance for the single branch
            for (Vertex visitedVertex : visitedVertices) {

                // TODO: cache computed branch distances as in single objective case

                int distance = -1;
                GraphPath<Vertex, Edge> path = bfs.getPath(visitedVertex, branch);

                if (path != null) {
                    distance = path.getLength();
                }

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
    private String initCFG(String cmdStr) {

        // limit is required to avoid splitting a Windows path
        String[] parts = cmdStr.split(":", 3);
        String packageName = parts[1];
        String apkPath = parts[2];

        System.out.println("Package Name: " + packageName);
        System.out.println("APK path: " + apkPath);

        try {
            graph = new CFG(Main.computerInterCFGWithBB(apkPath,true), packageName);
        } catch (IOException e) {
            e.printStackTrace();
            return "false";
        }
        return "true";
    }

    private String getActivity(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return device.getCurrentActivity();
    }

    private String storeCurrentTraceFile(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return device.storeCurrentTraceFile();
    }

    private String storeCoverageData(String cmdStr) {
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

    private String copyCoverageData(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        String chromosome_source = parts[2];
        String chromosome_target = parts[3];
        String entities = parts[4];
        Device device = Device.devices.get(deviceID);
        return device.copyCoverageData(chromosome_source, chromosome_target, entities);
    }


    private String getActivities(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return String.join("\n", device.getActivities());
    }

    private String getSourceLines(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return String.join("\n", device.getSourceLines());
    }

    private String getCoverage(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        String chromosome = parts[2];
        Device device = Device.devices.get(deviceID);
        return device.getCoverage(chromosome);
    }

    private String getLineCoveredPercentage(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        String chromosome = parts[2];
        String line = parts[3];
        Device device = Device.devices.get(deviceID);
        return String.join("\n", device.getLineCoveredPercentage(chromosome, line));
    }

    private String getCombinedCoverage(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        String chromosomes = "all";
        if (parts.length > 2) {
            chromosomes = parts[2];
        }
        return device.getCombinedCoverage(chromosomes);
    }

    private String clearApp(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return device.clearApp();
    }
}
