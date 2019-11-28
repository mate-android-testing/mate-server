package org.mate;

import Catalano.Imaging.FastBitmap;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Hashtable;

/**
 * Created by marceloeler on 16/02/17.
 */
public class ColorUtils {


    public static double getRelativeLuminance(Color color){
        double RsRGB = Math.abs((double)color.getRed()/255);
        double GsRGB = Math.abs((double)color.getGreen()/255);
        double BsRGB = Math.abs((double)color.getBlue()/255);


        double R, G, B;

        if (RsRGB <= 0.03928)
            R = RsRGB/12.92;
        else
            R = Math.pow(((RsRGB+0.055)/1.055),2.4);

        if (GsRGB <= 0.03928)
            G = GsRGB/12.92;
        else G = Math.pow(((GsRGB+0.055)/1.055), 2.4);

        if (BsRGB <= 0.03928)
            B = BsRGB/12.92;
        else B = Math.pow ( ((BsRGB+0.055)/1.055), 2.4);


        double L = 0.2126 * R + 0.7152 * G + 0.0722 * B;
        return L;
    }

    public static double calculateContrastRatioForAreaOtsu(BufferedImage image,int x1, int y1, int x2, int y2){
        if (x2-x1<=0||y2-y1<=0) {
            return 21;
        }
        if (x1<0||x2<0||y1<0||y1<0) {
            return 21;
        }
        BufferedImage buff = null;
        FastBitmap fb = null;
        //System.out.println(x1 + " " + y1 + " " + x2 + " "+y2);
        try{
            buff = image.getSubimage(x1, y1, x2 - x1, y2 - y1);
            fb = new FastBitmap(buff);
            fb.toGrayscale();

        }
        catch(Exception e){
            e.printStackTrace();
            return 21;
        }

        byte[] grayScaleValues = new byte[fb.getHeight()*fb.getWidth()];
        int index = 0;
        for (int y=0; y<y2-y1; y++) {
            for (int x = 0; x < x2 - x1; x++) {

                grayScaleValues[index] = (byte)fb.getGray(y, x);
                index++;
            }
        }

        OtsuThresholder otsu = new OtsuThresholder();
        int o = otsu.doThreshold(grayScaleValues,null);

        Hashtable<Integer,Integer> freqHigh = new Hashtable<Integer, Integer>();
        Hashtable<Integer,Integer> freqLow= new Hashtable<Integer, Integer>();

        try {
            int i=0;
            double genMean=0;
            int cont=0;
            int xcolor = x1;
            int ycolor = y1;
            for (int y=0; y<y2-y1; y++){
                ycolor = y1+y;
                //if (y1==63)
                  //  System.out.println();
                for (int x=0; x<x2-x1; x++){
                    xcolor = x1+x;
                    int grayValue = fb.getGray(y,x);
                    int rgbColor = image.getRGB(xcolor,ycolor);
                    //if (y1==63){
                      //  System.out.print(rgbColor+ " ");
                    //}
                    if (grayValue < o){
                        if (freqLow.get(rgbColor)==null){
                            freqLow.put(rgbColor,1);
                        }
                        else{
                            int freq = freqLow.get(rgbColor);
                            freq+=1;
                            freqLow.put(rgbColor,freq);
                        }
                    }
                    else {
                        if (freqHigh.get(rgbColor) == null) {
                            freqHigh.put(rgbColor, 1);
                        } else {
                            int freq = freqHigh.get(rgbColor);
                            freq += 1;
                            freqHigh.put(rgbColor, freq);
                        }
                    }
                }
            }
        }
        catch(Exception ex){

            ex.printStackTrace();
        }

        if (freqHigh.size()==0 || freqLow.size()==0)
            return 0;

        int maxHigh = 0;
        int colorHigh = 0;

        for (Integer key: freqHigh.keySet()){
            int freq = freqHigh.get(key);
            if (freq > maxHigh) {
                maxHigh=freq;
                colorHigh=key;
            }
        }

        int maxLow = 0;
        int colorLow=0;
        for (Integer key: freqLow.keySet()){
            int freq = freqLow.get(key);
            //  System.out.println(" lum: " + lum+ " - qtde: "+ freq);
            if (freq>maxLow){
                maxLow = freqLow.get(key);
                colorLow = key;
            }
        }

       // if (y1==63) {
         //   System.out.println(maxHigh);
           // System.out.println(maxLow);
        //}

        Color cHigh = new Color(colorHigh);
        Color cLow = new Color(colorLow);

        double lowLum = ColorUtils.getRelativeLuminance(cLow);
        double highLum = ColorUtils.getRelativeLuminance(cHigh);

        buff.flush();
        fb=null;
        buff=null;
        return ColorUtils.getContrastRatio(highLum, lowLum);
    }



