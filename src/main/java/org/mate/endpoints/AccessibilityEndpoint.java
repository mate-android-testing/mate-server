package org.mate.endpoints;

import org.mate.accessibility.ImageHandler;
import org.mate.io.Device;
import org.mate.network.message.Message;
import org.mate.network.Endpoint;
import org.mate.util.Log;

public class AccessibilityEndpoint implements Endpoint {
    private final ImageHandler imageHandler;

    public AccessibilityEndpoint(ImageHandler imageHandler) {
        this.imageHandler = imageHandler;
    }

    @Override
    public Message handle(Message request) {

        if (request.getSubject().startsWith("/accessibility/get_contrast_ratio")) {
            return getContrastRatio(request);
        } else if (request.getSubject().startsWith("/accessibility/check_flickering")) {
            return checkForFlickering(request);
        } else if (request.getSubject().startsWith("/accessibility/matches_surrounding_color")) {
            return matchesSurroundingColor(request);
        } else if (request.getSubject().startsWith("/accessibility/get_luminance")) {
            return getLuminance(request);
        }

        throw new IllegalArgumentException("Message request with subject: "
                + request.getSubject() + " can't be handled by AccessibilityEndpoint!");
    }

    private Message getLuminance(Message request) {

        Log.println("Getting luminance...");

        var packageName = request.getParameter("packageName");
        var stateID = request.getParameter("stateId");

        int x1 = Integer.parseInt(request.getParameter("x1"));
        int x2 = Integer.parseInt(request.getParameter("x2"));
        int y1 = Integer.parseInt(request.getParameter("y1"));
        int y2 = Integer.parseInt(request.getParameter("y2"));

        String luminance = imageHandler.calculateLuminance(packageName, stateID, x1, x2, y1, y2);

        return new Message.MessageBuilder("/accessibility/get_luminance")
                .withParameter("luminance", luminance).build();
    }

    private Message getContrastRatio(Message request) {

        Log.println("Retrieving contrast ratio...");

        var packageName = request.getParameter("packageName");
        var stateID = request.getParameter("stateId");

        int x1 = Integer.parseInt(request.getParameter("x1"));
        int x2 = Integer.parseInt(request.getParameter("x2"));
        int y1 = Integer.parseInt(request.getParameter("y1"));
        int y2 = Integer.parseInt(request.getParameter("y2"));

        double contrastRatio = imageHandler.calculateContrastRatio(packageName, stateID, x1, x2, y1, y2);

        return new Message.MessageBuilder("/accessibility/get_contrast_ratio")
                .withParameter("contrastRatio", String.valueOf(contrastRatio)).build();
    }

    private Message checkForFlickering(Message request) {

        Log.println("Check for flickering...");

        var deviceID = request.getParameter("deviceId");
        var packageName = request.getParameter("packageName");
        var stateID = request.getParameter("stateId");

        Device device = Device.devices.get(deviceID);
        boolean flickering = imageHandler.checkForFlickering(device, packageName, stateID);

        return new Message.MessageBuilder("/accessibility/check_flickering")
                .withParameter("flickering", String.valueOf(flickering)).build();
    }

    private Message matchesSurroundingColor(Message request) {

        Log.println("Check for surrounding color match...");

        var packageName = request.getParameter("packageName");
        var stateID = request.getParameter("stateId");

        int x1 = Integer.parseInt(request.getParameter("x1"));
        int x2 = Integer.parseInt(request.getParameter("x2"));
        int y1 = Integer.parseInt(request.getParameter("y1"));
        int y2 = Integer.parseInt(request.getParameter("y2"));

        double match = imageHandler.matchSurroundingColor(packageName, stateID, x1, x2, y1, y2);
        return new Message.MessageBuilder("/accessibility/matches_surrounding_color")
                .withParameter("match", String.valueOf(match)).build();
    }
}
