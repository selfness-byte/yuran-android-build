package com.gongyo.orderly;

import com.gongyo.photo.PhotoContract;
import java.util.*;

/** The preview and writer must resolve aliases/color rules to the same frozen target. */
public final class PhotoTargetChecks {
 private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 private static String id(){return UUID.randomUUID().toString();}
 public static void main(String[] ignored)throws Exception{
  StateCodec.State state=new StateCodec.State();state.revision=3;state.draft.stockSessionId=id();
  String product=id(),variant=id();char[] chars=new char[64];Arrays.fill(chars,'a');String hash=new String(chars);
  state.catalog.products.put(product,new PhotoCatalog.Product(product,"QA-PHOTO",Arrays.asList("QA-ALIAS")));
  state.catalog.assets.put(hash,new PhotoCatalog.Asset(hash,8,2,2));
  state.catalog.variants.put(variant,new PhotoCatalog.Variant(variant,product,"WHITE",Arrays.asList("IVORY"),hash));
  state.globalRules.put("WHITE","WH");state.globalRules.put("IVORY","WH");
  InventoryRepository repo=new InventoryRepository(state,s->{throw new AssertionError("preview cannot persist");});
  InventoryRepository.Snapshot current=repo.snapshot();byte[] before=StateCodec.encode(current.stateCopy);
  for(String color:new String[]{"WHITE","IVORY","WH"}){
   PhotoRegistration.Preview preview=PhotoRegistration.preview(current,current.generation,current.revision,state.draft.stockSessionId,"qa-alias",color);
   PhotoRegistration.Target writer=PhotoRegistration.target(current.stateCopy,"","","qa-alias",color);
   check(PhotoContract.OK.equals(preview.status)&&preview.productId.equals(writer.product.id)&&preview.variantId.equals(writer.variant.id)&&hash.equals(preview.photoVersionId),"preview preserves existing mapped/aliased photo target");
  }
  PhotoRegistration.Preview fresh=PhotoRegistration.preview(current,current.generation,current.revision,state.draft.stockSessionId,"QA-NEW","BLACK");
  check(PhotoContract.OK.equals(fresh.status)&&fresh.productId.isEmpty()&&fresh.variantId.isEmpty()&&fresh.photoVersionId.isEmpty(),"new target does not publish nonexistent IDs");
  check(Arrays.equals(before,StateCodec.encode(current.stateCopy)),"preview never mutates snapshot");
  check(Arrays.equals(before,StateCodec.encode(repo.snapshot().stateCopy)),"preview never mutates confirmed state");
  check("GENERATION_CHANGED".equals(PhotoRegistration.preview(current,id(),current.revision,state.draft.stockSessionId,"QA-PHOTO","WHITE").reason),"dataset conflict");
  check("REVISION_CHANGED".equals(PhotoRegistration.preview(current,current.generation,0,state.draft.stockSessionId,"QA-PHOTO","WHITE").reason),"revision race cannot approve old photo");
  check("WORK_CHANGED".equals(PhotoRegistration.preview(current,current.generation,current.revision,id(),"QA-PHOTO","WHITE").reason),"work conflict");
  current.stateCopy.draft.completed=true;
  check("WORK_COMPLETED".equals(PhotoRegistration.preview(current,current.generation,current.revision,state.draft.stockSessionId,"QA-PHOTO","WHITE").reason),"completed work rejects replacement");
  System.out.println("PASS read-only photo target: writer-equivalent aliases, existing-photo detection, no mutations and context conflicts");
 }
}
