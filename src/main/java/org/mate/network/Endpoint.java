package org.mate.network;

import org.mate.message.Message;

public interface Endpoint {
    Message handle(Message request);
}
