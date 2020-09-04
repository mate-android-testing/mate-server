package org.mate.accessibility;

import Catalano.Imaging.FastBitmap;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Hashtable;

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
        System.out.println("size: " + fb.getWidth()+" vs " + fb.getHeight());
        int index = 0;
        for (int y=0; y<y2-y1; y++) {
            for (int x = 0; x < x2 - x1; x++) {
                grayScaleValues[index] = (byte)fb.getGray(y, x);
                index++;
            }
        }
        System.out.println("lenght grayscalevalues: " + grayScaleValues.length);
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
                //System.out.print (y);
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
        System.out.println("Freqhigh: " + freqHigh.size());
        System.out.println("FreqLow: " + freqLow.size());
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

        //System.out.println("chigh: " + cHigh.getRed()+"-"+cHigh.getGreen()+" " + cHigh.getBlue());
        buff.flush();

        String chighStr = cHigh.getRed()+":"+cHigh.getGreen()+":"+cHigh.getBlue()+":"+rgbToHex(cHigh.getRed(),cHigh.getGreen(),cHigh.getBlue())+":"+getHue(cHigh.getRed(),cHigh.getGreen(),cHigh.getBlue());
        String clowStr = cLow.getRed()+":"+cLow.getGreen()+":"+cLow.getBlue()+":"+rgbToHex(cLow.getRed(),cLow.getGreen(),cLow.getBlue())+":"+getHue(cLow.getRed(),cLow.getGreen(),cLow.getBlue());

        return clowStr+","+chighStr+"#"+maxLow+","+maxHigh;
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
            //System.out.println("zero");
            return 0;
        }

        float hue = 0f;
        if (max == red) {
            hue = (green - blue) / (max - min);
            //System.out.println("g-b: " + (green-blue));
            //System.out.println("red: "+ hue);

        } else if (max == green) {
            hue = 2f + (blue - red) / (max - min);

        } else {
            hue = 4f + (red - green) / (max - min);
        }

        hue = hue * 60;
        if (hue < 0) hue = hue + 360;

        //System.out.println("hue: " + hue);
        return Math.round(hue);
    }



    public static double getContrastRatio(double l1, double l2){
        return (l1+0.05)/(l2+0.05);
    }

    public static double matchesSurroundingColor(BufferedImage image,int x1, int y1, int x2, int y2){

        //System.out.println(x1+","+y1+","+x2+","+y2);

        if (x2-x1<=0||y2-y1<=0) {
            return 0;
        }
        if (x1<0||x2<0||y1<0||y1<0) {
            return 0;
        }
        BufferedImage buff = null;
        FastBitmap fb = null;
        //System.out.println(x1 + " " + y1 + " " + x2 + " "+y2);

        int modx2=x2+2;
        int mody1=y1-2;
        int mody2=y2+2;
        int modx1=x1-2;

        if (x1<=2)
            modx1=0;
        if (x2+2>=image.getWidth()){
            modx2 = image.getWidth()-1;
        }
        if (y1<=2){
            mody1=0;
        }
        if (y2+2>=image.getHeight()){
            mody2=image.getHeight()-1;
        }

        try{
            buff = image.getSubimage(modx1, mody1, modx2 - modx1, mody2 - mody1);
            fb = new FastBitmap(buff);
            fb.toGrayscale();
        }
        catch(Exception e){
            e.printStackTrace();
            return 0;
        }

        byte[] grayScaleValues = new byte[fb.getHeight()*fb.getWidth()];
        int index = 0;
        for (int y=0; y<mody2-mody1; y++) {
            for (int x = 0; x < modx2 - modx1; x++) {
                grayScaleValues[index] = (byte)fb.getGray(y, x);
                index++;
            }
        }

        //iterate through the X axis

        int difx1 = x1-modx1;
        int difx2 = modx2-x2;
        int dify1 = y1-mody1;
        int dify2 = mody2-y2;

        int relX1 = difx1;
        int relX2 = fb.getWidth()-difx2-1;
        int relY1 = dify1;
        int relY2 = fb.getHeight()-dify2-1;

        byte surroundingColorA = 0;
        byte surroundingColorB = 0;

        int matchCount = 0;
        try {
            for (int y=relY1; y<relY2; y++) {

                byte borderColor = (byte) fb.getGray(y, relX1);

                if (difx1>=1) {
                    surroundingColorA = (byte) fb.getGray(y, relX1 - 1);
                    if (borderColor == surroundingColorA) {
                        matchCount += 1;
                    }

                    if (difx1>=2) {
                        surroundingColorB = (byte) fb.getGray(y, relX1 - 2);
                        if (borderColor == surroundingColorB) {
                            matchCount += 1;
                        }
                    }
                }


                if (difx2>=1) {
                    borderColor = (byte) fb.getGray(y, relX2);
                    surroundingColorA = (byte) fb.getGray(y, relX2 + 1);
                    if (borderColor == surroundingColorA) {
                        matchCount += 1;
                    }

                    if (difx2>=2) {
                        surroundingColorB = (byte) fb.getGray(y, relX2 + 2);
                        if (borderColor == surroundingColorB) {
                            matchCount += 1;
                        }
                    }
                }

            }
        }
        catch(Exception e) {
            e.printStackTrace();
        }


        int currentX=0;
        //iterate through the Y axis
        try {
            for (int x=relX1; x<relX2; x++) {
                currentX = x;
                //System.out.print(x+", ");

                byte borderColor = (byte) fb.getGray(relY1,x);

                if (dify1>=1) {
                    surroundingColorA = (byte) fb.getGray(relY1 - 1, x);
                    if (borderColor == surroundingColorA) {
                        matchCount += 1;
                    }

                    if (dify1>=2) {
                        surroundingColorB = (byte) fb.getGray(relY1 - 2, x);
                        if (borderColor == surroundingColorB) {
                            matchCount += 1;
                        }
                    }
                }

                if (dify2>=1) {
                    borderColor = (byte) fb.getGray(relY2, x);
                    surroundingColorA = (byte) fb.getGray(relY2 + 1, x);
                    if (borderColor == surroundingColorA) {
                        matchCount += 1;
                    }

                    if (dify2>=2) {
                        surroundingColorB = (byte) fb.getGray(relY2 + 2, x);
                        if (borderColor == surroundingColorB) {
                            matchCount += 1;
                        }
                    }
                }
            }
        }
        catch(Exception e) {
            e.printStackTrace();
            //System.out.println("x: "+ currentX + ", y2: " + relY2 + ", y1: " +relY1 + ", relX2: " + relX2);
            //System.out.println("width: " + fb.getWidth() + "  height: " + fb.getHeight());
        }

        int sidesSum = 2*(fb.getWidth()-6)+2*(fb.getHeight()-6);
        return (double) matchCount/(2*sidesSum);
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

