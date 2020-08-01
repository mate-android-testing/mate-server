package org.mate.pdf;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfWriter;
import org.mate.Server;
import org.mate.accessibility.ImageHandler;
import org.mate.io.Device;

import java.io.*;
import java.util.HashMap;

public class Report {

    public static String generalReportDir;

    {
        uniqueStateIds = new HashMap<String,String>();
    }

    public static HashMap<String, String> uniqueStateIds = null;

    public static String reportDir;

    public static void generateReport(String cmdStr) throws Exception{

        //System.out.println(cmdStr);
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

    public static void generalReport(String cmdStr){
        System.out.println("GENERAL REPORT: " +  cmdStr);
        String parts[] = cmdStr.split("@");
        String deviceId = parts[1];
        String header = parts[2];
        String values = parts[3];

        String reportFile = generalReportDir+"/generalResults.csv";
        System.out.println(reportFile);
        File tempFile = new File( reportFile);
        boolean exists = tempFile.exists();

        FileWriter fw = null;
        try {
            fw = new FileWriter(reportFile, true);
            BufferedWriter bw = new BufferedWriter(fw);
            PrintWriter out = new PrintWriter(bw);
            if (!exists) {
                out.println("emulator,"+header);
            }
            out.println(deviceId+","+values);
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void actionMeasuresReport(String cmdStr){
        //System.out.println("MEASURES: " +  cmdStr);
        String parts[] = cmdStr.split("%");
        String deviceId = parts[1];
        String header = parts[2];
        String values = parts[3];

        String reportFile = generalReportDir+"/actionMeasures.csv";
        //System.out.println(reportFile);
        File tempFile = new File( reportFile);
        boolean exists = tempFile.exists();

        FileWriter fw = null;
        try {
            fw = new FileWriter(reportFile, true);
            BufferedWriter bw = new BufferedWriter(fw);
            PrintWriter out = new PrintWriter(bw);
            if (!exists) {
                out.println(header);
            }
            out.println(values);
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String addFlaw(String cmdStr){

        //System.out.println ("add flaw::");
        String date = new java.util.Date().toGMTString();

        //System.out.println(cmdStr);

        if (cmdStr.contains("%") && (cmdStr.split("%").length>3)){
            actionMeasuresReport(cmdStr);
        }
        else
        if (cmdStr.contains("@") && (cmdStr.split("@").length>3)){
            generalReport(cmdStr);
        }
        else {


            String parts[] = cmdStr.split(":");
            //  for (int i=0; i<parts.length; i++)
            //  System.out.println(i+ " - " + parts[i]);

            //System.out.println(parts);
            String deviceID = parts[1];
            String packageName = parts[2];

            if (packageName.contains("com.google.android.apps.nexuslauncher"))
                Server.failed=true;

            Report.createCSVFolder(deviceID,packageName);
            ImageHandler.createPicturesFolder(deviceID,packageName);

            String sessionID = parts[3];
            String activityName = parts[4];
            String screenId = parts[5];
            String flawType = parts[6];
            String widgetType = parts[7];
            String widgetId = parts[8];
            String widgetText = parts[9];
            String extraInfo = parts[10];
            String x1 = parts[11];
            String y1 = parts[12];
            String x2 = parts[13];
            String y2 = parts[14];


            String flawDetails = "";
            flawDetails += date + ",";
            flawDetails += packageName + ",";
            flawDetails += sessionID + ",";
            flawDetails += activityName + ",";
            flawDetails += screenId + ",";
            flawDetails += flawType + ",";
            flawDetails += widgetType + ",";
            flawDetails += widgetId + ",";
            flawDetails += widgetText + ",";
            flawDetails += extraInfo + ",";
            flawDetails += x1 + ",";
            flawDetails += y1 + ",";
            flawDetails += x2 + ",";
            flawDetails += y2 + ",";


            flawDetails += ImageHandler.currentScreenShotLocation.get(deviceID) + ",";


            //   System.out.println(flawDetails);
            int ix1 = Integer.valueOf(x1);
            int ix2 = Integer.valueOf(x2);
            int iy1 = Integer.valueOf(y1);
            int iy2 = Integer.valueOf(y2);


            //String newImagePath = ImageHandler.markImage(device.getCurrentScreenShotLocation(), ix1, iy1, ix2, iy2, flawType);
            String newImagePath = "not used";

            flawDetails += newImagePath;

            String reportFile = reportDir + packageName + "/" + deviceID + "-" + packageName + "-" + sessionID + ".csv";


            Report.createHeader(deviceID, packageName, sessionID);

            FileWriter fw = null;
            try {
                fw = new FileWriter(reportFile, true);
                BufferedWriter bw = new BufferedWriter(fw);
                PrintWriter out = new PrintWriter(bw);
                out.println(flawDetails);
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return "ok";
    }

    public static void createHeader(String deviceID,String packageName, String sessionID) {

        String reportFile = reportDir+packageName+"/"+deviceID+"-"+packageName+"-"+sessionID+".csv";
        File tempFile = new File( reportFile);
        boolean exists = tempFile.exists();
        if (exists)
            return;


        FileWriter fw = null;
        try {
            fw = new FileWriter(reportFile, true);
            BufferedWriter bw = new BufferedWriter(fw);
            PrintWriter out = new PrintWriter(bw);
            out.println("Date,App,SessionID,activity,screen,flaw,widget_type,widget_id,widget_text,extra-info,x1,y1,x2,y2,imgpath,marked_imgpath");
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void createCSVFolder(String deviceID, String packageName) {
        try {
            new File(reportDir+packageName).mkdir();
            //System.out.println("created: " + reportDir+packageName+"/");
        } catch(Exception e){
        }

    }
}
