package com.gongyo.orderly;

import java.util.*;
import com.gongyo.photo.PhotoContract;

/** Explicit owner-confirmed row binding changes; never a stock mutation. */
public final class PhotoBindings {
 private PhotoBindings() {}
 public static void link(StateCodec.State candidate,String rowId,String productId,String variantId,String photoVersionId,long commitRevision){
  Inventory.Row row=editableRow(candidate,rowId,commitRevision);
  PhotoCatalog.Product product=candidate.catalog.products.get(productId);PhotoCatalog.Variant variant=candidate.catalog.variants.get(variantId);
  if(product==null||variant==null||!variant.productId.equals(productId)||!candidate.catalog.assets.containsKey(photoVersionId))throw new IllegalArgumentException("선택한 상품·색상·사진을 찾을 수 없습니다.");
  if(!matchesRow(candidate,row,product,variant))throw new IllegalArgumentException("행의 품번과 색상에 맞는 사진을 선택해 주세요.");
  if(!photoVersionId.equals(variant.currentPhotoVersionId)
      &&(row.binding==null||!productId.equals(row.binding.productId)||!variantId.equals(row.binding.variantId)||!photoVersionId.equals(row.binding.photoVersionId)))throw new IllegalArgumentException("선택한 색상의 현재 사진 또는 이미 연결된 사진을 확인해 주세요.");
  PhotoCatalog.Binding next=new PhotoCatalog.Binding(productId,variantId,photoVersionId,row.sku,row.sourceColor,commitRevision,false);
  capture(candidate,row,next);row.binding=next;
  ChangeHistory.record(candidate,candidate.draft.title,"사진 연결",row.sku+" · "+row.sourceColor+"\n확인한 사진을 연결했습니다.");
 }
 public static void unlink(StateCodec.State candidate,String rowId,long commitRevision){
  Inventory.Row row=editableRow(candidate,rowId,commitRevision);
  if(row.binding==null)throw new IllegalArgumentException("연결된 사진이 없습니다.");
  // Retain a hidden reference so automatic matching cannot undo an intentional unlink.
  PhotoCatalog.Binding hidden=row.binding.copy();hidden.needsConfirmation=true;
  capture(candidate,row,hidden);row.binding=hidden;
  ChangeHistory.record(candidate,candidate.draft.title,"사진 연결 해제",row.sku+" · "+row.sourceColor+"\n사진 연결을 해제했습니다.");
 }
 /** Called before assigning edited SKU/sourceColor; output code is deliberately absent. */
 public static void identityEdited(Inventory.Row row,String sku,String sourceColor){
  if(row.binding!=null&&(!PhotoContract.sameKey(row.sku,sku)||!PhotoContract.sameKey(row.sourceColor,sourceColor)))row.binding.needsConfirmation=true;
 }
 static Inventory.Row findRow(StateCodec.State state,String rowId){if(state==null||state.draft==null)return null;for(Inventory.Row row:state.draft.rows)if(Objects.equals(row.rowId,rowId))return row;return null;}
 static boolean matches(String actual,String canonical,List<String> aliases){if(PhotoContract.sameKey(actual,canonical))return true;for(String alias:aliases)if(PhotoContract.sameKey(actual,alias))return true;return false;}
 /** A registered color may be the original name or the row's configured output code. */
 public static boolean matchesRow(StateCodec.State state,Inventory.Row row,PhotoCatalog.Product product,PhotoCatalog.Variant variant){
  if(state==null||row==null||product==null||variant==null||!product.id.equals(variant.productId)||!matches(row.sku,product.sku,product.confirmedSkuAliases))return false;
  if(matches(row.sourceColor,variant.sourceColor,variant.confirmedColorAliases)||matches(row.color,variant.sourceColor,variant.confirmedColorAliases))return true;
  if(PhotoContract.sameKey(row.color,Parser.mappedColor(row.sku,variant.sourceColor,state.globalRules,state.skuRules)))return true;
  for(String alias:variant.confirmedColorAliases)if(PhotoContract.sameKey(row.color,Parser.mappedColor(row.sku,alias,state.globalRules,state.skuRules)))return true;
  return false;
 }
 /** Collapse legacy casing duplicates without rewriting IDs or historical bindings. */
 public static List<PhotoCatalog.Variant> catalogVariants(StateCodec.State state){
  Map<String,Long> revisions=new HashMap<>();
  for(PhotoCatalog.Receipt receipt:state.catalog.receipts.values()){
   if(receipt.binding==null)continue;
   PhotoCatalog.Variant v=state.catalog.variants.get(receipt.binding.variantId);
   if(v==null||!receipt.photoVersionId.equals(v.currentPhotoVersionId))continue;
   Long previous=revisions.get(v.id);if(previous==null||receipt.committedRevision>previous)revisions.put(v.id,receipt.committedRevision);
  }
  LinkedHashMap<String,PhotoCatalog.Variant> grouped=new LinkedHashMap<>();
  for(PhotoCatalog.Variant variant:state.catalog.variants.values()){
   PhotoCatalog.Product product=state.catalog.products.get(variant.productId);if(product==null)continue;
   String color=Parser.mappedColor(product.sku,variant.sourceColor,state.globalRules,state.skuRules);
   String key=PhotoContract.key(product.sku)+"\u0000"+PhotoContract.key(color);
   PhotoCatalog.Variant previous=grouped.get(key);
   if(previous==null||!variant.currentPhotoVersionId.isEmpty()&&(previous.currentPhotoVersionId.isEmpty()
     ||revisions.getOrDefault(variant.id,-1L)>=revisions.getOrDefault(previous.id,-1L)))grouped.put(key,variant);
  }
  return new ArrayList<>(grouped.values());
 }
 public static List<PhotoCatalog.Variant> candidates(StateCodec.State state,Inventory.Row row){
  List<PhotoCatalog.Variant> result=new ArrayList<>();
  for(PhotoCatalog.Variant variant:catalogVariants(state)){
   if(variant.currentPhotoVersionId.isEmpty()||!state.catalog.assets.containsKey(variant.currentPhotoVersionId))continue;
   if(matchesRow(state,row,state.catalog.products.get(variant.productId),variant))result.add(variant);
  }
  return result;
 }
 /** Active drafts follow the latest registered photo; completed work remains frozen. */
 public static PhotoCatalog.Binding displayBinding(StateCodec.State state,Inventory.Row row){
  if(state==null||row==null)return null;
  if(state.draft==null||state.draft.completed||row.binding!=null&&row.binding.needsConfirmation)return row.binding==null?null:row.binding.copy();
  List<PhotoCatalog.Variant> matches=candidates(state,row);
  if(matches.size()!=1)return row.binding==null?null:row.binding.copy();
  PhotoCatalog.Variant variant=matches.get(0);
  return new PhotoCatalog.Binding(variant.productId,variant.id,variant.currentPhotoVersionId,row.sku,row.sourceColor,state.revision,false);
 }
 private static Inventory.Row editableRow(StateCodec.State state,String rowId,long revision){
  if(state==null||state.draft==null||state.draft.completed)throw new IllegalArgumentException("완료된 작업은 사진을 변경할 수 없습니다.");
  if(revision<=state.revision)throw new IllegalArgumentException("새 저장 revision이 필요합니다.");
  Inventory.Row row=findRow(state,rowId);if(row==null)throw new IllegalArgumentException("사진을 연결할 행을 찾을 수 없습니다.");return row;
 }
 private static void capture(StateCodec.State state,Inventory.Row row,PhotoCatalog.Binding next){
  UndoSnapshot u=new UndoSnapshot();u.kind=UndoSnapshot.PHOTO_BINDING;u.draftJobId=state.draft.id;u.targetRowId=row.rowId;u.previousBinding=row.binding==null?null:row.binding.copy();u.expectedAfterBinding=next==null?null:next.copy();u.expectedRow=row.copy();
  u.initialQuantity=state.draft.initialQuantity;u.stockUpdatedAt=state.draft.stockUpdatedAt;u.updatedAt=state.draft.updatedAt;
  String rule=UndoManager.effectivePhotoRule(state,row);
  if(rule!=null){u.ruleSku=row.sku;u.sourceColor=row.sourceColor.trim();u.previousRuleValue=rule;u.writtenRuleValue=rule;}
  if(!UndoManager.validPhotoRuleExpectation(u))throw new IllegalArgumentException("사진 규칙 기대값이 올바르지 않습니다.");
  state.undoSnapshot=u;
 }
}