    public static String calculateLuminance(BufferedImage image,int x1, int y1, int x2, int y2){
        if (x2-x1<=0||y2-y1<=0) {
            return "0,0";
        }
        if (x1<0||x2<0||y1<0||y1<0) {
            return "0,0";
        }
        BufferedImage buff = null;
        FastBitmap fb = null;
        //System.out.println(x1 + " " + y1 + " " + x2 + " "+y2);
        try{
            buff = image.getSubimage(x1, y1, x2 - x1, y2 - y1);
            fb = new FastBitmap(buff);
            fb.toGrayscale();

        }
        catch(Exception e){
            e.printStackTrace();
            return "0,0";
        }

        byte[] grayScaleValues = new byte[fb.getHeight()*fb.getWidth()];
        int index = 0;
        for (int y=0; y<y2-y1; y++) {
            for (int x = 0; x < x2 - x1; x++) {

                grayScaleValues[index] = (byte)fb.getGray(y, x);
                index++;
            }
        }

        OtsuThresholder otsu = new OtsuThresholder();
        int o = otsu.doThreshold(grayScaleValues,null);

        Hashtable<Integer,Integer> freqHigh = new Hashtable<Integer, Integer>();
        Hashtable<Integer,Integer> freqLow= new Hashtable<Integer, Integer>();

        try {
            int i=0;
            double genMean=0;
            int cont=0;
            int xcolor = x1;
            int ycolor = y1;
            for (int y=0; y<y2-y1; y++){
                ycolor = y1+y;
                //if (y1==63)
                //  System.out.println();
                for (int x=0; x<x2-x1; x++){
                    xcolor = x1+x;
                    int grayValue = fb.getGray(y,x);
                    int rgbColor = image.getRGB(xcolor,ycolor);
                    //if (y1==63){
                    //  System.out.print(rgbColor+ " ");
                    //}
                    if (grayValue < o){
                        if (freqLow.get(rgbColor)==null){
                            freqLow.put(rgbColor,1);
                        }
                        else{
                            int freq = freqLow.get(rgbColor);
                            freq+=1;
                            freqLow.put(rgbColor,freq);
                        }
                    }
                    else {
                        if (freqHigh.get(rgbColor) == null) {
                            freqHigh.put(rgbColor, 1);
                        } else {
                            int freq = freqHigh.get(rgbColor);
                            freq += 1;
                            freqHigh.put(rgbColor, freq);
                        }
                    }
                }
            }
        }
        catch(Exception ex){

            ex.printStackTrace();
        }

        if (freqHigh.size()==0 || freqLow.size()==0)
            return "0,0";

        int maxHigh = 0;
        int colorHigh = 0;

        for (Integer key: freqHigh.keySet()){
            int freq = freqHigh.get(key);
            if (freq > maxHigh) {
                maxHigh=freq;
                colorHigh=key;
            }
        }

        int maxLow = 0;
        int colorLow=0;
        for (Integer key: freqLow.keySet()){
            int freq = freqLow.get(key);
            //  System.out.println(" lum: " + lum+ " - qtde: "+ freq);
            if (freq>maxLow){
                maxLow = freqLow.get(key);
                colorLow = key;
            }
        }

        // if (y1==63) {
        //   System.out.println(maxHigh);
        // System.out.println(maxLow);
        //}

        Color cHigh = new Color(colorHigh);
        Color cLow = new Color(colorLow);

        System.out.println("chigh: " + cHigh.getRed()+"-"+cHigh.getGreen()+" " + cHigh.getBlue());
        buff.flush();

        String chighStr = maxHigh+"#"+cHigh.getRed()+":"+cHigh.getGreen()+":"+cHigh.getBlue()+":"+rgbToHex(cHigh.getRed(),cHigh.getGreen(),cHigh.getBlue())+":"+getHue(cHigh.getRed(),cHigh.getGreen(),cHigh.getBlue());
        String clowStr = maxLow+"#"+cLow.getRed()+":"+cLow.getGreen()+":"+cLow.getBlue()+":"+rgbToHex(cLow.getRed(),cLow.getGreen(),cLow.getBlue())+":"+getHue(cLow.getRed(),cLow.getGreen(),cLow.getBlue());

        return clowStr+","+chighStr;
    }

