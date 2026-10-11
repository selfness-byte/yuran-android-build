package com.gongyo.tests;
import com.gongyo.photo.CaptureSession;
public final class CaptureSessionChecks {
 private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
 public static void main(String[] args){
  CaptureSession s=new CaptureSession("g","work-a","intake","SKU123",false);
  check(s.claim("SKU123",0,false)==0,"restored label suppressed");
  s.claim("",100,false);s.pause();
  check(s.claim("SKU123",5000,false)==0,"pause must discard stale blank interval");
  s.reset();check(s.claim("SKU123",5100,false)==0,"first frame");
  long owner=s.claim("SKU123",5500,false);check(owner!=0,"stable capture reserved");
  check(s.claim("SKU999",6000,true)==0,"manual cannot overlap active save");
  s.saved(owner,"SKU123");check(s.claim("SKU999",6500,true)==0,"saved photo keeps owner through request");
  s.release(owner+1);check(s.busy(),"foreign completion cannot release owner");
  s.release(owner);check(!s.busy(),"matching owner completes");
  check(s.claim("SKU123",7000,false)==0,"same saved label still suppressed");
  CaptureSession restored=new CaptureSession("g","work-a","intake","SKU123",true);
  check(restored.claim("SKU999",0,true)==0,"interrupted reservation needs explicit reset");
  restored.reset();check(restored.claim("SKU999",1,true)!=0,"explicit retry available");
  check(s.generation.equals("g")&&s.targetWorkId.equals("work-a"),"immutable session context");
  System.out.println("CaptureSessionChecks passed (synthetic ownership, restart and suppression)");
 }
}
