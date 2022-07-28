package org.mate.endpoints;

import org.mate.io.Device;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

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
        } else if (request.getSubject().startsWith("/utility/write_file")) {
            return writeFile(request);
        } else if (request.getSubject().startsWith("/utility/let_user_pick")) {
            return letUserPickOption(request);
        } else if (request.getSubject().startsWith("/utility/write_traces_diff_file")) {
            return writeTraceDiffFile(request);
        }
        throw new IllegalArgumentException("Message request with subject: "
                + request.getSubject() + " can't be handled by UtilityEndpoint!");
    }

    private Message writeFile(Message request) {
        String content = request.getParameter("content");
        String fileName = request.getParameter("fileName");

        try {
            Files.writeString(Path.of("./results/").resolve(fileName), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return new Message.MessageBuilder("/utility/write_file").build();
    }

    private Message writeTraceDiffFile(Message request) {
        String fileName = request.getParameter("fileName");
        Path tracesDir = appsDir.resolve(request.getParameter("packageName"))
                .resolve("traces");
        List<Set<String>> chromosomeTraces = Arrays.stream(request.getParameter("chromosomes").split("\\+"))
                .map(tracesDir::resolve)
                .map(f -> {
                    try {
                        return Files.readAllLines(f);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .map(HashSet::new)
                .collect(Collectors.toList());

        Set<String> seen = new HashSet<>();

        for (Set<String> traces : chromosomeTraces) {
            traces.removeAll(seen);
            seen.addAll(traces);
        }

        StringJoiner stringJoiner = new StringJoiner("\n");

        int pos = 0;
        for (Set<String> traces : chromosomeTraces) {
            stringJoiner.add("Chromosome " + pos);
            traces.forEach(stringJoiner::add);
            stringJoiner.add("");
            pos++;
        }

        try {
            Files.writeString(Path.of("./results/").resolve(fileName), stringJoiner.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return new Message.MessageBuilder(request.getSubject()).build();
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

    private Message letUserPickOption(Message request) {
        int numberOfOptions = Integer.parseInt(request.getParameter("options"));
        String[] options = new String[numberOfOptions];

        for (int i = 0; i < numberOfOptions; i++) {
            options[i] = request.getParameter("option_" + i);
        }

        return new Message.MessageBuilder("/utility/let_user_pick").withParameter("picked_option", String.valueOf(letUserPickOption(options))).build();
    }

    private int letUserPickOption(String[] options) {
        JDialog dialog = new JDialog((JFrame) null, "Select one",true);

        int[] selected = new int[1];
        JPanel panel = new JPanel();
        panel.setLayout(new GridLayout(options.length,1));
        JButton[] buttons = new JButton[options.length];
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
}
