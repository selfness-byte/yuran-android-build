package com.gongyo.photo;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Conservative, offline label fields. Clothing notation is never an intake quantity. */
public final class IntakeText {
 public static final String OCR_REVIEW_MARKER="[YURAN_OCR_REVIEW_REQUIRED]";
 public static final Pattern SKU=Pattern.compile("(?:품\\s*번|品番|货号|貨號|款号|款號|(?<![A-Z0-9])(?:SKU|STYLE|ITEM|CODE)(?:\\s*NO\\.?)?)[ \\t]*[:：#=.-]?[ \\t]*([A-Z0-9][A-Z0-9._/-]{2,79})(?![A-Z0-9._/-])");
 public static final Pattern COLOR=Pattern.compile("(?:색\\s*상|颜色|顏色|色号|色號|(?<![A-Z0-9])(?:COLOR|COLOUR))[ \\t]*[:：#=.-]?[ \\t]*([\\p{L}\\p{N}_-]+(?:[ \\t]+(?!(?:SIZE|SKU|STYLE|ITEM|CODE|COLOR|COLOUR|PRICE|QTY|QUANTITY|호\\s*칭|사이즈|품\\s*번|색\\s*상|판매가격|尺码|尺碼|号型|號型|尺寸|品番|货号|貨號|款号|款號|颜色|顏色|色号|色號)(?![A-Z0-9]))[\\p{L}\\p{N}_-]+){0,5})");
 public static final Pattern GARMENT_SIZE=Pattern.compile("(?:호\\s*칭|사이즈|尺码|尺碼|号型|號型|尺寸|(?<![A-Z0-9])SIZE)[ \\t]*[:：#=.-]?[ \\t]*([0-9]{2,3}[ \\t]*\\([ \\t]*(?:XXXL|XXL|XL|XS|S|M|L)[ \\t]*\\)|(?:[0-9]{1,3})?(?:XXXL|XXL|XL|XS|S|M|L)|[0-9]{2,3})(?![A-Z0-9])");
 private static final Pattern STYLE=Pattern.compile("(?<![A-Z0-9])EY[0-9]UTS[0-9]{3,6}(?:R[0-9]{1,3})?(?![A-Z0-9])");
 private static final Pattern COUNTS=Pattern.compile("(XL|S|M|L)[ \\t]*[:X×=]?[ \\t]*([0-9]{1,6})(?![0-9])");
 private static final Pattern VISIBLE_COUNT=Pattern.compile("(?<![A-Z0-9])(?:XL|S|M|L)(?:[ \\t]*[:X×=]|[ \\t]*[-+]?[0-9]|[ \\t]*[?？]|[ \\t]*[OIL][0-9OIL]*(?![A-Z0-9])|[ \\t]*$)");
 private static final Pattern KNOWN_COLOR=Pattern.compile("(?m)^[ \\t]*(WHITE|BLACK|IVORY|NAVY|BLUE|BEIGE|PINK|RED|GREEN|GREY|GRAY|MELANGE[ \\t]+(?:GREY|GRAY))[ \\t]*$");
 private IntakeText(){}
 public static final class Result {
  public final String sku,color,size,warning;
  public final long[] quantities;
  public final boolean ready;
  Result(String sku,String color,String size,long[] quantities,boolean ready,String warning){
   this.sku=sku;this.color=color;this.size=size;this.quantities=quantities.clone();this.ready=ready;this.warning=warning;
  }
 }
 public static String normalize(String text){return Normalizer.normalize(text==null?"":text,Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);}
 public static String unique(Pattern pattern,String text){Set<String> values=fields(pattern,text);return values.size()==1?values.iterator().next():"";}
 private static Set<String> fields(Pattern pattern,String text){Set<String> values=new LinkedHashSet<>();Matcher m=pattern.matcher(text);while(m.find())values.add(m.group(1).trim().replaceAll("[ \\t]+"," "));return values;}
 public static boolean isGarmentSpan(String text,int start,int end){Matcher m=GARMENT_SIZE.matcher(text);while(m.find())if(start<m.end(1)&&end>m.start(1))return true;return false;}
 private static boolean isLabelFieldSpan(String text,int start,int end){
  for(Pattern field:new Pattern[]{SKU,COLOR,GARMENT_SIZE}){Matcher m=field.matcher(text);while(m.find())if(start<m.end(1)&&end>m.start(1)&&(field!=COLOR||start==m.start(1)))return true;}
  return false;
 }
 public static Result parse(String input){
  String text=normalize(input);long[] counts=new long[4];boolean[] seen=new boolean[4];List<String> warnings=new ArrayList<>();
  if(text.length()>12000)return new Result("","","",counts,false,"읽힌 글자가 너무 많습니다.");
  if(text.contains(OCR_REVIEW_MARKER))warnings.add("여러 번 읽은 결과가 달라 사진 전체 확인 필요");
  Set<String> skus=fields(SKU,text);
  if(skus.isEmpty()){Matcher m=STYLE.matcher(text);while(m.find())skus.add(m.group());}
  Set<String> colors=fields(COLOR,text);
  if(colors.isEmpty())colors=fields(KNOWN_COLOR,text);
  boolean conflict=false,malformed=false;int countFields=0;
  for(String line:text.split("\\r?\\n")){
   String value=line.trim();
   if(value.matches("\\(?[ \\t]*(?:XL|S|M|L)(?:[ \\t]*[-/·][ \\t]*(?:XL|S|M|L))+[ \\t]*\\)?"))continue;
   Matcher m=COUNTS.matcher(value);List<String[]> row=new ArrayList<>();int end=0;boolean clean=true;
   while(m.find()){
    if(!value.substring(end,m.start()).trim().matches("[,;/·]*")){clean=false;break;}
    row.add(new String[]{m.group(1),m.group(2)});end=m.end();
   }
   if(!clean||row.isEmpty()||!value.substring(end).trim().matches("[,;/·]*")){
    if(value.matches("(?:XL|S|M|L)[ \\t]+.*"))malformed=true;
    Matcher visible=VISIBLE_COUNT.matcher(value);
    while(visible.find())if(!isLabelFieldSpan(value,visible.start(),visible.end()))malformed=true;
    continue;
   }
   for(String[] pair:row){int index=pair[0].equals("S")?0:pair[0].equals("M")?1:pair[0].equals("L")?2:3;long n=Long.parseLong(pair[1]);
    if(seen[index]&&counts[index]!=n)conflict=true;
    else {counts[index]=n;seen[index]=true;}countFields++;
   }
  }
  String sku=skus.size()==1?skus.iterator().next():"",color=colors.size()==1?colors.iterator().next():"";
  String size=unique(GARMENT_SIZE,text).replace(" ","");
  if(sku.isEmpty())warnings.add("품번 확인 필요");if(color.isEmpty())warnings.add("색상 확인 필요");
  if(malformed)warnings.add("읽힌 사이즈 수량의 형식을 확인해 주세요.");
  if(conflict)warnings.add("같은 사이즈의 수량이 서로 다름");
  long total=0;for(long n:counts)total+=n;
  if(countFields==0||total==0)warnings.add("입고 수량 확인 필요");
  if(sku.length()>80||color.length()>30)warnings.add("품번 또는 색상이 너무 김");
  return new Result(sku,color,size,counts,warnings.isEmpty(),String.join(" · ",warnings));
 }
}
