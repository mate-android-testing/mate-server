package org.mate.endpoints;

import de.uni_passau.fim.auermich.graphs.Vertex;
import de.uni_passau.fim.auermich.statement.BasicStatement;
import de.uni_passau.fim.auermich.statement.BlockStatement;
import de.uni_passau.fim.auermich.statement.Statement;
import org.apache.commons.io.FileUtils;
import org.mate.graphs.Graph;
import org.mate.graphs.GraphType;
import org.mate.graphs.InterCFG;
import org.mate.graphs.IntraCFG;
import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This endpoint offers an interface to operate with graphs in the background.
 * This can be a simple control flow graph to evaluate branch distance, but also
 * a system dependence graph. The usage of this endpoint requires the
 * android-graphs-all.jar as a dependency.
 */
public class GraphEndpoint implements Endpoint {

    private final AndroidEnvironment androidEnvironment;
    private Graph graph;
    private final Path appsDir;

    // a target vertex (a random branch)
    private Vertex target;

    public GraphEndpoint(AndroidEnvironment androidEnvironment, Path appsDir) {
        this.androidEnvironment = androidEnvironment;
        this.appsDir = appsDir;
    }

    @Override
    public Message handle(Message request) {
        // TODO: do we need a command to set the target vertex or always select it randomly?
        if (request.getSubject().startsWith("/graph/init")) {
            return initGraph(request);
        } else if (request.getSubject().startsWith("/graph/get_branches")) {
            return getBranches(request);
        } else if (request.getSubject().startsWith("/graph/store")) {
            return storeBranchDistanceData(request);
        } else if (request.getSubject().startsWith("/graph/get_branch_distance_vector")) {
            return getBranchDistanceVector(request);
        } else if (request.getSubject().startsWith("/graph/get_branch_distance")) {
            return getBranchDistance(request);
        } else {
            throw new IllegalArgumentException("Message request with subject: "
                    + request.getSubject() + " can't be handled by GraphEndPoint!");
        }
    }

    /**
     * Selects a branch vertex as target in a random fashion.
     */
    private Vertex selectTargetVertex(List<Vertex> branchVertices) {
        while (true) {
            Random rand = new Random();
            Vertex randomBranch = branchVertices.get(rand.nextInt(branchVertices.size()));

            if (graph.isReachable(randomBranch)) {
                Log.println("Randomly selected target vertex: " + randomBranch + " [" + randomBranch.getMethod() + "]");
                return randomBranch;
            }
        }
    }

    private Message initGraph(Message request) {

        // TODO: add param that describes how the target vertex should be selected (no, random, random branch, ...)

        String deviceID = request.getParameter("deviceId");
        String packageName = request.getParameter("packageName");
        GraphType graphType = GraphType.valueOf(request.getParameter("graph_type"));
        File apkPath = new File(request.getParameter("apk"));

        if (!apkPath.exists()) {
            throw new IllegalArgumentException("Can't locate APK: " + apkPath.getAbsolutePath() + "!");
        }

        boolean useBasicBlocks = Boolean.parseBoolean(request.getParameter("basic_blocks"));

        switch (graphType) {
            case INTRA_CFG:
                String methodName = request.getParameter("method");
                return initIntraCFG(apkPath, methodName, useBasicBlocks, packageName);
            case INTER_CFG:
                boolean excludeARTClasses = Boolean.parseBoolean(request.getParameter("exclude_art_classes"));
                return initInterCFG(apkPath, useBasicBlocks, excludeARTClasses, packageName);
            default:
                throw new UnsupportedOperationException("Graph type not yet supported!");
        }
    }

    private Message initIntraCFG(File apkPath, String methodName, boolean useBasicBlocks, String packageName) {
        graph = new IntraCFG(apkPath, methodName, useBasicBlocks, packageName);
        target = selectTargetVertex(graph.getBranchVertices());
        return new Message("/graph/init");
    }

