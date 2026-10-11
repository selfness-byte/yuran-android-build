package com.gongyo.photo;
import java.util.List;
/** Bounded file OCR and conservative selection; never merge quantities across passes. */
public final class OcrReadPolicy {
 private OcrReadPolicy(){}
 public static int sampleSize(int width,int height){
  if(width<=0||height<=0)throw new IllegalArgumentException("Invalid image dimensions");
  int sample=1;
  while(true){
   long w=(width+(long)sample-1)/sample,h=(height+(long)sample-1)/sample;
   if(Math.max(w,h)<=3072&&w*h<=4000000L)return sample;
   sample*=2;
  }
 }
 public static int[] rotationAngles(){return new int[]{0,90,180,270};}
 public static String selectText(List<String> candidates){
  String selected="",first="";int best=-1;boolean different=false;
  for(String candidate:candidates){
   if(candidate==null||candidate.trim().isEmpty())continue;
   // A newline can distinguish a split size/count from a genuine original line.
   String normalized=IntakeText.normalize(candidate).replace("\r\n","\n").trim();
   if(first.isEmpty())first=normalized;else if(!first.equals(normalized))different=true;
   IntakeText.Result parsed=IntakeText.parse(candidate);
   int score=(parsed.ready?100:0)+(parsed.sku.isEmpty()?0:10)+(parsed.color.isEmpty()?0:5);
   for(long count:parsed.quantities)if(count>0)score++;
   if(score>best){selected=candidate;best=score;}
  }
  return different?IntakeText.OCR_REVIEW_MARKER+"\n"+selected:selected;
 }
}
