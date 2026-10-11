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
 private final CheckedAtomicFile file;private final String attemptId;
 private PhotoContract.RegisterRequest request;
 private boolean acknowledged,revisionRejected;
 private IOException unavailable;
 public CaptureDelivery(File directory,CheckedAtomicFile.FileOps io,String entryId)throws IOException{
  if(!uuid(entryId))throw new IOException("Invalid entry identity");attemptId=entryId;
  CheckedAtomicFile.directory(directory,io);
  file=new CheckedAtomicFile(new File(directory,"delivery-"+entryId+".bin"),io);
  try{Values value=decode(file.readFully(16384,CaptureDelivery::decode));request=value.request;if(!attemptId.equals(request.requestId))throw new IOException("Delivery request identity changed");acknowledged=value.ack;revisionRejected=value.revisionRejected;}
  catch(CheckedAtomicFile.MissingFileException absent){}
 }
 private void check()throws IOException{if(unavailable!=null)throw unavailable;}
 public synchronized PhotoContract.RegisterRequest request()throws IOException{check();return request;}
 public synchronized boolean acknowledged()throws IOException{check();return acknowledged;}
 public synchronized boolean revisionRejected()throws IOException{check();return revisionRejected;}
 public synchronized void prepare(PhotoContract.RegisterRequest value)throws IOException{
  check();validate(value);if(!attemptId.equals(value.requestId))throw new IOException("Delivery request identity mismatch");
  if(request!=null){if(!same(request,value))throw new IOException("사진은 원래 등록 요청을 유지합니다.");return;}
  write(value,false,false);request=value;
 }
 /** Never refreshes revision/generation. A missing response is retried with its original fingerprint. */
 public synchronized PhotoContract.Reply deliver(Transport transport)throws IOException{
  check();if(request==null)throw new IOException("사진 전송 준비 기록이 없습니다.");
  if(acknowledged)return null;
  PhotoContract.Reply reply=transport.receipt(request);boolean registered=false;
  if(reply!=null&&PhotoContract.NOT_FOUND.equals(reply.status)){reply=transport.register(request);registered=true;}
  if(reply==null)throw new IOException("사진 등록 응답이 없습니다. 원래 요청을 유지합니다.");
  if(PhotoContract.OK.equals(reply.status)){
   if(!request.requestId.equals(reply.requestId)||!request.generation.equals(reply.generation)||reply.revision<0||(!request.productId.isEmpty()&&!request.productId.equals(reply.productId))||(!request.variantId.isEmpty()&&!request.variantId.equals(reply.variantId))||!uuid(reply.productId)||!uuid(reply.variantId)||reply.photoVersionId==null||!reply.photoVersionId.matches("[0-9a-f]{64}"))throw new IOException("사진 등록 확인 응답이 원래 요청과 다릅니다.");
   write(request,true,false);acknowledged=true;revisionRejected=false;
  }
  if(registered&&PhotoContract.CONFLICT.equals(reply.status)&&"REVISION_CHANGED".equals(reply.reason)&&request.requestId.equals(reply.requestId)&&request.generation.equals(reply.generation)&&reply.revision>=request.revision){write(request,false,true);revisionRejected=true;}
  return reply;
 }
 /** Caller publication is durable before source removal. Failures remain resumable. */
 public synchronized void finish(Completion completion)throws IOException{
  check();if(!acknowledged)throw new IOException("사진 등록이 아직 확인되지 않았습니다.");
  completion.publish();completion.cleanup(request);
 }

 public interface Link {void publish(String attemptId)throws IOException;}
 /** A new attempt is explicit and only follows a proved revision rejection, never an unknown ACK. */
 public synchronized void linkReplacement(CaptureDelivery replacement,PhotoContract.RegisterRequest next,Transport transport,Link link)throws IOException{
  check();if(request==null||acknowledged||!revisionRejected)throw new IOException("원래 사진의 결과가 불명확합니다. 같은 등록 결과부터 확인해 주세요.");
  validate(next);
  if(next.requestId.equals(request.requestId)||!next.generation.equals(request.generation)||!next.sku.equals(request.sku)||!next.sourceColor.equals(request.sourceColor)||!next.sourceSha256.equals(request.sourceSha256)||next.revision<request.revision)throw new IOException("사진 재확인 대상이 변경되었습니다.");
  PhotoContract.Reply receipt=transport.receipt(request);
  if(receipt==null||!PhotoContract.NOT_FOUND.equals(receipt.status)||!request.requestId.equals(receipt.requestId)||!request.generation.equals(receipt.generation))throw new IOException("원래 사진의 미등록을 확인하지 못했습니다. 같은 등록 결과를 확인해 주세요.");
  try{replacement.prepareAndLink(next,link);}catch(IOException e){if(e instanceof CommitUncertainException)unavailable=e;throw e;}
 }

 public synchronized void prepareAndLink(PhotoContract.RegisterRequest next,Link link)throws IOException{
  check();try{prepare(next);link.publish(next.requestId);}catch(IOException e){if(e instanceof CommitUncertainException)unavailable=e;throw e;}
 }
 private void write(PhotoContract.RegisterRequest value,boolean ack,boolean rejected)throws IOException{
  try{file.write(encode(value,ack,rejected));}catch(IOException e){if(e instanceof CommitUncertainException)unavailable=e;throw e;}
 }
 private static boolean same(PhotoContract.RegisterRequest a,PhotoContract.RegisterRequest b)throws IOException{return a.sourceUri.equals(b.sourceUri)&&PhotoContract.fingerprint(a).equals(PhotoContract.fingerprint(b));}
 private static byte[] encode(PhotoContract.RegisterRequest r,boolean ack,boolean rejected)throws IOException{
  validate(r);ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
  out.writeInt(0x59434431);out.writeUTF(r.requestId);out.writeUTF(r.generation);out.writeLong(r.revision);out.writeUTF(r.productId);out.writeUTF(r.variantId);out.writeUTF(r.sku);out.writeUTF(r.sourceColor);out.writeUTF(r.sourceSha256);out.writeUTF(r.sourceUri);out.writeUTF(PhotoContract.fingerprint(r));out.writeBoolean(ack);out.writeBoolean(rejected);out.flush();return bytes.toByteArray();
 }
 private static final class Values {PhotoContract.RegisterRequest request;boolean ack,revisionRejected;}
 private static Values decode(byte[] bytes)throws IOException{
  DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=0x59434431)throw new IOException("Invalid delivery journal");
  Values v=new Values();v.request=new PhotoContract.RegisterRequest(in.readUTF(),in.readUTF(),in.readLong(),in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF());
  String fingerprint=in.readUTF();v.ack=in.readBoolean();v.revisionRejected=in.available()>0?in.readBoolean():false;validate(v.request);
  if(v.ack&&v.revisionRejected)throw new IOException("Invalid delivery state");
  if(!fingerprint.equals(PhotoContract.fingerprint(v.request))||in.available()!=0)throw new IOException("Delivery identity changed");return v;
 }
 private static void validate(PhotoContract.RegisterRequest r)throws IOException{
  if(r==null||!uuid(r.requestId)||!uuid(r.generation)||r.revision<0||r.productId==null||r.variantId==null||(!r.productId.isEmpty()&&!uuid(r.productId))||(!r.variantId.isEmpty()&&!uuid(r.variantId))||r.sku==null||r.sku.isEmpty()||r.sku.length()>80||r.sourceColor==null||r.sourceColor.isEmpty()||r.sourceColor.length()>60||r.sourceSha256==null||!r.sourceSha256.matches("[0-9a-f]{64}")||r.sourceUri==null||r.sourceUri.length()>1024||!r.sourceUri.startsWith("content://"))throw new IOException("Invalid delivery request");
 }
 private static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");}
}
