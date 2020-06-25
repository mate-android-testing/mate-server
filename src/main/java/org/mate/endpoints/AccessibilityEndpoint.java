package org.mate.endpoints;

import org.mate.accessibility.ImageHandler;
import org.mate.message.Message;
import org.mate.network.Endpoint;

public class AccessibilityEndpoint implements Endpoint {
    private final ImageHandler imageHandler;

    public AccessibilityEndpoint(ImageHandler imageHandler) {
        this.imageHandler = imageHandler;
    }

    @Override
    public Message handle(Message request) {
        return new Message.MessageBuilder("/accessibility")
                .withParameter("response", handleRequest(request.getParameter("cmd")))
                .build();
    }

    private String handleRequest(String cmdStr){
        if (cmdStr.startsWith("surroundingColor")) {
            System.out.println("matches color");
            return imageHandler.matchesSurroundingColor(cmdStr);
        }
        return "";
    }
}
