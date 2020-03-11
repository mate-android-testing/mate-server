package org.mate.accessibility;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Created by marceloeler on 04/07/17.
 */
public class AccessibilityUtils {

    public static BufferedImage image=null;
    public static String lastImagePath="";

    public static double  getContrastRatio(String imagePath,int x1, int y1, int x2, int y2){
        System.out.println("get contrast ratio");
        String path = imagePath;
        if (true || !imagePath.equals(lastImagePath)){
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
            System.out.println("here");
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


    public static double  matchesSurroundingColor(String imagePath,int x1, int y1, int x2, int y2){
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

        double matches = ColorUtils.matchesSurroundingColor(image, x1, y1, x2, y2);

        return matches;
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

    public static boolean checkFlickering(String targetFolder, String imgPath){

        for (int i=0; i<50; i++){
            String img = targetFolder+"/"+imgPath.replace(".png","_flicker_"+i+".png");
            File file= new File(img);
            try {
                //System.out.println("load img: " + img);
                image = ImageIO.read(file);
            } catch (IOException e) {
                //System.out.println("image not found: "+img);
                image = null;
            }

            int[] histogram = CalculateHist(image);

            int sum = 0;
            for (int h=0; h<histogram.length; h++){
                sum+=histogram[h];
                //System.out.print(histogram[h]+" ");
            }
            System.out.println(sum);
        }

        return false;
    }

    public static int[] CalculateHist(BufferedImage img) {
        int k;
        int pixel[];
        //array represents the intecity values of the pixels
        int levels[] = new int[256];
        for (int i = 0; i < img.getWidth(); i++) {
            for (int j = 0; j < img.getHeight(); j++) {
                pixel = img.getRaster().getPixel(i, j, new int[4]);
                //increase if same pixel appears
                levels[pixel[0]]++;
            }
        }

        return levels;
    }




    public void histEqualize(BufferedImage img) {
        //call CalculateHist method to get the histogram
        int[] h = CalculateHist(img);
        //calculate total number of pixel
        int mass = img.getWidth() * img.getHeight();
        int k = 0;
        long sum = 0;
        int pixel[];
        //calculate the scale factor
        float scale = (float) 255.0 / mass;
        //calculte cdf
        for (int x = 0; x < h.length; x++) {
            sum += h[x];
            int value = (int) (scale * sum);
            if (value > 255) {
                value = 255;
            }
            h[x] = value;
        }
        for (int i = 0; i < img.getWidth(); i++) {
            for (int j = 0; j < img.getHeight(); j++) {
                pixel = img.getRaster().getPixel(i, j, new int[3]);
                //set the new value
                k = h[pixel[0]];
                Color color = new Color(k, k, k);
                int rgb = color.getRGB();
                img.setRGB(i, j, rgb);
            }
        }
    }
}
