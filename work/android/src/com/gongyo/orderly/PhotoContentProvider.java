package com.gongyo.orderly;

import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.os.Process;
import com.gongyo.photo.*;
import java.io.*;
import java.util.*;

/** Signature and identity checked photo protocol; every operation shares the process repository. */
public final class PhotoContentProvider extends ContentProvider {
 @Override public boolean onCreate(){return true;}
 private Context owner(){Context c=getContext();if(c==null)throw new IllegalStateException("Photo owner unavailable");return c;}
 private void authorize(String permission){
  Context c=owner();int uid=Binder.getCallingUid(),pid=Binder.getCallingPid();
  if(c.checkPermission(permission,pid,uid)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Photo permission denied");
  PackageManager pm=c.getPackageManager();String calling=getCallingPackage();String[] packages=pm.getPackagesForUid(uid);
  if(packages==null)throw new SecurityException("Photo caller unavailable");
  String expected=uid==Process.myUid()?RuntimeIds.OWNER_PACKAGE:RuntimeIds.PHOTO_PACKAGE;
  if(!Arrays.asList(packages).contains(expected)||(calling!=null&&!expected.equals(calling)))throw new SecurityException("Photo caller identity denied");
  if(calling==null&&uid!=Process.myUid())throw new SecurityException("Missing photo caller package");
  if(pm.checkSignatures(RuntimeIds.OWNER_PACKAGE,expected)!=PackageManager.SIGNATURE_MATCH)throw new SecurityException("Photo caller signature denied");
 }
 @Override public Bundle call(String method,String arg,Bundle extras){
  // Authorization precedes all Bundle deserialization, including rejected methods/payloads.
  authorize("registerPhoto".equals(method)||"registerBarcode".equals(method)||"registerIntake".equals(method)?RuntimeIds.WRITE_PHOTOS:RuntimeIds.READ_PHOTOS);
  if(!Arrays.asList("getCompatibility","getCatalogPage","getReceipt","registerPhoto","registerBarcode","lookupBarcode","registerIntake","getStock").contains(method))throw new UnsupportedOperationException("Unknown photo method");
  PhotoContract.RegisterRequest parsed=null;Uri source=null;
  try{
   if(arg!=null)throw new IllegalArgumentException("Photo call arg forbidden");
   PhotoBundleCodec.request(extras,method);
   if(extras.containsKey("protocolVersion")&&PhotoBundleCodec.integer(extras,"protocolVersion")!=PhotoContract.PROTOCOL_VERSION)return error(PhotoContract.INCOMPATIBLE,"PROTOCOL_VERSION");
   if(method.equals("registerPhoto")){
    parsed=PhotoBundleCodec.readRegisterRequest(extras);source=PhotoBundleCodec.uri(extras);
   }else if(method.equals("getCatalogPage")){
    String query=PhotoBundleCodec.string(extras,"query");int offset=PhotoBundleCodec.integer(extras,"offset"),size=PhotoBundleCodec.integer(extras,"pageSize");
    if(query.length()>80||offset<0||size<1||size>50)throw new IllegalArgumentException("Photo page bounds");
    if(extras.containsKey("scope")&&!Arrays.asList("all","note").contains(PhotoBundleCodec.string(extras,"scope")))throw new IllegalArgumentException("Photo page scope");
    if(extras.containsKey("details")&&!"label".equals(PhotoBundleCodec.string(extras,"details")))throw new IllegalArgumentException("Photo page details");
    if(offset>0||extras.containsKey("generation")||extras.containsKey("revision")){PhotoCatalog.uuid(PhotoBundleCodec.string(extras,"generation"));if(PhotoBundleCodec.number(extras,"revision")<0)throw new IllegalArgumentException("Photo page revision");}
   }else if(method.equals("registerIntake")){
    PhotoCatalog.uuid(PhotoBundleCodec.string(extras,"requestId"));IntakeContract.fingerprint(PhotoBundleCodec.string(extras,"generation"),PhotoBundleCodec.string(extras,"sourceSha256"),PhotoBundleCodec.string(extras,"sku"),PhotoBundleCodec.string(extras,"sourceColor"),IntakeContract.counts(PhotoBundleCodec.string(extras,"quantities")));
   }else if(method.equals("getStock")){PhotoCatalog.text(PhotoBundleCodec.string(extras,"sku"),80);
   }else if(method.equals("getReceipt")){PhotoCatalog.uuid(PhotoBundleCodec.string(extras,"requestId"));PhotoCatalog.uuid(PhotoBundleCodec.string(extras,"generation"));PhotoCatalog.hash(PhotoBundleCodec.string(extras,"fingerprint"));}
   else if(method.equals("lookupBarcode")||method.equals("registerBarcode")){
    if(PhotoBundleCodec.integer(extras,"protocolVersion")!=PhotoContract.PROTOCOL_VERSION)return error(PhotoContract.INCOMPATIBLE,"PROTOCOL_VERSION");
    PhotoCatalog.barcode(PhotoBundleCodec.string(extras,"barcode"));
    if(method.equals("registerBarcode")){
     PhotoCatalog.uuid(PhotoBundleCodec.string(extras,"generation"));if(PhotoBundleCodec.number(extras,"revision")<0)throw new IllegalArgumentException("Barcode revision");
     String product=PhotoBundleCodec.string(extras,"productId"),variant=PhotoBundleCodec.string(extras,"variantId");if(!product.isEmpty())PhotoCatalog.uuid(product);if(!variant.isEmpty())PhotoCatalog.uuid(variant);
     PhotoCatalog.text(PhotoBundleCodec.string(extras,"sku"),PhotoLimits.MAX_SKU_CHARS);PhotoCatalog.text(PhotoBundleCodec.string(extras,"sourceColor"),PhotoLimits.MAX_SOURCE_COLOR_CHARS);
    }
   }
  }catch(BadParcelableException|IllegalArgumentException invalid){return error(PhotoContract.INVALID_REQUEST,"INVALID_FIELDS");}
   catch(IOException invalid){return error(PhotoContract.INVALID_REQUEST,"INVALID_FIELDS");}
  // Service/runtime faults are outside the input-validation catch; never guess invalid source from them.
  final PhotoContract.RegisterRequest request=parsed;final Uri uri=source;
  try{
   InventoryRepository repo=RepositoryOwner.get(owner());
   if(method.equals("getCompatibility")){
    InventoryRepository.Snapshot s=repo.snapshot();int code=owner().getPackageManager().getPackageInfo(RuntimeIds.OWNER_PACKAGE,0).versionCode;
    return PhotoBundleCodec.compatibility(new PhotoContract.Compatibility(1,s.generation,s.revision,code));
   }
   if(method.equals("getStock")){
    InventoryRepository.Snapshot current=repo.snapshot();String sku=PhotoBundleCodec.string(extras,"sku");StringBuilder summary=new StringBuilder();int matched=0;
    for(Inventory.Row row:current.stateCopy.draft.rows)if(PhotoContract.sameKey(sku,row.sku)){matched++;if(summary.length()<700)summary.append(row.color).append("  S ").append(row.quantities[0]).append(" · M ").append(row.quantities[1]).append(" · L ").append(row.quantities[2]).append(" · XL ").append(row.quantities[3]).append("\n");}
    summary.append(matched==0?"현재 작업에 등록된 재고 없음":"선택 품번 전체 "+Inventory.totalForSku(current.stateCopy.draft.rows,sku)+"개");
    Bundle stock=PhotoBundleCodec.base(PhotoContract.OK,current.generation,current.revision,"");stock.putString("stockSummary",summary.toString());PhotoBundleCodec.response(stock);return stock;
   }
   if(method.equals("registerIntake")){
    IntakeRegistration.Result result=IntakeRegistration.register(repo,PhotoBundleCodec.string(extras,"generation"),PhotoBundleCodec.string(extras,"requestId"),PhotoBundleCodec.string(extras,"sourceSha256"),PhotoBundleCodec.string(extras,"sku"),PhotoBundleCodec.string(extras,"sourceColor"),IntakeContract.counts(PhotoBundleCodec.string(extras,"quantities")));
    if(PhotoContract.OK.equals(result.status))owner().getContentResolver().notifyChange(Uri.parse("content://"+RuntimeIds.PHOTO_AUTHORITY),null);
    return PhotoBundleCodec.base(result.status,result.snapshot.generation,result.snapshot.revision,result.reason);
   }
   if(method.equals("getCatalogPage"))return catalog(repo.snapshot(),extras);
   if(method.equals("getReceipt"))return receipt(repo.snapshot(),extras);
   if(method.equals("lookupBarcode"))return barcodeReply(BarcodeRegistration.lookup(repo.snapshot(),PhotoBundleCodec.string(extras,"barcode")));
   if(method.equals("registerBarcode")){
    BarcodeRegistration.Result r=BarcodeRegistration.register(repo,PhotoBundleCodec.string(extras,"generation"),PhotoBundleCodec.number(extras,"revision"),PhotoBundleCodec.string(extras,"productId"),PhotoBundleCodec.string(extras,"variantId"),PhotoBundleCodec.string(extras,"sku"),PhotoBundleCodec.string(extras,"sourceColor"),PhotoBundleCodec.string(extras,"barcode"));
    if(PhotoContract.OK.equals(r.status))owner().getContentResolver().notifyChange(Uri.parse("content://"+RuntimeIds.PHOTO_AUTHORITY),null);
    return barcodeReply(r);
   }
   MediaStore media=new MediaStore(owner().getFilesDir(),new AndroidFileOps(owner().getFilesDir(),owner().getCacheDir()));
   PhotoRegistration.Result r=new PhotoRegistration(repo,media,new PhotoNormalizer()::normalize).register(request,()->{
    // Lazily validate only new source work; historical receipts never enter this callback.
    if(PhotoContract.preparedSourceNonce(uri.toString(),RuntimeIds.CAPTURE_AUTHORITY)==null)throw new SourceBoundaryException("Canonical prepared photo URI required");
    if(owner().checkUriPermission(uri,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED)throw new SourceBoundaryException("Temporary photo READ grant required");
    // Read under the owner identity that actually holds the temporary grant.
    long identity=Binder.clearCallingIdentity();try{InputStream in=owner().getContentResolver().openInputStream(uri);if(in==null)throw new IOException("Photo source unavailable");return in;}finally{Binder.restoreCallingIdentity(identity);}
   });
   if(PhotoContract.OK.equals(r.status))owner().getContentResolver().notifyChange(Uri.parse("content://"+RuntimeIds.PHOTO_AUTHORITY),null);
   return reply(r.status,request.requestId,r.latestSnapshot,r.receipt,r.reason);
  }catch(SourceBoundaryException invalid){return error(PhotoContract.INVALID_REQUEST,"SOURCE_URI_OR_GRANT");}
   catch(IOException failure){return error(PhotoContract.IO_ERROR,failure instanceof CommitUncertainException?"OWNER_UNCERTAIN":"PHOTO_IO");}
   catch(PackageManager.NameNotFoundException missing){return error(PhotoContract.IO_ERROR,"OWNER_UNAVAILABLE");}
 }
 private static Bundle error(String status,String reason){return PhotoBundleCodec.reply(new PhotoContract.Reply(status,"","",-1,"","","",reason));}
 private Bundle catalog(InventoryRepository.Snapshot s,Bundle b)throws IOException{
  String query=PhotoContract.key(PhotoBundleCodec.string(b,"query"));int offset=PhotoBundleCodec.integer(b,"offset"),size=PhotoBundleCodec.integer(b,"pageSize");
  if(query.length()>80||offset<0||size<1||size>50)throw new IllegalArgumentException("Photo page bounds");
  boolean token=b.containsKey("generation")||b.containsKey("revision");
  if(offset>0&&!token)throw new IllegalArgumentException("Photo page token required");
  if(token&&(!s.generation.equals(PhotoBundleCodec.string(b,"generation"))||s.revision!=PhotoBundleCodec.number(b,"revision")))return PhotoBundleCodec.base(PhotoContract.STALE_PAGE,s.generation,s.revision,"TOKEN_CHANGED");
  LinkedHashMap<String,Bundle> choices=new LinkedHashMap<>();
  boolean labelDetails="label".equals(b.getString("details",""));
  List<Inventory.Row> knownRows=new ArrayList<>(s.stateCopy.draft.rows);for(Inventory.Job job:s.stateCopy.jobs)knownRows.addAll(job.rows);
  for(Inventory.Row row:knownRows){
   String sku=PhotoContract.key(row.sku),color=PhotoContract.key(row.color),key=sku+"\u0000"+color;
   if(choices.containsKey(key)){if(labelDetails)addColorAlias(choices.get(key),row.sourceColor);continue;}
   List<PhotoCatalog.Variant> photos=PhotoBindings.candidates(s.stateCopy,row);
   PhotoCatalog.Variant v=photos.size()==1?photos.get(0):null;
   if(v==null){List<PhotoCatalog.Variant> identities=new ArrayList<>();for(PhotoCatalog.Variant candidate:PhotoBindings.catalogVariants(s.stateCopy))if(PhotoBindings.matchesRow(s.stateCopy,row,s.stateCopy.catalog.products.get(candidate.productId),candidate))identities.add(candidate);if(identities.size()==1)v=identities.get(0);}
   choices.put(key,catalogItem(v==null?"":v.productId,v==null?"":v.id,sku,color,v==null?"":v.currentPhotoVersionId,BarcodeRegistration.joined(s.stateCopy,row)));
   if(labelDetails){Bundle item=choices.get(key);addColorAlias(item,row.sourceColor);if(v!=null){addColorAlias(item,v.sourceColor);for(String alias:v.confirmedColorAliases)addColorAlias(item,alias);}}
  }
  if(!"note".equals(b.getString("scope","all")))for(PhotoCatalog.Variant v:PhotoBindings.catalogVariants(s.stateCopy)){
   PhotoCatalog.Product p=s.stateCopy.catalog.products.get(v.productId);
   String sku=PhotoContract.key(p.sku),color=PhotoContract.key(Parser.mappedColor(p.sku,v.sourceColor,s.stateCopy.globalRules,s.stateCopy.skuRules));
   String key=sku+"\u0000"+color;
   if(!choices.containsKey(key))choices.put(key,catalogItem(p.id,v.id,sku,color,v.currentPhotoVersionId,BarcodeRegistration.joined(s.stateCopy,BarcodeRegistration.row(sku,color))));
   if(labelDetails){Bundle item=choices.get(key);addColorAlias(item,v.sourceColor);for(String alias:v.confirmedColorAliases)addColorAlias(item,alias);}
  }
  ArrayList<Bundle> items=new ArrayList<>();int matched=0;boolean more=false;
  for(Bundle item:choices.values()){
   if(!query.isEmpty()&&!PhotoContract.key(item.getString("sku")).contains(query)&&!PhotoContract.key(item.getString("sourceColor")).contains(query)&&!PhotoContract.key(item.getString("barcodes")).contains(query))continue;
   if(matched++<offset)continue;
   if(items.size()==size){more=true;break;}
   items.add(item);
   Bundle bounded=PhotoBundleCodec.base(PhotoContract.OK,s.generation,s.revision,"");bounded.putParcelableArrayList("items",items);Parcel measured=Parcel.obtain();boolean exceeds;try{measured.writeBundle(bounded);exceeds=measured.dataSize()>PhotoBundleCodec.MAX_BUNDLE_BYTES-2048;}finally{measured.recycle();}if(exceeds){items.remove(items.size()-1);more=true;break;}
  }
  Bundle out=PhotoBundleCodec.base(PhotoContract.OK,s.generation,s.revision,"");out.putParcelableArrayList("items",items);out.putInt("nextOffset",more?offset+items.size():-1);PhotoBundleCodec.response(out);return out;
 }
 private static Bundle catalogItem(String product,String variant,String sku,String color,String photo,String barcodes){Bundle item=new Bundle();item.putString("productId",product);item.putString("variantId",variant);item.putString("sku",sku);item.putString("sourceColor",color);item.putString("photoVersionId",photo);item.putString("barcodes",barcodes);return item;}
 private static void addColorAlias(Bundle item,String value){String alias=PhotoContract.key(value);if(alias.isEmpty()||alias.indexOf('\n')>=0||alias.indexOf('\r')>=0)return;String current=item.getString("colorAliases","");for(String known:current.split("\n"))if(PhotoContract.sameKey(known,alias))return;String joined=current.isEmpty()?alias:current+"\n"+alias;if(joined.length()<=2048)item.putString("colorAliases",joined);}
 private static Bundle barcodeReply(BarcodeRegistration.Result r){
  PhotoCatalog.Variant variant=r.variant;StateCodec.State state=r.snapshot.stateCopy;PhotoCatalog.Product product=variant==null?null:state.catalog.products.get(variant.productId);
  String sku=product==null?"":PhotoContract.key(product.sku),color=variant==null?"":PhotoContract.key(Parser.mappedColor(sku,variant.sourceColor,state.globalRules,state.skuRules));
  Bundle out=PhotoBundleCodec.base(r.status,r.snapshot.generation,r.snapshot.revision,r.reason);out.putString("productId",product==null?"":product.id);out.putString("variantId",variant==null?"":variant.id);out.putString("sku",sku);out.putString("sourceColor",color);out.putString("photoVersionId",variant==null?"":variant.currentPhotoVersionId);out.putString("barcode",r.barcode);out.putString("barcodes",variant==null?"":BarcodeRegistration.joined(state,BarcodeRegistration.row(sku,color)));PhotoBundleCodec.response(out);return out;
 }
 private Bundle receipt(InventoryRepository.Snapshot s,Bundle b)throws IOException{
  String id=PhotoBundleCodec.string(b,"requestId"),fingerprint=PhotoBundleCodec.string(b,"fingerprint"),generation=PhotoBundleCodec.string(b,"generation");try{PhotoCatalog.uuid(id);PhotoCatalog.uuid(generation);PhotoCatalog.hash(fingerprint);}catch(IOException invalid){throw new IllegalArgumentException("Receipt fields",invalid);}
  if(!s.generation.equals(generation))return reply(PhotoContract.CONFLICT,id,s,null,"GENERATION_CHANGED");
  PhotoCatalog.Receipt r=s.stateCopy.catalog.receipts.get(id);
  if(r==null)return reply(PhotoContract.NOT_FOUND,id,s,null,"RECEIPT_NOT_FOUND");
  if(!r.requestContentFingerprint.equals(fingerprint))return reply(PhotoContract.INVALID_REQUEST,id,s,null,"REQUEST_ID_CONTENT_CHANGED");
  return reply(PhotoContract.OK,id,s,r,"");
 }
 private static Bundle reply(String status,String id,InventoryRepository.Snapshot s,PhotoCatalog.Receipt r,String reason){PhotoCatalog.Binding binding=r==null?null:r.binding;return PhotoBundleCodec.reply(new PhotoContract.Reply(status,id,s.generation,r==null?s.revision:r.committedRevision,binding==null?"":binding.productId,binding==null?"":binding.variantId,r==null?"":r.photoVersionId,reason));}
 private static final class SourceBoundaryException extends IllegalArgumentException {SourceBoundaryException(String message){super(message);}}
 @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
  authorize(RuntimeIds.READ_PHOTOS);
  ParcelFileDescriptor[] fd={null};try{
   if(!"r".equals(mode)||uri==null||!"content".equals(uri.getScheme())||!RuntimeIds.PHOTO_AUTHORITY.equals(uri.getAuthority())||uri.getQuery()!=null||uri.getFragment()!=null)throw new IOException("Read only photo URI required");
   List<String> path=uri.getPathSegments();if(path.size()!=2||!path.get(0).equals("media")||!path.get(1).matches("[0-9a-f]{64}\\.jpg")||!uri.getEncodedPath().equals("/media/"+path.get(1)))throw new IOException("Photo media path denied");
   String hash=path.get(1).substring(0,64);InventoryRepository repo=RepositoryOwner.get(owner());
   repo.exclusive(()->{MediaStore media=new MediaStore(owner().getFilesDir(),new AndroidFileOps(owner().getFilesDir(),owner().getCacheDir()));try(InputStream in=media.open(hash)){if(!(in instanceof FileInputStream))throw new IOException("Checked photo descriptor required");fd[0]=ParcelFileDescriptor.dup(((FileInputStream)in).getFD());}});
   return fd[0];
  }catch(IOException failure){if(fd[0]!=null)try{fd[0].close();}catch(IOException close){if(close!=failure)failure.addSuppressed(close);}FileNotFoundException e=new FileNotFoundException("Photo media unavailable");e.initCause(failure);throw e;}
 }
 @Override public String getType(Uri uri){authorize(RuntimeIds.READ_PHOTOS);throw new UnsupportedOperationException("Photo calls/openFile only");}
 @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){authorize(RuntimeIds.READ_PHOTOS);throw new UnsupportedOperationException("Photo calls/openFile only");}
 @Override public Uri insert(Uri u,ContentValues v){authorize(RuntimeIds.WRITE_PHOTOS);throw new UnsupportedOperationException("Photo registration call only");}
 @Override public int update(Uri u,ContentValues v,String s,String[] a){authorize(RuntimeIds.WRITE_PHOTOS);throw new UnsupportedOperationException("Photo registration call only");}
 @Override public int delete(Uri u,String s,String[] a){authorize(RuntimeIds.WRITE_PHOTOS);throw new UnsupportedOperationException("Photo registration call only");}
}
