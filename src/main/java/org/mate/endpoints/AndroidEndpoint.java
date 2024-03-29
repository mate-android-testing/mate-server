package org.mate.endpoints;

import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.network.message.Messages;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;
import org.mate.util.Result;

import java.nio.file.Path;
import java.util.List;

public class AndroidEndpoint implements Endpoint {
    private final AndroidEnvironment androidEnvironment;

    public AndroidEndpoint(AndroidEnvironment androidEnvironment) {
        this.androidEnvironment = androidEnvironment;
    }

    @Override
    public Message handle(Message request) {
        if (request.getSubject().startsWith("/android/clearApp")) {
            return clearApp(request);
        } else if (request.getSubject().startsWith("/android/get_activities")) {
            return getActivities(request);
        } else if (request.getSubject().startsWith("/android/get_current_activity")) {
            return getCurrentActivity(request);
        } else if (request.getSubject().startsWith("/android/grant_runtime_permissions")) {
            return grantRuntimePermissions(request);
        } else {
            throw new IllegalArgumentException("Message request with subject: "
                    + request.getSubject() + " can't be handled by AndroidEndpoint!");
        }
    }

    /**
     * Grants certain runtime permissions to the AUT.
     *
     * @param request A message containing the device id and
     *                the name of the AUT.
     * @return Returns a message containing the response of grant operation.
     */
    private Message grantRuntimePermissions(Message request) {

        String deviceID = request.getParameter("deviceId");
        String packageName = request.getParameter("packageName");

        Device device = Device.devices.get(deviceID);
        boolean success = device.grantPermissions(packageName);
        Log.println("Granted runtime permissions: " + success);
        return Messages.buildResponse(request, success);
    }

    /**
     * Returns the list of activities of the AUT.
     *
     * @param request The request message.
     * @return Returns the list of activities of the AUT.
     */
    private Message getActivities(Message request) {

        var deviceId = request.getParameter("deviceId");
        Device device = Device.devices.get(deviceId);
        var activities  = String.join("\n", device.getActivities());

        return new Message.MessageBuilder("/android/get_activities")
                .withParameter("activities", activities)
                .build();
    }

    /**
     * Returns the current activity name.
     *
     * @param request The request message.
     * @return Returns the name of the currently visible activity.
     */
    private Message getCurrentActivity(Message request) {

        var deviceId = request.getParameter("deviceId");
        Device device = Device.devices.get(deviceId);

        return new Message.MessageBuilder("/android/get_current_activity")
                .withParameter("activity", device.getCurrentActivity())
                .build();
    }

    /**
     * Clears the app cache of the AUT.
     *
     * @param request The request message.
     * @return Returns the response message.
     */
    private Message clearApp(Message request) {

        var deviceId = request.getParameter("deviceId");
        var packageName = Device.getDevice(deviceId).getPackageName();

        Result<List<String>, String> result = ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(),
                "-s",
                deviceId,
                "shell",
                "pm",
                "clear",
                packageName);
        if (result.isErr()) {
            return Messages.errorMessage(result.getErr());
        }

        // pipe into stdin of 'adb shell'
        String[] inputCommands = {
                "run-as " + packageName,
                "mkdir -p files",
                "touch files/coverage.exec",
                // TODO: are those exit commands really necessary at all?
                "exit",
                "exit"
        };

        result = ProcessRunner.runProcess((Path) null,
                String.join("\n", inputCommands),
                androidEnvironment.getAdbExecutable(), "-s", deviceId, "shell");

        if (result.isErr()) {
            return Messages.errorMessage(result.getErr());
        }

        return Messages.buildResponse(request, true);
    }
}
