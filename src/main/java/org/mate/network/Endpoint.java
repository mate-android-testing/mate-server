package org.mate.network;

import org.mate.network.message.Message;

public interface Endpoint {
    Message handle(Message request);
}
