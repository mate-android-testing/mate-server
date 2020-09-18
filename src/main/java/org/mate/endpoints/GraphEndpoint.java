package org.mate.endpoints;

import org.mate.graphs.Graph;
import org.mate.graphs.GraphType;
import org.mate.graphs.InterCFG;
import org.mate.graphs.IntraCFG;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;

import java.io.File;

/**
 * This endpoint offers an interface to operate with graphs in the background.
 * This can be a simple control flow graph to evaluate branch distance, but also
 * a system dependence graph. The usage of this endpoint requires the
 * android-graphs-all.jar as a dependency.
 */
public class GraphEndpoint implements Endpoint {

    private final AndroidEnvironment androidEnvironment;
    private Graph graph;

    public GraphEndpoint(AndroidEnvironment androidEnvironment) {
        this.androidEnvironment = androidEnvironment;
    }

    @Override
    public Message handle(Message request) {
        if (request.getSubject().startsWith("/graph/init")) {
            return initGraph(request);
        } else if (request.getSubject().startsWith("/graph/get_branches")) {
            return getBranches(request);
        } else if (request.getSubject().startsWith("/graph/get_branch_distance")) {
            return getBranchDistance(request);
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
            throw new IllegalArgumentException("Can't locate APK!");
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

    private Message getBranchDistance(Message request) {
        return null;
    }
}
