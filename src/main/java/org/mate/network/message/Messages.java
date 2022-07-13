package org.mate.network.message;

import org.mate.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Messages {
    private static final String METADATA_PREFIX = "__meta__";
    private static final String MESSAGE_PROTOCOL_VERSION = "2.8";
    private static final String MESSAGE_PROTOCOL_VERSION_KEY = "version";

    //util class
    private Messages() {
    }

    public static Message errorMessage(String info) {
        return new Message.MessageBuilder("/error").withParameter("info", info).build();
    }

    public static void addMetadata(Message message) {
        message.addParameter(
                METADATA_PREFIX + MESSAGE_PROTOCOL_VERSION_KEY, MESSAGE_PROTOCOL_VERSION);
    }

    public static void stripMetadata(Message message) {
        List<String> metadataKeys = new ArrayList<>();
        Map<String, String> parameters = message.getParameters();
        for (String parameterKey: parameters.keySet()) {
            if (parameterKey.startsWith(METADATA_PREFIX)) {
                metadataKeys.add(parameterKey);
            }
        }
        for (String metadataKey : metadataKeys) {
            parameters.remove(metadataKey);
        }
    }

    public static void verifyMetadata(Message message) {
        String protocolVersion = message.getParameter(
                METADATA_PREFIX + MESSAGE_PROTOCOL_VERSION_KEY);
        if (!protocolVersion.equals(MESSAGE_PROTOCOL_VERSION)) {
            Log.printWarning(
                    "Message protocol version used by MATE-Server ("
                            + MESSAGE_PROTOCOL_VERSION
                            + ") does not match with the version used by MATE ("
                            + protocolVersion
                            + ")");
        }
    }

    public static Message unknownEndpoint(String subject) {
        return errorMessage("Endpoint for message with subject \""
                + subject
                + "\" not registered in MATE-Server. Maybe you are "
                + "using an outdated version of the server?");
    }

    public static Message unhandledMessage(String subject) {
        return errorMessage("Endpoint for message with subject \""
                + subject
                + "\" was unable produce a response.");
    }
}
