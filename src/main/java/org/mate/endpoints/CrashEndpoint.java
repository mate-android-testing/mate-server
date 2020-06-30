package org.mate.endpoints;

import org.mate.io.ProcessRunner;
import org.mate.util.AndroidEnvironment;
import org.mate.io.Device;
import org.mate.network.message.Message;
import org.mate.network.Endpoint;

import java.util.List;

public class CrashEndpoint implements Endpoint {
    private final AndroidEnvironment androidEnvironment;

    public CrashEndpoint(AndroidEnvironment androidEnvironment) {
        this.androidEnvironment = androidEnvironment;
    }

    @Override
    public Message handle(Message request) {
        if (request.getSubject().startsWith("/crash/stacktrace")) {
            return new Message.MessageBuilder("/crash/stacktrace")
                    .withParameter("stacktrace", getLatestCrashStackTrace(request.getParameter("deviceId")))
                    .build();
        }
        return null;
    }

    private String getLatestCrashStackTrace(String deviceID) {
        List<String> response = ProcessRunner.runProcess(
                androidEnvironment.getAdbExecutable(),
                "-s",
                deviceID,
                "exec-out",
                "run-as",
                Device.getDevice(deviceID).getPackageName(),
                "logcat",
                "-b",
                "crash",
                "-t",
                "2000",
                "AndroidRuntime:E",
                "*:S").getOk();

        for (int i = response.size() - 1; i >= 0; i--) {
            if (response.get(i).contains("E AndroidRuntime: FATAL EXCEPTION: ")) {
                return String.join("\n", response.subList(i, response.size()));
            }
        }

        return null;
    }
}
