package com.gongyo.photo;
import java.util.List;
/** File OCR decisions independent of Android's native recognition task. */
public final class OcrReadPolicy {
 private OcrReadPolicy(){}
 public static int sampleSize(int width,int height){int sample=1;while(Math.max(width,height)/sample>2048)sample*=2;return sample;}
 public static int[] rotationAngles(){return new int[]{0};}
 public static String selectText(List<String> candidates){return candidates.isEmpty()?"":candidates.get(0);}
}
