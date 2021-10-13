package org.mate.endpoints;

import org.mate.io.Device;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

/**
 * This endpoint is responsible for handling intent fuzzing requests.
 * This includes primarily the broadcasting of system events.
 */
public class FuzzerEndpoint implements Endpoint {

    private final AndroidEnvironment androidEnvironment;

    public FuzzerEndpoint(AndroidEnvironment androidEnvironment) {
        this.androidEnvironment = androidEnvironment;
    }

    @Override
    public Message handle(Message request) {
        if (request.getSubject().startsWith("/fuzzer/execute_system_event")) {
            return executeSystemEvent(request);
        } else if (request.getSubject().startsWith("/fuzzer/push_dummy_files")) {
            return pushDummyFiles(request);
        } else {
            throw new IllegalArgumentException("Message request with subject: "
                    + request.getSubject() + " can't be handled by FuzzerEndPoint!");
        }
    }

    /**
     * Broadcasts a certain system event.
     *
     * @param request A message containing the device id, the package name of the AUT,
     *                the name of the broadcast receiver, the action tag as well as
     *                whether the broadcast receiver is a dynamic receiver.
     * @return Returns a message containing the response of the broadcast.
     */
    private Message executeSystemEvent(Message request) {

        String deviceID = request.getParameter("deviceId");
        // TODO: use packageName of device
        String packageName = request.getParameter("packageName");
        String receiver = request.getParameter("receiver");
        String action = request.getParameter("action");
        boolean dynamicReceiver = Boolean.parseBoolean(request.getParameter("dynamic"));

        Device device = Device.devices.get(deviceID);
        boolean response = device.executeSystemEvent(packageName, receiver, action, dynamicReceiver);
        Log.println("System event broadcast: " + response);

        return new Message.MessageBuilder("/fuzzer/execute_system_event")
                .withParameter("response", String.valueOf(response))
                .build();
    }

    /**
     * Pushes a set of pre-defined dummy files on the device.
     * See the 'mediafiles' folder for the respective files.
     *
     * @param request A message containing the device id.
     * @return Returns a message containing the result of the push operation.
     */
    private Message pushDummyFiles(Message request) {

        String deviceID = request.getParameter("deviceId");

        Device device = Device.devices.get(deviceID);
        boolean response = device.pushDummyFiles();
        Log.println("Pushing dummy files: " + response);

        return new Message.MessageBuilder("/fuzzer/push_dummy_files")
                .withParameter("response", String.valueOf(response))
                .build();
    }
}
