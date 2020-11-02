package org.mate.endpoints;

import de.uni_passau.fim.auermich.graphs.Vertex;
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
        } else if (request.getSubject().startsWith("/graph/get_branch_distance")) {
            return getBranchDistance(request);
        } else if (request.getSubject().startsWith("/graph/get_branch_distance_vector")) {
            return getBranchDistanceVector(request);
        } else {
            throw new IllegalArgumentException("Message request with subject: "
                    + request.getSubject() + " can't be handled by GraphEndPoint!");
        }
    }

    private Message initGraph(Message request) {

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
        return new Message("/graph/init");
    }

    private Message initInterCFG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses, String packageName) {
        graph = new InterCFG(apkPath, useBasicBlocks, excludeARTClasses, packageName);
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

        // the branches.txt should be located within the app directory
        File branchesFile = new File(appDir, "branches.txt");

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

        Log.println("Number of collected traces: " + traces.size());

        long end = System.currentTimeMillis();

        Log.println("Reading traces from file(s) took: " + (end - start) + " seconds");

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

        Log.println("Number of visited vertices: " + visitedVertices.size());

        // TODO: compute minimal approach level

        start = System.currentTimeMillis();

        // the minimal distance between a execution path and a chosen target vertex
        AtomicInteger minDistance = new AtomicInteger(Integer.MAX_VALUE);
        AtomicReference<Vertex> minDistanceVertex = new AtomicReference<>();

        visitedVertices.parallelStream().forEach(visitedVertex -> {

            int distance = graph.getDistance(visitedVertex);

            synchronized (this) {
                if (distance < minDistance.get() && distance != -1) {
                    // found shorter path
                    minDistanceVertex.set(visitedVertex);
                    minDistance.set(distance);
                }
            }
        });

        Log.println("Shortest path length: " + minDistance.get());

        end = System.currentTimeMillis();
        Log.println("Computing approach level took: " + (end-start) + " seconds");

        /*
        * The fitness value we are interested is a combination of the heuristics
        * approach level + branch distance. The branch distance has to be normalized
        * since the approach level gears the search and is more important.
        * The branch distance is computed based on the closest shared ancestor of
        * the visited nodes and the target branch node. By construction, this is
        * the node with the closest approach level and must be an IF node.
        *
         */

        // TODO: get minimal distance (if distance == 0), then return branch distance
        String branchDistance;

        // TODO: normalise distance in the range [0,1] where 1 is best
        if (minDistance.get() == Integer.MAX_VALUE) {
            // branch not reachable by execution path
            branchDistance = String.valueOf(0);
        } else {
            branchDistance = String.valueOf(1 - ((double) minDistance.get() / (minDistance.get() + 1)));
        }

        // TODO: return message wrapping branch distance value
        return new Message.MessageBuilder("/graph/get_branches")
                .withParameter("branch_distance", branchDistance)
                .build();
    }

    private Message getBranchDistanceVector(Message request) {
        return null;
    }
}
