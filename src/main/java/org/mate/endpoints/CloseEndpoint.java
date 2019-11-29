package org.mate.endpoints;

import org.mate.message.Message;
import org.mate.network.Endpoint;

public class CloseEndpoint implements Endpoint {
    private boolean closed = false;

    public boolean isClosed() {
        return closed;
    }

    @Override
    public Message handle(Message request) {
        closed = true;
        return new Message("/close");
    }
}
