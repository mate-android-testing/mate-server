package org.mate;

import org.mate.accessibility.ImageHandler;
import org.mate.endpoints.*;
import org.mate.io.ProcessRunner;
import org.mate.io.Device;
import org.mate.message.Message;
import org.mate.message.serialization.Parser;
import org.mate.message.serialization.Serializer;
import org.mate.network.Endpoint;
import org.mate.network.Router;
import org.mate.pdf.Report;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

public class Server {
    private static final String METADATA_PREFIX = "__meta__";
    private static final String MESSAGE_PROTOCOL_VERSION = "1.2";
    private static final String MESSAGE_PROTOCOL_VERSION_KEY = "version";

    public static boolean failed;

    private Router router;
    public static String emuName = null;

    public static void main(String[] args) {
        //read arguments and set default values otherwise

        long timeout = 30;//in minutes
        long length = 1000;
        int port = 12345;
        String emuName = null;
        if (args.length > 0) {
            port = Integer.valueOf(args[0]);
        }
        new Server().run(timeout, length, port, emuName);
    }

    public void run(long timeout, long length, int port, String emuName) {
        router = new Router();
        router.add("/legacy", new LegacyEndpoint(timeout, length));
        CloseEndpoint closeEndpoint = new CloseEndpoint();
        router.add("/close", closeEndpoint);
        router.add("/crash", new CrashEndpoint());
        router.add("/properties", new PropertiesEndpoint());
        router.add("/accessibility",new AccessibilityEndpoint());

        Server.emuName = emuName;

        createFolders();

        //Check OS (windows or linux)
        boolean isWin = false;
        String os = System.getProperty("os.name");
        if (os != null && os.startsWith("Windows"))
            isWin = true;
        ProcessRunner.isWin = isWin;


        //ProcessRunner.runProcess(isWin, "rm *.png");
        try {


            ServerSocket server = null;
            boolean connected = false;

            while (!connected) {
                try {
                    server = new ServerSocket(port);
                    connected = true;
                }
                catch(Exception e){
                    port++;
                }

            }

            System.out.println("Port: " + port);


            Socket client;

            Device.loadActiveDevices();


            while (true) {
                closeEndpoint.reset();
                Device.listActiveDevices();
                System.out.println("Waiting for connection (" + new Date().toGMTString() + ")");
                client = server.accept();
                System.out.println("Accepted connection (" + new Date().toGMTString() + ")");

                OutputStream out = client.getOutputStream();
                Parser messageParser = new Parser(client.getInputStream());

                try {
                    Message request;
                    int cont = 0;
                    failed = false;
                    while (!closeEndpoint.isClosed() && !failed) {
                        //Device.listActiveDevices();
                        //System.out.println("Waiting new message on port "+port);
                        Message response;
                        try {
                            request = messageParser.nextMessage();

                            cont++;
                            if (cont%175==0)
                                System.out.println(".");
                            else
                                System.out.print(".");

                            verifyMetadata(request);
                            stripMetadata(request);
                            Endpoint endpoint = router.resolve(request.getSubject());
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
                        catch(Exception e){
                            e.printStackTrace();
                            failed = true;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                client.close();
                System.out.println("Connection closed (" + new Date().toGMTString() + ")");
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
            System.out.println(
                    "WARNING: Message protocol version used by MATE-Server ("
                            + MESSAGE_PROTOCOL_VERSION
                            + ") does not match with the version used by MATE ("
                            + protocolVersion
                            + ")");
        }
    }

    private void createFolders() {
        String workingDir = System.getProperty("user.dir");
        // System.out.println(workingDir);
        workingDir = "/home/marceloeler/mate-uso/sast2020/results";
        try {
            new File(workingDir+"/csvs").mkdir();
        } catch(Exception e){

        }

        try {
            new File(workingDir+"/pictures").mkdir();
        } catch(Exception e){
        }

        ImageHandler.screenShotDir = workingDir+"/pictures/";
        Report.reportDir = workingDir+"/csvs/";
        Report.generalReportDir = workingDir;
    }

}
