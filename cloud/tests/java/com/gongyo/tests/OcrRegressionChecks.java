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
  System.out.println("OCR RESULT "+passed+" passed; "+failed+" failed");
  if(failed>0)System.exit(1);
 }
}
