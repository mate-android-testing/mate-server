package org.mate.endpoints;

import org.mate.util.AndroidEnvironment;
import org.mate.io.Device;
import org.mate.io.ProcessRunner;
import org.mate.message.Message;
import org.mate.network.Endpoint;
import org.mate.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CoverageEndpoint implements Endpoint {
    private final AndroidEnvironment androidEnvironment;

    public CoverageEndpoint(AndroidEnvironment androidEnvironment) {
        this.androidEnvironment = androidEnvironment;
    }

    @Override
    public Message handle(Message request) {
        if (request.getSubject().startsWith("/coverage/store")) {
            storeCoverageData(request);
        }
        return null;
    }

    private void storeCoverageData(Message request) {
        var deviceId = request.getParameter("deviceId");
        var packageName = Device.getDevice(deviceId).getPackageName();
        var chromosome = request.getParameter("chromosome");
        var entity = request.getParameter("entity");
        var coverageDir = Paths.get(packageName + ".coverage").resolve(chromosome);
        coverageDir.toFile().mkdirs();
        Path coverageFile;
        if (entity == null) {
            try {
                coverageFile = Files.createTempFile(coverageDir, null, null);
            } catch (IOException e) {
                Log.printError("Failed to create coverage file: " + e.toString() + "\n" + e.fillInStackTrace());
                return;
            }
        } else {
            coverageFile = coverageDir.resolve(entity);
        }

        //Close app in order to start coverage write to internal app storage
        ProcessRunner.runProcess(androidEnvironment.getAaptExecutable(), "-s", deviceId, "shell", "input", "keyevent", "3");
        //Start app to restore original state of the emulator
        ProcessRunner.runProcess(androidEnvironment.getAdbExecutable(), "-s", deviceId, "shell", "monkey", "-p", packageName, "1");
        //Extract coverage from internal app storage to local coverage file
        if (!ProcessRunner.runProcess(coverageFile, androidEnvironment.getAdbExecutable(), "-s", deviceId, "exec-out", "run-as", packageName, "cat", "files/coverage.exec")) {
            Log.printError("Failed to extract coverage file from internal app storage");
        }
    }
}
