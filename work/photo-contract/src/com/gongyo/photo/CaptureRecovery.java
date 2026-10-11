package com.gongyo.photo;

import com.gongyo.orderly.CheckedAtomicFile;
import java.io.*;
import java.util.*;

/** Bounded journals for capture suppression and selected sources. No framework references. */
public final class CaptureRecovery {
 public static final class Item {
  public final String id,uri,hash,entryId;public final boolean releaseGrant;
  Item(String id,String uri,String hash,String entryId,boolean releaseGrant){this.id=id;this.uri=uri;this.hash=hash;this.entryId=entryId;this.releaseGrant=releaseGrant;}
 }
 public static final class Batch {
  public final String generation,targetWorkId;
  public final List<Item> items;
  Batch(String generation,String targetWorkId,List<Item> items){this.generation=generation;this.targetWorkId=targetWorkId;this.items=Collections.unmodifiableList(new ArrayList<>(items));}
 }
 private final CheckedAtomicFile cameraFile,queueFile;
 private final String mode;
 private CaptureSession session;private Batch batch;
 private long queueSequence,queueOwner;
 private IOException unavailable;
 private void check()throws IOException{if(unavailable!=null)throw unavailable;}
 private void writeChecked(CheckedAtomicFile file,byte[] bytes)throws IOException{
  check();try{file.write(bytes);}catch(IOException e){if(e instanceof com.gongyo.orderly.CommitUncertainException)unavailable=e;throw e;}
 }
 public CaptureRecovery(File directory,CheckedAtomicFile.FileOps io,String mode)throws IOException{
  if(!"intake".equals(mode)&&!"lookup".equals(mode))throw new IOException("Invalid capture mode");this.mode=mode;
  CheckedAtomicFile.directory(directory,io);
  cameraFile=new CheckedAtomicFile(new File(directory,"camera-"+mode+".bin"),io);
  queueFile=new CheckedAtomicFile(new File(directory,"queue-"+mode+".bin"),io);
  try{session=decodeSession(cameraFile.readFully(2048,b->decodeSession(b)));}catch(CheckedAtomicFile.MissingFileException absent){}
  try{batch=decodeBatch(queueFile.readFully(128*1024,b->decodeBatch(b)));}catch(CheckedAtomicFile.MissingFileException absent){}
 }
 public synchronized CaptureSession session()throws IOException{check();return session;}
 public synchronized CaptureSession begin(String generation,String target)throws IOException{
  check();if(session!=null)return session;
  CaptureSession fresh=new CaptureSession(generation,target,mode,"",false);
  writeSession(fresh,"",false);session=fresh;return session;
 }
 public synchronized void reserve(CaptureSession value,long token)throws IOException{
  requireSession(value);if(!value.owns(token))throw new IOException("Capture owner changed");
  writeSession(value,value.suppressed(),true);
 }
 public synchronized void saved(CaptureSession value,long token,String sku)throws IOException{
  requireSession(value);if(!value.owns(token))throw new IOException("Capture owner changed");
  writeSession(value,sku,false);value.saved(token,sku);
 }
 public synchronized void reset(CaptureSession value)throws IOException{
  requireSession(value);if(value.busy())throw new IOException("사진 저장과 등록 확인이 진행 중입니다.");writeSession(value,"",false);value.reset();
 }
 public synchronized void end(CaptureSession value)throws IOException{
  requireSession(value);if(value.busy())throw new IOException("사진 저장과 등록 확인이 진행 중입니다.");writeSession(null,"",false);session=null;
 }
 private void requireSession(CaptureSession value)throws IOException{check();if(value==null||value!=session)throw new IOException("촬영 세션이 변경되었습니다.");}
 private void writeSession(CaptureSession value,String sku,boolean pending)throws IOException{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
  out.writeBoolean(value!=null);if(value!=null){out.writeUTF(value.generation);out.writeUTF(value.targetWorkId);out.writeUTF(sku);out.writeBoolean(pending);}out.flush();writeChecked(cameraFile,bytes.toByteArray());
 }
 private CaptureSession decodeSession(byte[] bytes)throws IOException{
  DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));CaptureSession value=null;
  if(in.readBoolean())value=new CaptureSession(word(in,128),word(in,128),mode,word(in,80),in.readBoolean());
  if(in.available()!=0)throw new IOException("Invalid camera journal");return value;
 }
 public synchronized Batch batch()throws IOException{check();return batch;}
 public synchronized long claimQueue()throws IOException{check();if(queueOwner!=0)return 0;queueOwner=++queueSequence;return queueOwner;}
 public synchronized void releaseQueue(long token){if(token!=0&&token==queueOwner)queueOwner=0;}
 public synchronized void enqueue(String generation,String target,List<String> sources)throws IOException{enqueue(generation,target,sources,Collections.emptySet());}
 public synchronized void enqueue(String generation,String target,List<String> sources,Set<String> acquiredGrants)throws IOException{
  check();if(batch!=null)throw new IOException("남은 사진 처리를 먼저 이어 주세요.");
  if(sources.isEmpty()||sources.size()>20||new HashSet<>(sources).size()!=sources.size())throw new IOException("사진은 한 번에 20장까지 선택해 주세요.");
  List<Item> items=new ArrayList<>();for(String source:sources){if(source==null||!source.startsWith("content://")||source.length()>4096)throw new IOException("사진 주소를 확인해 주세요.");items.add(new Item(UUID.randomUUID().toString(),source,"","",acquiredGrants.contains(source)));}
  publish(new Batch(generation,target,items));
 }
 public synchronized Item sourceHash(long owner,String id,String hash)throws IOException{
  Item old=head(owner,id);if(!hash.matches("[0-9a-f]{64}")||(!old.hash.isEmpty()&&!old.hash.equals(hash)))throw new IOException("선택한 원본 사진이 변경되었습니다. 남은 항목을 유지합니다.");
  Item value=new Item(old.id,old.uri,hash,old.entryId,old.releaseGrant);replaceHead(value);return value;
 }
 public synchronized Item saved(long owner,String id,String entryId)throws IOException{
  Item old=head(owner,id);if(old.hash.isEmpty()||!uuid(entryId)||(!old.entryId.isEmpty()&&!old.entryId.equals(entryId)))throw new IOException("저장 사진 식별자가 변경되었습니다.");
  Item value=new Item(old.id,old.uri,old.hash,entryId,old.releaseGrant);replaceHead(value);return value;
 }
 public synchronized void complete(long owner,String id)throws IOException{
  Item old=head(owner,id);if(old.entryId.isEmpty())throw new IOException("사진 저장 전에는 대기 항목을 지울 수 없습니다.");
  List<Item> items=new ArrayList<>(batch.items);items.remove(0);publish(items.isEmpty()?null:new Batch(batch.generation,batch.targetWorkId,items));
 }
 public interface GrantReleaser { void release(String uri); }
 /** Keeps cancellation and owned-grant release under the same queue lock. */
 public synchronized void discardRemaining(GrantReleaser releaser)throws IOException{
  for(Item item:discardRemaining())if(item.releaseGrant)releaser.release(item.uri);
 }
 public synchronized void complete(long owner,String id,GrantReleaser releaser)throws IOException{
  Item item=head(owner,id);complete(owner,id);if(item.releaseGrant)releaser.release(item.uri);
 }
 public synchronized List<Item> remainingForDiscard()throws IOException{
  check();if(queueOwner!=0)throw new IOException("사진 처리가 진행 중입니다.");return batch==null?Collections.emptyList():new ArrayList<>(batch.items);
 }
 public synchronized List<Item> discardRemaining()throws IOException{
  check();if(queueOwner!=0)throw new IOException("사진 처리가 진행 중입니다.");List<Item> result=batch==null?Collections.emptyList():new ArrayList<>(batch.items);publish(null);return result;
 }
 private Item head(long owner,String id)throws IOException{
  check();if(owner==0||owner!=queueOwner||batch==null||batch.items.isEmpty()||!batch.items.get(0).id.equals(id))throw new IOException("대기 사진의 처리 소유자가 변경되었습니다.");return batch.items.get(0);
 }
 private void replaceHead(Item value)throws IOException{List<Item> items=new ArrayList<>(batch.items);items.set(0,value);publish(new Batch(batch.generation,batch.targetWorkId,items));}
 private void publish(Batch value)throws IOException{
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeBoolean(value!=null);
  if(value!=null){out.writeUTF(value.generation);out.writeUTF(value.targetWorkId);out.writeInt(value.items.size());for(Item item:value.items){out.writeUTF(item.id);out.writeUTF(item.uri);out.writeUTF(item.hash);out.writeUTF(item.entryId);out.writeBoolean(item.releaseGrant);}}out.flush();
  if(bytes.size()>128*1024)throw new IOException("선택한 사진 주소가 너무 깁니다. 더 적은 파일을 선택해 주세요.");writeChecked(queueFile,bytes.toByteArray());batch=value;
 }
 private static Batch decodeBatch(byte[] bytes)throws IOException{
  DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));Batch value=null;
  if(in.readBoolean()){
   String generation=word(in,128),target=word(in,128);int count=in.readInt();if(count<1||count>20)throw new IOException("Invalid queue length");
   List<Item> items=new ArrayList<>();Set<String> ids=new HashSet<>();
   for(int i=0;i<count;i++){
    Item item=new Item(word(in,36),word(in,4096),word(in,64),word(in,36),in.readBoolean());
    if(!uuid(item.id)||!ids.add(item.id)||!item.uri.startsWith("content://")||(!item.hash.isEmpty()&&!item.hash.matches("[0-9a-f]{64}"))||(!item.entryId.isEmpty()&&(!uuid(item.entryId)||item.hash.isEmpty())))throw new IOException("Invalid queue identity");items.add(item);
   }value=new Batch(generation,target,items);
  }
  if(in.available()!=0)throw new IOException("Invalid queue journal");return value;
 }
 private static String word(DataInputStream in,int max)throws IOException{String value=in.readUTF();if(value.length()>max)throw new IOException("Journal field too long");return value;}
 private static boolean uuid(String value){return value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");}
}
