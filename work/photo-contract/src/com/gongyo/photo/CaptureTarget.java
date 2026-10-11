package com.gongyo.photo;
import java.io.IOException;
/** One owner snapshot: work identity and revision must be checked together. */
public final class CaptureTarget {
 public final PhotoContract.Compatibility compatibility;
 public final String workId,productId,variantId,photoVersionId;
 public CaptureTarget(PhotoContract.Compatibility compatibility,String workId){this(compatibility,workId,"","","");}
 public CaptureTarget(PhotoContract.Compatibility compatibility,String workId,String productId,String variantId,String photoVersionId){this.compatibility=compatibility;this.workId=workId==null?"":workId;this.productId=productId;this.variantId=variantId;this.photoVersionId=photoVersionId;}
 public void require(String generation,String originalWork)throws IOException{
  if(compatibility==null||compatibility.protocolVersion!=1||compatibility.revision<0||workId.isEmpty())throw new IOException("사진 연결을 위해 유란노트를 업데이트하고 원래 입고 작업을 열어 주세요.");
  if(!compatibility.generation.equals(generation)||!workId.equals(originalWork))throw new IOException("자료 또는 입고 작업이 바뀌었습니다. 원래 사진과 수량 요청을 유지합니다.");
 }
 public void requireSame(CaptureTarget current)throws IOException{
  if(current==null)throw new IOException("입고 작업을 다시 확인해 주세요.");
  current.require(compatibility.generation,workId);
  if(current.compatibility.revision!=compatibility.revision||!productId.equals(current.productId)||!variantId.equals(current.variantId)||!photoVersionId.equals(current.photoVersionId))throw new IOException("확인하는 동안 사진 또는 작업이 바뀌었습니다. 다시 확인해 주세요.");
 }
}
