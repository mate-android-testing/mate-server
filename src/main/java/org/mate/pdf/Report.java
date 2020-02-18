package org.mate.pdf;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfWriter;
import org.mate.accessibility.ImageHandler;
import org.mate.io.Device;

import java.io.*;

public class Report {
    public static String reportDir;

    public static void generateReport(String cmdStr) throws Exception{

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

    public static String addFlaw(String cmdStr){

        //System.out.println ("add flaw::");

        System.out.println(cmdStr);

        String parts[] = cmdStr.split(":");
      //  for (int i=0; i<parts.length; i++)
          //  System.out.println(i+ " - " + parts[i]);
        System.out.println(parts);
        String deviceID = parts[1];
        String packageName = parts[2];
        String activityName = parts[3];
        String screenId = parts[4];
        String flawType = parts[5];
        String widgetType = parts[6];
        String widgetId = parts[7];
        String widgetText = parts[8];
        String extraInfo = parts[9];
        String x1 = parts[10];
        String y1 = parts[11];
        String x2 = parts[12];
        String y2 = parts[13];

        Device device = Device.getDevice(deviceID);

        String flawDetails = "";
        flawDetails += packageName+",";
        flawDetails += activityName+",";
        flawDetails += screenId+",";
        flawDetails += flawType+",";
        flawDetails += widgetType+",";
        flawDetails += widgetId+",";
        flawDetails += widgetText+",";
        flawDetails += extraInfo+",";
        flawDetails += x1+",";
        flawDetails += y1+",";
        flawDetails += x2+",";
        flawDetails += y2+",";


        flawDetails += device.getCurrentScreenShotLocation()+",";


     //   System.out.println(flawDetails);
        int ix1 = Integer.valueOf(x1);
        int ix2 = Integer.valueOf(x2);
        int iy1 = Integer.valueOf(y1);
        int iy2 = Integer.valueOf(y2);


        String newImagePath = ImageHandler.markImage(device.getCurrentScreenShotLocation(),ix1,iy1,ix2,iy2,flawType);

        flawDetails +=newImagePath;

        String reportFile = reportDir+deviceID+"-"+packageName+".csv";


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

        return "ok";
    }

    public static void createHeader(String deviceID,String packageName) {

        String reportFile = reportDir+deviceID+"-"+packageName+".csv";
        File tempFile = new File( reportFile);
        boolean exists = tempFile.exists();
        if (exists)
            return;


        FileWriter fw = null;
        try {
            fw = new FileWriter(reportFile, true);
            BufferedWriter bw = new BufferedWriter(fw);
            PrintWriter out = new PrintWriter(bw);
            out.println("App,activity,screen,flaw,widget_type,widget_id,widget_text,extra-info,x1,y1,x2,y2,imgpath,marked_imgpath");
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
