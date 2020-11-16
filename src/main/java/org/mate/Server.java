package org.mate;

import org.mate.accessibility.ImageHandler;
import org.mate.endpoints.*;
import org.mate.util.AndroidEnvironment;
import org.mate.io.Device;
import org.mate.network.message.Message;
import org.mate.network.message.Messages;
import org.mate.network.message.serialization.Parser;
import org.mate.network.message.serialization.Serializer;
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
    private static final String MATE_SERVER_PROPERTIES_PATH = "mate-server.properties";

    private final Router router;
    private long timeout;
    private long length;
    private int port;
    private boolean cleanup;
    private Path resultsPath;
    private final Log logger;
    private CloseEndpoint closeEndpoint;

    // path to the apps directory
    private Path appsDir;

    public static String emuName = null;
    private AndroidEnvironment androidEnvironment;
    private ImageHandler imageHandler;

    public static void main(String[] args) {
        Server server = new Server();

        server.loadConfig();
        if (args.length > 0) {
            // Read configuration from commandline arguments for backwards compatibility
            server.timeout = Long.parseLong(args[0]);

            if (args.length > 1) {
                server.length = Long.parseLong(args[1]);
            }
            if (args.length > 2) {
                server.port = Integer.parseInt(args[2]);
            }
            if (args.length > 3) {
                emuName = args[3];
            }
        }
        server.init();
        server.run();
    }

    /**
     * Initialize server with default values and register a logger
     */
    public Server() {
        router = new Router();
        timeout = 5;
        length = 1000;
        port = 12345;
        cleanup = true;
        resultsPath = Path.of("results");
        appsDir = Path.of("apps");
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
            Log.printWarning("failed to load " + MATE_SERVER_PROPERTIES_PATH + " file: " + e.getLocalizedMessage());
        }

        timeout = Optional.ofNullable(properties.getProperty("timeout")).map(Long::valueOf).orElse(timeout);
        length = Optional.ofNullable(properties.getProperty("length")).map(Long::valueOf).orElse(length);
        port = Optional.ofNullable(properties.getProperty("port")).map(Integer::valueOf).orElse(port);
        cleanup = Optional.ofNullable(properties.getProperty("cleanup")).map(Boolean::valueOf).orElse(cleanup);
        resultsPath = Optional.ofNullable(properties.getProperty("results_path")).map(Paths::get).orElse(resultsPath);
        appsDir = Optional.ofNullable(properties.getProperty("apps_dir")).map(Paths::get).orElse(appsDir);
    }

    /**
     * Setup the endpoints, cleanup old results and setup the needed directories
     */
    public void init() {
        androidEnvironment = new AndroidEnvironment();
        imageHandler = new ImageHandler(androidEnvironment);
        router.add("/legacy", new LegacyEndpoint(timeout, length, androidEnvironment, imageHandler));
        closeEndpoint = new CloseEndpoint();
        router.add("/close", closeEndpoint);
        router.add("/crash", new CrashEndpoint(androidEnvironment));
        router.add("/properties", new PropertiesEndpoint());
        router.add("/emulator/interaction", new EmulatorInteractionEndpoint(androidEnvironment));
        router.add("/android", new AndroidEndpoint(androidEnvironment));
        router.add("/accessibility",new AccessibilityEndpoint(imageHandler));
        router.add("/coverage", new CoverageEndpoint(androidEnvironment, resultsPath, appsDir));
        router.add("/fuzzer", new FuzzerEndpoint(androidEnvironment));
        router.add("/utility", new UtilityEndpoint(androidEnvironment, resultsPath, appsDir));
        router.add("/graph", new GraphEndpoint(androidEnvironment, appsDir));

        cleanup();
        createFolders();
    }

    /**
     * Delete old results folder if cleanup is {@code true}
     */
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

    /**
     * Start listening on configured {@code port} and pass incoming messages to router
     */
    public void run() {
        //ProcessRunner.runProcess(isWin, "rm *.png");
        try {
            ServerSocket server = new ServerSocket(port);
            if (port == 0) {
                System.out.println(server.getLocalPort());
            }
            logger.doLog();
            Socket client;

            Device.loadActiveDevices(androidEnvironment);
            Device.appsDir = appsDir;

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
                        Messages.verifyMetadata(request);
                        Messages.stripMetadata(request);
                        Log.println("Request: " + request.getSubject());
                        Endpoint endpoint = router.resolve(request.getSubject());
                        Message response;
                        if (endpoint == null) {
                            response = Messages.unknownEndpoint(request.getSubject());
                        } else {
                            response = endpoint.handle(request);
                        }
                        if (response == null) {
                            response = Messages.unhandledMessage(request.getSubject());
                        }
                        Messages.addMetadata(response);
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

    /**
     * Create directories for saving (intermediate) results
     */
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
