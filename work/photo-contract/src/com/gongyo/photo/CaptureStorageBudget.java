package com.gongyo.photo;
import com.gongyo.orderly.CheckedAtomicFile;
import java.io.*;

/** Bounded staging, including abandoned attempts. Never deletes an unacknowledged source. */
public final class CaptureStorageBudget {
 public static final long MAX_PREPARED_BYTES=256L*1024*1024;
 private CaptureStorageBudget(){}
 public static void requireAdditional(File directory,CheckedAtomicFile.FileOps io,long incoming)throws IOException{
  if(incoming<0||incoming>MAX_PREPARED_BYTES)throw new IOException("사진 준비 용량을 확인해 주세요.");
  long used=0;
  for(File file:io.list(directory)){
   CheckedAtomicFile.Kind kind=io.inspect(file);
   if(kind==CheckedAtomicFile.Kind.MISSING)continue;
   if(kind!=CheckedAtomicFile.Kind.REGULAR)throw new IOException("사진 준비 폴더의 파일을 확인해 주세요.");
   long size=file.length();if(size<0||size>MAX_PREPARED_BYTES-used)throw new IOException("연결 대기 사진 보관 공간이 가득 찼습니다. 기존 요청과 사진은 유지합니다.");used+=size;
  }
  if(incoming>MAX_PREPARED_BYTES-used)throw new IOException("연결 대기 사진 보관 공간이 가득 찼습니다. 확인된 사진의 저장 결과를 먼저 확인해 주세요. 기존 요청은 유지합니다.");
 }
}
