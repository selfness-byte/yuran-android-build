package com.gongyo.photo;

import com.gongyo.orderly.CheckedAtomicFile;
import com.gongyo.orderly.CommitUncertainException;
import java.io.*;

/** Per-entry immutable delivery identity. Kept after cleanup to make retries receipt-only. */
public final class CaptureDelivery {
 public interface Transport {
  PhotoContract.Reply receipt(PhotoContract.RegisterRequest request)throws IOException;
  PhotoContract.Reply register(PhotoContract.RegisterRequest request)throws IOException;
 }
 public interface Completion {
  void publish()throws IOException;
  void cleanup(PhotoContract.RegisterRequest request)throws IOException;
 }
 private final CheckedAtomicFile file;
 private PhotoContract.RegisterRequest request;
 private boolean acknowledged;
 private IOException unavailable;
 public CaptureDelivery(File directory,CheckedAtomicFile.FileOps io,String entryId)throws IOException{
  if(!uuid(entryId))throw new IOException("Invalid entry identity");
  CheckedAtomicFile.directory(directory,io);
  file=new CheckedAtomicFile(new File(directory,"delivery-"+entryId+".bin"),io);
  try{Values value=decode(file.readFully(16384,CaptureDelivery::decode));request=value.request;acknowledged=value.ack;}
  catch(CheckedAtomicFile.MissingFileException absent){}
 }
 private void check()throws IOException{if(unavailable!=null)throw unavailable;}
 public synchronized PhotoContract.RegisterRequest request()throws IOException{check();return request;}
 public synchronized boolean acknowledged()throws IOException{check();return acknowledged;}
 public synchronized void prepare(PhotoContract.RegisterRequest value)throws IOException{
  check();validate(value);
  if(request!=null){if(!same(request,value))throw new IOException("사진은 원래 등록 요청을 유지합니다.");return;}
  write(value,false);request=value;
 }
 /** Never refreshes revision/generation. A missing response is retried with its original fingerprint. */
 public synchronized PhotoContract.Reply deliver(Transport transport)throws IOException{
  check();if(request==null)throw new IOException("사진 전송 준비 기록이 없습니다.");
  if(acknowledged)return null;
  PhotoContract.Reply reply=transport.receipt(request);
  if(reply!=null&&PhotoContract.NOT_FOUND.equals(reply.status))reply=transport.register(request);
  if(reply==null)throw new IOException("사진 등록 응답이 없습니다. 원래 요청을 유지합니다.");
  if(PhotoContract.OK.equals(reply.status)){
   if(!request.requestId.equals(reply.requestId)||!request.generation.equals(reply.generation)||reply.revision<0||!uuid(reply.productId)||!uuid(reply.variantId)||reply.photoVersionId==null||!reply.photoVersionId.matches("[0-9a-f]{64}"))throw new IOException("사진 등록 확인 응답이 원래 요청과 다릅니다.");
   write(request,true);acknowledged=true;
  }
  return reply;
 }
 /** Caller publication is durable before source removal. Failures remain resumable. */
 public synchronized void finish(Completion completion)throws IOException{
  check();if(!acknowledged)throw new IOException("사진 등록이 아직 확인되지 않았습니다.");
  completion.publish();completion.cleanup(request);
 }
 private void write(PhotoContract.RegisterRequest value,boolean ack)throws IOException{
  try{file.write(encode(value,ack));}catch(IOException e){if(e instanceof CommitUncertainException)unavailable=e;throw e;}
 }
 private static boolean same(PhotoContract.RegisterRequest a,PhotoContract.RegisterRequest b)throws IOException{return a.sourceUri.equals(b.sourceUri)&&PhotoContract.fingerprint(a).equals(PhotoContract.fingerprint(b));}
 private static byte[] encode(PhotoContract.RegisterRequest r,boolean ack)throws IOException{
  validate(r);ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
  out.writeInt(0x59434431);out.writeUTF(r.requestId);out.writeUTF(r.generation);out.writeLong(r.revision);out.writeUTF(r.productId);out.writeUTF(r.variantId);out.writeUTF(r.sku);out.writeUTF(r.sourceColor);out.writeUTF(r.sourceSha256);out.writeUTF(r.sourceUri);out.writeUTF(PhotoContract.fingerprint(r));out.writeBoolean(ack);out.flush();return bytes.toByteArray();
 }
 private static final class Values {PhotoContract.RegisterRequest request;boolean ack;}
 private static Values decode(byte[] bytes)throws IOException{
  DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=0x59434431)throw new IOException("Invalid delivery journal");
  Values v=new Values();v.request=new PhotoContract.RegisterRequest(in.readUTF(),in.readUTF(),in.readLong(),in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF());
  String fingerprint=in.readUTF();v.ack=in.readBoolean();validate(v.request);
  if(!fingerprint.equals(PhotoContract.fingerprint(v.request))||in.available()!=0)throw new IOException("Delivery identity changed");return v;
 }
 private static void validate(PhotoContract.RegisterRequest r)throws IOException{
  if(r==null||!uuid(r.requestId)||!uuid(r.generation)||r.revision<0||r.productId==null||r.variantId==null||(!r.productId.isEmpty()&&!uuid(r.productId))||(!r.variantId.isEmpty()&&!uuid(r.variantId))||r.sku==null||r.sku.isEmpty()||r.sku.length()>80||r.sourceColor==null||r.sourceColor.isEmpty()||r.sourceColor.length()>60||r.sourceSha256==null||!r.sourceSha256.matches("[0-9a-f]{64}")||r.sourceUri==null||r.sourceUri.length()>1024||!r.sourceUri.startsWith("content://"))throw new IOException("Invalid delivery request");
 }
 private static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");}
}
