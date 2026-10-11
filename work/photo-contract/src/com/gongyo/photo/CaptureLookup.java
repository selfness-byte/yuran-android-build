package com.gongyo.photo;
import java.io.IOException;
/** Keeps a lookup result attached to its captured entry, even when a newer label is visible. */
public final class CaptureLookup {
 private CaptureLookup(){}
 public interface Store {void save(String entryId,String summary)throws IOException;}
 public interface Presenter {void show(String summary);}
 public static final class Result {
  public final String entryId,summary;
  private Result(String entryId,String summary){this.entryId=entryId;this.summary=summary;}
  public void present(String currentEntryId,Presenter presenter){if(entryId.equals(currentEntryId))presenter.show(summary);}
 }
 public static String bounded(String summary)throws IOException{
  if(summary==null)return "";if(summary.length()>12000)throw new IOException("재고 결과가 너무 깁니다. 사진은 보관됐으며 현재 재고 조회에서 확인해 주세요.");return summary;
 }
 public static Result publish(String entryId,String summary,Store store)throws IOException{
  if(entryId==null||entryId.isEmpty())throw new IOException("저장 사진 식별자가 없습니다.");
  String value=bounded(summary);store.save(entryId,value);return new Result(entryId,value);
 }
}
