package com.gongyo.photo;

/** Pure continuous-capture state. A true observation reserves one capture until saved/reset. */
public final class CaptureGate {
 private String candidate="",lastSaved="";
 private long candidateSince,missingSince,lastNow;
 private boolean missing,hasClock,pending;
 public synchronized boolean observe(String sku,long nowMillis){
  String value=PhotoContract.key(sku);
  if(hasClock&&nowMillis<lastNow){candidate="";missing=false;}
  hasClock=true;lastNow=nowMillis;
  if(pending)return false;
  if(value.isEmpty()){
   candidate="";
   if(!missing){missing=true;missingSince=nowMillis;}
   if(nowMillis-missingSince>=600)lastSaved="";
   return false;
  }
  if(missing&&nowMillis-missingSince>=600)lastSaved="";
  missing=false;
  if(value.equals(lastSaved)){candidate="";return false;}
  if(!value.equals(candidate)){candidate=value;candidateSince=nowMillis;return false;}
  if(nowMillis-candidateSince<300)return false;
  pending=true;return true;
 }
 /** Can also restore repeat suppression from the last durable capture after recreation. */
 public synchronized void saved(String sku){
  lastSaved=PhotoContract.key(sku);candidate="";missing=false;pending=false;
 }
 public synchronized void reset(){
  candidate="";lastSaved="";missing=false;pending=false;hasClock=false;
 }
}
