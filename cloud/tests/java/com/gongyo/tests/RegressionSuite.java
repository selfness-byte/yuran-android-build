package com.gongyo.tests;
import android.os.Bundle;
import com.gongyo.orderly.Inventory;
import com.gongyo.yuranphoto.LabelMatcher;
import com.gongyo.yuranphoto.PhotoClient;
import java.lang.reflect.*;
import java.util.*;
public final class RegressionSuite {
 private static int passed,failed;
 interface Check {void run()throws Exception;}
 static void test(String name,Check check){try{check.run();passed++;System.out.println("PASS "+name);}catch(Throwable t){failed++;Throwable cause=t instanceof InvocationTargetException?t.getCause():t;System.out.println("FAIL "+name+": "+cause);}}
 static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected "+expected+" but received "+actual);}
 static Bundle match(String text,String[][] rows)throws Exception{return LabelMatcher.match(new PhotoClient(rows),text,new ArrayList<String>());}
 static Inventory.Row row(String sku,long... quantities){Inventory.Row r=new Inventory.Row();r.sku=sku;r.quantities=quantities;return r;}
 static long total(List<Inventory.Row> rows,String sku)throws Exception{
  Method method;try{method=Inventory.class.getMethod("totalForSku",List.class,String.class);}catch(NoSuchMethodException missing){throw new AssertionError("Selected SKU total is not implemented");}
  return (Long)method.invoke(null,rows,sku);
 }
 static Object intake(String text)throws Exception{
  Class<?> type;try{type=Class.forName("com.gongyo.photo.IntakeText");}catch(ClassNotFoundException missing){throw new AssertionError("Photo intake quantity parser is not implemented");}
  return type.getMethod("parse",String.class).invoke(null,text);
 }
 static Object field(Object result,String name)throws Exception{return result.getClass().getField(name).get(result);}
 static void quantities(Object result,long... expected)throws Exception{long[] actual=(long[])field(result,"quantities");if(!Arrays.equals(expected,actual))throw new AssertionError("Expected "+Arrays.toString(expected)+" but received "+Arrays.toString(actual));}
 public static void main(String[] args){
  test("selected SKU includes every color and size",()->{
   List<Inventory.Row> rows=Arrays.asList(row("QA100",1,2,3,4),row(" qa100 ",2,0,1,3),row("QA1000",99,99,99,99));
   equal(16L,total(rows,"Qa100"));equal(0L,total(rows,"missing"));equal(0L,total(rows,""));equal(0L,total(rows,null));
   rows.get(0).quantities[1]+=5;equal(21L,total(rows,"QA100"));
  });
  test("selected SKU total uses long arithmetic",()->{
   List<Inventory.Row> rows=new ArrayList<>();for(int i=0;i<600;i++)rows.add(row("QA200",999999,999999,999999,999999));equal(2399997600L,total(rows,"QA200"));
  });
  test("explicit size M is not a color M",()->{
   Bundle b=match("STYLE: QA100\nCOLOR: BLACK\nSIZE: M",new String[][]{{"QA100","BLACK"},{"QA100","M"}});
   equal("BLACK",b.getString("color"));equal(1,b.<Bundle>getParcelableArrayList("matches").size());equal("M",b.getString("size"));
  });
  test("explicit size 66 is not color 66",()->{
   Bundle b=match("STYLE: QA100\nCOLOR: 55\nSIZE: 66",new String[][]{{"QA100","55"},{"QA100","66"}});
   equal("55",b.getString("color"));equal("66",b.getString("size"));
  });
  test("explicit size is not another product number",()->{
   Bundle b=match("STYLE: QA100\nCOLOR: WHITE\nSIZE: 55",new String[][]{{"QA100","WHITE"},{"55","WHITE"}});
   equal("QA100",b.getString("sku"));equal(1,b.<Bundle>getParcelableArrayList("matches").size());
  });
  test("explicit product suffix is preserved",()->equal("EY8UTS123R2",match("STYLE: EY8UTS123R2\nCOLOR: IVORY\nSIZE: M",new String[0][]).getString("sku")));
  test("multi-word color is preserved",()->equal("MELANGE GREY",match("STYLE: QA100\nCOLOR: MELANGE GREY\nSIZE: M",new String[0][]).getString("color")));
  test("garment size keeps numeric and letter notation",()->equal("95(M)",match("STYLE: QA100\nCOLOR: IVORY\nSIZE: 95(M)\n(S-M-L-XL)",new String[0][]).getString("size")));
  test("photo quantities exclude garment size price and barcode",()->{
   Object r=intake("품 번: EY8UTS123R2\n색 상: IVORY\n호 칭: 95(M)\n(S-M-L-XL)\n판매가격: 59,000원\nM2\nL3\nXL5\nEY8UTS123IV00M\n품 번: EY8UTS123R2");
   equal("EY8UTS123R2",field(r,"sku"));equal("IVORY",field(r,"color"));quantities(r,0,2,3,5);equal(true,field(r,"ready"));
  });
  test("repeated identical quantities are not added twice",()->{
   Object r=intake("STYLE: QA100\nCOLOR: BLUE\nS 2\nM 3\nL 4\nXL 6\nM 3");quantities(r,2,3,4,6);equal(true,field(r,"ready"));
  });
  test("conflicting quantities require review",()->{Object r=intake("STYLE: QA100\nCOLOR: BLUE\nM 2\nM 3");equal(false,field(r,"ready"));});
  test("printed garment size alone does not change stock",()->{Object r=intake("STYLE: QA100\nCOLOR: BLUE\nSIZE: 95(M)\n(S-M-L-XL)\nPRICE: 49000");equal(false,field(r,"ready"));quantities(r,0,0,0,0);});
  test("missing color requires review",()->equal(false,field(intake("STYLE: QA100\nM 2"),"ready")));
  test("conflicting product numbers require review",()->equal(false,field(intake("STYLE: QA100\nSTYLE: QA200\nCOLOR: BLUE\nM 2"),"ready")));
  System.out.println("RESULT "+passed+" passed; "+failed+" failed");
  if(failed>0)System.exit(1);
 }
}
