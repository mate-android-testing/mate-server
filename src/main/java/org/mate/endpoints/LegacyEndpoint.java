package org.mate.endpoints;

import de.uni_passau.fim.auermich.graphs.Edge;
import de.uni_passau.fim.auermich.graphs.Vertex;
import de.uni_passau.fim.auermich.statement.BasicStatement;
import de.uni_passau.fim.auermich.statement.BlockStatement;
import de.uni_passau.fim.auermich.statement.ExitStatement;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.mate.accessibility.ImageHandler;
import org.mate.graphs.CFG2;
import org.mate.network.message.Messages;
import org.mate.util.AndroidEnvironment;
import org.mate.io.ProcessRunner;
import org.mate.io.Device;
import org.mate.network.message.Message;
import org.mate.network.Endpoint;
import org.mate.pdf.Report;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LegacyEndpoint implements Endpoint {
    private final AndroidEnvironment androidEnvironment;
    private final ImageHandler imageHandler;
    // graph instance needed for branch distance computation
    private CFG2 graph = null;
    private final long timeout;
    private final long length;
    private final boolean generatePDFReport = false;

    public LegacyEndpoint(long timeout, long length, AndroidEnvironment androidEnvironment, ImageHandler imageHandler) {
        this.timeout = timeout;
        this.length = length;
        this.androidEnvironment = androidEnvironment;
        this.imageHandler = imageHandler;
    }

    @Override
    public Message handle(Message request) {
        final var response = handleRequest(request.getParameter("cmd"));
        if (response == null) {
            return Messages.errorMessage("legacy message was not understood");
        }
        return new Message.MessageBuilder("/legacy")
                .withParameter("response", response)
                .build();
    }

    private String handleRequest(String cmdStr) {
        System.out.println();
        System.out.println(cmdStr);

        if (cmdStr.startsWith("reportFlaw")){
            return Report.addFlaw(cmdStr, imageHandler);
        }

        if (cmdStr.startsWith("getActivity"))
            return getActivity(cmdStr);

        if (cmdStr.startsWith("getActivities"))
            return getActivities(cmdStr);

        if (cmdStr.startsWith("getEmulator"))
            return Device.allocateDevice(cmdStr, imageHandler, androidEnvironment);

        if (cmdStr.startsWith("releaseEmulator"))
            return Device.releaseDevice(cmdStr);

        if (cmdStr.startsWith("getBranchDistanceVector"))
            return getBranchDistanceVector(cmdStr);

        if (cmdStr.startsWith("getBranchDistance"))
            return getBranchDistance(cmdStr);

        //format commands
        if (cmdStr.startsWith("screenshot"))
            return imageHandler.takeScreenshot(cmdStr);

        if (cmdStr.startsWith("flickerScreenshot"))
            return imageHandler.takeFlickerScreenshot(cmdStr);

        if (cmdStr.startsWith("mark-image") && generatePDFReport)
            return imageHandler.markImage(cmdStr);

        if (cmdStr.startsWith("contrastratio"))
            return imageHandler.calculateConstratRatio(cmdStr);

        if (cmdStr.startsWith("luminance"))
            return imageHandler.calculateLuminance(cmdStr);

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
     * Checks whether writing the collected traces onto the external storage has been completed.
     * This is done by checking if an info.txt file exists in the app-internal storage.
     *
     * @param deviceID The id of the emulator.
     * @return Returns {@code true} if the writing process has been finished,
     *          otherwise {@code false}.
     */
    private boolean completedWritingTraces(String deviceID) {
        List<String> files = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceID, "shell", "run-as", graph.getPackageName(), "ls").getOk();
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

    private String getActivity(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return device.getCurrentActivity();
    }

    private String getActivities(String cmdStr) {
        String parts[] = cmdStr.split(":");
        String deviceID = parts[1];
        Device device = Device.devices.get(deviceID);
        return String.join("\n", device.getActivities());
    }
}
