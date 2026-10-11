package com.gongyo.photo;

/** App-owned capture state: no Activity references; context and owner token never change. */
public final class CaptureSession {
 public final String generation,targetWorkId,mode;
 private final CaptureGate gate=new CaptureGate();
 private long sequence,owner;
 private String suppressed;
 private boolean interrupted;
 public CaptureSession(String generation,String targetWorkId,String mode,String suppressed,boolean interrupted){
  this.generation=generation;this.targetWorkId=targetWorkId;this.mode=mode;
  this.suppressed=PhotoContract.key(suppressed);this.interrupted=interrupted;gate.saved(this.suppressed);
 }
 public synchronized long claim(String sku,long now,boolean manual){
  if(owner!=0||interrupted)return 0;
  if(!manual&&!gate.observe(sku,now))return 0;
  owner=++sequence;interrupted=true;suppressed=PhotoContract.key(sku);return owner;
 }
 public synchronized boolean owns(long token){return token!=0&&owner==token;}
 public synchronized boolean busy(){return owner!=0;}
 public synchronized boolean interrupted(){return interrupted;}
 public synchronized String suppressed(){return suppressed;}
 public synchronized void saved(long token,String sku){if(!owns(token))throw new IllegalStateException("Capture owner changed");suppressed=PhotoContract.key(sku);interrupted=false;gate.saved(suppressed);}
 public synchronized void release(long token){if(owns(token))owner=0;}
 public synchronized void reset(){if(owner!=0)throw new IllegalStateException("Capture still in progress");interrupted=false;suppressed="";gate.reset();}
 /** Missing-frame time while paused is not evidence that the physical label changed. */
 public synchronized void pause(){if(owner==0&&!interrupted)gate.saved(suppressed);}
}
