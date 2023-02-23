package org.mate;

import org.mate.accessibility.ImageHandler;
import org.mate.endpoints.*;
import org.mate.io.Device;
import org.mate.network.Endpoint;
import org.mate.network.Router;
import org.mate.network.message.Message;
import org.mate.network.message.Messages;
import org.mate.network.message.serialization.Parser;
import org.mate.network.message.serialization.Serializer;
import org.mate.pdf.Report;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;

public class Server {
    private static final String MATE_SERVER_PROPERTIES_PATH = "mate-server.properties";

    private final Router router;
    private int port;
    private boolean cleanup;
    private Path resultsPath;
    private final Log logger;
    private CloseEndpoint closeEndpoint;

    // path to the apps directory
    private Path appsDir;

    private AndroidEnvironment androidEnvironment;
    private ImageHandler imageHandler;

    public static void main(String[] args) {
        Server server = new Server();
        server.loadConfig();
        server.init();
        server.run();
    }

    /**
     * Initialize server with default values and register a logger
     */
    public Server() {
        router = new Router();
        port = 12345;
        cleanup = true;
        // TODO: store results within app directory, e.g. apps/com.zola.bmi/results/
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
            properties.load(new FileReader(MATE_SERVER_PROPERTIES_PATH));
        } catch (IOException e) {
            Log.printWarning("failed to load " + MATE_SERVER_PROPERTIES_PATH + " file: " + e.getLocalizedMessage());
        }

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
        imageHandler = new ImageHandler(androidEnvironment, appsDir);
        router.add("/legacy", new LegacyEndpoint(androidEnvironment, imageHandler));
        closeEndpoint = new CloseEndpoint();
        router.add("/close", closeEndpoint);
        router.add("/crash", new CrashEndpoint(androidEnvironment));
        router.add("/properties", new PropertiesEndpoint());
        router.add("/emulator/interaction", new EmulatorInteractionEndpoint(androidEnvironment, imageHandler));
        router.add("/android", new AndroidEndpoint(androidEnvironment));
        router.add("/accessibility", new AccessibilityEndpoint(imageHandler));
        router.add("/coverage", new CoverageEndpoint(androidEnvironment, resultsPath, appsDir));
        router.add("/fuzzer", new FuzzerEndpoint(androidEnvironment));
        router.add("/utility", new UtilityEndpoint(androidEnvironment, resultsPath, appsDir));
        router.add("/fitness", new FitnessEndpoint(androidEnvironment, resultsPath, appsDir));
        router.add("/graph", new GraphEndpoint(androidEnvironment, resultsPath, appsDir));

        cleanup();
        createFolders();
    }

    /**
     * Delete old results folder if cleanup is {@code true}.
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
     * Start listening on configured {@code port} for incoming requests.
     */
    public void run() {

        final var executorService = Executors.newCachedThreadPool();

        try (final ServerSocket server = new ServerSocket(port)) {
            if (port == 0) {
                // Don't remove this log, it is read by mate-commander.
                System.out.println(server.getLocalPort());
            }

            logger.doLog();
            Device.loadActiveDevices(androidEnvironment);
            Device.appsDir = appsDir;

            while (true) {
                Device.listActiveDevices();
                Log.println("waiting for connection");
                final var client = server.accept();
                executorService.submit(() -> handleConnection(client));
            }
        } catch (Exception e) {
            Log.println("Unexpected exception:", e);
            Device.listDevices(androidEnvironment);
        } finally {
            executorService.shutdownNow();
        }
    }

    /**
     * Handles an incoming connection.
     *
     * @param client The client socket.
     */
    private void handleConnection(final Socket client) {

        Log.println("accepted connection");

        try (final var in = Channels.newInputStream(Channels.newChannel(client.getInputStream()));
             // Closing the output stream inherently closes the associated socket, see the docs.
             final var out = client.getOutputStream()) {

            final Parser messageParser = new Parser(in);

            while (!closeEndpoint.isClosed()) {
                var request = messageParser.nextMessage();
                Messages.verifyMetadata(request);
                Messages.stripMetadata(request);
                Log.println("Request: " + request.getSubject());

                Endpoint endpoint = router.resolve(request.getSubject());
                Message response;
                if (endpoint == null) {
                    response = Messages.unknownEndpoint(request.getSubject());
                } else {
                    try {
                        response = endpoint.handle(request);
                    } catch (Exception e) {
                        Log.println("Unexpected exception during handling request: ", e);
                        Device.listDevices(androidEnvironment);
                        /*
                         * If MATE-Server fails to process the request, the only viable option is to send back an error
                         * message, which in turn should quit MATE's execution. It doesn't make sense to close the
                         * socket and ask MATE to re-send the request.
                         */
                        response = Messages.errorMessage(e.getMessage());
                    }
                }
                if (response == null) {
                    response = Messages.unhandledMessage(request.getSubject());
                }
                Messages.addMetadata(response);

                try {
                    out.write(Serializer.serialize(response));
                    out.flush();
                } catch (Exception e) {
                    Log.println("Can't send response:" + e);
                    Device.listDevices(androidEnvironment);
                    /*
                     * If we can't send the response, we should close the socket, which in turn should lead to an
                     * IOException/EOF on MATE's side. This in turn will be transformed to a lexing failure, which is
                     * caught and the request is sent again on a new socket.
                     */
                    break;
                }
            }
        } catch (final IOException e) {
            /*
             * If we encounter an IOException the socket is broken. We close the connection, which in turn should lead
             * to an IOException/EOF on MATE's side. This in turn will be transformed to a lexing failure, which is
             * caught and the request is sent again on a new socket.
             */
            Log.println("IOException during handling request:" + e);
            Device.listDevices(androidEnvironment);
        } catch (final Throwable e) {
            Log.println("Unexpected exception: " + e);
            throw e;
        } finally {
            Log.println("connection closed");
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

        Report.reportDir = csvsDir.getPath() + File.pathSeparator;
    }
}
