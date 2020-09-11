package org.mate.endpoints;

import org.mate.network.message.Message;
import org.mate.network.Endpoint;

public class CloseEndpoint implements Endpoint {
    private boolean closed = false;

    public boolean isClosed() {
        return closed;
    }

    public void reset() {
        closed = false;
    }

    @Override
    public Message handle(Message request) {
        closed = true;
        return new Message("/close");
    }
}
