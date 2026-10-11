package com.gongyo.orderly;

import com.gongyo.photo.PhotoContract;
import java.io.*;
import java.util.*;
import java.security.*;

/** The only catalogue registration writer. Source/media/pins and metadata share the owner lock. */
public final class PhotoRegistration {
 public interface PreparedSource {InputStream open()throws IOException;}
 public interface Normalizer {PreparedPhoto normalize(InputStream raw,File staging)throws IOException;}
 public static final class Result {
  public final String status,reason;public final PhotoCatalog.Receipt receipt;public final InventoryRepository.Snapshot latestSnapshot;
  private Result(String status,PhotoCatalog.Receipt receipt,InventoryRepository.Snapshot latestSnapshot,String reason){this.status=status;this.receipt=receipt==null?null:receipt.copy();this.latestSnapshot=latestSnapshot;this.reason=reason;}
 }
 private final InventoryRepository repo;private final MediaStore media;private final Normalizer normalizer;
 public PhotoRegistration(InventoryRepository repo,MediaStore media,Normalizer normalizer){this.repo=Objects.requireNonNull(repo);this.media=Objects.requireNonNull(media);this.normalizer=Objects.requireNonNull(normalizer);}
 public Result register(PhotoContract.RegisterRequest request,PreparedSource source)throws IOException {
  Result[] result={null};repo.exclusive(()->result[0]=registerLocked(request,source));return result[0];
 }
 private Result registerLocked(PhotoContract.RegisterRequest request,PreparedSource source)throws IOException {
  InventoryRepository.Snapshot current=repo.snapshot();
  if(request==null)return result(PhotoContract.INVALID_REQUEST,null,current,"MISSING_REQUEST");
  if(!current.generation.equals(request.generation))return result(PhotoContract.CONFLICT,null,current,"GENERATION_CHANGED");
  String fingerprint;
  try{validate(request);fingerprint=PhotoContract.fingerprint(request);}catch(IOException invalid){return result(PhotoContract.INVALID_REQUEST,null,current,"INVALID_FIELDS");}
  PhotoCatalog catalog=current.stateCopy.catalog;PhotoCatalog.Receipt old=catalog.receipts.get(request.requestId);
  if(old!=null)return old.requestContentFingerprint.equals(fingerprint)?result(PhotoContract.OK,old,current,null):result(PhotoContract.INVALID_REQUEST,null,current,"REQUEST_ID_CONTENT_CHANGED");
  if(current.revision!=request.revision)return result(PhotoContract.CONFLICT,null,current,"REVISION_CHANGED");
  if(catalog.receipts.size()>=PhotoLimits.MAX_RECEIPTS)return result(PhotoContract.LIMIT_REACHED,null,current,"RECEIPT_LIMIT");
  Target target;
  try{target=target(current.stateCopy,request);}catch(IOException invalid){return result(PhotoContract.INVALID_REQUEST,null,current,"IDENTITY_MISMATCH");}
  if(target.newProduct&&catalog.products.size()>=PhotoLimits.MAX_PRODUCTS)return result(PhotoContract.LIMIT_REACHED,null,current,"PRODUCT_LIMIT");
  if(target.newVariant&&catalog.variants.size()>=PhotoLimits.MAX_VARIANTS)return result(PhotoContract.LIMIT_REACHED,null,current,"VARIANT_LIMIT");
  if(source==null)return result(PhotoContract.INVALID_REQUEST,null,current,"MISSING_SOURCE");
  if(media.pins().size()>=PhotoLimits.MAX_PHOTO_ASSETS)return result(PhotoContract.LIMIT_REACHED,null,current,"PIN_LIMIT");
  current.stateCopy.nextRevision();
  PreparedPhoto prepared=null;String pinToken=null;Throwable primary=null;
  try {
   File staging=media.registrationStagingDirectory();
   // Catch only positively rejected incoming input, outside the source-close scope.
   try {
    InputStream opened=source.open();if(opened==null)throw new IOException("Source returned no stream");
    SourceInput input=new SourceInput(opened);Throwable sourceFailure=null;
    try {
     prepared=normalizer.normalize(input,staging);
     if(!input.eof)throw new IOException("Normalizer did not consume the complete source");
     if(!input.sourceHash().equals(request.sourceSha256))throw new InvalidPhotoSourceException(InvalidPhotoSourceException.Reason.MALFORMED,"SOURCE_SHA_MISMATCH");
    }catch(IOException|RuntimeException|Error failure){sourceFailure=failure;throw failure;}finally{closeInput(input,sourceFailure);}
   }catch(InvalidPhotoSourceException invalid){primary=invalid;return result(PhotoContract.INVALID_REQUEST,null,current,invalid.reason.name());}
   if(prepared==null||prepared.file==null)throw new IOException("Normalizer returned no prepared photo");
   PhotoCatalog.Asset asset=new PhotoCatalog.Asset(prepared.hash,prepared.byteLength,prepared.width,prepared.height);PhotoCatalog.hash(asset.hash);
   if(!catalog.assets.containsKey(asset.hash)&&catalog.assets.size()>=PhotoLimits.MAX_PHOTO_ASSETS)return result(PhotoContract.LIMIT_REACHED,null,current,"ASSET_LIMIT");
   InputStream normalized=media.openRegistrationPrepared(prepared.file);Throwable mediaFailure=null;
   try {
    pinToken=UUID.randomUUID().toString();media.pin(new PhotoCatalog.Pin(pinToken,PhotoCatalog.Pin.REGISTRATION,current.generation,Collections.singletonList(asset.hash),""));
    media.prepare(normalized,asset);
   }catch(IOException|RuntimeException|Error failure){mediaFailure=failure;throw failure;}finally{closeInput(normalized,mediaFailure);}
   final PhotoCatalog.Asset committedAsset=asset;final Target committedTarget=target;
   InventoryRepository.CommitResult commit=repo.commit(request.generation,request.revision,(candidate,next)->{
    PhotoCatalog c=candidate.catalog;
    if(committedTarget.newProduct)c.products.put(committedTarget.product.id,committedTarget.product.copy());
    PhotoCatalog.Variant variant=committedTarget.variant.copy();variant.currentPhotoVersionId=committedAsset.hash;c.variants.put(variant.id,variant);
    if(!c.assets.containsKey(committedAsset.hash))c.assets.put(committedAsset.hash,committedAsset.copy());
    PhotoCatalog.Binding binding=new PhotoCatalog.Binding(committedTarget.product.id,variant.id,committedAsset.hash,request.sku,request.sourceColor,next,false);
    c.receipts.put(request.requestId,new PhotoCatalog.Receipt(request.requestId,fingerprint,binding,committedAsset.hash,next));
   });
   if(!commit.committed)return result(PhotoContract.CONFLICT,null,commit.latestSnapshot,commit.conflictReason);
   return result(PhotoContract.OK,commit.latestSnapshot.stateCopy.catalog.receipts.get(request.requestId),commit.latestSnapshot,null);
  }catch(IOException|RuntimeException|Error failure){primary=failure;throw failure;}finally{
   // An uncertain publication needs its pin and prepared bytes for owner recovery.
   if(!(primary instanceof CommitUncertainException)){
    IOException cleanup=null;
    if(pinToken!=null)try{media.release(pinToken);}catch(IOException failure){cleanup=failure;}
    if(!(cleanup instanceof CommitUncertainException)&&prepared!=null&&prepared.file!=null)try{media.discardRegistrationPrepared(prepared.file);}catch(IOException failure){cleanup=combine(cleanup,failure);}
    if(cleanup!=null){if(primary==null)throw cleanup;if(cleanup instanceof CommitUncertainException){if(cleanup!=primary)cleanup.addSuppressed(primary);throw cleanup;}if(cleanup!=primary)primary.addSuppressed(cleanup);}
   }
  }
 }
 /** Preserve first-failure evidence; genuine uncertainty takes precedence over any earlier throwable. */
 private static void closeInput(InputStream input,Throwable primary)throws IOException {
  try{input.close();}catch(IOException|RuntimeException|Error close){
   if(primary==null)throw close;if(primary==close)return;
   if(close instanceof CommitUncertainException&&!(primary instanceof CommitUncertainException)){close.addSuppressed(primary);throw close;}
   primary.addSuppressed(close);
  }
 }
 private static IOException combine(IOException first,IOException later){if(first==null)return later;if(first==later)return first;if(later instanceof CommitUncertainException&&!(first instanceof CommitUncertainException)){later.addSuppressed(first);return later;}first.addSuppressed(later);return first;}
 /** Counts/hashes every read, including skip; EOF is required before immutable publication. */
 private static final class SourceInput extends DigestInputStream {
  long bytes;boolean eof;
  SourceInput(InputStream raw){super(raw,MediaStore.digest());}
  public int read()throws IOException{int value=super.read();count(value<0?-1:1);return value;}
  public int read(byte[] buffer,int offset,int length)throws IOException{int n=super.read(buffer,offset,length);count(n);return n;}
  private void count(int n)throws IOException{if(n<0)eof=true;else{if(n>PhotoLimits.MAX_INPUT_BYTES-bytes)throw new InvalidPhotoSourceException(InvalidPhotoSourceException.Reason.LIMIT_EXCEEDED,"Source exceeds 25MiB");bytes+=n;}}
  String sourceHash(){return MediaStore.hex(getMessageDigest().digest());}
  public long skip(long n)throws IOException{long skipped=0;byte[] buffer=new byte[4096];while(skipped<n){int count=read(buffer,0,(int)Math.min(buffer.length,n-skipped));if(count<0)break;if(count==0){if(read()<0)break;count=1;}skipped+=count;}return skipped;}
  public boolean markSupported(){return false;}public void mark(int limit){}public void reset()throws IOException{throw new IOException("Registration source cannot reset");}
 }
 private static Result result(String status,PhotoCatalog.Receipt receipt,InventoryRepository.Snapshot snapshot,String reason){return new Result(status,receipt,snapshot,reason);}
 private static void validate(PhotoContract.RegisterRequest r)throws IOException {
  PhotoCatalog.uuid(r.requestId);PhotoCatalog.uuid(r.generation);PhotoCatalog.require(r.revision>=0,"revision");
  PhotoCatalog.require(r.productId!=null&&r.variantId!=null,"selected identity");if(!r.productId.isEmpty())PhotoCatalog.uuid(r.productId);if(!r.variantId.isEmpty())PhotoCatalog.uuid(r.variantId);
  PhotoCatalog.text(r.sku,PhotoLimits.MAX_SKU_CHARS);PhotoCatalog.text(r.sourceColor,PhotoLimits.MAX_SOURCE_COLOR_CHARS);PhotoCatalog.hash(r.sourceSha256);
 }
 /** Read the exact registration target without changing catalogue, work IDs or photos. */
 public static final class Preview {
  public final String status,reason,productId,variantId,photoVersionId,sku,sourceColor;
  private Preview(String status,String reason,Target target,String sku,String color){
   this.status=status;this.reason=reason;this.sku=sku;sourceColor=color;
   productId=target==null||target.newProduct?"":target.product.id;
   variantId=target==null||target.newVariant?"":target.variant.id;
   photoVersionId=target==null?"":target.variant.currentPhotoVersionId;
  }
 }
 public static Preview preview(InventoryRepository.Snapshot current,String generation,long revision,String workId,String sku,String color)throws IOException{
  PhotoCatalog.uuid(generation);PhotoCatalog.uuid(workId);PhotoCatalog.require(revision>=0,"revision");
  PhotoCatalog.text(sku,PhotoLimits.MAX_SKU_CHARS);PhotoCatalog.text(color,PhotoLimits.MAX_SOURCE_COLOR_CHARS);
  sku=PhotoContract.key(sku);color=PhotoContract.key(color);
  if(!current.generation.equals(generation))return new Preview(PhotoContract.CONFLICT,"GENERATION_CHANGED",null,sku,color);
  if(current.revision!=revision)return new Preview(PhotoContract.CONFLICT,"REVISION_CHANGED",null,sku,color);
  if(current.stateCopy.draft.completed)return new Preview(PhotoContract.CONFLICT,"WORK_COMPLETED",null,sku,color);
  if(!current.stateCopy.draft.stockSessionId.equals(workId))return new Preview(PhotoContract.CONFLICT,"WORK_CHANGED",null,sku,color);
  try{return new Preview(PhotoContract.OK,"",target(current.stateCopy,"","",sku,color),sku,color);}
  catch(IOException mismatch){return new Preview(PhotoContract.INVALID_REQUEST,"IDENTITY_MISMATCH",null,sku,color);}
 }
 static final class Target {
  final PhotoCatalog.Product product;final PhotoCatalog.Variant variant;final boolean newProduct,newVariant;
  Target(PhotoCatalog.Product p,PhotoCatalog.Variant v,boolean np,boolean nv){product=p;variant=v;newProduct=np;newVariant=nv;}
 }
 /** Use canonical identity, confirmed aliases, and the owner's explicit color rules. */
 private static Target target(StateCodec.State state,PhotoContract.RegisterRequest r)throws IOException {return target(state,r.productId,r.variantId,r.sku,r.sourceColor);}
 static Target target(StateCodec.State state,String productId,String variantId,String sku,String sourceColor)throws IOException {
  PhotoCatalog c=state.catalog;
  PhotoCatalog.Product p=null;PhotoCatalog.Variant v=null;
  if(!variantId.isEmpty()){v=c.variants.get(variantId);PhotoCatalog.require(v!=null,"unknown selected variant");}
  if(!productId.isEmpty()){p=c.products.get(productId);PhotoCatalog.require(p!=null,"unknown selected product");}
  if(v!=null){PhotoCatalog.Product parent=c.products.get(v.productId);PhotoCatalog.require(p==null||p.id.equals(parent.id),"selected parent mismatch");p=parent;}
  if(p==null)for(PhotoCatalog.Product candidate:c.products.values())if(matches(sku,candidate.sku,candidate.confirmedSkuAliases)){p=candidate;break;}
  boolean newProduct=p==null;if(newProduct)p=new PhotoCatalog.Product(UUID.randomUUID().toString(),PhotoContract.key(sku),Collections.emptyList());else PhotoCatalog.require(matches(sku,p.sku,p.confirmedSkuAliases),"selected SKU mismatch");
  if(v==null)for(PhotoCatalog.Variant candidate:c.variants.values())if(candidate.productId.equals(p.id)&&matchesColor(state,sku,sourceColor,candidate)){v=candidate;break;}
  boolean newVariant=v==null;if(newVariant)v=new PhotoCatalog.Variant(UUID.randomUUID().toString(),p.id,PhotoContract.key(sourceColor),Collections.emptyList(),"");else PhotoCatalog.require(v.productId.equals(p.id)&&matchesColor(state,sku,sourceColor,v),"selected color mismatch");
  return new Target(p.copy(),v.copy(),newProduct,newVariant);
 }
 private static boolean matches(String text,String canonical,List<String> aliases){if(PhotoContract.sameKey(text,canonical))return true;for(String alias:aliases)if(PhotoContract.sameKey(text,alias))return true;return false;}
 private static boolean matchesColor(StateCodec.State state,String sku,String color,PhotoCatalog.Variant variant){
  if(matches(color,variant.sourceColor,variant.confirmedColorAliases))return true;
  String expected=Parser.mappedColor(sku,color,state.globalRules,state.skuRules);
  if(PhotoContract.sameKey(expected,Parser.mappedColor(sku,variant.sourceColor,state.globalRules,state.skuRules)))return true;
  for(String alias:variant.confirmedColorAliases)if(PhotoContract.sameKey(expected,Parser.mappedColor(sku,alias,state.globalRules,state.skuRules)))return true;
  return false;
 }
}
