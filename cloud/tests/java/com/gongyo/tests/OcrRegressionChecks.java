package com.gongyo.tests;

import com.gongyo.photo.IntakeText;
import com.gongyo.photo.LabelTextLayout;
import com.gongyo.photo.LabelTextLayout.Line;
import java.util.*;

/** Synthetic QA100 fixtures only. Exercises the same line assembler used by ML Kit. */
public final class OcrRegressionChecks {
 private static int passed,failed;
 private interface Check {void run();}
 private static void test(String name,Check check){
  try{check.run();passed++;System.out.println("PASS "+name);}
  catch(Throwable failure){failed++;System.out.println("FAIL "+name+": "+failure);}
 }
 private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected "+expected+" but received "+actual);}
 private static void quantities(IntakeText.Result result,long... expected){if(!Arrays.equals(expected,result.quantities))throw new AssertionError("Expected "+Arrays.toString(expected)+" but received "+Arrays.toString(result.quantities));}
 private static final String LABEL="STYLE: QA100\nCOLOR: BLUE\n";
 private static List<Line> lines(Line... body){
  List<Line> result=new ArrayList<>();result.add(new Line("STYLE: QA100",0,0,180,20));result.add(new Line("COLOR: BLUE",0,30,180,50));result.addAll(Arrays.asList(body));return result;
 }
 private static IntakeText.Result parse(Line... body){return IntakeText.parse(LabelTextLayout.intakeText(lines(body)));}
 public static void main(String[] args){
  test("nearby price cannot become an intake count",()->{
   IntakeText.Result result=parse(new Line("M",0,80,20,100),new Line("PRICE",22,80,55,100),new Line("100",56,80,86,100));
   equal(false,result.ready);quantities(result,0,0,0,0);
  });
  test("one ambiguous geometric count blocks all quantities",()->{
   IntakeText.Result result=parse(new Line("S2",0,55,30,75),new Line("M",0,80,20,100),new Line("PRICE",22,80,55,100),new Line("100",56,80,86,100));
   equal(false,result.ready);quantities(result,2,0,0,0);
  });
  test("adjacency alone does not establish quantity provenance",()->{
   equal(false,parse(new Line("S2",0,55,30,75),new Line("M",0,80,20,100),new Line("3",24,80,40,100)).ready);
  });
  test("raw split size and number remain visible for review",()->{
   String text=LabelTextLayout.intakeText(lines(new Line("M",0,80,20,100),new Line("100",24,80,55,100)));
   equal(true,text.contains("\nM\n100"));equal(false,IntakeText.parse(text).ready);
  });
  test("clear single line quantities retain fast intake",()->{
   IntakeText.Result result=parse(new Line("S2/M3/L4/XL5",0,80,180,100));
   equal(true,result.ready);quantities(result,2,3,4,5);
  });
  test("clear counts on separate original lines retain fast intake",()->{
   IntakeText.Result result=parse(new Line("S 2"),new Line("M 3"),new Line("L 4"),new Line("XL 5"));
   equal(true,result.ready);quantities(result,2,3,4,5);
  });
  test("split count remains review without bounding boxes",()->equal(false,parse(new Line("S2"),new Line("M"),new Line("3")).ready));
  test("size row and price do not become quantities",()->{
   IntakeText.Result result=parse(new Line("SIZE: 95(M)"),new Line("(S-M-L-XL)"),new Line("PRICE: 100"));equal(false,result.ready);quantities(result,0,0,0,0);
  });
  test("ambiguous count glyphs block partial intake",()->{
   for(String count:new String[]{"M O","MO","M I","MI","M L","ML","XL O","M1O"}){
    IntakeText.Result result=IntakeText.parse(LABEL+"S2\n"+count);
    if(result.ready)throw new AssertionError("Uncertain count was accepted: "+count);
    quantities(result,2,0,0,0);
   }
  });
  test("literal product letters are not rewritten as numbers",()->{
   IntakeText.Result result=IntakeText.parse("STYLE: QA1OO\nCOLOR: BLUE\nS2");
   equal("QA1OO",result.sku);equal(true,result.ready);
  });
  test("unreadable and conflicting quantities block partial intake",()->{
   for(String count:new String[]{"M","M ?","M:","M: ?","M3/4","M2\nM3"})equal(false,IntakeText.parse(LABEL+"S2\n"+count).ready);
  });
  test("review decision survives serialized text and reload",()->{
   String saved=LabelTextLayout.intakeText(lines(new Line("S2",0,55,30,75),new Line("M",0,80,20,100),new Line("100",24,80,60,100)));
   equal(false,IntakeText.parse(new String(saved.toCharArray())).ready);
  });
  test("large label preserves detail within bounded decode size",()->{
   equal(2,com.gongyo.photo.OcrReadPolicy.sampleSize(4097,3073));
   equal(1,com.gongyo.photo.OcrReadPolicy.sampleSize(3000,1000));
   for(int[] dimensions:new int[][]{{30000,8000},{8000,30000},{20000,12000},{4097,3073},{3000,1000},{640,480}}){
    int sample=com.gongyo.photo.OcrReadPolicy.sampleSize(dimensions[0],dimensions[1]);
    long width=(dimensions[0]+sample-1)/sample,height=(dimensions[1]+sample-1)/sample;
    equal(true,width*height<=4000000L);equal(true,Math.max(width,height)<=3072);
   }
  });
  test("file fallback considers all quarter turns",()->equal("[0, 90, 180, 270]",Arrays.toString(com.gongyo.photo.OcrReadPolicy.rotationAngles())));
  test("empty first read may recover from another orientation",()->{
   String text=com.gongyo.photo.OcrReadPolicy.selectText(Arrays.asList("",LABEL+"S2/M3"));
   equal(true,IntakeText.parse(text).ready);quantities(IntakeText.parse(text),2,3,0,0);
  });
  test("conflicting rotation readings require persistent review",()->{
   String text=com.gongyo.photo.OcrReadPolicy.selectText(Arrays.asList(LABEL+"S2/M3",LABEL+"S2/M8"));
   equal(false,IntakeText.parse(text).ready);equal(false,IntakeText.parse(new String(text.toCharArray())).ready);
  });
  test("fallback cannot hide a previously unreadable size",()->{
   String text=com.gongyo.photo.OcrReadPolicy.selectText(Arrays.asList(LABEL+"S2\nM ?",LABEL+"S2"));
   equal(false,IntakeText.parse(text).ready);
  });
  test("fallback does not stitch separate count candidates",()->{
   String text=com.gongyo.photo.OcrReadPolicy.selectText(Arrays.asList(LABEL+"S2",LABEL+"M3"));
   IntakeText.Result result=IntakeText.parse(text);equal(false,result.ready);equal(false,result.quantities[0]==2&&result.quantities[1]==3);
  });
  test("duplicate recognition leaves clear result ready",()->{
   String text=com.gongyo.photo.OcrReadPolicy.selectText(Arrays.asList(LABEL+"S2/M3",LABEL+"S2/M3"));
   equal(true,IntakeText.parse(text).ready);quantities(IntakeText.parse(text),2,3,0,0);
  });
  test("arbitrary unreadable count token blocks whole photo",()->{
   for(String count:new String[]{"M B","M Z","M |","XL B","M UNKNOWN","M #","M @","M 8B","m b","S2/M B/L4"}){
    IntakeText.Result result=IntakeText.parse(LABEL+"S2\n"+count);
    if(result.ready)throw new AssertionError("Unknown quantity was accepted: "+count);
    quantities(result,2,0,0,0);
   }
  });
  test("unknown quantity review survives raw text reload",()->{
   String saved=LABEL+"S2\nM B";
   equal(false,IntakeText.parse(new String(saved.toCharArray())).ready);
  });
  test("unknown quantity cannot disappear in a retry candidate",()->{
   String selected=com.gongyo.photo.OcrReadPolicy.selectText(Arrays.asList(LABEL+"S2\nM B",LABEL+"S2"));
   equal(false,IntakeText.parse(selected).ready);
   equal(false,IntakeText.parse(new String(selected.toCharArray())).ready);
  });
  test("explicit color fields do not become unknown counts",()->{
   for(String color:new String[]{"M","B","M/B","M BLUE","MELANGE GREY"}){
    IntakeText.Result result=IntakeText.parse("STYLE: QA100\nCOLOR: "+color+"\nS2");
    equal(true,result.ready);quantities(result,2,0,0,0);
   }
  });
  test("garment fields and printed size lists keep clear counts ready",()->{
   for(String garment:new String[]{"SIZE: 95(M)","SIZE: M","(S-M-L-XL)","S / M / L / XL"}){
    IntakeText.Result result=IntakeText.parse(LABEL+garment+"\nS2/M3/L4/XL5");
    equal(true,result.ready);quantities(result,2,3,4,5);
   }
  });
  test("clear spaced counts retain original fast intake",()->{
   IntakeText.Result result=IntakeText.parse(LABEL+"S 2 / M 3 / L 4 / XL 5");
   equal(true,result.ready);quantities(result,2,3,4,5);
  });
  System.out.println("OCR RESULT "+passed+" passed; "+failed+" failed");
  if(failed>0)System.exit(1);
 }
}