    private Message initInterCFG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses, String packageName) {
        graph = new InterCFG(apkPath, useBasicBlocks, excludeARTClasses, packageName);
        target = selectTargetVertex(graph.getBranchVertices());
        return new Message("/graph/init");
    }

    private Message getBranches(Message request) {

        GraphType graphType = GraphType.valueOf(request.getParameter("graph_type"));

        if (graphType == GraphType.SGD) {
            throw new UnsupportedOperationException("Graph type not yet supported!");
        }

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        String branches = String.join("\\+", graph.getBranches());
        return new Message.MessageBuilder("/graph/get_branches")
                .withParameter("branches", branches)
                .build();
    }

    private Message storeBranchDistanceData(Message request) {

        String deviceID = request.getParameter("deviceId");
        // TODO: use packageName of device
        String packageName = request.getParameter("packageName");
        String chromosome = request.getParameter("chromosome");
        String entity = request.getParameter("entity");

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        // grant read/write permission on external storage
        Device device = Device.getDevice(deviceID);
        boolean granted = device.grantPermissions(packageName);

        if (!granted) {
            throw new IllegalStateException("Couldn't grant runtime permissions!");
        }

        // send broadcast in order to write out traces
        var broadcastOperation = ProcessRunner.runProcess(
                androidEnvironment.getAdbExecutable(),
                "-s",
                deviceID,
                "shell",
                "am",
                "broadcast",
                "-a",
                "STORE_TRACES",
                "-n",
                packageName + "/de.uni_passau.fim.auermich.tracer.Tracer",
                "--es",
                "packageName",
                packageName);

        if (broadcastOperation.isErr()) {
            throw new IllegalStateException("Couldn't send broadcast!");
        }

        // fetch the traces from emulator
        device.pullTraceFile(chromosome, entity);
        return new Message("/graph/store");
    }

    private Message getBranchDistance(Message request) {

        String deviceID = request.getParameter("deviceId");
        String chromosomes = request.getParameter("chromosomes");

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        Device device = Device.getDevice(deviceID);

        // get list of traces file
        File appDir = new File(appsDir.toFile(), device.getPackageName());
        File tracesDir = new File(appDir, "traces");

        // collect the relevant traces files
        List<File> tracesFiles = getTraceFiles(tracesDir, chromosomes);

        // read traces from trace file(s)
        Set<String> traces = readTraces(tracesFiles);

        // we need to mark vertices we visited
        Set<Vertex> visitedVertices = mapTracesToVertices(traces);

        long start = System.currentTimeMillis();

        // the minimal distance between a execution path and a chosen target vertex
        AtomicInteger minDistance = new AtomicInteger(Integer.MAX_VALUE);
        AtomicReference<Vertex> minDistanceVertex = new AtomicReference<>();

        visitedVertices.parallelStream().forEach(visitedVertex -> {

            int distance = graph.getDistance(visitedVertex, target);

            synchronized (this) {
                if (distance < minDistance.get() && distance != -1) {
                    // found shorter path
                    minDistanceVertex.set(visitedVertex);
                    minDistance.set(distance);
                }
            }
        });

        Log.println("Shortest path length: " + minDistance.get());

        long end = System.currentTimeMillis();
        Log.println("Computing approach level took: " + (end-start) + " seconds");

        // TODO: draw graph with visited vertices if graph is not too big

        /*
         * We compute the fitness value according to the paper 'Reformulating Branch Coverage as
         * a Many-Objective Optimization Problem' in the context of the MOSA algorithm, although
         * the algorithm itself should be responsible for the normalization of the fitness value.
         * In contrast to traditional approaches, the fitness value lies in the range [0,1] where
         * 1 is the best value, i.e. the target branch has been covered.
         */
        String branchDistance;

        if (minDistance.get() == Integer.MAX_VALUE) {
            // branch not reachable by execution path
            branchDistance = String.valueOf(0);
        } else if (minDistance.get() == 0) {
            // target branch covered (recall that '1' is the best fitness value)
            branchDistance = String.valueOf(1);
        } else {
            // we need to combine approach level + branch distance
            int approachLevel = minDistance.get();

            /*
            * The vertex with the closest distance represents an if stmt, at which
            * the execution path took the wrong direction. We need to find the shortest
            * branch distance value for the given if stmt. Note that the if stmt could have been
            * visited multiple times.
             */
            Vertex ifVertex = minDistanceVertex.get();
            Statement stmt = ifVertex.getStatement();

            // we only support basic blocks right now
            assert stmt.getType() == Statement.StatementType.BLOCK_STATEMENT;

            // the if stmt is located the last position of the block
            BasicStatement ifStmt = (BasicStatement) ((BlockStatement) stmt).getLastStatement();

            // find the branch distance trace(s) that describes the if stmt
            String prefix = ifVertex.getMethod() + "->" + ifStmt.getInstructionIndex() + ":";
            // if the if vertex is itself a branch vertex
            // String traceAlternative = ifVertex.getMethod() + "->" + ifStmt.getInstructionIndex();

            Log.println("Trace describing closest if stmt: " + prefix);

            double minBranchDistance = Double.MAX_VALUE;

            for (String trace: traces) {
                if (trace.startsWith(prefix)) {

                    // found a branch distance value for the given if stmt
                    double distance = Double.parseDouble(trace.split(":")[1]);
                    Log.println("Distance value: " + distance);

                    // we need to track minimal distance
                    if (distance < minBranchDistance) {
                        minBranchDistance = distance;
                    }
                }
            }

            Log.println("Approach level: " + approachLevel);
            Log.println("Minimal branch distance: " + minBranchDistance);

            // combine and normalise
            double combined = approachLevel + minBranchDistance;
            branchDistance = String.valueOf(1 - (combined / (combined + 1)));
            // branchDistance = String.valueOf(1 - ((double) minDistance.get() / (minDistance.get() + 1)));
        }

        return new Message.MessageBuilder("/graph/get_branch_distance")
                .withParameter("branch_distance", branchDistance)
                .build();
    }

    private Message getBranchDistanceVector(Message request) {

        String deviceID = request.getParameter("deviceId");
        String chromosomes = request.getParameter("chromosomes");

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        Device device = Device.getDevice(deviceID);

        // get list of traces file
        File appDir = new File(appsDir.toFile(), device.getPackageName());
        File tracesDir = new File(appDir, "traces");

        // collect the relevant traces files
        List<File> tracesFiles = getTraceFiles(tracesDir, chromosomes);

        // read the traces from the traces files
        Set<String> traces = readTraces(tracesFiles);

        // we need to mark vertices we visited
        Set<Vertex> visitedVertices = mapTracesToVertices(traces);

        // evaluate fitness value (approach level + branch distance) for each single branch
        List<String> branchDistanceVector = new LinkedList<>();

        for (Vertex branch : graph.getBranchVertices()) {

            // find the shortest distance (approach level) to the given branch
            AtomicInteger minDistance = new AtomicInteger(Integer.MAX_VALUE);
            AtomicReference<Vertex> minDistanceVertex = new AtomicReference<>();

            visitedVertices.parallelStream().forEach(visitedVertex -> {

                int distance = graph.getDistance(visitedVertex, branch);

                synchronized (this) {
                    if (distance < minDistance.get() && distance != -1) {
                        // found shorter path
                        minDistanceVertex.set(visitedVertex);
                        minDistance.set(distance);
                    }
                }
            });

            if (minDistance.get() == Integer.MAX_VALUE) {
                // branch not reachable by execution path
                branchDistanceVector.add(String.valueOf(0));
            } else if (minDistance.get() == 0) {
                // target branch covered (recall that '1' is the best fitness value)
                branchDistanceVector.add(String.valueOf(1));
            } else {
                // we need to combine approach level + branch distance
                int approachLevel = minDistance.get();

                /*
                 * The vertex with the closest distance represents an if stmt, at which
                 * the execution path took the wrong direction. We need to find the shortest
                 * branch distance value for the given if stmt. Note that the if stmt could have been
                 * visited multiple times.
                 */
                Vertex ifVertex = minDistanceVertex.get();
                Statement stmt = ifVertex.getStatement();

                // we only support basic blocks right now
                assert stmt.getType() == Statement.StatementType.BLOCK_STATEMENT;

                // the if stmt is located the last position of the block
                BasicStatement ifStmt = (BasicStatement) ((BlockStatement) stmt).getLastStatement();

                // find the branch distance trace(s) that describes the if stmt
                String prefix = ifVertex.getMethod() + "->" + ifStmt.getInstructionIndex() + ":";
                // if the if vertex is itself a branch vertex
                // String traceAlternative = ifVertex.getMethod() + "->" + ifStmt.getInstructionIndex();

                Log.println("Trace describing closest if stmt: " + prefix);

                double minBranchDistance = Double.MAX_VALUE;

                for (String trace : traces) {
                    if (trace.startsWith(prefix)) {

                        // found a branch distance value for the given if stmt
                        double distance = Double.parseDouble(trace.split(":")[1]);
                        Log.println("Distance value: " + distance);

                        // we need to track minimal distance
                        if (distance < minBranchDistance) {
                            minBranchDistance = distance;
                        }
                    }
                }

                Log.println("Approach level: " + approachLevel);
                Log.println("Minimal branch distance: " + minBranchDistance);

                // combine and normalise
                double combined = approachLevel + minBranchDistance;
                branchDistanceVector.add(String.valueOf(1 - (combined / (combined + 1))));
            }
        }

        Log.println("Branch Distance Vector: " + branchDistanceVector);

        return new Message.MessageBuilder("/graph/get_branch_distance_vector")
                .withParameter("branch_distance_vector", String.join("+", branchDistanceVector))
                .build();
    }

    /**
     * Gets the list of traces files specified by the given chromosomes.
     *
     * @param tracesDir The base directory containing the traces files.
     * @param chromosomes Encodes a mapping to one or several traces files.
     * @return Returns the list of traces files described by the given chromosomes.
     */
    private List<File> getTraceFiles(File tracesDir, String chromosomes) {

        // collect the relevant traces files
        List<File> tracesFiles = new ArrayList<>(FileUtils.listFiles(tracesDir, null, true));

        if (chromosomes != null) {

            // only consider the traces files described by the chromosome ids
            tracesFiles = new ArrayList<>();

            for (String chromosome : chromosomes.split("\\+")) {
                try {
                    tracesFiles.addAll(
                            Files.walk(tracesDir.toPath().resolve(chromosome))
                                    .filter(Files::isRegularFile)
                                    .map(Path::toFile)
                                    .collect(Collectors.toList()));
                } catch (IOException e) {
                    Log.printError("Couldn't retrieve traces files!");
                    throw new IllegalArgumentException(e);
                }
            }
        }

        Log.println("Number of considered traces files: " + tracesFiles.size());
        return tracesFiles;
    }

    /**
     * Reads the traces from the given list of traces files.
     *
     * @param tracesFiles A list of traces files.
     * @return Returns the unique traces contained in the given traces files.
     */
    private Set<String> readTraces(List<File> tracesFiles) {

        // read traces from trace file(s)
        long start = System.currentTimeMillis();

        Set<String> traces = new HashSet<>();

        for (File traceFile : tracesFiles) {
            try (Stream<String> stream = Files.lines(traceFile.toPath(), StandardCharsets.UTF_8)) {
                traces.addAll(stream.collect(Collectors.toList()));
            } catch (IOException e) {
                Log.println("Reading traces.txt failed!");
                throw new IllegalStateException(e);
            }
        }

        long end = System.currentTimeMillis();
        Log.println("Reading traces from file(s) took: " + (end - start) + " seconds");

        Log.println("Number of collected traces: " + traces.size());
        return traces;
    }

    /**
     * Maps the given set of traces to vertices in the graph.
     *
     * @param traces The set of traces that should be mapped to vertices.
     * @return Returns the vertices described by the given set of traces.
     */
    private Set<Vertex> mapTracesToVertices(Set<String> traces) {

        // read traces from trace file(s)
        long start = System.currentTimeMillis();

        // we need to mark vertices we visited
        Set<Vertex> visitedVertices = Collections.newSetFromMap(new ConcurrentHashMap<Vertex, Boolean>());

        // map trace to vertex
        traces.parallelStream().forEach(traceEntry -> {

            // TODO: should we also mark 'virtual' entry/exit vertices as visited?

            Vertex visitedVertex = graph.lookupVertex(traceEntry);

            if (visitedVertex == null) {
                // Log.printWarning("Couldn't derive vertex for trace entry: " + traceEntry);
            } else {
                visitedVertices.add(visitedVertex);
            }
        });

        long end = System.currentTimeMillis();
        Log.println("Mapping traces to vertices took: " + (end - start) + " seconds");

        Log.println("Number of visited vertices: " + visitedVertices.size());
        return visitedVertices;
    }
}