    private static String rgbToHex(int R,int G,int B) {
        return toHex(R)+toHex(G)+toHex(B);
    }
    private static String toHex(int n) {
        n = Math.max(0,Math.min(n,255));
        return String.valueOf("0123456789ABCDEF".charAt((n-n%16)/16))+ String.valueOf("0123456789ABCDEF".charAt(n%16));
    }

    public static int getHue(int redx, int greenx, int bluex) {
        float red = redx;
        float green = greenx;
        float blue = bluex;

        float min = Math.min(Math.min(red, green), blue);
        float max = Math.max(Math.max(red, green), blue);
        System.out.println(min + " x " + max);
        if (min == max) {
            System.out.println("zero");
            return 0;
        }

        float hue = 0f;
        if (max == red) {
            hue = (green - blue) / (max - min);
            System.out.println("g-b: " + (green-blue));
            System.out.println("red: "+ hue);

        } else if (max == green) {
            hue = 2f + (blue - red) / (max - min);

        } else {
            hue = 4f + (red - green) / (max - min);
        }

        hue = hue * 60;
        if (hue < 0) hue = hue + 360;

        System.out.println("hue: " + hue);
        return Math.round(hue);
    }



    public static double getContrastRatio(double l1, double l2){
        return (l1+0.05)/(l2+0.05);
    }
/*
    relative luminance
    the relative brightness of any point in a colorspace, normalized to 0 for darkest black and 1 for lightest white
    Note 1: For the sRGB colorspace, the relative luminance of a color is defined as L = 0.2126 * R + 0.7152 * G + 0.0722 * B where R, G and B are defined as:

            if RsRGB <= 0.03928 then R = RsRGB/12.92 else R = ((RsRGB+0.055)/1.055) ^ 2.4
            if GsRGB <= 0.03928 then G = GsRGB/12.92 else G = ((GsRGB+0.055)/1.055) ^ 2.4
            if BsRGB <= 0.03928 then B = BsRGB/12.92 else B = ((BsRGB+0.055)/1.055) ^ 2.4
    and RsRGB, GsRGB, and BsRGB are defined as:

    RsRGB = R8bit/255
    GsRGB = G8bit/255
    BsRGB = B8bit/255



    other: http://springmeier.org/www/contrastcalculator/index.php
    function adjustValue($val) {
// Parameter $val:
// Hexadecimal value of colour component (00-FF)

	$val = hexdec($val)/255;
	if ($val <= 0.03928) {
		$val = $val / 12.92;
	} else {
		$val = pow((($val + 0.055) / 1.055), 2.4);
	}
	return $val;
}

->  Luminance = (0.2126 × red) + (0.7152 × green) + (0.0722 × blue)


ratio

(L1 + 0.05) / (L2 + 0.05)
where L1 is the relative luminance of the lighter of the colors, and L2 is the relative luminance of the darker of the colors. Contrast ratios can range from 1 to 21 (commonly written 1:1 to 21:1). [w3.org] 21:1
*/

}
