package com.gongyo.orderly;
import android.content.Context;
import java.io.*;
/** Checked metadata publication; missing and failed recovery have distinct outcomes. */
public final class JobStore {
 private final CheckedAtomicFile file,pending;
 private final File statePath,pendingPath;private final CheckedAtomicFile.FileOps io;
 private boolean initializationRequired;
 public JobStore(Context context){this(context.getFilesDir(),context.getCacheDir(),new AndroidFileOps(context.getFilesDir(),context.getCacheDir()));}
 public JobStore(File filesDir,File cacheDir,CheckedAtomicFile.FileOps io){this.io=io;statePath=new File(filesDir,"orders.snote");pendingPath=new File(cacheDir,"pending-export.snote");file=new CheckedAtomicFile(statePath,io);pending=new CheckedAtomicFile(pendingPath,io);}
 public StateCodec.State load()throws IOException{
  new MediaStore(statePath.getParentFile(),io,MainActivity.backupJpegValidator(io)).recoverRestoreProtection();
  initializationRequired=false;
  byte[] bytes;try{bytes=read(file);}catch(CheckedAtomicFile.MissingFileException missing){initializationRequired=true;return new StateCodec.State();}
  StateCodec.State loaded=StateCodec.decode(bytes);DataInputStream header=new DataInputStream(new ByteArrayInputStream(bytes));header.readInt();initializationRequired=header.readInt()<PhotoLimits.SCHEMA_VERSION;return loaded;
 }
 public boolean requiresInitialization(){return initializationRequired;}
 public void save(StateCodec.State state)throws IOException{initialize(file);file.write(StateCodec.encode(state));}
 public long storageBytes()throws IOException{CheckedAtomicFile.Kind kind=io.inspect(statePath);if(kind==CheckedAtomicFile.Kind.MISSING)return 0;if(kind!=CheckedAtomicFile.Kind.REGULAR)throw new IOException("Unexpected state type");return read(file).length;}
 public void writePending(byte[] bytes)throws IOException{validatePending(bytes);try{readPending();}catch(CheckedAtomicFile.MissingFileException absent){}pending.write(bytes);}
 public byte[] readPending()throws IOException{return pending.readFully(PhotoLimits.MAX_METADATA_BYTES,JobStore::validatePending);}
 public void clearPending()throws IOException{pending.delete();}
 public byte[] prepareBackup(InventoryRepository repository,MediaStore media,String expectedGeneration,long expectedRevision,long at)throws IOException {
  final byte[][] result={null};repository.exclusive(()->{
   try{readPending();throw new IOException("Existing pending operation retained");}catch(CheckedAtomicFile.MissingFileException absent){}
   InventoryRepository.Snapshot current=repository.snapshot();if(!current.generation.equals(expectedGeneration)||current.revision!=expectedRevision)throw new IOException("Backup preparation conflict");
   StateCodec.State state=current.stateCopy;if(at<=0)throw new IOException("Backup time");state.lastBackupAt=at;byte[] bytes=StateCodec.encode(state);String token=java.util.UUID.randomUUID().toString(),name="backup-"+token+".snote";
   media.writeOperation(name,bytes);media.pin(new PhotoCatalog.Pin(token,PhotoCatalog.Pin.BACKUP,current.generation,new java.util.ArrayList<>(IntegratedBackup.liveVersions(state)),name));
   writePending(bytes);result[0]=bytes;
  });return result[0];
 }
 public void recoverBackup(InventoryRepository repository,MediaStore media,byte[] bytes)throws IOException {
  repository.exclusive(()->{StateCodec.State frozen=StateCodec.decode(bytes);java.util.Set<String> refs=IntegratedBackup.liveVersions(frozen);for(PhotoCatalog.Pin p:media.pins())if(p.kind.equals(PhotoCatalog.Pin.BACKUP)&&!p.snapshotFile.isEmpty()&&java.util.Arrays.equals(bytes,media.readOperation(p.snapshotFile)))return;
   for(String hash:refs)media.validateBackupJpeg(hash,frozen.catalog.assets.get(hash));String token=java.util.UUID.randomUUID().toString(),name="backup-"+token+".snote";media.writeOperation(name,bytes);media.pin(new PhotoCatalog.Pin(token,PhotoCatalog.Pin.BACKUP,frozen.generation,new java.util.ArrayList<>(refs),name));
  });
 }
 public MediaSource backupMedia(InventoryRepository repository,MediaStore media,byte[] bytes)throws IOException {
  final byte[] frozen=bytes.clone();final StateCodec.State state=StateCodec.decode(frozen);final java.util.Set<String> refs=IntegratedBackup.liveVersions(state);
  return new MediaSource(){private void check()throws IOException{for(PhotoCatalog.Pin pin:media.pins())if(pin.kind.equals(PhotoCatalog.Pin.BACKUP)&&pin.generation.equals(state.generation)&&new java.util.LinkedHashSet<>(pin.photoVersionIds).equals(refs)&&!pin.snapshotFile.isEmpty()&&java.util.Arrays.equals(frozen,media.readOperation(pin.snapshotFile)))return;throw new IOException("Missing matching durable backup pin");}
   public void checkReady()throws IOException{repository.exclusive(this::check);}
   public InputStream open(String hash)throws IOException{final InputStream[] stream={null};repository.exclusive(()->{check();if(!refs.contains(hash))throw new IOException("Outside frozen backup");stream[0]=media.open(hash);});return stream[0];}
  };
 }
 public void finishBackup(InventoryRepository repository,MediaStore media,byte[] bytes)throws IOException {
  repository.exclusive(()->{if(!java.util.Arrays.equals(bytes,readPending()))throw new IOException("Backup pending changed");backupMedia(repository,media,bytes).checkReady();clearPending();
   for(PhotoCatalog.Pin pin:media.pins())if(pin.kind.equals(PhotoCatalog.Pin.BACKUP)&&!pin.snapshotFile.isEmpty()&&java.util.Arrays.equals(bytes,media.readOperation(pin.snapshotFile))){media.release(pin.token);media.deleteOperation(pin.snapshotFile);}
  });
 }
 private static void validatePending(byte[] bytes)throws IOException{if(ExportSnapshot.isEncoded(bytes))ExportSnapshot.decode(bytes);else StateCodec.decode(bytes);}
 /** Capture, durable protection and pending admission have one Repository boundary. */
 public ExportSnapshot prepareExport(InventoryRepository repository,MediaStore media,String jobId,String expectedGeneration,long expectedRevision,String format,String route)throws IOException {
  final ExportSnapshot[] result={null};repository.exclusive(()->{
   try{readPending();throw new IOException("진행 중인 출력을 먼저 완료하거나 취소해 주세요.");}catch(CheckedAtomicFile.MissingFileException absent){}
   InventoryRepository.Snapshot current=repository.snapshot();if(!current.generation.equals(expectedGeneration)||current.revision!=expectedRevision)throw new IOException("출력 준비 중 자료가 변경되었습니다. 다시 확인해 주세요.");
   ExportSnapshot frozen=ExportSnapshot.capture(current,jobId);frozen.format=format;frozen.pinToken=java.util.UUID.randomUUID().toString();frozen.options.put("route",route);
   String suffix=format.equals("print")?"pdf":format;frozen.options.put("displayName",ExportFileName.create(frozen.jobCopy.title,System.currentTimeMillis(),suffix,java.util.TimeZone.getDefault()));
   byte[] bytes=ExportSnapshot.encode(frozen);
   publishExport(media,frozen,bytes);
   result[0]=frozen;
  });return result[0];
 }
 /** Upgrade a previous state-wrapped export only when its saved callback format is known. */
 public ExportSnapshot recoverLegacyExport(InventoryRepository repository,MediaStore media,String route)throws IOException {
  final ExportSnapshot[] result={null};repository.exclusive(()->{
   byte[] bytes=readPending();if(ExportSnapshot.isEncoded(bytes)){result[0]=ExportSnapshot.decode(bytes);return;}
   if(!"pdf".equals(route)&&!"xlsx".equals(route))throw new IOException("이전 출력의 형식을 확인할 수 없습니다. 진행 파일을 보존했습니다.");
   StateCodec.State legacy=StateCodec.decode(bytes);
   ExportSnapshot frozen=ExportSnapshot.capture(new InventoryRepository(legacy,s->{throw new IOException("Read-only legacy export");}).snapshot(),null);
   frozen.format=route;frozen.pinToken=java.util.UUID.randomUUID().toString();frozen.options.put("route",route);frozen.options.put("displayName",ExportFileName.create(frozen.jobCopy.title,System.currentTimeMillis(),route,java.util.TimeZone.getDefault()));
   publishExport(media,frozen,ExportSnapshot.encode(frozen));result[0]=frozen;
  });return result[0];
 }
 private void publishExport(MediaStore media,ExportSnapshot frozen,byte[] bytes)throws IOException {
   media.pin(new PhotoCatalog.Pin(frozen.pinToken,PhotoCatalog.Pin.EXPORT,frozen.generation,new java.util.ArrayList<>(frozen.protectedVersions()),""));
   try{writePending(bytes);}catch(CommitUncertainException uncertain){throw uncertain;}catch(IOException failed){try{media.release(frozen.pinToken);}catch(IOException cleanup){failed.addSuppressed(cleanup);if(cleanup instanceof CommitUncertainException)throw new CommitUncertainException("Export pin cleanup uncertain",failed,cleanup);}throw failed;}
 }
 /** Only the matching confirmed pending operation can relinquish its protection. */
 public void finishExport(InventoryRepository repository,MediaStore media,ExportSnapshot frozen)throws IOException {
  repository.exclusive(()->{
   ExportSnapshot durable;try{durable=ExportSnapshot.decode(readPending());}catch(CheckedAtomicFile.MissingFileException absent){media.release(frozen.pinToken);return;}if(!durable.pinToken.equals(frozen.pinToken)||!java.util.Arrays.equals(ExportSnapshot.encode(durable),ExportSnapshot.encode(frozen)))throw new IOException("출력 진행 정보가 변경되었습니다.");
   // Remove pending durably before release; failed cleanup may retain an extra safe pin.
   clearPending();media.release(frozen.pinToken);
  });
 }
 public MediaSource exportMedia(InventoryRepository repository,MediaStore media,ExportSnapshot frozen)throws IOException {
  final ExportSnapshot owned=ExportSnapshot.decode(ExportSnapshot.encode(frozen));
  return new MediaSource(){
   private void check()throws IOException{boolean found=false;for(PhotoCatalog.Pin p:media.pins())if(p.token.equals(owned.pinToken)){if(!p.kind.equals(PhotoCatalog.Pin.EXPORT)||!p.generation.equals(owned.generation)||!new java.util.LinkedHashSet<>(p.photoVersionIds).equals(owned.protectedVersions()))throw new IOException("Export pin mismatch");found=true;}if(!found)throw new IOException("Missing durable export pin");}
   public void checkReady()throws IOException{repository.exclusive(this::check);}
   public InputStream open(String hash)throws IOException{final InputStream[] stream={null};repository.exclusive(()->{check();if(!owned.renderedVersions().contains(hash))throw new IOException("Photo outside frozen export");stream[0]=media.open(hash);});return stream[0];}
  };
 }
 private static byte[] read(CheckedAtomicFile f)throws IOException{return f.readFully(PhotoLimits.MAX_METADATA_BYTES,StateCodec::decode);}
 private static void initialize(CheckedAtomicFile f)throws IOException{try{read(f);}catch(CheckedAtomicFile.MissingFileException missing){/* Only proven absence permits first publication. */}}
}
