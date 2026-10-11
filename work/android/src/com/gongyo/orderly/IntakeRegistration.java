package com.gongyo.orderly;
import com.gongyo.photo.*;
import java.io.*;
import java.util.*;
/** Receipt and all four quantity additions are one checked repository publication. */
public final class IntakeRegistration {
 public static final int MAX_RECEIPTS=10000;
 private IntakeRegistration(){}
 public static final class Receipt {
  public final String requestId,sourceHash,fingerprint;public final long revision;
  public Receipt(String id,String hash,String fingerprint,long revision){this.requestId=id;this.sourceHash=hash;this.fingerprint=fingerprint;this.revision=revision;}
 }
 public static final class Result {
  public final String status,reason;public final InventoryRepository.Snapshot snapshot;
  Result(String status,String reason,InventoryRepository.Snapshot snapshot){this.status=status;this.reason=reason;this.snapshot=snapshot;}
 }
 public static void validate(Map<String,Receipt> receipts,long revision)throws IOException{
  if(receipts==null||receipts.size()>MAX_RECEIPTS)throw new IOException("입고 기록 한도를 넘었습니다.");Set<String> ids=new HashSet<>();
  for(Map.Entry<String,Receipt> e:receipts.entrySet()){
   Receipt r=e.getValue();if(r==null)throw new IOException("입고 기록이 없습니다.");PhotoCatalog.uuid(r.requestId);PhotoCatalog.hash(r.sourceHash);PhotoCatalog.hash(r.fingerprint);
   if(!e.getKey().equals(r.sourceHash)||!ids.add(r.requestId)||r.revision<1||r.revision>revision)throw new IOException("입고 기록 식별자가 올바르지 않습니다.");
  }
 }
 public static Result register(InventoryRepository repo,String generation,String id,String hash,String sku,String color,long[] counts)throws IOException{
  final String fingerprint;
  try{PhotoCatalog.uuid(id);fingerprint=IntakeContract.fingerprint(generation,hash,sku,color,counts);}
  catch(IOException invalid){return new Result(PhotoContract.INVALID_REQUEST,invalid.getMessage(),repo.snapshot());}
  final String product=PhotoContract.key(sku),shade=PhotoContract.key(color);final long[] owned=counts.clone();final Result[] answer={null};
  repo.exclusive(()->{
   InventoryRepository.Snapshot current=repo.snapshot();
   if(!current.generation.equals(generation)){answer[0]=new Result(PhotoContract.CONFLICT,"자료가 복원되거나 바뀌었습니다. 입고 사진을 다시 확인해 주세요.",current);return;}
   Receipt existing=current.stateCopy.intakeReceipts.get(hash);
   if(existing!=null){answer[0]=new Result(existing.fingerprint.equals(fingerprint)?PhotoContract.OK:PhotoContract.CONFLICT,existing.fingerprint.equals(fingerprint)?"이미 등록된 사진입니다. 수량을 다시 더하지 않았습니다.":"같은 사진의 등록 내용이 다릅니다. 유란노트에서 기존 수량을 확인해 주세요.",current);return;}
   for(Receipt receipt:current.stateCopy.intakeReceipts.values())if(receipt.requestId.equals(id)){answer[0]=new Result(PhotoContract.CONFLICT,"입고 요청 식별자가 다른 사진에 사용됐습니다.",current);return;}
   if(current.stateCopy.draft.completed){answer[0]=new Result(PhotoContract.CONFLICT,"현재 작업이 완료 상태입니다. 유란노트에서 새 작업을 열어 주세요.",current);return;}
   if(current.stateCopy.intakeReceipts.size()>=MAX_RECEIPTS){answer[0]=new Result(PhotoContract.LIMIT_REACHED,"입고 기록이 가득 찼습니다. 사진은 보관됩니다.",current);return;}
   try{
    InventoryRepository.CommitResult result=repo.commit(generation,current.revision,(state,next)->{
     Inventory.Job before=state.draft.copy();Inventory.Row old=null,after=null;
     for(int i=0;i<4;i++)if(owned[i]>0){
      QuantityAdjuster.Result adjusted=QuantityAdjuster.adjust(state.draft,product,shade,i,owned[i],false,state.globalRules,state.skuRules);
      after=adjusted.row;
     }
     for(Inventory.Row row:before.rows)if(after!=null&&row.rowId.equals(after.rowId)){old=row;break;}
     long at=System.currentTimeMillis();StockHistory.noteRowChange(before,state.draft,old,after,at);state.draft.updatedAt=at;
     ChangeHistory.recordRow(state,state.draft,state.draft.title,"사진 새입고",old,after);
     state.undoSnapshot=null;
     state.intakeReceipts.put(hash,new Receipt(id,hash,fingerprint,next));
    });
    answer[0]=new Result(result.committed?PhotoContract.OK:PhotoContract.CONFLICT,result.committed?"새입고가 저장됐습니다.":result.conflictReason,result.latestSnapshot);
   }catch(IllegalArgumentException invalid){answer[0]=new Result(PhotoContract.INVALID_REQUEST,invalid.getMessage(),repo.snapshot());}
  });return answer[0];
 }
}
