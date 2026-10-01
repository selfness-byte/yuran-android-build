package com.gongyo.yuranphoto;

import com.gongyo.photo.PhotoContract;
import com.gongyo.orderly.CheckedAtomicFile;
import com.gongyo.orderly.CommitUncertainException;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;

/** One configured, checked pending session. Public values are proposals until save succeeds. */
public final class PendingRegistration {
 public static final int MAX_PENDING_METADATA_BYTES=65536;
 public PhotoContract.RegisterRequest request;
 public String rawPath;
 public boolean acknowledged;
 private final CheckedAtomicFile.FileOps io;
 private File anchor,resolvedAnchor;private CheckedAtomicFile file;private boolean initialized,present;private IOException unavailable;
 public PendingRegistration(CheckedAtomicFile.FileOps io){if(io==null)throw new NullPointerException("FileOps");this.io=io;}
 public synchronized boolean isPresent(){return present;}
 private void ready()throws IOException{if(unavailable!=null)throw unavailable;}
 private void bind(File directory)throws IOException{
  ready();if(directory==null||!directory.isAbsolute())throw new IOException("Established files anchor required");
  File established=io.directoryAnchor(directory);if(!directory.equals(established))throw new IOException("Pending directory must be the files anchor");
  File resolved=directory.getCanonicalFile();if(resolvedAnchor!=null&&!resolvedAnchor.equals(resolved))throw new IOException("Pending directory switching forbidden");
  CheckedAtomicFile.directory(directory,io);
  if(anchor==null){anchor=directory;resolvedAnchor=resolved;file=new CheckedAtomicFile(new File(directory,"pending-registration.bin"),io);}
 }
 private byte[] read()throws IOException{return file.readFully(MAX_PENDING_METADATA_BYTES,bytes->decode(bytes));}
 private void initialize()throws IOException{if(initialized)return;try{read();present=true;}catch(CheckedAtomicFile.MissingFileException absent){present=false;}initialized=true;}
 public synchronized void save(File directory)throws IOException{
  try{bind(directory);initialize();byte[] bytes=encode(request,rawPath,acknowledged);file.write(bytes);present=true;}
  catch(CommitUncertainException e){unavailable=e;throw e;}
 }
 public synchronized PendingRegistration load(File directory)throws IOException{
  try{bind(directory);byte[] bytes;try{bytes=read();}catch(CheckedAtomicFile.MissingFileException absent){initialized=true;present=false;throw absent;}
   Values value=decode(bytes);request=value.request;rawPath=value.path;acknowledged=value.ack;initialized=true;present=true;return this;
  }catch(CommitUncertainException e){unavailable=e;throw e;}
 }
 public synchronized void clear(File directory)throws IOException{
  try{bind(directory);if(!initialized)throw new IOException("Recovered pending session required");file.delete();present=false;request=null;rawPath=null;acknowledged=false;}
  catch(CommitUncertainException e){unavailable=e;throw e;}
 }
 private static final class Values{PhotoContract.RegisterRequest request;String path;boolean ack;}
 private static byte[] encode(PhotoContract.RegisterRequest r,String path,boolean ack)throws IOException{
  validate(r,path);ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(0x59505231);out.writeInt(1);
  string(out,r.requestId,36);string(out,r.generation,36);out.writeLong(r.revision);string(out,r.productId,36);string(out,r.variantId,36);string(out,r.sku,320);string(out,r.sourceColor,240);string(out,r.sourceSha256,64);string(out,r.sourceUri,4096);string(out,path,16384);out.writeByte(ack?1:0);out.flush();
  if(bytes.size()>MAX_PENDING_METADATA_BYTES)throw new IOException("Pending metadata limit");return bytes.toByteArray();
 }
 private static Values decode(byte[] bytes)throws IOException{
  if(bytes.length>MAX_PENDING_METADATA_BYTES)throw new IOException("Pending metadata limit");DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
  if(in.readInt()!=0x59505231||in.readInt()!=1)throw new IOException("Unsupported pending encoding");
  String id=string(in,36),generation=string(in,36);long revision=in.readLong();String product=string(in,36),variant=string(in,36),sku=string(in,320),color=string(in,240),sha=string(in,64),uri=string(in,4096),path=string(in,16384);int ack=in.readUnsignedByte();
  if(ack>1||in.read()!=-1)throw new IOException("Invalid pending tail");Values v=new Values();v.request=new PhotoContract.RegisterRequest(id,generation,revision,product,variant,sku,color,sha,uri);v.path=path;v.ack=ack==1;validate(v.request,path);return v;
 }
 private static void validate(PhotoContract.RegisterRequest r,String path)throws IOException{
  if(r==null||!uuid(r.requestId)||!uuid(r.generation)||r.revision<0||r.productId==null||r.variantId==null||(!r.productId.isEmpty()&&!uuid(r.productId))||(!r.variantId.isEmpty()&&!uuid(r.variantId))||(!r.variantId.isEmpty()&&r.productId.isEmpty())||r.sku==null||r.sku.isEmpty()||r.sku.length()>80||r.sourceColor==null||r.sourceColor.isEmpty()||r.sourceColor.length()>60||r.sourceSha256==null||!r.sourceSha256.matches("[0-9a-f]{64}")||r.sourceUri==null||r.sourceUri.isEmpty()||r.sourceUri.length()>1024||path==null||path.isEmpty()||path.length()>4096)throw new IOException("Invalid pending fields");
 }
 private static boolean uuid(String s){return s!=null&&s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");}
 private static byte[] utf8(String s)throws IOException{if(s==null)throw new IOException("Missing pending field");ByteBuffer b=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(s));byte[] bytes=new byte[b.remaining()];b.get(bytes);return bytes;}
 private static void string(DataOutputStream out,String s,int max)throws IOException{byte[] bytes=utf8(s);if(bytes.length>max)throw new IOException("Pending field limit");out.writeInt(bytes.length);out.write(bytes);}
 private static String string(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max||n>in.available())throw new IOException("Pending field limit/truncation");byte[] bytes=new byte[n];in.readFully(bytes);return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
}
