package com.gongyo.yuranphoto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import com.gongyo.orderly.*;
import com.gongyo.photo.*;
import org.json.*;
import java.io.*;
import java.security.*;
import java.util.*;

/** Durable private label inbox. Photo publication precedes audio; stock has a separate receipt. */
public final class CaptureInbox {
 private static CaptureInbox instance;
 private final Map<String,CaptureRecovery> recovery=new HashMap<>();
 public synchronized CaptureRecovery recovery(String mode)throws IOException{check();CaptureRecovery value=recovery.get(mode);if(value==null){value=new CaptureRecovery(directory,io,mode);recovery.put(mode,value);}return value;}
 public static synchronized CaptureInbox get(Context context)throws IOException{if(instance==null)instance=new CaptureInbox(context.getApplicationContext());return instance;}
 public static final class Entry {
  public String id="",generation="",targetWorkId="",sourceHash="",imageHash="",sku="",color="",text="",mode="",message="";
  public long createdAt;public long[] quantities=new long[4];public boolean ready,submitted,committed,photoCommitted;public int reviewVersion;
  public boolean complete(){return committed&&photoCommitted;}
 }
 private static final int MAX_ENTRIES=500,MAX_INDEX=8*1024*1024,MAX_IMAGE=8*1024*1024;
 private static final long MAX_DISK=256L*1024*1024;
 private final android.content.ContentResolver resolver;
 private final File directory;private final CheckedAtomicFile.FileOps io;private final CheckedAtomicFile index;private IOException unavailable;
 private CaptureInbox(Context context)throws IOException{
  resolver=context.getContentResolver();io=new AndroidFileOps(context.getFilesDir());directory=new File(context.getFilesDir(),"label-inbox");CheckedAtomicFile.directory(directory,io);index=new CheckedAtomicFile(new File(directory,"index.json"),io);
  entries();
 }
 private void check()throws IOException{if(unavailable!=null)throw unavailable;}
 private void fence(IOException e){if(e instanceof CommitUncertainException)unavailable=e;}
 public synchronized List<Entry> entries()throws IOException{
  check();try{return decode(index.readFully(MAX_INDEX,bytes->decode(bytes)));}catch(CheckedAtomicFile.MissingFileException absent){return new ArrayList<>();}catch(IOException e){fence(e);throw e;}
 }
 public synchronized Entry entry(String id)throws IOException{for(Entry e:entries())if(e.id.equals(id))return e;throw new IOException("저장된 사진을 찾지 못했습니다.");}
 private void persist(List<Entry> entries)throws IOException{
  try{JSONArray array=new JSONArray();for(Entry e:entries)array.put(json(e));byte[] bytes=array.toString().getBytes("UTF-8");if(bytes.length>MAX_INDEX)throw new IOException("사진 기록 보관 용량이 가득 찼습니다.");decode(bytes);index.write(bytes);try{resolver.notifyChange(android.net.Uri.parse("content://"+RuntimeIds.CAPTURE_AUTHORITY+"/inbox"),null);}catch(RuntimeException ignored){}}
  catch(JSONException e){throw new IOException("사진 기록을 저장하지 못했습니다.",e);}catch(IOException e){fence(e);throw e;}
 }
 public Entry save(Bitmap image,String originalHash,String text,String mode,String generation,String targetWorkId,String resolvedSku)throws IOException{
  if(image==null)throw new IOException("저장할 사진이 없습니다.");
  ByteArrayOutputStream out=new ByteArrayOutputStream();if(!image.compress(Bitmap.CompressFormat.JPEG,88,out))throw new IOException("사진을 압축하지 못했습니다.");byte[] bytes=out.toByteArray();
  String imageHash=hash(bytes),sourceHash=originalHash==null?imageHash:originalHash;
  if(!sourceHash.matches("[0-9a-f]{64}")||bytes.length>MAX_IMAGE)throw new IOException("사진 파일이 너무 큽니다.");
  String readText=text==null?"":text;
  IntakeText.Result parsed=IntakeText.parse(readText);
  if(parsed.sku.isEmpty()&&resolvedSku!=null&&!resolvedSku.isEmpty()){readText="STYLE: "+resolvedSku+"\n"+readText;parsed=IntakeText.parse(readText);}
  synchronized(this){
   check();List<Entry> entries=entries();
   for(Entry previous:entries)if(previous.generation.equals(generation)&&previous.targetWorkId.equals(targetWorkId==null?"":targetWorkId)&&previous.sourceHash.equals(sourceHash)&&previous.mode.equals(mode))return previous;
   if(entries.size()>=MAX_ENTRIES)throw new IOException("저장 사진이 500장입니다. 보관 용량을 확인해 주세요.");
   long used=0;for(File file:io.list(directory))if(io.inspect(file)==CheckedAtomicFile.Kind.REGULAR)used+=file.length();
   if(bytes.length>MAX_DISK-used)throw new IOException("사진 보관 공간이 가득 찼습니다.");
   Entry e=new Entry();e.id=UUID.randomUUID().toString();e.generation=generation;e.targetWorkId=targetWorkId==null?"":targetWorkId;e.sourceHash=sourceHash;e.imageHash=imageHash;e.text=readText;e.mode=mode;e.createdAt=System.currentTimeMillis();
   e.reviewVersion=2;e.sku=parsed.sku;e.color=parsed.color;e.quantities=parsed.quantities.clone();e.ready=parsed.ready;e.message="lookup".equals(mode)?"품번·사진 저장됨":parsed.ready?"사진 저장됨 · 입고 등록 대기":parsed.warning;
   CheckedAtomicFile photo=new CheckedAtomicFile(photoFile(e),io);
   try{photo.readFully(MAX_IMAGE,b->{if(b.length==0||!hash(b).equals(imageHash))throw new IOException("저장 사진 확인 실패");});}catch(CheckedAtomicFile.MissingFileException absent){}
   try{photo.write(bytes);entries.add(0,e);persist(entries);return e;}catch(IOException failure){fence(failure);throw failure;}
  }
 }
 public synchronized void update(Entry replacement)throws IOException{
  List<Entry> values=entries();int index=find(values,replacement.id);Entry old=values.get(index);
  if(!immutable(old).equals(immutable(replacement)))throw new IOException("저장 사진 식별자는 변경할 수 없습니다.");
  if((old.committed&&!replacement.committed)||(old.photoCommitted&&!replacement.photoCommitted))throw new IOException("확인된 등록 상태를 되돌릴 수 없습니다.");
  if(old.submitted&&(!old.sku.equals(replacement.sku)||!old.color.equals(replacement.color)||!Arrays.equals(old.quantities,replacement.quantities)||!replacement.submitted))throw new IOException("등록 결과 확인 중에는 같은 요청을 유지해 주세요.");
  values.set(index,replacement);persist(values);
 }
 /** Serializes entry submission so concurrent screens cannot prepare divergent requests. */
 public synchronized Entry submit(String id,PhotoClient client)throws IOException{
  try{return submitLocked(id,client);}catch(IOException e){fence(e);throw e;}
 }
 private Entry submitLocked(String id,PhotoClient client)throws IOException{
  Entry request=entry(id);if(!"intake".equals(request.mode)||(!request.ready&&!request.committed))return request;
  if(!request.committed){
   IntakeContract.fingerprint(request.generation,request.targetWorkId,request.sourceHash,request.sku,request.color,request.quantities);
   request.submitted=true;request.message="1/2 수량 등록 결과 확인 중";update(request);
   Bundle reply=client.registerIntake(request.generation,request.targetWorkId,request.id,request.sourceHash,request.sku,request.color,request.quantities);
   request=entry(id);request.committed=PhotoContract.OK.equals(reply.getString("status"));
   request.message=request.committed?"1/2 수량 저장 확인 · 사진 연결 대기":"수량 등록 확인 필요 · "+reply.getString("reason","");
   if(!request.committed&&PhotoContract.CONFLICT.equals(reply.getString("status")))request.message+=" · 원래 작업과 수량을 유지합니다.";
   update(request);if(!request.committed)return request;
  }
  final CaptureDelivery delivery=new CaptureDelivery(directory,io,id);
  if(delivery.request()==null){
   if(request.photoCommitted)throw new IOException("사진 확인 기록이 없습니다. 원래 요청을 확인해 주세요.");
   byte[] bytes=photoBytes(request);
   PhotoContract.RegisterRequest prepared=client.prepareCapture(request.id,request.generation,request.sku,request.color,bytes);
   delivery.prepare(prepared);
  }
  PhotoContract.RegisterRequest photoRequest=delivery.request();
  if(!photoRequest.requestId.equals(request.id)||!photoRequest.generation.equals(request.generation)||!photoRequest.sku.equals(request.sku)||!photoRequest.sourceColor.equals(request.color)||!photoRequest.sourceSha256.equals(request.imageHash))throw new IOException("입고 사진의 원래 요청이 변경되었습니다.");
  if(!delivery.acknowledged()){
   request.message="1/2 수량 저장 확인 · 2/2 사진 등록 결과 확인 중";update(request);
   PhotoContract.Reply reply=delivery.deliver(new CaptureDelivery.Transport(){
    public PhotoContract.Reply receipt(PhotoContract.RegisterRequest r)throws IOException{return client.captureReceipt(r);}
    public PhotoContract.Reply register(PhotoContract.RegisterRequest r)throws IOException{return client.registerCapture(r);}
   });
   if(!delivery.acknowledged()){
    request=entry(id);request.message="수량 저장 확인 · 사진 연결 미완료: "+reply.status+" "+(reply.reason==null?"":reply.reason)+" · 원래 사진 요청을 유지합니다.";update(request);return request;
   }
  }
  delivery.finish(new CaptureDelivery.Completion(){
   public void publish()throws IOException{Entry value=entry(id);value.photoCommitted=true;value.message="수량·사진 모두 유란노트 저장 확인";update(value);}
   public void cleanup(PhotoContract.RegisterRequest r)throws IOException{client.cleanupCapture(r);}
  });
  return entry(id);
 }
 public synchronized Entry lookupResult(String id,String summary)throws IOException{
  Entry value=entry(id);value.message=CaptureLookup.bounded(summary);update(value);return value;
 }
 private byte[] photoBytes(Entry e)throws IOException{
  check();return new CheckedAtomicFile(photoFile(e),io).readFully(MAX_IMAGE,b->{if(!hash(b).equals(e.imageHash))throw new IOException("보관 사진이 변경됐습니다.");});
 }
 public synchronized Bitmap thumbnail(Entry e,int edge)throws IOException{
  byte[] bytes=photoBytes(e);
  BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
  if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("보관 사진을 읽지 못했습니다.");BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>edge)options.inSampleSize*=2;
  Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(image==null)throw new IOException("보관 사진을 읽지 못했습니다.");return image;
 }
 public static String sourceHash(LabelRecognition.Source source)throws IOException{
  try{MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;try(InputStream in=source.open()){if(in==null)throw new IOException("사진을 열지 못했습니다.");byte[] block=new byte[32768];for(int n;(n=in.read(block))!=-1;){if(n==0)continue;size+=n;if(size>64L*1024*1024)throw new IOException("사진은 64MB 이하로 선택해 주세요.");digest.update(block,0,n);}}
   return hex(digest.digest());
  }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
 }
 private File photoFile(Entry e)throws IOException{if(!e.id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IOException("사진 식별자 오류");return new File(directory,e.id+".jpg");}
 private static int find(List<Entry> values,String id)throws IOException{for(int i=0;i<values.size();i++)if(values.get(i).id.equals(id))return i;throw new IOException("저장된 사진이 없습니다.");}
 private static String immutable(Entry e){return e.id+"|"+e.generation+"|"+e.targetWorkId+"|"+e.sourceHash+"|"+e.imageHash+"|"+e.mode+"|"+e.text+"|"+e.createdAt;}
 private static String hash(byte[] bytes)throws IOException{try{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}}
 private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
 private static JSONObject json(Entry e)throws JSONException{
  JSONObject j=new JSONObject();j.put("id",e.id);j.put("generation",e.generation);j.put("targetWorkId",e.targetWorkId);j.put("sourceHash",e.sourceHash);j.put("imageHash",e.imageHash);j.put("sku",e.sku);j.put("color",e.color);j.put("text",e.text);j.put("mode",e.mode);j.put("message",e.message);j.put("createdAt",e.createdAt);j.put("ready",e.ready);j.put("submitted",e.submitted);j.put("committed",e.committed);j.put("photoCommitted",e.photoCommitted);j.put("reviewVersion",e.reviewVersion);JSONArray q=new JSONArray();for(long n:e.quantities)q.put(n);j.put("quantities",q);return j;
 }
 private static List<Entry> decode(byte[] bytes)throws IOException{
  try{
   JSONArray a=new JSONArray(new String(bytes,"UTF-8"));if(a.length()>MAX_ENTRIES)throw new IOException("보관 사진 개수 오류");List<Entry> result=new ArrayList<>();Set<String> ids=new HashSet<>();
   for(int i=0;i<a.length();i++){JSONObject j=a.getJSONObject(i);Entry e=new Entry();e.id=j.getString("id");e.generation=j.getString("generation");e.targetWorkId=j.getString("targetWorkId");e.sourceHash=j.getString("sourceHash");e.imageHash=j.getString("imageHash");e.sku=j.getString("sku");e.color=j.getString("color");e.text=j.getString("text");e.mode=j.getString("mode");e.message=j.getString("message");e.createdAt=j.getLong("createdAt");e.ready=j.getBoolean("ready");e.submitted=j.getBoolean("submitted");e.committed=j.getBoolean("committed");e.photoCommitted=j.optBoolean("photoCommitted",false);e.reviewVersion=j.optInt("reviewVersion",0);JSONArray q=j.getJSONArray("quantities");
    if(!e.id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||!ids.add(e.id)||!e.sourceHash.matches("[0-9a-f]{64}")||!e.imageHash.matches("[0-9a-f]{64}")||e.sku.length()>80||e.color.length()>80||e.text.length()>12000||e.message.length()>12000||q.length()!=4||!(e.mode.equals("intake")||e.mode.equals("lookup"))||e.createdAt<=0)throw new IOException("보관 사진 기록 오류");
    for(int n=0;n<4;n++){e.quantities[n]=q.getLong(n);if(e.quantities[n]<0||e.quantities[n]>999999)throw new IOException("보관 수량 오류");}
    if(e.ready||e.submitted||e.committed){if(e.mode.equals("intake"))IntakeContract.fingerprint(e.generation,e.targetWorkId,e.sourceHash,e.sku,e.color,e.quantities);}
    if(e.committed&&!e.submitted||e.photoCommitted&&!e.committed)throw new IOException("확인되지 않은 입고 완료 기록");
    if(e.mode.equals("intake")&&!e.submitted&&e.reviewVersion<2){e.ready=false;e.message="이전 인식 사진입니다. 품번·색상·수량을 확인한 뒤 등록해 주세요.";}
    result.add(e);
   }return result;
  }catch(JSONException|IllegalArgumentException e){throw new IOException("보관 사진 기록을 읽지 못했습니다.",e);}
 }
}
