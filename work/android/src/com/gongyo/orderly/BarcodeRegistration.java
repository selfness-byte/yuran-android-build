package com.gongyo.orderly;

import com.gongyo.photo.PhotoContract;
import java.io.IOException;
import java.util.*;

/** Barcode identity lives with the owner's backed-up catalogue, independently of photographs. */
public final class BarcodeRegistration {
 private BarcodeRegistration(){}
 public static final class Result {
  public final String status,reason,barcode;
  public final InventoryRepository.Snapshot snapshot;
  public final PhotoCatalog.Variant variant;
  Result(String status,String reason,String barcode,InventoryRepository.Snapshot snapshot,PhotoCatalog.Variant variant){this.status=status;this.reason=reason;this.barcode=barcode;this.snapshot=snapshot;this.variant=variant==null?null:variant.copy();}
 }
 public static Result register(InventoryRepository repository,String generation,long revision,String productId,String variantId,String sku,String color,String barcode)throws IOException {
  PhotoCatalog.uuid(generation);PhotoCatalog.require(revision>=0,"barcode revision");
  PhotoCatalog.require(productId!=null&&variantId!=null,"barcode selection");
  if(!productId.isEmpty())PhotoCatalog.uuid(productId);if(!variantId.isEmpty())PhotoCatalog.uuid(variantId);
  PhotoCatalog.text(sku,PhotoLimits.MAX_SKU_CHARS);PhotoCatalog.text(color,PhotoLimits.MAX_SOURCE_COLOR_CHARS);PhotoCatalog.barcode(barcode);
  final Result[] result={null};
  repository.exclusive(()->{
   InventoryRepository.Snapshot current=repository.snapshot();StateCodec.State state=current.stateCopy;
   if(!current.generation.equals(generation)){result[0]=new Result(PhotoContract.CONFLICT,"GENERATION_CHANGED",barcode,current,null);return;}
   if(current.revision!=revision){result[0]=new Result(PhotoContract.CONFLICT,"REVISION_CHANGED",barcode,current,null);return;}
   final PhotoRegistration.Target target;
   try{target=PhotoRegistration.target(state,productId,variantId,sku,color);}
   catch(IOException invalid){result[0]=new Result(PhotoContract.INVALID_REQUEST,"IDENTITY_MISMATCH",barcode,current,null);return;}
   Inventory.Row row=row(sku,color);row.color=Parser.mappedColor(sku,color,state.globalRules,state.skuRules);
   for(PhotoCatalog.Variant existing:state.catalog.variants.values())if(existing.barcodes.contains(barcode)){
    if(PhotoBindings.matchesRow(state,row,state.catalog.products.get(existing.productId),existing))
     result[0]=new Result(PhotoContract.OK,"ALREADY_REGISTERED",barcode,current,representative(state,existing));
    else result[0]=new Result(PhotoContract.CONFLICT,"BARCODE_ALREADY_LINKED",barcode,current,existing);
    return;
   }
   if((target.newProduct&&state.catalog.products.size()>=PhotoLimits.MAX_PRODUCTS)||(target.newVariant&&state.catalog.variants.size()>=PhotoLimits.MAX_VARIANTS)){
    result[0]=new Result(PhotoContract.LIMIT_REACHED,"CATALOG_LIMIT",barcode,current,null);return;
   }
   // Apply the cap to the displayed SKU/color, including legacy case-equivalent variants.
   if(barcodes(state,row).size()>=PhotoLimits.MAX_BARCODES_PER_VARIANT){result[0]=new Result(PhotoContract.LIMIT_REACHED,"BARCODE_LIMIT",barcode,current,target.variant);return;}
   InventoryRepository.CommitResult commit=repository.commit(generation,revision,(candidate,next)->{
    if(target.newProduct)candidate.catalog.products.put(target.product.id,target.product.copy());
    PhotoCatalog.Variant variant=target.variant.copy();variant.barcodes.add(barcode);candidate.catalog.variants.put(variant.id,variant);
   });
   PhotoCatalog.Variant saved=commit.latestSnapshot.stateCopy.catalog.variants.get(target.variant.id);
   result[0]=new Result(commit.committed?PhotoContract.OK:PhotoContract.CONFLICT,commit.committed?"":commit.conflictReason,barcode,commit.latestSnapshot,saved);
  });return result[0];
 }
 public static Result lookup(InventoryRepository.Snapshot snapshot,String barcode)throws IOException {
  PhotoCatalog.barcode(barcode);
  for(PhotoCatalog.Variant variant:snapshot.stateCopy.catalog.variants.values())if(variant.barcodes.contains(barcode))
   return new Result(PhotoContract.OK,"",barcode,snapshot,representative(snapshot.stateCopy,variant));
  return new Result(PhotoContract.NOT_FOUND,"BARCODE_NOT_REGISTERED",barcode,snapshot,null);
 }
 public static List<String> barcodes(StateCodec.State state,Inventory.Row row){
  LinkedHashSet<String> codes=new LinkedHashSet<>();
  for(PhotoCatalog.Variant variant:state.catalog.variants.values())if(PhotoBindings.matchesRow(state,row,state.catalog.products.get(variant.productId),variant))codes.addAll(variant.barcodes);
  return new ArrayList<>(codes);
 }
 public static String joined(StateCodec.State state,Inventory.Row row){List<String> codes=barcodes(state,row);return String.join("\n",codes.subList(0,Math.min(codes.size(),PhotoLimits.MAX_BARCODES_PER_VARIANT)));}
 public static Inventory.Row row(String sku,String color){Inventory.Row row=new Inventory.Row();row.sku=sku;row.sourceColor=color;row.color=color;return row;}
 private static PhotoCatalog.Variant representative(StateCodec.State state,PhotoCatalog.Variant original){
  PhotoCatalog.Product product=state.catalog.products.get(original.productId);Inventory.Row row=row(product.sku,original.sourceColor);row.color=Parser.mappedColor(product.sku,original.sourceColor,state.globalRules,state.skuRules);
  List<PhotoCatalog.Variant> choices=PhotoBindings.candidates(state,row);return choices.size()==1?choices.get(0):original;
 }
}
