package org.mate.endpoints;

import org.mate.accessibility.ImageHandler;
import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
            } else if ("mark_on_screenshot".equals(request.getParameter("type"))) {
                return markOnScreenshots(request);
            }
        }
        throw new IllegalArgumentException("Message request with subject: "
                + request.getSubject() + " can't be handled by EmulatorInteractionEndpoint!");
    }

    private Message takeScreenshot(Message request) {

        Log.println("Taking screenshot...");

        var deviceID = request.getParameter("deviceId");
        var nodeID = request.getParameter("nodeId");

        Device device = Device.devices.get(deviceID);
        device.takeScreenshot(nodeID);

        return new Message.MessageBuilder("/emulator/interaction").build();
    }

    // TODO: Is this functionality relevant?
    private Message markOnScreenshots(Message request) {

        final var packageName = request.getParameter("packageName");
        final var stateId = request.getParameter("state");

        final List<Rectangle> rectangles = Arrays.stream(request.getParameter("rectangles").split(";"))
                .map(recString -> {
                    String[] parts = recString.split(",");
                    int x1 = Integer.parseInt(parts[0]);
                    int y1 = Integer.parseInt(parts[1]);
                    int x2 = Integer.parseInt(parts[2]);
                    int y2 = Integer.parseInt(parts[3]);

                    return new Rectangle(x1, y1, x2 - x1, y2 - y1);
                }).collect(Collectors.toList());

        try {
            // TODO: Avoid ImageHandler if possible and use Device instead.
            imageHandler.markImage(rectangles, stateId, packageName);
        } catch (IOException e) {
            // TODO: Prepend custom error message.
            throw new UncheckedIOException(e);
        }

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
