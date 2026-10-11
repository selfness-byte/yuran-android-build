package com.gongyo.yuranphoto;

import android.content.*;
import android.content.pm.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.*;
import com.gongyo.photo.*;
import com.gongyo.orderly.*;
import java.io.*;
import java.security.*;
import java.util.*;

/** Offline client: one process session, durable preparation and explicit registration. */
public final class PhotoClient {
 public static final long MAX_SOURCE_BYTES=26214400L,MAX_PIXELS=40000000L;
 private static PhotoClient instance;
 public static synchronized PhotoClient get(Context context)throws IOException{if(instance==null)instance=new PhotoClient(context);return instance;}
 private final Context context;private final File files;private final CheckedAtomicFile.FileOps io;private final PendingRegistration pending;
 private final Object lock=new Object();private IOException unavailable;private boolean inflight;private PhotoContract.RegisterRequest confirmed;private String confirmedPath;private boolean confirmedAck;
 public PhotoClient(Context context)throws IOException{this(context,new AndroidFileOps(context.getApplicationContext().getFilesDir()));}
 PhotoClient(Context context,CheckedAtomicFile.FileOps io)throws IOException{
  this.context=context.getApplicationContext();files=this.context.getFilesDir();this.io=io;pending=new PendingRegistration(io);
  synchronized(lock){try{pending.load(files);validatePath(pending.request,pending.rawPath);confirmed=pending.request;confirmedPath=pending.rawPath;confirmedAck=pending.acknowledged;}catch(CheckedAtomicFile.MissingFileException absent){}catch(IOException e){unavailable=e;}}
 }
 public PhotoContract.RegisterRequest pendingRequest()throws IOException{synchronized(lock){ready();return confirmed;}}
 public boolean acknowledged()throws IOException{synchronized(lock){ready();return confirmedAck;}}
 public String rawPath()throws IOException{synchronized(lock){ready();return confirmedPath;}}
 private void ready()throws IOException{if(unavailable!=null)throw unavailable;}
 private void idle()throws IOException{ready();if(inflight)throw new IOException("등록 응답을 기다리는 중입니다. 입력을 유지합니다.");}
 private void fence(IOException e){if(e instanceof CommitUncertainException)unavailable=e;}
 public PhotoContract.Compatibility compatibility()throws IOException{
  synchronized(lock){ready();}
  try{
   PackageManager pm=context.getPackageManager();PackageInfo info=pm.getPackageInfo(RuntimeIds.OWNER_PACKAGE,0);
   if(info.versionCode<17)throw new IOException("유란노트 1.13.0 이상으로 업데이트해야 합니다.");
   if(pm.checkSignatures(RuntimeIds.PHOTO_PACKAGE,RuntimeIds.OWNER_PACKAGE)!=PackageManager.SIGNATURE_MATCH)throw new IOException("유란노트의 서명을 확인할 수 없습니다.");
   Bundle b=new Bundle();b.putInt("protocolVersion",1);Bundle response=call("getCompatibility",b);PhotoBundleCodec.response(response);
   if(!PhotoContract.OK.equals(response.getString("status")))throw new IOException("유란노트가 준비되지 않았습니다: "+response.getString("reason"));
   PhotoContract.Compatibility c=PhotoBundleCodec.readCompatibility(response);if(c.protocolVersion!=1||c.noteVersionCode<17||c.revision<0)throw new IOException("유란노트 사진 연동 버전이 호환되지 않습니다.");return c;
  }catch(PackageManager.NameNotFoundException e){throw new IOException("유란노트를 먼저 설치해야 합니다. 준비 사진은 유지합니다.",e);}catch(IllegalArgumentException|SecurityException e){throw new IOException("유란노트 사진 연결을 사용할 수 없습니다. 준비 사진은 유지합니다.",e);}
 }
 public Bundle catalogPage(String query,int offset,String generation,long revision)throws IOException{return catalogPage(query,offset,generation,revision,false);}
 public Bundle noteCatalogPage(String query,int offset,String generation,long revision)throws IOException{return catalogPage(query,offset,generation,revision,true);}
 public Bundle labelCatalogPage(String query,int offset,String generation,long revision)throws IOException{
  synchronized(lock){ready();}PhotoContract.Compatibility c=compatibility();if(c.noteVersionCode<23)throw new IOException("사진 구별을 사용하려면 유란노트 1.17.1 이상으로 업데이트해 주세요.");
  query=PhotoContract.key(query);if(query.length()>80||offset<0)throw new IOException("검색 입력을 확인해 주세요.");Bundle b=new Bundle();b.putInt("protocolVersion",1);b.putString("query",query);b.putInt("offset",offset);b.putInt("pageSize",50);b.putString("details","label");if(offset>0){b.putString("generation",generation);b.putLong("revision",revision);}Bundle result=call("getCatalogPage",b);PhotoBundleCodec.response(result);return result;
 }
 private Bundle catalogPage(String query,int offset,String generation,long revision,boolean noteOnly)throws IOException{
  synchronized(lock){ready();}
  query=PhotoContract.key(query);compatibility();
  if(query.length()>80||offset<0)throw new IOException("검색 입력을 확인해 주세요.");Bundle b=new Bundle();b.putInt("protocolVersion",1);b.putString("query",query);b.putInt("offset",offset);b.putInt("pageSize",50);if(noteOnly)b.putString("scope","note");if(offset>0){b.putString("generation",generation);b.putLong("revision",revision);}Bundle result=call("getCatalogPage",b);PhotoBundleCodec.response(result);return result;
 }
 public Bundle registerIntake(String generation,String requestId,String sourceHash,String sku,String color,long[] quantities)throws IOException{
  compatibility();IntakeContract.fingerprint(generation,sourceHash,sku,color,quantities);
  Bundle input=new Bundle();input.putInt("protocolVersion",1);input.putString("generation",generation);input.putString("requestId",requestId);input.putString("sourceSha256",sourceHash);input.putString("sku",sku);input.putString("sourceColor",color);input.putString("quantities",IntakeContract.counts(quantities));PhotoBundleCodec.request(input,"registerIntake");
  Bundle reply=call("registerIntake",input);PhotoBundleCodec.response(reply);return reply;
 }
 public String stockSummary(String sku)throws IOException{
  compatibility();Bundle input=new Bundle();input.putInt("protocolVersion",1);input.putString("sku",PhotoContract.key(sku));Bundle reply=call("getStock",input);PhotoBundleCodec.response(reply);
  if(!PhotoContract.OK.equals(reply.getString("status")))throw new IOException("재고를 확인하지 못했습니다. "+reply.getString("reason",""));return reply.getString("stockSummary","");
 }
 public Bundle lookupBarcode(String barcode)throws IOException{
  compatibility();Bundle input=new Bundle();input.putInt("protocolVersion",1);input.putString("barcode",barcodeText(barcode));Bundle result=call("lookupBarcode",input);PhotoBundleCodec.response(result);return result;
 }
 public Bundle registerBarcode(PhotoContract.Compatibility current,String product,String variant,String sku,String color,String barcode)throws IOException{
  synchronized(lock){ready();}compatibility();if(current==null||current.protocolVersion!=1)throw new IOException("유란노트 연결을 다시 확인해 주세요.");sku=PhotoContract.key(sku);color=PhotoContract.key(color);if(sku.isEmpty()||sku.length()>80||color.isEmpty()||color.length()>60)throw new IOException("품번과 색상을 먼저 입력하세요.");Bundle input=new Bundle();input.putInt("protocolVersion",1);input.putString("generation",current.generation);input.putLong("revision",current.revision);input.putString("productId",product);input.putString("variantId",variant);input.putString("sku",sku);input.putString("sourceColor",color);input.putString("barcode",barcodeText(barcode));Bundle result=call("registerBarcode",input);PhotoBundleCodec.response(result);return result;
 }
 public static String barcodeText(String value)throws IOException{if(value==null)throw new IOException("먼저 바코드를 촬영하세요.");String text=value.trim();if(text.isEmpty()||text.length()>128)throw new IOException("바코드는 1~128자여야 합니다.");for(int i=0;i<text.length();i++)if(Character.isISOControl(text.charAt(i)))throw new IOException("등록할 수 없는 바코드 문자입니다.");return text;}
 public Bitmap registeredPhoto(String hash)throws IOException{
  compatibility();if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new IOException("등록된 사진을 찾을 수 없습니다.");
  Uri uri=Uri.parse("content://"+RuntimeIds.PHOTO_AUTHORITY+"/media/"+hash+".jpg");BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
  try(InputStream in=context.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,bounds);}
  if(bounds.outWidth<=0||bounds.outHeight<=0||(long)bounds.outWidth*bounds.outHeight>MAX_PIXELS)throw new IOException("등록된 사진을 읽을 수 없습니다.");
  BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>600)options.inSampleSize*=2;
  try(InputStream in=context.getContentResolver().openInputStream(uri)){Bitmap image=BitmapFactory.decodeStream(in,null,options);if(image==null)throw new IOException("등록된 사진을 읽을 수 없습니다.");return image;}
 }
 private Bundle call(String method,Bundle input)throws IOException{try{Bundle result=context.getContentResolver().call(Uri.parse("content://"+RuntimeIds.PHOTO_AUTHORITY),method,null,input);if(result==null)throw new IOException("유란노트가 응답하지 않았습니다. 같은 요청을 유지합니다.");return result;}catch(IllegalArgumentException|SecurityException e){throw new IOException("사진 연결 요청을 사용할 수 없습니다. 같은 요청을 유지합니다.",e);}}
 public Uri reserveCamera()throws IOException{synchronized(lock){File owned=null;try{idle();if(confirmed!=null)throw new IOException("먼저 대기 중인 사진을 등록하거나 취소해 주세요.");File dir=new File(files,"capture");CheckedAtomicFile.directory(dir,io);String nonce=UUID.randomUUID().toString();File file=new File(dir,nonce+".jpg");OutputStream out=io.createExclusive(file);owned=file;Throwable failure=null;try{io.sync(out);}catch(Throwable e){failure=e;}failure=close(out,failure);if(failure!=null)throwFailure(failure);io.syncDirectory(dir);return Uri.parse("content://"+RuntimeIds.CAPTURE_AUTHORITY+PhotoContract.CAMERA_OUTPUT_PATH_PREFIX+nonce+PhotoContract.CAMERA_OUTPUT_SUFFIX);
  }catch(Throwable e){if(e instanceof IOException)fence((IOException)e);if(owned!=null&&unavailable==null)try{removeRaw(owned);}catch(Throwable cleanup){e=prefer(e,cleanup);if(e instanceof IOException)fence((IOException)e);}throwFailure(e);return null;}}}
 public void finishCamera(Uri uri)throws IOException{synchronized(lock){try{idle();File f=cameraFile(uri);context.revokeUriPermission(uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION);removeRaw(f);}catch(IOException e){fence(e);throw e;}}}
 /** Internal read only access to this app's reserved label capture, never a registration. */
 public InputStream openLabelCamera(Uri uri)throws IOException{synchronized(lock){idle();return io.openRead(cameraFile(uri));}}
 public PhotoContract.RegisterRequest prepareCamera(Uri uri,PhotoContract.Compatibility c,String product,String variant,String sku,String color)throws IOException{
  InputStream in;synchronized(lock){idle();File f=cameraFile(uri);context.revokeUriPermission(uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION);in=io.openRead(f);}
  PhotoContract.RegisterRequest r=prepare(in,c,product,variant,sku,color);finishCamera(uri);return r;
 }
 public PhotoContract.RegisterRequest prepare(InputStream input,PhotoContract.Compatibility c,String product,String variant,String sku,String color)throws IOException{
  synchronized(lock){File raw=null;Throwable failure=null;PhotoContract.RegisterRequest result=null;
   try{idle();sku=PhotoContract.key(sku);color=PhotoContract.key(color);if(confirmed!=null)throw new IOException("대기 중인 등록을 먼저 완료하거나 취소해 주세요.");if(input==null)throw new IOException("선택한 사진을 읽을 수 없습니다.");
    // Validate proposed identity before allocating a file.
    if(c==null||c.protocolVersion!=1||c.noteVersionCode<17||sku==null||sku.isEmpty()||sku.length()>80||color==null||color.isEmpty()||color.length()>60)throw new IOException("품번과 원문 색상, 유란노트 연결을 확인해 주세요.");
    File dir=new File(files,"prepared");CheckedAtomicFile.directory(dir,io);String nonce=UUID.randomUUID().toString();raw=new File(dir,nonce+".input");MessageDigest digest=digest();OutputStream out=io.createExclusive(raw);Throwable copyFailure=null;
    try{byte[] block=new byte[32768];long total=0;for(int n;(n=input.read(block))!=-1;){if(n==0)continue;if(n>MAX_SOURCE_BYTES-total)throw new IOException("사진은 25MiB 이하여야 합니다.");total+=n;io.write(out,block,0,n);digest.update(block,0,n);}if(total==0)throw new IOException("카메라 결과 또는 선택한 사진이 비어 있습니다.");io.sync(out);}catch(Throwable e){copyFailure=e;}copyFailure=close(out,copyFailure);if(copyFailure!=null)throwFailure(copyFailure);
    failure=close(input,null);input=null;if(failure!=null)throwFailure(failure);io.syncDirectory(dir);checkImage(raw);
    String uri="content://"+RuntimeIds.CAPTURE_AUTHORITY+PhotoContract.PREPARED_SOURCE_PATH_PREFIX+nonce+PhotoContract.PREPARED_SOURCE_SUFFIX;
    PhotoContract.RegisterRequest candidate=new PhotoContract.RegisterRequest(UUID.randomUUID().toString(),c.generation,c.revision,product,variant,sku,color,hex(digest.digest()),uri);
    pending.request=candidate;pending.rawPath=raw.getPath();pending.acknowledged=false;pending.save(files);confirmed=candidate;confirmedPath=raw.getPath();confirmedAck=false;result=candidate;
   }catch(Throwable e){failure=prefer(failure,e);if(e instanceof IOException)fence((IOException)e);}finally{if(input!=null)failure=close(input,failure);}
   if(failure!=null){if(failure instanceof IOException)fence((IOException)failure);if(raw!=null&&unavailable==null&&confirmed==null){try{removeRaw(raw);}catch(Throwable cleanup){failure=prefer(failure,cleanup);}}throwFailure(failure);}return result;
  }
 }
 private void checkImage(File raw)throws IOException{
  byte[] head=new byte[8];try(InputStream in=io.openRead(raw)){int n=0;for(int x;n<8&&(x=in.read(head,n,8-n))!=-1;)n+=x;if(n<8||!((head[0]==(byte)255&&head[1]==(byte)216&&head[2]==(byte)255)||(head[0]==(byte)137&&head[1]==80&&head[2]==78&&head[3]==71&&head[4]==13&&head[5]==10&&head[6]==26&&head[7]==10)))throw new IOException("JPEG 또는 PNG 사진만 선택할 수 있습니다.");}
  BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;try(InputStream in=io.openRead(raw)){BitmapFactory.decodeStream(in,null,options);}if(options.outWidth<=0||options.outHeight<=0)throw new IOException("사진을 해독할 수 없습니다.");if((long)options.outWidth*options.outHeight>MAX_PIXELS)throw new IOException("사진은 4천만 화소 이하여야 합니다.");
 }
 private File validatePath(PhotoContract.RegisterRequest r,String path)throws IOException{String nonce=r==null?null:PhotoContract.preparedSourceNonce(r.sourceUri,RuntimeIds.CAPTURE_AUTHORITY);if(nonce==null)throw new IOException("준비 사진 주소가 올바르지 않습니다.");File f=new File(new File(files,"prepared"),nonce+".input");if(!f.getPath().equals(path))throw new IOException("준비 사진 경로가 올바르지 않습니다.");return f;}
 private File cameraFile(Uri uri)throws IOException{String nonce=PhotoContract.cameraOutputNonce(uri==null?null:uri.toString(),RuntimeIds.CAPTURE_AUTHORITY);if(nonce==null)throw new IOException("카메라 주소가 올바르지 않습니다.");return new File(new File(files,"capture"),nonce+".jpg");}
 private void validateRaw()throws IOException{File f=validatePath(confirmed,confirmedPath);CheckedAtomicFile.directory(f.getParentFile(),io);MessageDigest d=digest();long total=0;try(InputStream in=io.openRead(f)){byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;){if(n>MAX_SOURCE_BYTES-total)throw new IOException("준비 사진 크기 초과");total+=n;d.update(b,0,n);}}if(total==0||!hex(d.digest()).equals(confirmed.sourceSha256))throw new IOException("준비 사진이 없거나 변경되었습니다. 같은 요청의 등록 결과를 확인해 주세요.");io.syncFile(f);io.syncDirectory(f.getParentFile());}
 public PhotoContract.Reply register(PhotoContract.RegisterRequest request)throws IOException{return exchange(request,false);}
 public PhotoContract.Reply getReceipt()throws IOException{PhotoContract.RegisterRequest r;synchronized(lock){idle();r=confirmed;if(r==null)throw new IOException("대기 중인 요청이 없습니다.");}return exchange(r,true);}
 private PhotoContract.Reply exchange(PhotoContract.RegisterRequest r,boolean receipt)throws IOException{
  synchronized(lock){idle();if(confirmedAck)throw new IOException("이미 등록이 확인되었습니다. 남은 파일 정리만 가능합니다.");if(!same(r,confirmed))throw new IOException("현재 대기 요청과 일치하지 않습니다.");if(!receipt)validateRaw();inflight=true;}
  PhotoContract.Reply response=null;Throwable failure=null;
  try{
   compatibility(); // Recheck actual counterpart identity before granting or accepting an ACK.
   if(receipt){Bundle b=new Bundle();b.putInt("protocolVersion",1);b.putString("requestId",r.requestId);b.putString("generation",r.generation);b.putString("fingerprint",PhotoContract.fingerprint(r));response=PhotoBundleCodec.readReply(call("getReceipt",b));}
   else{context.grantUriPermission(RuntimeIds.OWNER_PACKAGE,Uri.parse(r.sourceUri),Intent.FLAG_GRANT_READ_URI_PERMISSION);response=PhotoBundleCodec.readReply(call("registerPhoto",PhotoBundleCodec.registerRequest(r)));}
  }catch(Throwable e){failure=e;}
  synchronized(lock){try{ready();if(!same(r,confirmed))throw new IOException("등록 응답이 현재 요청과 일치하지 않습니다.");if(failure==null&&PhotoContract.OK.equals(response.status)){
    if(!r.requestId.equals(response.requestId)||!r.generation.equals(response.generation)||response.revision<0||response.photoVersionId==null||!response.photoVersionId.matches("[0-9a-f]{64}")||!uuid(response.productId)||!uuid(response.variantId))throw new IOException("등록 ACK를 확인할 수 없습니다. 원래 요청을 유지합니다.");
    pending.request=confirmed;pending.rawPath=confirmedPath;pending.acknowledged=true;
    try{pending.save(files);}catch(IOException e){pending.acknowledged=false;throw e;}confirmedAck=true;cleanupAcknowledged();
   }}catch(Throwable e){failure=prefer(failure,e);if(e instanceof IOException)fence((IOException)e);}finally{inflight=false;}}
  if(failure!=null)throwFailure(failure);return response;
 }
 public void cleanupAcknowledged()throws IOException{synchronized(lock){ready();if(!confirmedAck||confirmed==null)throw new IOException("확인된 등록이 없습니다.");try{context.revokeUriPermission(Uri.parse(confirmed.sourceUri),Intent.FLAG_GRANT_READ_URI_PERMISSION);removeRaw(validatePath(confirmed,confirmedPath));pending.clear(files);confirmed=null;confirmedPath=null;confirmedAck=false;}catch(IOException e){fence(e);throw e;}}}
 public void cancel()throws IOException{synchronized(lock){idle();if(confirmed==null)return;if(confirmedAck){cleanupAcknowledged();return;}PhotoContract.RegisterRequest old=confirmed;File f=validatePath(old,confirmedPath);try{pending.clear(files);}catch(IOException e){fence(e);throw e;}confirmed=null;confirmedPath=null;confirmedAck=false;context.revokeUriPermission(Uri.parse(old.sourceUri),Intent.FLAG_GRANT_READ_URI_PERMISSION);removeRaw(f);}}
 /** Returns null when the original success was recovered and durably cleaned. */
 public PhotoContract.RegisterRequest reconfirm(PhotoContract.Compatibility current,String product,String variant,String sku,String color)throws IOException{
  sku=PhotoContract.key(sku);color=PhotoContract.key(color);
  if(sku.isEmpty()||sku.length()>80||color.isEmpty()||color.length()>60)throw new IOException("품번과 색상을 확인해 주세요.");
  PhotoContract.RegisterRequest old;synchronized(lock){idle();if(confirmed==null||confirmedAck)throw new IOException("재확인할 준비 사진이 없습니다.");old=confirmed;}
  PhotoContract.Reply receipt=exchange(old,true); // Match the captured identity; never hold the session lock across the owner call.
  if(PhotoContract.OK.equals(receipt.status))return null;
  if(!PhotoContract.NOT_FOUND.equals(receipt.status)&&!(PhotoContract.CONFLICT.equals(receipt.status)&&"GENERATION_CHANGED".equals(receipt.reason)))throw new IOException("원래 요청의 등록 결과를 확인하지 못했습니다. 원래 요청과 사진을 유지합니다: "+receipt.status);
  synchronized(lock){idle();if(!same(old,confirmed)||confirmedAck)throw new IOException("현재 요청이 바뀌었습니다. 원래 등록 결과를 확인해 주세요.");validateRaw();PhotoContract.RegisterRequest r=new PhotoContract.RegisterRequest(UUID.randomUUID().toString(),current.generation,current.revision,product,variant,sku,color,old.sourceSha256,old.sourceUri);pending.request=r;pending.rawPath=confirmedPath;pending.acknowledged=false;try{pending.save(files);}catch(IOException e){pending.request=old;fence(e);throw e;}confirmed=r;return r;}
 }
 private void removeRaw(File f)throws IOException{CheckedAtomicFile.directory(f.getParentFile(),io);CheckedAtomicFile.Kind kind=io.inspect(f);if(kind==CheckedAtomicFile.Kind.REGULAR)io.unlink(f);else if(kind!=CheckedAtomicFile.Kind.MISSING)throw new IOException("준비 파일 형식을 확인할 수 없습니다.");io.syncDirectory(f.getParentFile());}
 /** Framework grants plus checked fixed-route descriptor mapping; no arbitrary URI to file conversion. */
 ParcelFileDescriptor open(Uri uri,String mode)throws IOException{synchronized(lock){ready();String p=PhotoContract.preparedSourceNonce(uri.toString(),RuntimeIds.CAPTURE_AUTHORITY),c=PhotoContract.cameraOutputNonce(uri.toString(),RuntimeIds.CAPTURE_AUTHORITY);File f;boolean write;
   if(p!=null&&mode.equals("r")){f=new File(new File(files,"prepared"),p+".input");write=false;}
   else if(c!=null&&(mode.equals("w")||mode.equals("wt"))){f=cameraFile(uri);write=true;}
   else throw new FileNotFoundException("허용되지 않는 사진 경로 또는 모드");
   int grant=write?Intent.FLAG_GRANT_WRITE_URI_PERMISSION:Intent.FLAG_GRANT_READ_URI_PERMISSION;
   if(Binder.getCallingUid()!=android.os.Process.myUid()&&context.checkUriPermission(uri,Binder.getCallingPid(),Binder.getCallingUid(),grant)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Actual temporary photo grant required");
   CheckedAtomicFile.directory(f.getParentFile(),io);if(io.inspect(f)!=CheckedAtomicFile.Kind.REGULAR)throw new FileNotFoundException("준비 사진을 열 수 없습니다: "+f.getName());
   if(!write){InputStream in=io.openRead(f);ParcelFileDescriptor fd=null;Throwable failure=null;try{fd=ParcelFileDescriptor.dup(((FileInputStream)in).getFD());}catch(Throwable e){failure=e;}failure=close(in,failure);if(failure!=null){if(fd!=null)failure=close(fd,failure);throwFailure(failure);}return fd;}
   // Camera may write only a pre-reserved regular file. O_NOFOLLOW and descriptor identity prevent aliases.
   FileDescriptor nativeFd=null;ParcelFileDescriptor cameraFd=null;Throwable failure=null;
   try{android.system.StructStat before=android.system.Os.lstat(f.getPath());nativeFd=android.system.Os.open(f.getPath(),android.system.OsConstants.O_WRONLY|android.system.OsConstants.O_NOFOLLOW|android.system.OsConstants.O_NONBLOCK,0);android.system.StructStat after=android.system.Os.fstat(nativeFd);if(!android.system.OsConstants.S_ISREG(after.st_mode)||before.st_dev!=after.st_dev||before.st_ino!=after.st_ino)throw new IOException("Camera descriptor changed");if(mode.equals("wt"))android.system.Os.ftruncate(nativeFd,0);cameraFd=ParcelFileDescriptor.dup(nativeFd);}catch(android.system.ErrnoException e){failure=new IOException(e);}catch(Throwable e){failure=e;}
   if(nativeFd!=null)try{android.system.Os.close(nativeFd);}catch(android.system.ErrnoException e){failure=prefer(failure,new IOException(e));}
   if(failure!=null){if(cameraFd!=null)failure=close(cameraFd,failure);throwFailure(failure);}return cameraFd;
  }}
 private static boolean uuid(String s){return s!=null&&s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");}
 private static boolean same(PhotoContract.RegisterRequest a,PhotoContract.RegisterRequest b)throws IOException{return a!=null&&b!=null&&a.sourceUri.equals(b.sourceUri)&&PhotoContract.fingerprint(a).equals(PhotoContract.fingerprint(b));}
 private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
 private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return s.toString();}
 private Throwable close(Closeable c,Throwable primary){try{io.close(c);}catch(Throwable e){return prefer(primary,e);}return primary;}
 private static Throwable prefer(Throwable a,Throwable b){if(a==null)return b;if(a==b)return a;if(b instanceof CommitUncertainException&&!(a instanceof CommitUncertainException)||b instanceof Error&&!(a instanceof Error)&&!(a instanceof CommitUncertainException)){if(!reaches(a,b))b.addSuppressed(a);return b;}if(!reaches(b,a))a.addSuppressed(b);return a;}
 private static boolean reaches(Throwable start,Throwable target){Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());ArrayDeque<Throwable> todo=new ArrayDeque<>();todo.add(start);while(!todo.isEmpty()){Throwable value=todo.remove();if(value==target)return true;if(!seen.add(value))continue;if(value.getCause()!=null)todo.add(value.getCause());Collections.addAll(todo,value.getSuppressed());}return false;}
 private static void throwFailure(Throwable e)throws IOException{if(e instanceof IOException)throw (IOException)e;if(e instanceof RuntimeException)throw (RuntimeException)e;if(e instanceof Error)throw (Error)e;throw new IOException(e);}
}
