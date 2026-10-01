package com.gongyo.orderly;

import java.util.Arrays;
import java.util.UUID;

/** Captured, local change receipts. Later edits cannot change their readable details. */
public final class ChangeHistory {
 public static final int MAX_CHANGES=500;
 private ChangeHistory(){}
 public static final class Entry {
  public final String id,jobTitle,action,detail,stockSessionId;
  public final long timestamp;
  public Entry(String id,long timestamp,String jobTitle,String action,String detail){this(id,timestamp,jobTitle,action,detail,"");}
  public Entry(String id,long timestamp,String jobTitle,String action,String detail,String stockSessionId){this.id=id;this.timestamp=timestamp;this.jobTitle=jobTitle;this.action=action;this.detail=detail;this.stockSessionId=stockSessionId;}
 }
 public static boolean sameRow(Inventory.Row a,Inventory.Row b){
  return a==b||(a!=null&&b!=null&&a.sku.equals(b.sku)&&a.sourceColor.equals(b.sourceColor)&&a.color.equals(b.color)&&Arrays.equals(a.quantities,b.quantities));
 }
 public static boolean recordRow(StateCodec.State state,String jobTitle,String action,Inventory.Row before,Inventory.Row after){
  return recordRow(state,null,jobTitle,action,before,after);
 }
 public static boolean recordRow(StateCodec.State state,Inventory.Job job,String jobTitle,String action,Inventory.Row before,Inventory.Row after){
  if(sameRow(before,after))return false;
  if(job!=null&&job.stockChangeCount==Long.MAX_VALUE)throw new IllegalArgumentException("변경 기록 개수가 너무 큽니다.");
  Inventory.Row identity=after==null?before:after;
  StringBuilder details=new StringBuilder(identity.sku).append(" · ").append(identity.color);
  if(!identity.sourceColor.equals(identity.color))details.append(" ( ").append(identity.sourceColor).append(" )");
  if(before==null)details.append("\n행 추가");else if(after==null)details.append("\n행 삭제");
  if(before!=null&&after!=null){
   field(details,"품번",before.sku,after.sku);field(details,"원문 색상",before.sourceColor,after.sourceColor);field(details,"색상 코드",before.color,after.color);
  }
  for(int i=0;i<4;i++){
   long old=before==null?0:before.quantities[i],next=after==null?0:after.quantities[i],delta=next-old;
   if(delta!=0)details.append('\n').append(Inventory.SIZES[i]).append(": ").append(old).append(" → ").append(next).append("개 (").append(delta>0?"+":"").append(delta).append(')');
  }
  String sessionId=job==null?"":StockHistory.ensureSession(job);
  record(state,jobTitle,action,details.toString(),sessionId);
  if(job!=null)job.stockChangeCount++;
  return true;
 }
 private static void field(StringBuilder detail,String name,String before,String after){if(!before.equals(after))detail.append('\n').append(name).append(": ").append(before).append(" → ").append(after);}
 public static void record(StateCodec.State state,String jobTitle,String action,String detail){
  record(state,jobTitle,action,detail,"");
 }
 private static void record(StateCodec.State state,String jobTitle,String action,String detail,String stockSessionId){
  if(state==null||jobTitle==null||jobTitle.length()>80||action==null||action.trim().isEmpty()||action.length()>60||detail==null||detail.trim().isEmpty()||detail.length()>4000)throw new IllegalArgumentException("변경 기록의 내용이 올바르지 않습니다.");
  Entry entry=new Entry(UUID.randomUUID().toString(),System.currentTimeMillis(),jobTitle,action,detail,stockSessionId);
  state.changes.add(0,entry);while(state.changes.size()>MAX_CHANGES)state.changes.remove(state.changes.size()-1);
 }
}
