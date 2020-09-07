package org.mate.endpoints;

import org.mate.io.ProcessRunner;
import org.mate.message.Message;
import org.mate.network.Endpoint;

public class EmulatorInteractionEndpoint implements Endpoint {
    private boolean disabledAutoRotate = false;
    private boolean isInPortraitMode = true;

    @Override
    public Message handle(Message request) {
        if (request.getSubject().startsWith("/emulator/interaction")) {
            if ("rotation".equals(request.getParameter("type"))) {

                String deviceID = request.getParameter("deviceId");

                if (!disabledAutoRotate) {

                    ProcessRunner.runProcess(
                            "adb",
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
                                "adb",
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
                                "adb",
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
                            "adb",
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
            }
        }
        return null;
    }
}
