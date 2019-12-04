package org.mate;

import org.mate.accessibility.ImageHandler;
import org.mate.endpoints.CloseEndpoint;
import org.mate.endpoints.CrashEndpoint;
import org.mate.endpoints.LegacyEndpoint;
import org.mate.io.ADB;
import org.mate.io.Device;
import org.mate.message.Message;
import org.mate.message.serialization.Parser;
import org.mate.message.serialization.Serializer;
import org.mate.network.Router;
import org.mate.pdf.Report;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

public class Server {
    private Router router;
    public static String emuName = null;

    public static void main(String[] args) {
        //read arguments and set default values otherwise
        long timeout = 5;
        long length = 1000;
        int port = 12345;
        String emuName = null;
        if (args.length > 0) {
            timeout = Long.valueOf(args[0]);
        }
        if (args.length > 1) {
            length = Long.valueOf(args[1]);
        }
        if (args.length > 2) {
            port = Integer.valueOf(args[2]);
        }
        if (args.length > 3) {
            emuName = args[3];
        }

        new Server().run(timeout, length, port, emuName);
    }

    public void run(long timeout, long length, int port, String emuName) {
        router = new Router();
        router.add("/legacy", new LegacyEndpoint(timeout, length));
        CloseEndpoint closeEndpoint = new CloseEndpoint();
        router.add("/close", closeEndpoint);
        router.add("/crash", new CrashEndpoint());

        Server.emuName = emuName;

        createFolders();

        //Check OS (windows or linux)
        boolean isWin = false;
        String os = System.getProperty("os.name");
        if (os != null && os.startsWith("Windows"))
            isWin = true;
        ADB.isWin = isWin;


        //ProcessRunner.runProcess(isWin, "rm *.png");
        try {
            ServerSocket server = new ServerSocket(port);
            if (port == 0) {
                System.out.println(server.getLocalPort());
            }
            Socket client;

            Device.loadActiveDevices();

            while (true) {
                closeEndpoint.reset();
                Device.listActiveDevices();
                System.out.println("Waiting for connection (" + new Date().toGMTString() + ")");
                client = server.accept();
                System.out.println("Accepted connection (" + new Date().toGMTString() + ")");

                Parser messageParser = new Parser(client.getInputStream());

                try {
                    Message request;
                    while (!closeEndpoint.isClosed()) {
                        request = messageParser.nextMessage();
                        Message response = router.resolve(request.getSubject()).handle(request);
                        replyMessage(response, client.getOutputStream());
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


    private void replyMessage(Message response, OutputStream out) throws IOException {
        out.write(Serializer.serialize(response));
        out.flush();
    }

    private void createFolders() {
        String workingDir = System.getProperty("user.dir");
        // System.out.println(workingDir);
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
    }

}
