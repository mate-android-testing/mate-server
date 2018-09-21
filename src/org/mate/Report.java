package org.mate;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfWriter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;

/**
 * Created by marceloeler on 14/09/18.
 */
public class Report {
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
}
