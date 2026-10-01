package com.gongyo.orderly;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Explicit local display timezone; does not infer dates missing from older backups. */
public final class TimeLabels {
 private TimeLabels(){}
 public static String format(long timestamp){
  if(timestamp<=0)return "기록 없음";
  SimpleDateFormat formatter=new SimpleDateFormat("yyyy년 MM월 dd일 HH:mm:ss",Locale.KOREA);
  formatter.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
  return formatter.format(new Date(timestamp))+" (한국시간)";
 }
}
