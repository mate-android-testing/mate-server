package org.mate.endpoints;

import org.mate.accessibility.ImageHandler;
import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

public class EmulatorInteractionEndpoint implements Endpoint {

    private boolean disabledAutoRotate = false;
    private boolean isInPortraitMode = true;
    private final AndroidEnvironment androidEnvironment;
    private final ImageHandler imageHandler;

    public EmulatorInteractionEndpoint(AndroidEnvironment androidEnvironment, ImageHandler imageHandler) {
        this.androidEnvironment = androidEnvironment;
        this.imageHandler = imageHandler;
    }

    @Override
    public Message handle(Message request) {
        if (request.getSubject().startsWith("/emulator/interaction")) {

            /*
            * NOTE: The rotation operations are now directly invoked by MATE itself. We just keep the code in case of
            * compatibility issues with newer emulator images.
             */
            if ("rotation".equals(request.getParameter("type"))) {

                String deviceID = request.getParameter("deviceId");

                if (!disabledAutoRotate) {

                    ProcessRunner.runProcess(
                            androidEnvironment.getAdbExecutable(),
                            "-s",
                            deviceID,
                            "shell",
                            "content",
                            "insert",
                            "--uri",
                            "content://settings/system",
                            "--bind",
                            "name:s:accelerometer_rotation",
                            "--bind",
                            "value:i:0");
                }

                if ("toggle".equals(request.getParameter("rotation"))) {
                    String rotationMode;
                    if (isInPortraitMode) {

                        rotationMode = "landscape";
                        ProcessRunner.runProcess(
                                androidEnvironment.getAdbExecutable(),
                                "-s",
                                deviceID,
                                "shell",
                                "content",
                                "insert",
                                "--uri",
                                "content://settings/system",
                                "--bind",
                                "name:s:user_rotation",
                                "--bind",
                                "value:i:1");
                    } else {

                        rotationMode = "portrait";
                        ProcessRunner.runProcess(
                                androidEnvironment.getAdbExecutable(),
                                "-s",
                                deviceID,
                                "shell",
                                "content",
                                "insert",
                                "--uri",
                                "content://settings/system",
                                "--bind",
                                "name:s:user_rotation",
                                "--bind",
                                "value:i:0");
                    }
                    isInPortraitMode = !isInPortraitMode;

                    return new Message.MessageBuilder("/emulator/interaction")
                            .withParameter("rotation", rotationMode)
                            .build();
                } else if ("portrait".equals(request.getParameter("rotation"))) {
                    isInPortraitMode = true;

                    ProcessRunner.runProcess(
                            androidEnvironment.getAdbExecutable(),
                            "-s",
                            deviceID,
                            "shell",
                            "content",
                            "insert",
                            "--uri",
                            "content://settings/system",
                            "--bind",
                            "name:s:user_rotation",
                            "--bind",
                            "value:i:0");

                    return new Message.MessageBuilder("/emulator/interaction")
                            .withParameter("rotation", "portrait")
                            .build();
                }
            } else if ("release_emulator".equals(request.getParameter("type"))) {
                return releaseEmulator(request);
            } else if ("allocate_emulator".equals(request.getParameter("type"))) {
                return allocateEmulator(request);
            } else if ("take_screenshot".equals(request.getParameter("type"))) {
                return takeScreenshot(request);
            }
        }
        throw new IllegalArgumentException("Message request with subject: "
                + request.getSubject() + " can't be handled by EmulatorInteractionEndpoint!");
    }

    private Message takeScreenshot(Message request) {

        Log.println("Taking screenshot...");

        var deviceID = request.getParameter("deviceId");
        var packageName = request.getParameter("packageName");
        var nodeID = request.getParameter("nodeId");

        Device device = Device.devices.get(deviceID);
        imageHandler.takeScreenshot(device, packageName, nodeID);

        return new Message.MessageBuilder("/emulator/interaction").build();
    }

    private Message releaseEmulator(Message request) {

        Log.println("Releasing emulator...");

        var deviceId = request.getParameter("deviceId");
        Device device = Device.devices.get(deviceId);

        return new Message.MessageBuilder("/emulator/interaction")
                .withParameter("response", device.releaseDevice())
                .build();
    }

    private Message allocateEmulator(Message request) {

        Log.println("Allocating emulator...");

        var packageName = request.getParameter("packageName");

        return new Message.MessageBuilder("/emulator/interaction")
                .withParameter("emulator", Device.allocateDevice(packageName, androidEnvironment))
                .build();
    }
}
