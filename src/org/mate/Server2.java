package org.mate;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

/**
 * Created by marceloeler on 14/03/17.
 */
public class Server2 {

    public static boolean isWin;


    public static String getEmulator(Hashtable<String,Boolean> emulatorsAllocated, String packageName){
        for (String key: emulatorsAllocated.keySet()){
            String cmd = "adb -s " + key + " shell ps " + packageName;
            List<String> result = ProcessRunner.runProcess(isWin,cmd);
            for (String res: result){
                System.out.println(res);
                if (res.contains(packageName))
                    return key;
            }
        }
        return "";
    }

    public static void main(String[] args) {
        isWin = false;
        String os = System.getProperty("os.name");
        if (os!=null && os.startsWith("Windows"))
            isWin = true;


        long timeout = 5;
        long length = 1000;
        if (args!=null && args.length==2) {
            String timeoutstr = args[0];
            timeout = Long.valueOf(timeoutstr);
            String lenghStr = args[1];
            length = Long.valueOf(lenghStr);

        }
        String screeShotsDir = "";

        Hashtable<String,Boolean> emulatorsAllocated = new Hashtable<String,Boolean>();
        Hashtable<String,String> emulatorsPackage = new Hashtable<String,String>();
        String getDevices = "adb devices";
        List<String> resultDevices = ProcessRunner.runProcess(isWin, getDevices);

        int count = 0;
        for (String res:resultDevices){
            String em="";
            if (res.contains("device") && !res.contains("attached")){
                em = res.replace("device","");
                em = em.replace(" ","");
                if (em.length()>13)
                    em = em.substring(0,em.length()-1);
                emulatorsAllocated.put(em,Boolean.FALSE);
                System.out.println(++count + " : " + em);
            }
        }

        //ProcessRunner.runProcess(isWin, "rm *.png");
        try {
            ServerSocket server = new ServerSocket(12345, 5000);
            Socket client = null;
            while (true) {

                for (String key: emulatorsAllocated.keySet()){
                    String pck = emulatorsPackage.get(key);
                    if (pck==null)
                        pck="";
                    System.out.println(key+":"+emulatorsAllocated.get(key) + " - " + pck);
                }

                System.out.println("ACCEPT: " + new Date().toGMTString());
                client = server.accept();

                Scanner cmd = new Scanner(client.getInputStream());
                String cmdStr = cmd.nextLine();

                String response = "";


                if (cmdStr.contains("getEmulator")){


                    String parts[] = cmdStr.split(":");
                    String packageName = parts[1];

                    response = getEmulator(emulatorsAllocated,packageName);
                    emulatorsAllocated.put(response, Boolean.TRUE);
                    emulatorsPackage.put(response,packageName);

                    if (response.equals("")){
                        int i=0;
                        boolean emulatorFound = false;
                        Enumeration<String> keys = emulatorsAllocated.keys();
                        while (keys.hasMoreElements() && !emulatorFound){
                            String key = keys.nextElement();
                            boolean allocated = emulatorsAllocated.get(key);
                            if (!allocated){
                                response = key;
                                emulatorFound=true;
                                emulatorsAllocated.put(response, Boolean.TRUE);
                                emulatorsPackage.put(response,packageName);
                                System.out.println("found: " + response);
                            }
                        }
                        if (!emulatorFound)
                            response="";
                    }
                    //response = "4f60d1bb";
                    System.out.println("get emulator: " + response);
                }

                if (cmdStr.contains("releaseEmulator")){
                    response = "";
                    String[] parts = cmdStr.split(":");
                    if (parts!=null){
                        if (parts.length>0) {
                            String emulatorToRelease = parts[1];
                            if (emulatorToRelease.contains("emulator")) {
                                emulatorsAllocated.put(emulatorToRelease, Boolean.FALSE);
                                emulatorsPackage.put(emulatorToRelease, "");
                                response = "released";
                            }
                        }
                    }

                }

                //format commands
                if (cmdStr.contains("screenshot")) {
                    String[] parts = cmdStr.split(":");
                    String emulator = parts[1];

                    int index = parts[2].lastIndexOf("_");
                    //String packageName = parts[1].substring(0,index-1);
                    cmdStr = "adb -s " + emulator+" shell screencap -p /sdcard/" + parts[2] + " && adb -s "+ parts[1] + " pull /sdcard/" + parts[2];
                    System.out.println(cmdStr);
                }

                if (cmdStr.contains("contrastratio")) {
                    try {
                        System.out.println(cmdStr);
                        String[] parts = cmdStr.split(":");
                        String packageName = parts[1];
                        String stateId = parts[2];
                        String coord = parts[3];

                        String[] positions = coord.split(",");
                        int x1 = Integer.valueOf(positions[0]);
                        int y1 = Integer.valueOf(positions[1]);
                        int x2 = Integer.valueOf(positions[2]);
                        int y2 = Integer.valueOf(positions[3]);

                        String fileName = screeShotsDir + packageName + "_" + stateId + ".png";
                        System.out.println(fileName);
                        System.out.println(coord);
                        double contrastRatio = AccessibilityUtils.getContrastRatio(fileName, x1, y1, x2, y2);
                        System.out.println("contrast ratio: " + contrastRatio);
                        response = String.valueOf(contrastRatio);
                    } catch (Exception e) {
                        System.out.println("PROBLEMS CALCULATING CONTRAST RATIO");
                        response = "21";
                    }
                    cmdStr = "";

                }

                if (cmdStr.contains("rm emulator")) {
                    cmdStr="do not delete pngs";
                }

                //execute commands
                //result = null;
                System.out.println(cmdStr);

                List<String> result = ProcessRunner.runProcess(isWin, cmdStr);

                //get results
                if (cmdStr.contains("dumpsys activity activities")) {
                    response = "unkonwn";
                    if (result != null && result.size() > 0)
                        response = result.get(0);
                    System.out.println("activity: " + response);
                }

                if (cmdStr.contains("density")) {
                    response = "0";
                    if (result != null && result.size() > 0)
                        response = result.get(0).replace("Physical density: ", "");
                    System.out.println("Density: " + response);
                }

                if (cmdStr.contains("clear")) {
                    response = "clear";
                    System.out.println("clear: app data deleted");
                }

                if (cmdStr.contains("rm -rf")) {
                    response = "delete";
                    System.out.println("pngs deleted");
                }

                if (cmdStr.contains("screencap")) {
                    response = "screenshot";
                }

                if (cmdStr.contains("timeout"))
                    response = String.valueOf(timeout);

                if (cmdStr.contains("randomlength"))
                    response = String.valueOf(length);


                PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                out.println(response);
                out.close();


                client.close();
                cmd.close();
            }


        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }
}
