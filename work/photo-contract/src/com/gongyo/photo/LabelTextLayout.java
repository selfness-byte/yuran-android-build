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
 public static String intakeText(List<Line> lines){
  Map<Integer,Integer> pairs=new HashMap<>();
  Set<Integer> claimed=new HashSet<>();
  for(int i=0;i<lines.size();i++){
   Line a=lines.get(i);String size=a.text.trim().toUpperCase(Locale.ROOT);
   if(!size.matches("S|M|L|XL")||!a.bounded)continue;
   int choice=-1;
   for(int j=0;j<lines.size();j++){
    Line b=lines.get(j);
    if(i==j||!b.text.trim().matches("[0-9]{1,6}")||!b.bounded)continue;
    int overlap=Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top);
    if(b.left<a.right||b.left-a.right>Math.max(a.bottom-a.top,b.bottom-b.top)*3||overlap*2<Math.min(a.bottom-a.top,b.bottom-b.top))continue;
    if(choice>=0){choice=-2;break;}choice=j;
   }
   if(choice>=0&&!claimed.contains(choice)){pairs.put(i,choice);claimed.add(choice);}
  }
  StringBuilder out=new StringBuilder();
  for(int i=0;i<lines.size();i++){if(claimed.contains(i))continue;if(out.length()>0)out.append('\n');out.append(lines.get(i).text);Integer count=pairs.get(i);if(count!=null)out.append(' ').append(lines.get(count).text);}
  return out.toString();
 }
}
