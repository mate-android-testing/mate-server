package org.mate.endpoints;

import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;

public class FuzzerEndpoint implements Endpoint {

    private final AndroidEnvironment androidEnvironment;

    public FuzzerEndpoint(AndroidEnvironment androidEnvironment) {
        this.androidEnvironment = androidEnvironment;
    }

    @Override
    public Message handle(Message request) {
        return null;
    }
}
