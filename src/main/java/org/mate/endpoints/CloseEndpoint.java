package org.mate.endpoints;

import org.mate.network.Endpoint;
import org.mate.network.message.Message;

import java.util.HashSet;
import java.util.Set;

/**
 * The end point responsible for handling 'close' requests. Whenever such request comes in the client socket should be
 * closed.
 */
public class CloseEndpoint implements Endpoint {

    /**
     * Maintains a set of closed threads/sockets.
     */
    private final Set<Long> closedThreadIds = new HashSet<>();

    /**
     * Checks whether the client socket has been closed.
     *
     * @return Returns {@code true} if the client socket has been closed, otherwise {@code false} is returned.
     */
    public boolean isClosed() {
        return closedThreadIds.remove(getCurrentThreadId());
    }

    /**
     * Handles an incoming 'close' request. This blocks further requests on the underlying socket.
     *
     * @param request The 'close' request.
     * @return Returns a dummy message acknowledging the 'close' request.
     */
    @Override
    public Message handle(Message request) {
        closedThreadIds.add(getCurrentThreadId());
        return new Message("/close");
    }

    /**
     * Retrieves the thread id of the current thread. Note that the thread id might be re-used when the thread has been
     * terminated.
     *
     * @return Returns the thread id of the current thread.
     */
    private long getCurrentThreadId() {
        return Thread.currentThread().getId();
    }
}
