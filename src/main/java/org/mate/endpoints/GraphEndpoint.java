package org.mate.endpoints;

import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;

/**
 * This endpoint offers an interface to operate with graphs in the background.
 * This can be a simple control flow graph to evaluate branch distance, but also
 * a system dependence graph. The usage of this endpoint requires the
 * android-graphs-all.jar as a dependency.
 */
public class GraphEndpoint implements Endpoint {

    private final AndroidEnvironment androidEnvironment;

    public GraphEndpoint(AndroidEnvironment androidEnvironment) {
        this.androidEnvironment = androidEnvironment;
    }

    @Override
    public Message handle(Message request) {
        if (request.getSubject().startsWith("/graph/cfg/init")) {
            return initCFG(request);
        } else if (request.getSubject().startsWith("/graph/cfg/get_branches")) {
            return getBranches(request);
        } else if (request.getSubject().startsWith("/graph/cfg/get_branch_distance")) {
            return getBranchDistance(request);
        } else {
            throw new IllegalArgumentException("Message request with subject: "
                    + request.getSubject() + " can't be handled by GraphEndPoint!");
        }
    }

    private Message initCFG(Message request) {
        return null;
    }

    private Message getBranches(Message request) {
        return null;
    }

    private Message getBranchDistance(Message request) {
        return null;
    }
}
