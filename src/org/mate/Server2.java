package org.mate;

import com.itextpdf.text.*;
import com.itextpdf.text.Font;
import com.itextpdf.text.Image;
import com.itextpdf.text.pdf.PdfWriter;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.List;

/**
 * Created by marceloeler on 14/03/17.
 */
public class Server2 {

    public static boolean isWin;
    public static boolean generatePDFReport;


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

    public static void main(String[] args) throws DocumentException {
        isWin = false;
        generatePDFReport = false;

        ADB.isWin=isWin;

        String os = System.getProperty("os.name");
        if (os!=null && os.startsWith("Windows"))
            isWin = true;


        long timeout = 1;
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

                System.out.println(cmdStr);
                String response = "";

                if (cmdStr.contains("getActivity")){
                    String parts[] = cmdStr.split(":");
                    String emulatorID = parts[1];
                    response = ADB.getCurrentActivity(emulatorID);
                }


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

                if(cmdStr.contains("mark-image") && generatePDFReport) {
                    try {
                        System.out.println(cmdStr);
                        String[] parts = cmdStr.split(":");
                        String imageName = parts[1];
                        int x = Integer.parseInt(parts[2].split("-")[1]);
                        int y = Integer.parseInt(parts[3].split("-")[1]);
                        int width = Integer.parseInt(parts[4].split("-")[1]);
                        int height = Integer.parseInt(parts[5].split("-")[1]);

                        String fileName = imageName.split("_")[0] + imageName.split("_")[1] + ".txt";

                        Writer output;
                        output = new BufferedWriter(new FileWriter(fileName, true));

                        output.append(imageName)
                                .append(",")
                                .append(parts[6])
                                .append(",")
                                .append(parts[7])
                                .append("\n");

                        output.close();

                        BufferedImage img = ImageIO.read(new File(imageName));
                        Graphics2D g2d = img.createGraphics();
                        g2d.setColor(Color.RED);
                        g2d.setStroke(new BasicStroke(5));
                        g2d.drawRect(x, y, width, height);
                        ImageIO.write(img, "PNG", new File(imageName));
                        g2d.dispose();
                    }catch (Exception e){
                        System.out.println("EXCEPTION --->" + e.getMessage());
                    }
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

//                //get results
//                if (cmdStr.contains("dumpsys activity activities")) {
//                    response = "unkonwn";
//                    if (result != null && result.size() > 0)
//                        response = result.get(0);
//                    System.out.println("activity: " + response);
//                }

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

                if(cmdStr.contains("FINISH") && generatePDFReport) {

                    System.out.println(cmdStr);
                    String name = cmdStr.split("_")[1];



                    Document document = new Document();
                    PdfWriter pdfWriter = PdfWriter.getInstance(document, new FileOutputStream(name + ".pdf"));

                    pdfWriter.setStrictImageSequence(true);
                    document.open();

                    BufferedReader b = new BufferedReader(new FileReader(new File(name + ".txt")));
                    String readLine = "";
                    while ((readLine = b.readLine()) != null) {

                        Font font = FontFactory.getFont(FontFactory.COURIER, 16, BaseColor.BLACK);
                        Chunk chunk = new Chunk(readLine.split(",")[1] + ": " + readLine.split(",")[2], font);

                        document.add(chunk);


                        document.add(new Paragraph(""));

                        Image img = Image.getInstance(readLine.split(",")[0]);
                        img.scalePercent(25);
                        document.add(img);
                        document.add(new Paragraph(""));
                        document.add(new Paragraph(""));
                        document.add(new Paragraph(""));
                    }


                    document.close();


                }

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
