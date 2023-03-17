package org.mate.endpoints;

import org.mate.io.Device;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

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
        } else if (request.getSubject().startsWith("/utility/fetch_espresso_test")) {
            return fetchEspressoTest(request);
        } else if (request.getSubject().startsWith("/utility/fetch_dot_graph")) {
            return fetchDotGraph(request);
        } else if (request.getSubject().startsWith("/utility/write_file")) {
            return writeContentToFile(request);
        } else if (request.getSubject().startsWith("/utility/let_user_pick")) {
            return letUserPickOption(request);
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
     * Lets the user pick an action from a dialog (manual exploration).
     *
     * @param request The request message.
     * @return Returns the response message included the selected action.
     */
    private Message letUserPickOption(Message request) {

        final int numberOfOptions = Integer.parseInt(request.getParameter("options"));
        final String[] options = new String[numberOfOptions];

        for (int i = 0; i < numberOfOptions; i++) {
            options[i] = request.getParameter("option_" + i);
        }

        return new Message.MessageBuilder("/utility/let_user_pick")
                .withParameter("picked_option", String.valueOf(letUserPickOption(options)))
                .build();
    }

    /**
     * Shows the user a dialog containing the applicable actions on the current screen.
     * 
     * @param options The applicable actions on the current screen.
     * @return Returns the selected option.
     */
    private int letUserPickOption(final String[] options) {

        final JDialog dialog = new JDialog((JFrame) null, "Select one",true);

        final int[] selected = new int[1];
        final JPanel panel = new JPanel();
        panel.setLayout(new GridLayout(options.length,1));
        final JButton[] buttons = new JButton[options.length];

        for (int i = 0; i < options.length; i++) {
            buttons[i] = new JButton(options[i]);
            int finalI = i;
            buttons[i].addActionListener(actionEvent -> {
                selected[0] = finalI;
                dialog.setVisible(false);
            });
            panel.add(buttons[i]);
        }

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        dialog.dispose();

        return selected[0];
    }

    /**
     * Writes the given content to a file.
     *
     * @param request The request message containing the content that should be written.
     * @return Returns a dummy response message indicating success of the operation.
     */
    private Message writeContentToFile(Message request) {

        final String deviceID = request.getParameter("deviceId");
        final String fileContent = request.getParameter("content");
        final String fileName = request.getParameter("fileName");

        Device device = Device.devices.get(deviceID);
        device.writeContentToFile(fileContent, fileName);

        return new Message.MessageBuilder("/utility/write_file").build();
    }

    /**
     * Fetches and removes an espresso test from the emulator.
     *
     * @param request The request message.
     * @return Returns a message wrapping the outcome of the operation, i.e. success or failure.
     */
    private Message fetchEspressoTest(Message request) {

        String deviceID = request.getParameter("deviceId");
        String espressoDir = request.getParameter("espressoDir");
        String testCase = request.getParameter("testcase");

        Device device = Device.devices.get(deviceID);
        boolean response = device.fetchEspressoTest(espressoDir, testCase);

        return new Message.MessageBuilder("/utility/fetch_espresso_test")
                .withParameter("response", String.valueOf(response))
                .build();
    }

    /**
     * Fetches and removes a dot file (model graph) from the emulator.
     *
     * @param request The request message.
     * @return A response message, with content about the success of the operation.
     */
    private Message fetchDotGraph(Message request) {

        String deviceID = request.getParameter("deviceId");
        String dirName = request.getParameter("dirName");
        String fileName = request.getParameter("fileName");

        Device device = Device.devices.get(deviceID);
        boolean response = device.fetchDotGraph(dirName, fileName);

        return new Message.MessageBuilder("/utility/fetch_dot_graph")
                .withParameter("response", String.valueOf(response))
                .build();
    }
}
