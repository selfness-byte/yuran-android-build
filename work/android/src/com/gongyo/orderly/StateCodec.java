package com.gongyo.orderly;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Bounded, versioned backup format. Never uses Java object deserialization. */
public final class StateCodec {
 private static final int MAGIC=0x534e3031, MAX_BYTES=PhotoLimits.MAX_METADATA_BYTES;
 public static final int MAX_RECENT_JOBS=20;
 public static final class State {
  public String generation=UUID.randomUUID().toString();
  public long revision;
  public PhotoCatalog catalog=new PhotoCatalog();
  public long nextRevision()throws IOException {if(revision<0||revision==Long.MAX_VALUE)throw new IOException("자료 revision이 허용 범위를 벗어났습니다.");return revision+1;}
  public Inventory.Job draft=new Inventory.Job();
  public List<Inventory.Job> jobs=new ArrayList<>();
  public List<Inventory.Job> recentJobs=new ArrayList<>();
  public List<ChangeHistory.Entry> changes=new ArrayList<>();
  public List<StockHistory.Entry> stockHistory=new ArrayList<>();
  public UndoSnapshot undoSnapshot;
  /** 0 means no successful export time is known. */
  public long lastBackupAt;
  public Map<String,String> globalRules=new LinkedHashMap<>();
  public Map<String,Map<String,String>> skuRules=new LinkedHashMap<>();
  public State(){globalRules.put("白色","WH");globalRules.put("黑色","BK");globalRules.put("碳灰色","CC");globalRules.put("浅蓝色","LB");globalRules.put("米色","LE");}
 }
 public static byte[] encode(State state)throws IOException {
  if(state==null)throw new IOException("자료가 없습니다.");
  validatePhotos(state);
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(new LimitedOutputStream(bytes));
  out.writeInt(MAGIC);out.writeInt(PhotoLimits.SCHEMA_VERSION);writeMap(out,state.globalRules);count(state.skuRules.size(),5000);out.writeInt(state.skuRules.size());
  for(Map.Entry<String,Map<String,String>> e:state.skuRules.entrySet()){writeString(out,e.getKey(),80);writeMap(out,e.getValue());}
  writeJob(out,state.draft);count(state.jobs.size(),100);out.writeInt(state.jobs.size());for(Inventory.Job job:state.jobs)writeJob(out,job);
  count(state.recentJobs.size(),MAX_RECENT_JOBS);out.writeInt(state.recentJobs.size());Set<String> recentIds=new HashSet<>(),recentSources=new HashSet<>();
  for(Inventory.Job job:state.recentJobs){validRecentJob(job,recentIds,recentSources);writeJob(out,job);}
  count(state.changes.size(),ChangeHistory.MAX_CHANGES);out.writeInt(state.changes.size());Set<String> changeIds=new HashSet<>();
  for(ChangeHistory.Entry e:state.changes){validChange(e,changeIds);writeString(out,e.id,80);out.writeLong(e.timestamp);writeString(out,e.jobTitle,80);writeString(out,e.action,60);writeString(out,e.detail,4000);writeString(out,e.stockSessionId,80);}
  count(state.stockHistory.size(),StockHistory.MAX_ENTRIES);out.writeInt(state.stockHistory.size());Set<String> stockIds=new HashSet<>();
  for(StockHistory.Entry e:state.stockHistory){validStockEntry(e,stockIds);writeString(out,e.id,80);writeString(out,e.jobTitle,80);out.writeLong(e.initialQuantity);out.writeLong(e.finalQuantity);out.writeLong(e.sourceRecordedAt);out.writeLong(e.stockUpdatedAt);out.writeLong(e.closedAt);writeString(out,e.stockSessionId,80);out.writeLong(e.changeCount);}
  if(state.lastBackupAt<0)throw new IOException("백업 시각이 올바르지 않습니다.");
  out.writeBoolean(state.undoSnapshot!=null);if(state.undoSnapshot!=null)writeUndoSnapshot(out,state.undoSnapshot);out.writeLong(state.lastBackupAt);
  writeString(out,state.generation,36);out.writeLong(state.revision);writeCatalog(out,state.catalog);
  out.flush();return bytes.toByteArray();
 }
 public static State decodeExternalLegacy(byte[] bytes)throws IOException {
  if(bytes==null||bytes.length<8||bytes.length>PhotoLimits.MAX_LEGACY_BYTES)throw new IOException("Legacy backup limits");DataInputStream header=new DataInputStream(new ByteArrayInputStream(bytes));header.readInt();int version=header.readInt();if(version<1||version>9)throw new IOException("Photo state requires integrated backup");return decode(bytes);
 }
 public static State decode(byte[] bytes)throws IOException {
  if(bytes==null||bytes.length>MAX_BYTES)throw new IOException("자료 크기가 올바르지 않습니다.");
  DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));int magic=in.readInt(),version=in.readInt();if(magic!=MAGIC||(version<1||version>PhotoLimits.SCHEMA_VERSION))throw new IOException("지원하지 않는 수량노트 백업입니다.");
  if(version<10&&bytes.length>PhotoLimits.MAX_LEGACY_BYTES)throw new IOException("구형 자료가 너무 큽니다.");
  State state=new State();state.globalRules=readMap(in);int n=readCount(in,5000);
  for(int i=0;i<n;i++){String key=readString(in,80);if(key.isEmpty()||state.skuRules.containsKey(key))throw new IOException("품번 규칙이 올바르지 않습니다.");state.skuRules.put(key,readMap(in));}
  state.draft=readJob(in,version);n=readCount(in,100);Set<String> ids=new HashSet<>();for(int i=0;i<n;i++){Inventory.Job j=readJob(in,version);if(j.id.isEmpty()||!ids.add(j.id))throw new IOException("작업 식별자가 올바르지 않습니다.");state.jobs.add(j);}
  if(version>=2){n=readCount(in,MAX_RECENT_JOBS);Set<String> recentIds=new HashSet<>(),recentSources=new HashSet<>();for(int i=0;i<n;i++){Inventory.Job job=readJob(in,version);validRecentJob(job,recentIds,recentSources);state.recentJobs.add(job);}}
  if(version>=3){n=readCount(in,ChangeHistory.MAX_CHANGES);Set<String> changeIds=new HashSet<>();for(int i=0;i<n;i++){String id=readString(in,80);long timestamp=in.readLong();String title=readString(in,80),action=readString(in,60),detail=readString(in,4000),session=version>=9?readString(in,80):"";ChangeHistory.Entry e=new ChangeHistory.Entry(id,timestamp,title,action,detail,session);validChange(e,changeIds);state.changes.add(e);}}
  if(version>=5){n=readCount(in,StockHistory.MAX_ENTRIES);Set<String> stockIds=new HashSet<>();for(int i=0;i<n;i++){String id=readString(in,80),title=readString(in,80);long initial=in.readLong(),finalQuantity=in.readLong(),sourceTime=in.readLong(),stockTime=in.readLong(),closed=in.readLong();String session=version>=9?readString(in,80):"";long changes=version>=9?in.readLong():-1;StockHistory.Entry e=new StockHistory.Entry(id,title,initial,finalQuantity,sourceTime,stockTime,closed,session,changes);validStockEntry(e,stockIds);state.stockHistory.add(e);}}
  if(version>=8){if(in.readBoolean())state.undoSnapshot=readUndoSnapshot(in,version);state.lastBackupAt=in.readLong();if(state.lastBackupAt<0)throw new IOException("백업 시각이 올바르지 않습니다.");}
  if(version>=10){state.generation=readString(in,36);state.revision=in.readLong();state.catalog=readCatalog(in,version);}
  validatePhotos(state);
  if(in.read()!=-1)throw new IOException("백업에 알 수 없는 내용이 있습니다.");
  if(version<5){StockHistory.recoverBaseline(state.draft,state.globalRules,state.skuRules);for(Inventory.Job job:state.jobs)StockHistory.recoverBaseline(job,state.globalRules,state.skuRules);for(Inventory.Job job:state.recentJobs)StockHistory.recoverBaseline(job,state.globalRules,state.skuRules);}
  return state;
 }
 private static void validRecentJob(Inventory.Job job,Set<String> ids,Set<String> sources)throws IOException{if(job==null||job.id==null||job.id.isEmpty()||!ids.add(job.id)||job.sourceText==null||job.sourceText.trim().isEmpty()||!sources.add(job.sourceText))throw new IOException("최근 원문 기록이 올바르지 않습니다.");}
 private static void validChange(ChangeHistory.Entry e,Set<String> ids)throws IOException{if(e==null||e.id==null||e.id.trim().isEmpty()||!ids.add(e.id)||e.timestamp<0||e.action==null||e.action.trim().isEmpty()||e.detail==null||e.detail.trim().isEmpty()||e.stockSessionId==null)throw new IOException("변경 기록이 올바르지 않습니다.");}
 private static void validStockEntry(StockHistory.Entry e,Set<String> ids)throws IOException{if(e==null||e.id==null||e.id.trim().isEmpty()||!ids.add(e.id)||e.jobTitle==null||e.initialQuantity< -1||e.initialQuantity>StockHistory.MAX_TOTAL||e.finalQuantity<0||e.finalQuantity>StockHistory.MAX_TOTAL||e.sourceRecordedAt<0||e.stockUpdatedAt<0||e.closedAt<=0||e.stockSessionId==null||e.changeCount< -1)throw new IOException("작업 재고 기록이 올바르지 않습니다.");}
 /** Refuse oversized states while writing, without discarding any in-memory jobs. */
 static final class LimitedOutputStream extends OutputStream {
  private final ByteArrayOutputStream bytes;
  LimitedOutputStream(ByteArrayOutputStream bytes){this.bytes=bytes;}
  private void reserve(int length)throws IOException{if(length>MAX_BYTES-bytes.size())throw new IOException("보관 자료가 너무 큽니다. 백업 후 오래된 작업을 정리해 주세요.");}
  @Override public void write(int value)throws IOException{reserve(1);bytes.write(value);}
  @Override public void write(byte[] values,int offset,int length)throws IOException{reserve(length);bytes.write(values,offset,length);}
 }
 private static void writeMap(DataOutputStream out,Map<String,String> map)throws IOException{count(map.size(),5000);out.writeInt(map.size());for(Map.Entry<String,String> e:map.entrySet()){validRule(e.getKey(),e.getValue());writeString(out,e.getKey(),60);writeString(out,e.getValue(),16);}}
 private static Map<String,String> readMap(DataInputStream in)throws IOException{Map<String,String> map=new LinkedHashMap<>();int n=readCount(in,5000);for(int i=0;i<n;i++){String k=readString(in,60),v=readString(in,16);validRule(k,v);if(map.put(k,v)!=null)throw new IOException("중복 색상 규칙이 있습니다.");}return map;}
 private static void validRule(String key,String value)throws IOException{if(key==null||key.trim().isEmpty()||value==null||!value.matches("[A-Z][A-Z0-9_-]{0,15}"))throw new IOException("색상 규칙 형식이 올바르지 않습니다.");}
 static void writeJob(DataOutputStream out,Inventory.Job job)throws IOException{if(job.sourceRecordedAt<0||job.stockUpdatedAt<0||job.initialQuantity< -1||job.initialQuantity>StockHistory.MAX_TOTAL||job.inputBuffer==null||job.inputBuffer.length()>Inventory.MAX_SOURCE_CHARS||job.inputBufferRecordedAt<0||job.stockChangeCount< -1)throw new IOException("작업 수량과 시각이 올바르지 않습니다.");writeString(out,job.id,80);writeString(out,job.title,80);writeString(out,job.sourceText,Inventory.MAX_SOURCE_CHARS);out.writeLong(job.updatedAt);count(job.rows.size(),2000);out.writeInt(job.rows.size());for(Inventory.Row r:job.rows)writeRow(out,r);out.writeLong(job.sourceRecordedAt);out.writeLong(job.initialQuantity);out.writeLong(job.stockUpdatedAt);out.writeBoolean(job.inputCleared);writeString(out,job.inputBuffer,Inventory.MAX_SOURCE_CHARS);out.writeLong(job.inputBufferRecordedAt);out.writeBoolean(job.completed);writeString(out,job.stockSessionId,80);out.writeLong(job.stockChangeCount);}
 static Inventory.Job readJob(DataInputStream in,int version)throws IOException{Inventory.Job job=new Inventory.Job();job.id=readString(in,80);job.title=readString(in,80);job.sourceText=readString(in,Inventory.MAX_SOURCE_CHARS);job.updatedAt=in.readLong();int n=readCount(in,2000);for(int i=0;i<n;i++)job.rows.add(readRow(in,version));if(version>=4){job.sourceRecordedAt=in.readLong();if(job.sourceRecordedAt<0)throw new IOException("입력 시각이 올바르지 않습니다.");}if(version>=5){job.initialQuantity=in.readLong();job.stockUpdatedAt=in.readLong();if(job.initialQuantity< -1||job.initialQuantity>StockHistory.MAX_TOTAL||job.stockUpdatedAt<0)throw new IOException("작업 수량과 시각이 올바르지 않습니다.");}if(version>=6)job.inputCleared=in.readBoolean();if(version>=7){job.inputBuffer=readString(in,Inventory.MAX_SOURCE_CHARS);job.inputBufferRecordedAt=in.readLong();if(job.inputBufferRecordedAt<0)throw new IOException("새 입력 시각이 올바르지 않습니다.");}if(version>=8)job.completed=in.readBoolean();if(version>=9){job.stockSessionId=readString(in,80);job.stockChangeCount=in.readLong();if(job.stockChangeCount< -1)throw new IOException("작업 변경 기록 개수가 올바르지 않습니다.");}else job.stockChangeCount=-1;return job;}
 private static void writeUndoSnapshot(DataOutputStream out,UndoSnapshot snapshot)throws IOException{
  if(snapshot==null||snapshot.draftJobId==null||snapshot.rows==null||snapshot.initialQuantity< -1||snapshot.initialQuantity>StockHistory.MAX_TOTAL||snapshot.stockUpdatedAt<0||snapshot.updatedAt<0)throw new IOException("되돌리기 자료가 올바르지 않습니다.");
  out.writeByte(snapshot.kind);writeString(out,snapshot.draftJobId,80);count(snapshot.rows.size(),Inventory.MAX_ROWS);out.writeInt(snapshot.rows.size());for(Inventory.Row row:snapshot.rows)writeRow(out,row);
  out.writeLong(snapshot.initialQuantity);out.writeLong(snapshot.stockUpdatedAt);out.writeLong(snapshot.updatedAt);
  boolean hasRule=snapshot.ruleSku!=null&&!snapshot.ruleSku.isEmpty();out.writeBoolean(hasRule);
  if(hasRule){if(snapshot.sourceColor==null||snapshot.sourceColor.trim().isEmpty()||!validRuleCode(snapshot.writtenRuleValue)||(snapshot.previousRuleValue!=null&&!validRuleCode(snapshot.previousRuleValue)))throw new IOException("되돌리기 색상 규칙이 올바르지 않습니다.");writeString(out,snapshot.ruleSku,80);writeString(out,snapshot.sourceColor,60);out.writeBoolean(snapshot.previousRuleValue!=null);if(snapshot.previousRuleValue!=null)writeString(out,snapshot.previousRuleValue,16);writeString(out,snapshot.writtenRuleValue,16);}
  else if((snapshot.sourceColor!=null&&!snapshot.sourceColor.isEmpty())||snapshot.previousRuleValue!=null||snapshot.writtenRuleValue!=null)throw new IOException("되돌리기 색상 규칙이 올바르지 않습니다.");
  if(snapshot.kind==UndoSnapshot.PHOTO_BINDING){writeString(out,snapshot.targetRowId,36);writeBinding(out,snapshot.previousBinding);writeBinding(out,snapshot.expectedAfterBinding);writeRow(out,snapshot.expectedRow);}
 }
 private static UndoSnapshot readUndoSnapshot(DataInputStream in,int version)throws IOException{
  UndoSnapshot snapshot=new UndoSnapshot();if(version>=10)snapshot.kind=in.readByte();snapshot.draftJobId=readString(in,80);int n=readCount(in,Inventory.MAX_ROWS);for(int i=0;i<n;i++)snapshot.rows.add(readRow(in,version));
  snapshot.initialQuantity=in.readLong();snapshot.stockUpdatedAt=in.readLong();snapshot.updatedAt=in.readLong();if(snapshot.initialQuantity< -1||snapshot.initialQuantity>StockHistory.MAX_TOTAL||snapshot.stockUpdatedAt<0||snapshot.updatedAt<0)throw new IOException("되돌리기 수량과 시각이 올바르지 않습니다.");
  if(in.readBoolean()){snapshot.ruleSku=readString(in,80);snapshot.sourceColor=readString(in,60);if(snapshot.ruleSku.isEmpty()||snapshot.sourceColor.trim().isEmpty())throw new IOException("되돌리기 색상 규칙이 올바르지 않습니다.");if(in.readBoolean())snapshot.previousRuleValue=readString(in,16);snapshot.writtenRuleValue=readString(in,16);if(!validRuleCode(snapshot.writtenRuleValue)||(snapshot.previousRuleValue!=null&&!validRuleCode(snapshot.previousRuleValue)))throw new IOException("되돌리기 색상 규칙이 올바르지 않습니다.");}
  if(snapshot.kind==UndoSnapshot.PHOTO_BINDING){snapshot.targetRowId=readString(in,36);snapshot.previousBinding=readBinding(in);snapshot.expectedAfterBinding=readBinding(in);snapshot.expectedRow=readRow(in,version);}
  return snapshot;
 }

 private static void validatePhotos(State s)throws IOException {
  PhotoCatalog.uuid(s.generation);PhotoCatalog.require(s.revision>=0&&s.catalog!=null,"state revision/catalogue");s.catalog.validate(s.revision);
  PhotoCatalog.require(s.draft!=null&&s.jobs!=null&&s.recentJobs!=null,"jobs");validatePhotoRows(s,s.draft.rows);
  for(Inventory.Job j:s.jobs){PhotoCatalog.require(j!=null,"job");validatePhotoRows(s,j.rows);}for(Inventory.Job j:s.recentJobs){PhotoCatalog.require(j!=null,"recent job");validatePhotoRows(s,j.rows);}
  if(s.undoSnapshot!=null){UndoSnapshot u=s.undoSnapshot;PhotoCatalog.require(u.kind==UndoSnapshot.TABLE_CHANGE||u.kind==UndoSnapshot.PHOTO_BINDING,"undo kind");validatePhotoRows(s,u.rows);
   if(u.kind==UndoSnapshot.PHOTO_BINDING){PhotoCatalog.uuid(u.targetRowId);PhotoCatalog.require(u.expectedRow!=null&&u.targetRowId.equals(u.expectedRow.rowId),"photo undo expected row");validateRow(u.expectedRow);s.catalog.validateBinding(u.expectedRow.binding,s.revision,true);s.catalog.validateBinding(u.previousBinding,s.revision,true);s.catalog.validateBinding(u.expectedAfterBinding,s.revision,true);}
   else PhotoCatalog.require((u.targetRowId==null||u.targetRowId.isEmpty())&&u.previousBinding==null&&u.expectedAfterBinding==null&&u.expectedRow==null,"table undo photo fields");
  }
 }
 private static void validatePhotoRows(State s,List<Inventory.Row> rows)throws IOException {
  PhotoCatalog.require(rows!=null,"rows");Set<String> ids=new HashSet<>();for(Inventory.Row r:rows){validateRow(r);PhotoCatalog.uuid(r.rowId);PhotoCatalog.require(ids.add(r.rowId),"duplicate row ID");s.catalog.validateBinding(r.binding,s.revision,true);}
 }
 private static void writeRow(DataOutputStream out,Inventory.Row r)throws IOException {
  validateRow(r);writeString(out,r.sku,80);writeString(out,r.sourceColor,60);writeString(out,r.color,30);out.writeInt(r.sourceLine);for(long q:r.quantities)out.writeLong(q);writeString(out,r.rowId,36);writeBinding(out,r.binding);
 }
 private static Inventory.Row readRow(DataInputStream in,int version)throws IOException {
  Inventory.Row r=new Inventory.Row();r.sku=readString(in,80);r.sourceColor=readString(in,60);r.color=readString(in,30);r.sourceLine=in.readInt();for(int k=0;k<4;k++)r.quantities[k]=in.readLong();if(version>=10){r.rowId=readString(in,36);r.binding=readBinding(in);}validateRow(r);return r;
 }
 private static void writeBinding(DataOutputStream out,PhotoCatalog.Binding b)throws IOException {
  out.writeBoolean(b!=null);if(b==null)return;writeString(out,b.productId,36);writeString(out,b.variantId,36);writeString(out,b.photoVersionId,64);writeString(out,b.skuAtBinding,80);writeString(out,b.sourceColorAtBinding,60);out.writeLong(b.bindingRevision);out.writeBoolean(b.needsConfirmation);
 }
 private static boolean readFlag(DataInputStream in)throws IOException {int flag=in.readUnsignedByte();if(flag>1)throw new IOException("사진 플래그가 올바르지 않습니다.");return flag==1;}
 private static PhotoCatalog.Binding readBinding(DataInputStream in)throws IOException {
  if(!readFlag(in))return null;return new PhotoCatalog.Binding(readString(in,36),readString(in,36),readString(in,64),readString(in,80),readString(in,60),in.readLong(),readFlag(in));
 }
 private static void writeAliases(DataOutputStream out,List<String> aliases,int max)throws IOException {count(aliases.size(),PhotoLimits.MAX_CONFIRMED_ALIASES);out.writeInt(aliases.size());for(String a:aliases)writeString(out,a,max);}
 private static List<String> readAliases(DataInputStream in,int max,int[] remaining)throws IOException {int n=readCount(in,remaining[0]);remaining[0]-=n;List<String> aliases=new ArrayList<>();for(int i=0;i<n;i++)aliases.add(readString(in,max));return aliases;}
 private static void writeCatalog(DataOutputStream out,PhotoCatalog c)throws IOException {
  out.writeInt(c.products.size());for(PhotoCatalog.Product p:c.products.values()){writeString(out,p.id,36);writeString(out,p.sku,80);writeAliases(out,p.confirmedSkuAliases,80);}
  out.writeInt(c.variants.size());for(PhotoCatalog.Variant v:c.variants.values()){writeString(out,v.id,36);writeString(out,v.productId,36);writeString(out,v.sourceColor,60);writeAliases(out,v.confirmedColorAliases,60);writeString(out,v.currentPhotoVersionId,64);out.writeInt(v.barcodes.size());for(String barcode:v.barcodes)writeString(out,barcode,PhotoLimits.MAX_BARCODE_CHARS);}
  out.writeInt(c.assets.size());for(PhotoCatalog.Asset a:c.assets.values()){writeString(out,a.hash,64);out.writeLong(a.byteLength);out.writeInt(a.width);out.writeInt(a.height);}
  out.writeInt(c.receipts.size());for(PhotoCatalog.Receipt r:c.receipts.values()){writeString(out,r.requestId,36);writeString(out,r.requestContentFingerprint,64);writeBinding(out,r.binding);writeString(out,r.photoVersionId,64);out.writeLong(r.committedRevision);}
 }
 private static PhotoCatalog readCatalog(DataInputStream in,int version)throws IOException {
  PhotoCatalog c=new PhotoCatalog();int[] remaining={PhotoLimits.MAX_CONFIRMED_ALIASES};int n=readCount(in,PhotoLimits.MAX_PRODUCTS);
  for(int i=0;i<n;i++){PhotoCatalog.Product p=new PhotoCatalog.Product(readString(in,36),readString(in,80),readAliases(in,80,remaining));if(c.products.put(p.id,p)!=null)throw new IOException("중복 상품 ID입니다.");}
  n=readCount(in,PhotoLimits.MAX_VARIANTS);for(int i=0;i<n;i++){PhotoCatalog.Variant v=new PhotoCatalog.Variant(readString(in,36),readString(in,36),readString(in,60),readAliases(in,60,remaining),readString(in,64));if(version>=11){int barcodes=readCount(in,PhotoLimits.MAX_BARCODES_PER_VARIANT);for(int j=0;j<barcodes;j++)v.barcodes.add(readString(in,PhotoLimits.MAX_BARCODE_CHARS));}if(c.variants.put(v.id,v)!=null)throw new IOException("중복 색상 ID입니다.");}
  n=readCount(in,PhotoLimits.MAX_PHOTO_ASSETS);for(int i=0;i<n;i++){PhotoCatalog.Asset a=new PhotoCatalog.Asset(readString(in,64),in.readLong(),in.readInt(),in.readInt());if(c.assets.put(a.hash,a)!=null)throw new IOException("중복 사진 hash입니다.");}
  n=readCount(in,PhotoLimits.MAX_RECEIPTS);for(int i=0;i<n;i++){PhotoCatalog.Receipt r=new PhotoCatalog.Receipt(readString(in,36),readString(in,64),readBinding(in),readString(in,64),in.readLong());if(c.receipts.put(r.requestId,r)!=null)throw new IOException("중복 등록 요청 ID입니다.");}return c;
 }
 private static boolean validRuleCode(String value){return value!=null&&value.matches("[A-Z][A-Z0-9_-]{0,15}");}
 private static void validateRow(Inventory.Row r)throws IOException{if(r==null||r.sku==null||r.sku.trim().isEmpty()||r.sourceColor==null||r.sourceColor.trim().isEmpty()||r.color==null||r.color.trim().isEmpty()||r.quantities==null||r.quantities.length!=4||r.sourceLine<0)throw new IOException("행의 내용이 올바르지 않습니다.");for(long q:r.quantities)if(q<0||q>999999)throw new IOException("수량은 0~999999여야 합니다.");}
 static void writeString(DataOutputStream out,String s,int max)throws IOException{if(s==null||s.length()>max)throw new IOException("입력 내용이 허용 길이를 넘었습니다.");byte[] b=s.getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);}
 static String readString(DataInputStream in,int max)throws IOException{int len=in.readInt();if(len<0||len>max*4||len>in.available())throw new IOException("자료 길이가 올바르지 않습니다.");byte[] b=new byte[len];in.readFully(b);String s=new String(b,StandardCharsets.UTF_8);if(s.length()>max||!Arrays.equals(b,s.getBytes(StandardCharsets.UTF_8)))throw new IOException("문자 자료가 올바르지 않습니다.");return s;}
 static int readCount(DataInputStream in,int max)throws IOException{int n=in.readInt();count(n,max);return n;}
 static void count(int n,int max)throws IOException{if(n<0||n>max)throw new IOException("자료 개수가 허용 범위를 벗어났습니다.");}
}
