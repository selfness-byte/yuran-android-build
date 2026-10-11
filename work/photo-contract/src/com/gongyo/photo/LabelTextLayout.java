package com.gongyo.photo;

import java.util.*;

/** OCR line layout shared by the Android adapter and platform-independent regression checks. */
public final class LabelTextLayout {
 public static final class Line {
  public final String text;
  public final int left,top,right,bottom;
  public final boolean bounded;
  public Line(String text){this(text,0,0,0,0,false);}
  public Line(String text,int left,int top,int right,int bottom){this(text,left,top,right,bottom,true);}
  private Line(String text,int left,int top,int right,int bottom,boolean bounded){
   this.text=text;this.left=left;this.top=top;this.right=right;this.bottom=bottom;this.bounded=bounded;
  }
 }
 private LabelTextLayout(){}
 /** Keep each original OCR line separate: proximity is not quantity evidence. */
 public static String intakeText(List<Line> lines){
  StringBuilder out=new StringBuilder();
  for(Line line:lines){
   if(out.length()>0)out.append('\n');
   out.append(line.text);
  }
  return out.toString();
 }
}
