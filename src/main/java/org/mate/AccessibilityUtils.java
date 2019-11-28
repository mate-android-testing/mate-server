package org.mate;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Vector;

/**
 * Created by marceloeler on 04/07/17.
 */
public class AccessibilityUtils {

    public static BufferedImage image=null;
    public static String lastImagePath="";

    public static double  getContrastRatio(String imagePath,int x1, int y1, int x2, int y2){
        String path = imagePath;
        if (!imagePath.equals(lastImagePath)){
            if (image!=null)
                image.flush();
            image=null;

            File file= new File(path);
            try {
                System.out.println("load img: " + path);
                image = ImageIO.read(file);
            } catch (IOException e) {
                System.out.println("image not found: "+path);
                image = null;
            }
        }
        lastImagePath = imagePath;

        double contrastRatio = 21;
        if (image!=null) {
            contrastRatio = ColorUtils.calculateContrastRatioForAreaOtsu(image, x1, y1, x2, y2);

        }
        else
            System.out.println("img: null");


        if (contrastRatio<4.5){
            for (int x = x1; x<(x2-x1); x++){
                for (int y=y1; y<(y2-y1); y++){
                    image.setRGB(x,y, Color.RED.getRGB());
                }
            }
        }

        return contrastRatio;
    }

    public static String getLuminance(String imagePath,int x1, int y1, int x2, int y2){
        String path = imagePath;
        if (!imagePath.equals(lastImagePath)){
            if (image!=null)
                image.flush();
            image=null;

            File file= new File(path);
            try {
                System.out.println("load img: " + path);
                image = ImageIO.read(file);
            } catch (IOException e) {
                System.out.println("image not found: "+path);
                image = null;
            }
        }
        lastImagePath = imagePath;

        String luminance = "0,0";
        if (image!=null) {
            luminance = ColorUtils.calculateLuminance(image, x1, y1, x2, y2);
        }
        else
            System.out.println("img: null");

        return luminance;
    }
}
