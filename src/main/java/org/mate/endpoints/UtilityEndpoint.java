package org.mate.endpoints;

import java.nio.file.Path;
import org.mate.io.Device;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;

/**
 * Handles requests that can't be directly assigned a dedicated end point.
 * Should replace the {@link LegacyEndpoint} in the future.
 */
public class UtilityEndpoint implements Endpoint {

    private final AndroidEnvironment androidEnvironment;
    private final Path resultsPath;
    private final Path appsDir;

    public UtilityEndpoint(AndroidEnvironment androidEnvironment, Path resultsPath, Path appsDir) {
        this.androidEnvironment = androidEnvironment;
        this.resultsPath = resultsPath;
        this.appsDir = appsDir;
    }

    @Override
    public Message handle(Message request) {
        if (request.getSubject().startsWith("/utility/fetch_test_case")) {
            return fetchTestCase(request);
        }
        if (request.getSubject().startsWith("/utility/fetch_transition_system")) {
            return fetchTransitionSystem(request);
        }
        throw new IllegalArgumentException("Message request with subject: "
            + request.getSubject() + " can't be handled by UtilityEndpoint!");
    }

    /**
     * Fetches a serialized test case from the internal storage and removes
     * the test case afterwards to keep memory clean.
     *
     * @param request A message containing the device id, the test case directory
     *                and the name of the test case file.
     * @return Returns a message wrapping the outcome of the operation, i.e. success or failure.
     */
    private Message fetchTestCase(Message request) {

        String deviceID = request.getParameter("deviceId");
        String testCaseDir = request.getParameter("testcaseDir");
        String testCase = request.getParameter("testcase");

        Device device = Device.devices.get(deviceID);
        boolean response = device.fetchTestCase(testCaseDir, testCase);

        return new Message.MessageBuilder("/utility/fetch_test_case")
            .withParameter("response", String.valueOf(response))
            .build();
    }

    /**
     * Fetches a serialized transition system from the internal storage and removes the transition
     * system afterwards to keep memory clean.
     *
     * @param request A message containing the device id, the test case directory and the name of the
     *                test case file.
     * @return Returns a message wrapping the outcome of the operation, i.e. success or failure.
     */
    private Message fetchTransitionSystem(Message request) {

        String deviceID = request.getParameter("deviceId");
        String transitionSystemDir = request.getParameter("transitionSystemDir");
        String transitionSystemFile = request.getParameter("transitionSystemFile");

        Device device = Device.devices.get(deviceID);
        boolean response = device.fetchTransitionSystem(transitionSystemDir, transitionSystemFile);

        return new Message.MessageBuilder("/utility/fetch_transition_system")
            .withParameter("response", String.valueOf(response))
            .build();
    }

}
