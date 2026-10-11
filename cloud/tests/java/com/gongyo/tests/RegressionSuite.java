package com.gongyo.tests;
import android.os.Bundle;
import com.gongyo.orderly.Inventory;
import com.gongyo.orderly.StateCodec;
import com.gongyo.orderly.InventoryRepository;
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

 static Object register(InventoryRepository repo,String id,String hash,String sku,String color,long[] q)throws Exception{
  Class<?> type;try{type=Class.forName("com.gongyo.orderly.IntakeRegistration");}catch(ClassNotFoundException missing){throw new AssertionError("Atomic intake registration is not implemented");}
  return type.getMethod("register",InventoryRepository.class,String.class,String.class,String.class,String.class,String.class,String.class,long[].class).invoke(null,repo,repo.snapshot().generation,repo.snapshot().stateCopy.draft.stockSessionId,id,hash,sku,color,q);
 }
 static InventoryRepository repository()throws Exception{StateCodec.State s=new StateCodec.State();s.draft.stockSessionId=uuid();return new InventoryRepository(s,v->{});}
 static String uuid(){return UUID.randomUUID().toString();}
 static final String HASH=String.join("",Collections.nCopies(64,"a"));

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

  test("intake registers four sizes exactly once across restart",()->{
   InventoryRepository repo=repository();String id=uuid();
   equal("OK",field(register(repo,id,HASH,"QA100","BLUE",new long[]{2,3,4,6}),"status"));
   byte[] saved=StateCodec.encode(repo.snapshot().stateCopy);
   InventoryRepository restart=new InventoryRepository(StateCodec.decode(saved),s->{});
   equal("OK",field(register(restart,id,HASH,"QA100","BLUE",new long[]{2,3,4,6}),"status"));
   equal(15L,Inventory.grandTotal(restart.snapshot().stateCopy.draft.rows));
   equal(1L,restart.snapshot().revision);
  });
  test("same photo with a new callback does not duplicate intake",()->{
   InventoryRepository repo=repository();register(repo,uuid(),HASH,"QA100","BLUE",new long[]{0,2,0,0});
   equal("OK",field(register(repo,uuid(),HASH,"QA100","BLUE",new long[]{0,2,0,0}),"status"));
   equal(2L,Inventory.grandTotal(repo.snapshot().stateCopy.draft.rows));
  });
  test("same photo with changed counts is rejected",()->{
   InventoryRepository repo=repository();String id=uuid();register(repo,id,HASH,"QA100","BLUE",new long[]{0,2,0,0});
   equal("CONFLICT",field(register(repo,id,HASH,"QA100","BLUE",new long[]{0,9,0,0}),"status"));
   equal(2L,Inventory.grandTotal(repo.snapshot().stateCopy.draft.rows));
  });
  test("failed persistence publishes no intake or receipt",()->{
   InventoryRepository repo=new InventoryRepository(repository().snapshot().stateCopy,s->{throw new java.io.IOException("synthetic disk failure");});
   try{register(repo,uuid(),HASH,"QA100","BLUE",new long[]{0,2,0,0});throw new AssertionError("Expected persistence failure");}
   catch(InvocationTargetException expected){if(!(expected.getCause() instanceof java.io.IOException))throw expected;}
   equal(0L,Inventory.grandTotal(repo.snapshot().stateCopy.draft.rows));equal(0L,repo.snapshot().revision);
  });
  test("intake overflow leaves every size unchanged",()->{
   StateCodec.State s=new StateCodec.State();s.draft.stockSessionId=uuid();Inventory.Row r=row("QA100",0,999999,0,0);r.color=r.sourceColor="BLUE";s.draft.rows.add(r);
   InventoryRepository repo=new InventoryRepository(s,v->{});
   equal("INVALID_REQUEST",field(register(repo,uuid(),HASH,"QA100","BLUE",new long[]{2,1,0,0}),"status"));
   equal(999999L,Inventory.grandTotal(repo.snapshot().stateCopy.draft.rows));equal(0L,repo.snapshot().revision);
  });
  test("completed stock is not changed by camera intake",()->{
   StateCodec.State s=new StateCodec.State();s.draft.stockSessionId=uuid();s.draft.completed=true;InventoryRepository repo=new InventoryRepository(s,v->{});
   equal("CONFLICT",field(register(repo,uuid(),HASH,"QA100","BLUE",new long[]{2,0,0,0}),"status"));equal(0L,repo.snapshot().revision);
  });
  test("zero quantity cannot be confirmed as new intake",()->{
   InventoryRepository repo=repository();equal("INVALID_REQUEST",field(register(repo,uuid(),HASH,"QA100","BLUE",new long[4]),"status"));equal(0L,repo.snapshot().revision);
  });


  test("malformed visible quantity cannot silently become zero",()->equal(false,field(intake("STYLE: QA100\nCOLOR: BLUE\nS2\nM3/4"),"ready")));
  test("inline size marker is excluded from color",()->{Object r=intake("STYLE: QA100\nCOLOR: BLUE SIZE: 95(M)\nM2");equal("BLUE",field(r,"color"));equal(true,field(r,"ready"));});
  test("explicit suffix wins over shorter catalog product",()->{Bundle b=match("STYLE: QA100-R2\nCOLOR: BLUE\nSIZE: M",new String[][]{{"QA100","BLUE"}});equal("QA100-R2",b.getString("sku"));equal(0,b.<Bundle>getParcelableArrayList("matches").size());});


  test("restoring stock preserves same-photo intake protection",()->{
   InventoryRepository repo=repository();register(repo,uuid(),HASH,"QA100","BLUE",new long[]{0,2,0,0});
   InventoryRepository.Snapshot before=repo.snapshot();repo.restore(before.generation,before.revision,before.stateCopy);
   equal("OK",field(register(repo,uuid(),HASH,"QA100","BLUE",new long[]{0,2,0,0}),"status"));
   equal(2L,Inventory.grandTotal(repo.snapshot().stateCopy.draft.rows));
  });
  test("continuous camera saves stable SKU once until next label",()->{
   Class<?> cls;try{cls=Class.forName("com.gongyo.photo.CaptureGate");}catch(ClassNotFoundException missing){throw new AssertionError("Continuous capture gate is not implemented");}
   Object gate=cls.newInstance();Method observe=cls.getMethod("observe",String.class,long.class),saved=cls.getMethod("saved",String.class);
   equal(false,observe.invoke(gate,"QA100",100L));equal(true,observe.invoke(gate,"QA100",500L));saved.invoke(gate,"QA100");
   equal(false,observe.invoke(gate,"QA100",900L));equal(false,observe.invoke(gate,"QA100",2000L));
   equal(false,observe.invoke(gate,"",2200L));equal(false,observe.invoke(gate,"",3000L));
   equal(false,observe.invoke(gate,"QA100",3200L));equal(true,observe.invoke(gate,"QA100",3700L));
   saved.invoke(gate,"QA100");equal(false,observe.invoke(gate,"QA200",4200L));equal(true,observe.invoke(gate,"QA200",4700L));
  });
  test("malformed and oversized quantities block partial intake",()->{
   for(String value:new String[]{"M1000000","M -3","M+3","M3/4","M2?","S2 M-3"})
    equal(false,field(intake("STYLE: QA100\nCOLOR: BLUE\nS2\n"+value),"ready"));
   equal(true,field(intake("STYLE: QA100\nCOLOR: BLUE\nS2 / M3 / L4"),"ready"));
  });
  test("color ends at inline multilingual annotation",()->{
   for(String value:new String[]{"SIZE: M","PRICE: 49000","QTY: 2","호칭: 95(M)","尺码: M"})
    equal("MELANGE GREY",field(intake("STYLE: QA100\nCOLOR: MELANGE GREY "+value+"\nM2"),"color"));
  });
  test("begin freezes one work atomically and persistence failure leaves it blank",()->{
   InventoryRepository repo=new InventoryRepository(new StateCodec.State(),v->{});
   InventoryRepository.Snapshot first=com.gongyo.orderly.IntakeRegistration.begin(repo);
   equal(false,first.stateCopy.draft.stockSessionId.isEmpty());equal(1L,first.revision);
   equal(first.stateCopy.draft.stockSessionId,com.gongyo.orderly.IntakeRegistration.begin(repo).stateCopy.draft.stockSessionId);
   equal(1L,repo.snapshot().revision);
   InventoryRepository failing=new InventoryRepository(new StateCodec.State(),v->{throw new java.io.IOException("synthetic");});
   try{com.gongyo.orderly.IntakeRegistration.begin(failing);throw new AssertionError("Expected failure");}catch(java.io.IOException expected){}
   equal("",failing.snapshot().stateCopy.draft.stockSessionId);equal(0L,failing.snapshot().revision);
   repo.commit(first.generation,first.revision,(state,next)->state.draft.completed=true);
   try{com.gongyo.orderly.IntakeRegistration.begin(repo);throw new AssertionError("Expected completed conflict");}catch(java.io.IOException expected){}
  });
  test("delayed request cannot land in another work but committed response retry succeeds",()->{
   InventoryRepository repo=repository();InventoryRepository.Snapshot a=repo.snapshot();String id=uuid(),target=a.stateCopy.draft.stockSessionId;
   equal("OK",com.gongyo.orderly.IntakeRegistration.register(repo,a.generation,target,id,HASH,"QA100","BLUE",new long[]{0,2,0,0}).status);
   InventoryRepository.Snapshot saved=repo.snapshot();repo.commit(saved.generation,saved.revision,(state,next)->{state.draft=new Inventory.Job();state.draft.stockSessionId=uuid();});
   long revision=repo.snapshot().revision;
   equal("OK",com.gongyo.orderly.IntakeRegistration.register(repo,a.generation,target,id,HASH,"QA100","BLUE",new long[]{0,2,0,0}).status);
   equal("CONFLICT",com.gongyo.orderly.IntakeRegistration.register(repo,a.generation,target,uuid(),HASH.replace('a','b'),"QA200","BLUE",new long[]{0,2,0,0}).status);
   equal("CONFLICT",field(register(repo,uuid(),HASH,"QA100","BLUE",new long[]{0,2,0,0}),"status"));
   equal(revision,repo.snapshot().revision);equal(0L,Inventory.grandTotal(repo.snapshot().stateCopy.draft.rows));
  });
  test("restore retains receipts rejects old generation and changed content",()->{
   InventoryRepository repo=repository();register(repo,uuid(),HASH,"QA100","BLUE",new long[]{0,2,0,0});
   InventoryRepository.Snapshot old=repo.snapshot();repo.restore(old.generation,old.revision,old.stateCopy);
   equal("CONFLICT",com.gongyo.orderly.IntakeRegistration.register(repo,old.generation,old.stateCopy.draft.stockSessionId,uuid(),HASH,"QA100","BLUE",new long[]{0,2,0,0}).status);
   equal("CONFLICT",field(register(repo,uuid(),HASH,"QA100","BLUE",new long[]{0,3,0,0}),"status"));
   equal("OK",field(register(repo,uuid(),HASH,"QA100","BLUE",new long[]{0,2,0,0}),"status"));
   equal(2L,Inventory.grandTotal(repo.snapshot().stateCopy.draft.rows));
  });
  test("capture requires stable time and reserves until saved or reset",()->{
   com.gongyo.photo.CaptureGate gate=new com.gongyo.photo.CaptureGate();
   equal(false,gate.observe("",0));equal(false,gate.observe("QA100",100));equal(false,gate.observe("QA100",399));equal(true,gate.observe("QA100",400));
   equal(false,gate.observe("QA200",1000));equal(false,gate.observe("QA200",1400));
   gate.reset();equal(false,gate.observe("QA100",1500));equal(true,gate.observe("QA100",1800));
   gate.saved("QA100");equal(false,gate.observe("",2000));equal(false,gate.observe("QA100",2599));
   equal(false,gate.observe("",2700));equal(false,gate.observe("QA100",3300));equal(true,gate.observe("QA100",3600));
  });
  test("capture restored suppression alternating reads and backward clock are safe",()->{
   com.gongyo.photo.CaptureGate gate=new com.gongyo.photo.CaptureGate();gate.saved("QA100");
   equal(false,gate.observe("QA100",100));equal(false,gate.observe("QA100",600));
   equal(false,gate.observe("QA200",1000));equal(false,gate.observe("QA300",1400));equal(false,gate.observe("QA200",1800));
   equal(false,gate.observe("QA200",1700));equal(false,gate.observe("QA200",1999));equal(true,gate.observe("QA200",2000));
  });
  test("explicit product and color digits are not quantities",()->{Object r=intake("STYLE: M123\nCOLOR: M3\nS2");equal(true,field(r,"ready"));quantities(r,2,0,0,0);});
  test("quantity annotation after color cannot be hidden in color span",()->{equal(false,field(intake("STYLE: QA100\nCOLOR: BLUE M3/4\nS2"),"ready"));equal(false,field(intake("STYLE: QA100\nCOLOR: BLUE M3\nS2"),"ready"));});
  test("unreadable explicit size count blocks partial intake",()->{for(String value:new String[]{"M: ?","M:","M ?","M"})equal(false,field(intake("STYLE: QA100\nCOLOR: BLUE\nS2\n"+value),"ready"));});
  System.out.println("RESULT "+passed+" passed; "+failed+" failed");
  if(failed>0)System.exit(1);
 }
}
