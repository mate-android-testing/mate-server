package org.mate.endpoints;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

        // TODO: convert traces to nodes in the graph

        // TODO: mark the nodes as visited and compute distance (approach level) using dijkstra

        // TODO: get minimal distance (if distance == 0), then return branch distance

        // TODO: return message wrapping branch distance value
        return null;
    }

    private Message getBranchDistanceVector(Message request) {
        return null;
    }
}
