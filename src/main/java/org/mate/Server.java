package org.mate;

import org.mate.accessibility.ImageHandler;
import org.mate.endpoints.*;
import org.mate.io.Device;
import org.mate.message.Message;
import org.mate.message.serialization.Parser;
import org.mate.message.serialization.Serializer;
import org.mate.network.Endpoint;
import org.mate.network.Router;
import org.mate.pdf.Report;
import org.mate.util.Log;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class Server {
    private static final String METADATA_PREFIX = "__meta__";
    private static final String MESSAGE_PROTOCOL_VERSION = "1.2";
    private static final String MESSAGE_PROTOCOL_VERSION_KEY = "version";
    private static final String MATE_SERVER_PROPERTIES_PATH = "mate-server.properties";

    private Router router;
    private long timeout;
    private long length;
    private int port;
    private boolean cleanup;
    private Path resultsPath;
    private Log logger;
    private CloseEndpoint closeEndpoint;

    public static String emuName = null;

    public static void main(String[] args) {
        Server server = new Server();

        server.loadConfig();
        if (args.length > 0) {
            // Read configuration from commandline arguments for backwards compatibility
            server.timeout = Long.valueOf(args[0]);

            if (args.length > 1) {
                server.length = Long.valueOf(args[1]);
            }
            if (args.length > 2) {
                server.port = Integer.valueOf(args[2]);
            }
            if (args.length > 3) {
                emuName = args[3];
            }
        }
        server.init();
        server.run();
    }

    public Server() {
        router = new Router();
        timeout = 5;
        length = 1000;
        port = 12345;
        cleanup = true;
        resultsPath = Paths.get("results");
        logger = new Log();
        logger.doNotLog();
        Log.registerLogger(logger);
    }

    /**
     * Load Server configuration from mate-server.properties
     */
    public void loadConfig() {
        Properties properties = new Properties();
        try {
            properties.load(new FileReader(new File(MATE_SERVER_PROPERTIES_PATH)));
        } catch (IOException e) {
            Log.println("WARNING: failed to load " + MATE_SERVER_PROPERTIES_PATH + " file: " + e.getLocalizedMessage());
        }

        timeout = Optional.ofNullable(properties.getProperty("timeout")).map(Long::valueOf).orElse(timeout);
        length = Optional.ofNullable(properties.getProperty("length")).map(Long::valueOf).orElse(length);
        port = Optional.ofNullable(properties.getProperty("port")).map(Integer::valueOf).orElse(port);
        cleanup = Optional.ofNullable(properties.getProperty("cleanup")).map(Boolean::valueOf).orElse(cleanup);
        resultsPath = Optional.ofNullable(properties.getProperty("results_path")).map(Paths::get).orElse(resultsPath);
    }

    /**
     * Setup the endpoints, cleanup old results and setup the needed directories
     */
    public void init() {
        router.add("/legacy", new LegacyEndpoint(timeout, length));
        closeEndpoint = new CloseEndpoint();
        router.add("/close", closeEndpoint);
        router.add("/crash", new CrashEndpoint());
        router.add("/properties", new PropertiesEndpoint());
        router.add("/accessibility",new AccessibilityEndpoint());

        cleanup();
        createFolders();
    }

    private void cleanup() {
        if (!cleanup || !Files.exists(resultsPath)) {
            return;
        }

        try {
            Files.walk(resultsPath)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            if (Files.exists(resultsPath)) {
                Log.printError("unable to delete old results dir (" + resultsPath + ") during cleanup");
            }
        } catch (IOException e) {
            Log.printError("unable to delete old results dir (" + resultsPath + ") during cleanup: " + e.getLocalizedMessage());
        }
    }

    public void run() {
        //ProcessRunner.runProcess(isWin, "rm *.png");
        try {
            ServerSocket server = new ServerSocket(port);
            if (port == 0) {
                System.out.println(server.getLocalPort());
            }
            logger.doLog();
            Socket client;

            Device.loadActiveDevices();

            while (true) {
                closeEndpoint.reset();
                Device.listActiveDevices();
                Log.println("waiting for connection");
                client = server.accept();
                Log.println("accepted connection");

                OutputStream out = client.getOutputStream();
                Parser messageParser = new Parser(client.getInputStream());

                try {
                    Message request;
                    while (!closeEndpoint.isClosed()) {
                        request = messageParser.nextMessage();
                        verifyMetadata(request);
                        stripMetadata(request);
                        Endpoint endpoint = router.resolve(request.getSubject());
                        Message response;
                        if (endpoint == null) {
                            response = new Message.MessageBuilder("/error")
                                    .withParameter("info", "Endpoint for message with subject \""
                                            + request.getSubject()
                                            + "\" not registered in MATE-Server. Maybe you are "
                                            + "using an outdated version of the server?")
                                    .build();
                        } else {
                            response = endpoint.handle(request);
                        }
                        addMetadata(response);
                        out.write(Serializer.serialize(response));
                        out.flush();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                client.close();
                Log.println("connection closed");
            }

        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    private void addMetadata(Message message) {
        message.addParameter(
                METADATA_PREFIX + MESSAGE_PROTOCOL_VERSION_KEY, MESSAGE_PROTOCOL_VERSION);
    }

    private void stripMetadata(Message message) {
        List<String> metadataKeys = new ArrayList<>();
        Map<String, String> parameters = message.getParameters();
        for (String parameterKey: parameters.keySet()) {
            if (parameterKey.startsWith(METADATA_PREFIX)) {
                metadataKeys.add(parameterKey);
            }
        }
        for (String metadataKey : metadataKeys) {
            parameters.remove(metadataKey);
        }
    }

    private void verifyMetadata(Message message) {
        String protocolVersion = message.getParameter(
                METADATA_PREFIX + MESSAGE_PROTOCOL_VERSION_KEY);
        if (!protocolVersion.equals(MESSAGE_PROTOCOL_VERSION)) {
            Log.println(
                    "WARNING: Message protocol version used by MATE-Server ("
                            + MESSAGE_PROTOCOL_VERSION
                            + ") does not match with the version used by MATE ("
                            + protocolVersion
                            + ")");
        }
    }

    private void createFolders() {
        File resultsDir = resultsPath.toFile();
        if (!resultsDir.mkdirs() && !resultsDir.isDirectory()) {
            Log.printError("unable to create new results dir (" + resultsDir.getPath() + ")");
        }
        File csvsDir = resultsPath.resolve("csvs").toFile();
        if (!csvsDir.mkdirs() && !csvsDir.isDirectory()) {
            Log.printError("unable to create csvs dir (" + csvsDir.getPath() + ")");
        }
        File picturesDir = resultsPath.resolve("pictures").toFile();
        if (!picturesDir.mkdirs() && !picturesDir.isDirectory()) {
            Log.printError("unable to create pictures dir (" + picturesDir.getPath() + ")");
        }

        Report.reportDir = csvsDir.getPath() + "/";
        ImageHandler.screenShotDir = picturesDir.getPath() + "/";
    }
}
