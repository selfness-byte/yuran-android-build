package com.gongyo.orderly;

import java.io.*;
import java.security.*;
import java.util.*;

/** Owner-private immutable JPEGs and durable pins. Pin/GC callers hold Repository.exclusive. */
public final class MediaStore {
 private final File root,media,pinFile;
 private static final Map<String,Object> LOCKS=new HashMap<>();
 private final Object lock;private final CheckedAtomicFile.FileOps io;private final CheckedAtomicFile pinStore;
 private IOException unavailable;
 public interface JpegValidator {void validate(File file,PhotoCatalog.Asset asset)throws IOException;}
 private JpegValidator jpegValidator;
 private static final int PIN_MAGIC=0x50504e31;
 /** Source compatibility only; every storage entry requires an explicit checked adapter. */
 @Deprecated public MediaStore(File filesDir){this(filesDir,null,true);}
 public MediaStore(File filesDir,CheckedAtomicFile.FileOps io){this(filesDir,Objects.requireNonNull(io),true);}
 public MediaStore(File filesDir,CheckedAtomicFile.FileOps io,JpegValidator validator){this(filesDir,io);jpegValidator=Objects.requireNonNull(validator);}
 private MediaStore(File filesDir,CheckedAtomicFile.FileOps io,boolean unused){root=Objects.requireNonNull(filesDir);media=new File(root,"media");pinFile=new File(root,"photo-pins.bin");try{if(io==null)lock=new Object();else{String path=root.getCanonicalPath();synchronized(LOCKS){lock=LOCKS.computeIfAbsent(path,k->new Object());}}}catch(IOException e){throw new IllegalArgumentException("Invalid media path",e);}this.io=io;pinStore=io==null?null:new CheckedAtomicFile(pinFile,io);}
 /** Caller holds Repository.exclusive; disposable scratch, never an inferred trust anchor. */
 File registrationStagingDirectory()throws IOException{synchronized(lock){ready();try{File staging=new File(root,"registration-staging");CheckedAtomicFile.directory(staging,io);return staging;}catch(CommitUncertainException e){unavailable=e;throw e;}}}
 /** Caller owns the stream and keeps Repository.exclusive through consumption and close. */
 InputStream openRegistrationPrepared(File prepared)throws IOException{synchronized(lock){ready();try{
  File staging=registrationPreparedParent(prepared);
  if(io.inspect(staging)!=CheckedAtomicFile.Kind.DIRECTORY||io.inspect(prepared)!=CheckedAtomicFile.Kind.REGULAR)throw new IOException("Regular owned registration prepared file required");
  return io.openRead(prepared);
 }catch(CommitUncertainException e){unavailable=e;throw e;}}}
 /** Dispose only the owned direct child; missing-file retries still confirm its parent entry. */
 void discardRegistrationPrepared(File prepared)throws IOException{synchronized(lock){ready();try{
  File staging=registrationPreparedParent(prepared);CheckedAtomicFile.Kind parent=io.inspect(staging);
  if(parent==CheckedAtomicFile.Kind.MISSING){io.syncDirectory(root);return;}
  if(parent!=CheckedAtomicFile.Kind.DIRECTORY)throw new IOException("Unexpected registration staging type");
  CheckedAtomicFile.Kind kind=io.inspect(prepared);if(kind==CheckedAtomicFile.Kind.REGULAR)io.unlink(prepared);else if(kind!=CheckedAtomicFile.Kind.MISSING)throw new IOException("Unexpected registration prepared type");
  io.syncDirectory(staging);
 }catch(CommitUncertainException e){unavailable=e;throw e;}}}
 private File registrationPreparedParent(File prepared)throws IOException {
  if(prepared==null||!prepared.isAbsolute())throw new IOException("Absolute registration prepared path required");
  for(String part:prepared.getPath().split("/"))if(part.equals(".")||part.equals(".."))throw new IOException("Dot registration prepared path");
  File parent=prepared.getParentFile();if(parent==null)throw new IOException("Missing registration prepared parent");
  File rootAnchor=io.directoryAnchor(root),parentAnchor=io.directoryAnchor(parent);
  // Canonical comparison is limited to already registered anchors, never a supplied descendant.
  if(!rootAnchor.getCanonicalFile().equals(parentAnchor.getCanonicalFile()))throw new IOException("Different registration owner anchor");
  String rootSuffix=registrationSuffix(root,rootAnchor),parentSuffix=registrationSuffix(parent,parentAnchor);
  String expected=rootSuffix.isEmpty()?"registration-staging":rootSuffix+File.separator+"registration-staging";
  if(!parentSuffix.equals(expected))throw new IOException("Outside registration prepared directory");
  if(io.inspect(root)!=CheckedAtomicFile.Kind.DIRECTORY)throw new IOException("Registration owner directory unavailable");return parent;
 }
 private static String registrationSuffix(File path,File anchor)throws IOException {
  if(path.equals(anchor))return "";String prefix=anchor.getPath()+File.separator;
  if(!path.getPath().startsWith(prefix))throw new IOException("Registration anchor mismatch");return path.getPath().substring(prefix.length());
 }
 private void ready()throws IOException{if(io==null)throw new IOException("Checked storage adapter required");if(unavailable!=null)throw unavailable;}
 private CommitUncertainException uncertain(IOException cause){CommitUncertainException e=cause instanceof CommitUncertainException?(CommitUncertainException)cause:new CommitUncertainException("Media storage uncertain",cause,null);unavailable=e;return e;}
 public File prepare(InputStream jpeg,PhotoCatalog.Asset asset)throws IOException{synchronized(lock){
  ready();if(jpeg==null||asset==null)throw new IOException("missing media");PhotoCatalog.hash(asset.hash);if(asset.byteLength<=0||asset.byteLength>PhotoLimits.MAX_INPUT_BYTES||asset.width<=0||asset.height<=0||Math.max(asset.width,asset.height)>PhotoLimits.MAX_REGISTERED_EDGE)throw new IOException("media bounds");
  CheckedAtomicFile.directory(media,io);File destination=mediaFile(asset.hash),temp=new File(media,"media-"+UUID.randomUUID()+".new");IOException primary=null;boolean promoted=false;
  try{
   MessageDigest digest=digest();long bytes=0;OutputStream out=io.createExclusive(temp);IOException writing=null;try{byte[] b=new byte[32768];for(int n;(n=jpeg.read(b))!=-1;){if(n==0){int v=jpeg.read();if(v==-1)break;b[0]=(byte)v;n=1;}if(n>asset.byteLength-bytes)throw new IOException("media length");bytes+=n;digest.update(b,0,n);io.write(out,b,0,n);}io.sync(out);}catch(IOException e){writing=e;throw e;}finally{try{io.close(out);}catch(IOException e){if(e instanceof CommitUncertainException){unavailable=e;if(writing!=null&&writing!=e)e.addSuppressed(writing);throw e;}if(writing==null)throw e;writing.addSuppressed(e);}}
   if(bytes!=asset.byteLength||!hex(digest.digest()).equals(asset.hash))throw new IOException("media bytes/hash");regular(temp);int[] dimensions=checkedDimensions(temp);if(dimensions[0]!=asset.width||dimensions[1]!=asset.height)throw new IOException("media dimensions");if(jpegValidator!=null)jpegValidator.validate(temp,asset);
   CheckedAtomicFile.Kind existing=io.inspect(destination);if(existing==CheckedAtomicFile.Kind.REGULAR){verify(destination,asset);if(jpegValidator!=null)jpegValidator.validate(destination,asset);io.syncFile(destination);io.syncDirectory(media);return destination;}if(existing!=CheckedAtomicFile.Kind.MISSING)throw new IOException("Unexpected media type");
   try{io.rename(temp,destination);promoted=true;}catch(IOException rename){try{if(io.inspect(temp)!=CheckedAtomicFile.Kind.REGULAR)throw uncertain(rename);}catch(CommitUncertainException e){throw e;}catch(IOException inspect){rename.addSuppressed(inspect);throw uncertain(rename);}throw rename;}
   try{io.syncDirectory(media);}catch(IOException sync){throw uncertain(sync);}return destination;
  }catch(IOException e){primary=e;if(e instanceof CommitUncertainException)unavailable=e;throw e;}finally{
   try{if(io.inspect(temp)==CheckedAtomicFile.Kind.REGULAR){io.unlink(temp);io.syncDirectory(media);}}catch(IOException cleanup){if(cleanup instanceof CommitUncertainException){unavailable=cleanup;if(primary!=null&&primary!=cleanup)cleanup.addSuppressed(primary);throw cleanup;}if(primary!=null)primary.addSuppressed(cleanup);else if(!promoted)throw cleanup;/* committed media remains valid; orphan cleanup deferred */}
  }
 }}
 public InputStream open(String hash)throws IOException{synchronized(lock){ready();File file=mediaFile(hash);regular(file);return io.openRead(file);}}
 public void collect(Set<String> protectedVersions)throws IOException{synchronized(lock){
  ready();if(protectedVersions==null)throw new IOException("missing protections");Set<String> protectedHashes=new HashSet<>();for(String h:protectedVersions){PhotoCatalog.hash(h);protectedHashes.add(h);}for(PhotoCatalog.Pin pin:pins())protectedHashes.addAll(pin.photoVersionIds);
  CheckedAtomicFile.Kind kind=io.inspect(media);if(kind==CheckedAtomicFile.Kind.MISSING)return;if(kind!=CheckedAtomicFile.Kind.DIRECTORY)throw new IOException("Unexpected media directory");
  for(File f:io.list(media)){String n=f.getName();if(n.matches("[0-9a-f]{64}\\.jpg")&&!protectedHashes.contains(n.substring(0,64))){File checked=mediaFile(n.substring(0,64));regular(checked);try{io.unlink(checked);io.syncDirectory(media);}catch(IOException failure){throw uncertain(failure);}}}
 }}
 public void pin(PhotoCatalog.Pin pin)throws IOException{synchronized(lock){
  ready();if(pin==null)throw new IOException("missing pin");pin.validate();validateSnapshot(pin.snapshotFile);List<PhotoCatalog.Pin> pins=pins();boolean replaced=false;for(int i=0;i<pins.size();i++)if(pins.get(i).token.equals(pin.token)){pins.set(i,pin.copy());replaced=true;break;}if(!replaced)pins.add(pin.copy());writePins(pins);
 }}
 public List<PhotoCatalog.Pin> pins()throws IOException{synchronized(lock){
  ready();try{return decodePins(pinStore.readFully(PhotoLimits.MAX_METADATA_BYTES,bytes->decodePins(bytes)));}catch(CheckedAtomicFile.MissingFileException absent){return new ArrayList<>();}catch(CommitUncertainException e){throw uncertain(e);}
 }}
 private List<PhotoCatalog.Pin> decodePins(byte[] bytes)throws IOException{
  List<PhotoCatalog.Pin> result=new ArrayList<>();long length=bytes.length;if(length<8||length>PhotoLimits.MAX_METADATA_BYTES)throw new IOException("pin file size");
  try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){if(in.readInt()!=PIN_MAGIC)throw new IOException("pin format");int count=in.readInt();if(count<0||count>PhotoLimits.MAX_PHOTO_ASSETS||count>(length-8)/24)throw new IOException("pin count");Set<String> tokens=new HashSet<>();for(int i=0;i<count;i++){String token=string(in,36),kind=string(in,12),generation=string(in,36),snapshot=string(in,4096);int refs=in.readInt();if(refs<0||refs>PhotoLimits.MAX_PHOTO_ASSETS||refs>(length-8)/68)throw new IOException("pin reference count");List<String> hashes=new ArrayList<>();for(int j=0;j<refs;j++)hashes.add(string(in,64));PhotoCatalog.Pin p=new PhotoCatalog.Pin(token,kind,generation,hashes,snapshot);p.validate();validateSnapshot(snapshot);if(!tokens.add(token))throw new IOException("duplicate pin token");result.add(p);}if(in.read()!=-1)throw new IOException("pin tail");}return result;
 }
 void pinRestorePair(PhotoCatalog.Pin previous,PhotoCatalog.Pin imported)throws IOException{synchronized(lock){ready();if(previous==null||imported==null||previous.token.equals(imported.token)||!previous.generation.equals(imported.generation)||!previous.kind.equals(PhotoCatalog.Pin.RECOVERY)||!imported.kind.equals(PhotoCatalog.Pin.RECOVERY)||!previous.snapshotFile.equals("restore-"+previous.token+".snote")||!imported.snapshotFile.equals("prepared-"+previous.token+".snote"))throw new IOException("Restore pair shape");previous.validate();imported.validate();validateSnapshot(previous.snapshotFile);validateSnapshot(imported.snapshotFile);List<PhotoCatalog.Pin> pins=pins();for(PhotoCatalog.Pin p:pins)if(p.token.equals(previous.token)||p.token.equals(imported.token)||p.snapshotFile.equals(previous.snapshotFile)||p.snapshotFile.equals(imported.snapshotFile))throw new IOException("Restore pair collision");pins.add(previous.copy());pins.add(imported.copy());writePins(pins);}}
 void releaseRestorePair(String operation)throws IOException{synchronized(lock){ready();PhotoCatalog.uuid(operation);List<PhotoCatalog.Pin> pins=pins();String importedToken=null;for(PhotoCatalog.Pin p:pins)if(p.kind.equals(PhotoCatalog.Pin.RECOVERY)&&p.snapshotFile.equals("prepared-"+operation+".snote")){if(importedToken!=null)throw new IOException("Duplicate restore association");importedToken=p.token;}if(importedToken==null)throw new IOException("Missing imported pin");final String associated=importedToken;int matches=0;for(PhotoCatalog.Pin p:pins)if(p.token.equals(operation)||p.token.equals(importedToken)){if(!p.kind.equals(PhotoCatalog.Pin.RECOVERY)||!(p.token.equals(operation)?p.snapshotFile.equals("restore-"+operation+".snote"):p.snapshotFile.equals("prepared-"+operation+".snote")))throw new IOException("Restore pair release mismatch");matches++;}if(matches!=2)throw new IOException("Missing restore pin pair");pins.removeIf(p->p.token.equals(operation)||p.token.equals(associated));writePins(pins);}}
 public void release(String token)throws IOException{synchronized(lock){ready();PhotoCatalog.uuid(token);List<PhotoCatalog.Pin> pins=pins();boolean changed=pins.removeIf(p->p.token.equals(token));if(changed)writePins(pins);}}
 private void writePins(List<PhotoCatalog.Pin> pins)throws IOException{
  if(pins.size()>PhotoLimits.MAX_PHOTO_ASSETS)throw new IOException("pin count");long bytes=8;for(PhotoCatalog.Pin p:pins){p.validate();validateSnapshot(p.snapshotFile);bytes+=20L+p.token.getBytes("UTF-8").length+p.kind.getBytes("UTF-8").length+p.generation.getBytes("UTF-8").length+p.snapshotFile.getBytes("UTF-8").length+68L*p.photoVersionIds.size();if(bytes>PhotoLimits.MAX_METADATA_BYTES)throw new IOException("pin bytes");}
  ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(buffer)){out.writeInt(PIN_MAGIC);out.writeInt(pins.size());for(PhotoCatalog.Pin p:pins){string(out,p.token);string(out,p.kind);string(out,p.generation);string(out,p.snapshotFile);out.writeInt(p.photoVersionIds.size());for(String h:p.photoVersionIds)string(out,h);}}
  try{pinStore.write(buffer.toByteArray());}catch(CommitUncertainException e){throw uncertain(e);}
 }
 void requireRestoreSpace(long bytes)throws IOException{synchronized(lock){ready();if(io.inspect(root)!=CheckedAtomicFile.Kind.DIRECTORY||bytes<0||bytes>root.getUsableSpace())throw new IOException("Insufficient commit restore space");}}
 void requireStrictJpeg()throws IOException{synchronized(lock){if(jpegValidator==null)throw new IOException("Strict JPEG validator required");ready();}}
 void validateBackupJpeg(String hash,PhotoCatalog.Asset asset)throws IOException{synchronized(lock){requireStrictJpeg();try{File file=mediaFile(hash);verify(file,asset);jpegValidator.validate(file,asset);}catch(CommitUncertainException e){unavailable=e;throw e;}}}
 static final class StagedArchive {final File file;final long bytes;StagedArchive(File f,long n){file=f;bytes=n;}}
 StagedArchive stageArchive(InputStream source,File staging,String token,long available)throws IOException{synchronized(lock){requireStrictJpeg();try{
  PhotoCatalog.uuid(token);File dir=restoreDirectory(staging),file=new File(dir,"archive-"+token+".zip");OutputStream out=io.createExclusive(file);long bytes=0;IOException primary=null;
  try{byte[] block=new byte[32768];for(int n;(n=source.read(block))!=-1;){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Archive interrupted");if(n==0){int one=source.read();if(one<0)break;block[0]=(byte)one;n=1;}if(n>available-bytes||n>(67108864L+8388608L+8388608L+20000L*(PhotoLimits.MAX_INPUT_BYTES+PhotoLimits.MAX_INPUT_BYTES/8L+65536L)+65557L)-bytes)throw new IOException("Archive space/size limit");bytes+=n;io.write(out,block,0,n);}io.sync(out);}catch(IOException e){primary=e;throw e;}finally{try{io.close(out);}catch(IOException e){if(e instanceof CommitUncertainException){unavailable=e;if(primary!=null&&primary!=e)e.addSuppressed(primary);throw e;}if(primary==null)throw e;primary.addSuppressed(e);}}io.syncDirectory(dir);regular(file);return new StagedArchive(file,bytes);
 }catch(CommitUncertainException e){unavailable=e;throw e;}catch(IOException failure){File owned=new File(staging,"archive-"+token+".zip");try{if(io.inspect(owned)==CheckedAtomicFile.Kind.REGULAR){io.unlink(owned);io.syncDirectory(staging);}}catch(IOException cleanup){if(cleanup instanceof CommitUncertainException){unavailable=cleanup;cleanup.addSuppressed(failure);throw cleanup;}failure.addSuppressed(cleanup);}throw failure;}}}
 InputStream openArchive(StagedArchive archive,File staging,String token)throws IOException{synchronized(lock){ready();restoreDirectory(staging);PhotoCatalog.uuid(token);if(!new File(staging,"archive-"+token+".zip").equals(archive.file))throw new IOException("Archive path");regular(archive.file);return io.openRead(archive.file);}}
 void discardArchive(StagedArchive archive,File staging,String token)throws IOException{synchronized(lock){ready();try{restoreDirectory(staging);PhotoCatalog.uuid(token);if(!new File(staging,"archive-"+token+".zip").equals(archive.file))throw new IOException("Archive path");regular(archive.file);io.unlink(archive.file);io.syncDirectory(staging);}catch(CommitUncertainException e){unavailable=e;throw e;}}}
 /** Checked Task11 operation metadata; filenames are fixed owner-relative UUID records. */
 private File operationFile(String relative)throws IOException {
  if(relative==null||!relative.matches("(?:backup|prepared|restore)-[0-9a-fA-F-]{36}\\.snote"))throw new IOException("Operation snapshot path");
  PhotoCatalog.uuid(relative.substring(relative.indexOf('-')+1,relative.length()-6));return new File(root,relative);
 }
 void writeOperation(String relative,byte[] bytes)throws IOException{synchronized(lock){ready();try{StateCodec.decode(bytes);CheckedAtomicFile file=new CheckedAtomicFile(operationFile(relative),io);try{file.readFully(PhotoLimits.MAX_METADATA_BYTES,StateCodec::decode);}catch(CheckedAtomicFile.MissingFileException absent){}file.write(bytes);}catch(CommitUncertainException e){unavailable=e;throw e;}}}
 byte[] readOperation(String relative)throws IOException{synchronized(lock){ready();try{return new CheckedAtomicFile(operationFile(relative),io).readFully(PhotoLimits.MAX_METADATA_BYTES,StateCodec::decode);}catch(CommitUncertainException e){unavailable=e;throw e;}}}
 void deleteOperation(String relative)throws IOException{synchronized(lock){ready();try{new CheckedAtomicFile(operationFile(relative),io).delete();}catch(CommitUncertainException e){unavailable=e;throw e;}}}
 private File restoreDirectory(File staging)throws IOException {
  if(staging==null||!staging.isAbsolute()||!staging.equals(new File(root,"restore-staging")))throw new IOException("Owned restore staging required");
  CheckedAtomicFile.directory(staging,io);return staging;
 }
 File stageRestore(InputStream source,File staging,String token,PhotoCatalog.Asset asset)throws IOException{synchronized(lock){ready();try{
  PhotoCatalog.uuid(token);PhotoCatalog.hash(asset.hash);File directory=restoreDirectory(staging),target=new File(directory,token+"-"+asset.hash+".jpg");
  OutputStream output=io.createExclusive(target);IOException primary=null;long count=0;MessageDigest digest=digest();
  try{byte[] block=new byte[32768];for(int n;(n=source.read(block))!=-1;){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Restore interrupted");if(n==0){int one=source.read();if(one<0)break;block[0]=(byte)one;n=1;}if(n>asset.byteLength-count)throw new IOException("Restore media length");count+=n;digest.update(block,0,n);io.write(output,block,0,n);}io.sync(output);}catch(IOException e){primary=e;throw e;}finally{try{io.close(output);}catch(IOException close){if(close instanceof CommitUncertainException){unavailable=close;if(primary!=null&&primary!=close)close.addSuppressed(primary);throw close;}if(primary!=null)primary.addSuppressed(close);else throw close;}}
  if(count!=asset.byteLength||!hex(digest.digest()).equals(asset.hash))throw new IOException("Restore media hash/length");int[] dims=checkedDimensions(target);if(dims[0]!=asset.width||dims[1]!=asset.height)throw new IOException("Restore media dimensions");requireStrictJpeg();jpegValidator.validate(target,asset);io.syncDirectory(directory);return target;
 }catch(CommitUncertainException e){unavailable=e;throw e;}}}
 InputStream openRestore(File file,File staging,String token,String hash)throws IOException{synchronized(lock){ready();PhotoCatalog.uuid(token);PhotoCatalog.hash(hash);restoreDirectory(staging);File expected=new File(staging,token+"-"+hash+".jpg");if(!expected.equals(file))throw new IOException("Restore prepared path");regular(file);return io.openRead(file);}}
 void discardRestore(File file,File staging,String token,String hash)throws IOException{synchronized(lock){ready();try{PhotoCatalog.uuid(token);PhotoCatalog.hash(hash);restoreDirectory(staging);if(!new File(staging,token+"-"+hash+".jpg").equals(file))throw new IOException("Restore cleanup path");CheckedAtomicFile.Kind kind=io.inspect(file);if(kind==CheckedAtomicFile.Kind.REGULAR)io.unlink(file);else if(kind!=CheckedAtomicFile.Kind.MISSING)throw new IOException("Restore cleanup type");io.syncDirectory(staging);}catch(CommitUncertainException e){unavailable=e;throw e;}}}
 /** Called on owner load before any GC. Retained journals remain protected across restart. */
 void recoverRestoreProtection()throws IOException{synchronized(lock){ready();try{
  List<PhotoCatalog.Pin> all=pins();Set<String> matched=new HashSet<>();
  for(PhotoCatalog.Pin pin:all)if(pin.kind.equals(PhotoCatalog.Pin.BACKUP)&&pin.snapshotFile.startsWith("backup-")){StateCodec.State frozen=StateCodec.decode(readOperation(pin.snapshotFile));if(!pin.generation.equals(frozen.generation)||!new LinkedHashSet<>(pin.photoVersionIds).equals(IntegratedBackup.liveVersions(frozen)))throw new IOException("Backup recovery mismatch");for(String hash:pin.photoVersionIds){verify(mediaFile(hash),frozen.catalog.assets.get(hash));io.syncFile(mediaFile(hash));}if(!pin.photoVersionIds.isEmpty())io.syncDirectory(media);}
  for(PhotoCatalog.Pin old:all)if(old.kind.equals(PhotoCatalog.Pin.RECOVERY)&&old.snapshotFile.startsWith("restore-")){
   if(!old.snapshotFile.equals("restore-"+old.token+".snote"))throw new IOException("Recovery operation identity");StateCodec.State previous=StateCodec.decode(readOperation(old.snapshotFile));if(!previous.generation.equals(old.generation)||!new LinkedHashSet<>(old.photoVersionIds).equals(IntegratedBackup.liveVersions(previous)))throw new IOException("Restore previous protection mismatch");
   PhotoCatalog.Pin next=null;for(PhotoCatalog.Pin candidate:all)if(candidate.kind.equals(PhotoCatalog.Pin.RECOVERY)&&candidate.snapshotFile.equals("prepared-"+old.token+".snote")){if(next!=null)throw new IOException("Duplicate recovery association");next=candidate;}if(next==null||next.token.equals(old.token))throw new IOException("Incomplete restore pair");StateCodec.State imported=StateCodec.decode(readOperation(next.snapshotFile));if(!next.generation.equals(old.generation)||!next.photoVersionIds.containsAll(IntegratedBackup.liveVersions(imported))||!imported.catalog.assets.keySet().containsAll(next.photoVersionIds))throw new IOException("Restore imported protection mismatch");matched.add(old.token);matched.add(next.token);
   for(String hash:old.photoVersionIds){File file=mediaFile(hash);verify(file,previous.catalog.assets.get(hash));io.syncFile(file);}if(!old.photoVersionIds.isEmpty())io.syncDirectory(media);
   for(String hash:next.photoVersionIds){PhotoCatalog.Asset asset=imported.catalog.assets.get(hash);File staged=new File(new File(root,"restore-staging"),old.token+"-"+hash+".jpg"),immutable=mediaFile(hash);CheckedAtomicFile.Kind kind=io.inspect(immutable);boolean immutablePresent=kind==CheckedAtomicFile.Kind.REGULAR;if(kind==CheckedAtomicFile.Kind.REGULAR){verify(immutable,asset);requireStrictJpeg();jpegValidator.validate(immutable,asset);io.syncFile(immutable);io.syncDirectory(media);}else if(kind!=CheckedAtomicFile.Kind.MISSING)throw new IOException("Recovery immutable type");kind=io.inspect(staged);if(kind==CheckedAtomicFile.Kind.REGULAR){verify(staged,asset);requireStrictJpeg();jpegValidator.validate(staged,asset);io.syncFile(staged);io.syncDirectory(staged.getParentFile());}else if(kind!=CheckedAtomicFile.Kind.MISSING)throw new IOException("Recovery staged type");else if(!immutablePresent)throw new IOException("Missing active prepared media");}
  }
  for(PhotoCatalog.Pin pin:all)if(pin.kind.equals(PhotoCatalog.Pin.RECOVERY)&&pin.snapshotFile.startsWith("prepared-")&&!matched.contains(pin.token))throw new IOException("Orphan imported recovery pin");
 }catch(CommitUncertainException e){unavailable=e;throw e;}}}
 private void regular(File file)throws IOException{if(io.inspect(file)!=CheckedAtomicFile.Kind.REGULAR)throw new IOException("Regular media required");}
 private void verify(File file,PhotoCatalog.Asset asset)throws IOException{regular(file);MessageDigest d=digest();long count=0;try(InputStream in=io.openRead(file)){byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;){count+=n;if(count>asset.byteLength)throw new IOException("existing media length");d.update(b,0,n);}}catch(IOException failure){throw promoteReadFailure(failure);}if(count!=asset.byteLength||!hex(d.digest()).equals(asset.hash))throw new IOException("existing immutable media corrupt");int[] dim=checkedDimensions(file);if(dim[0]!=asset.width||dim[1]!=asset.height)throw new IOException("existing dimensions");}
 private IOException promoteReadFailure(IOException failure){if(failure instanceof CommitUncertainException){unavailable=failure;return failure;}for(Throwable suppressed:failure.getSuppressed())if(suppressed instanceof IOException){IOException promoted=promoteReadFailure((IOException)suppressed);if(promoted instanceof CommitUncertainException)return promoted;}return failure;}
 private File mediaFile(String hash)throws IOException{PhotoCatalog.hash(hash);File f=new File(media,hash+".jpg");if(!f.getCanonicalFile().getParentFile().equals(media.getCanonicalFile()))throw new IOException("media path");return f;}
 private void validateSnapshot(String relative)throws IOException{if(relative.isEmpty())return;File f=new File(relative);if(f.isAbsolute()||Arrays.asList(relative.replace('\\','/').split("/")).contains(".."))throw new IOException("snapshot path");String base=root.getCanonicalPath()+File.separator;if(!new File(root,relative).getCanonicalPath().startsWith(base))throw new IOException("snapshot path");}
 static void directory(File dir)throws IOException{if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("directory unavailable: "+dir.getName());}
 static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
 static String hash(File file)throws IOException{MessageDigest d=digest();try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;)d.update(b,0,n);}return hex(d.digest());}
 static String hex(byte[] bytes){char[] chars="0123456789abcdef".toCharArray(),out=new char[bytes.length*2];for(int i=0;i<bytes.length;i++){out[i*2]=chars[(bytes[i]&255)>>>4];out[i*2+1]=chars[bytes[i]&15];}return new String(out);}
 private static String string(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max*4)throw new IOException("pin string bound");byte[] b=new byte[n];in.readFully(b);String s=new String(b,"UTF-8");if(s.length()>max||!Arrays.equals(b,s.getBytes("UTF-8")))throw new IOException("pin string");return s;}
 private static void string(DataOutputStream out,String value)throws IOException{byte[] b=value.getBytes("UTF-8");out.writeInt(b.length);out.write(b);}
 private int[] checkedDimensions(File file)throws IOException{regular(file);try(InputStream in=io.openRead(file)){return jpegDimensions(in,false);}catch(IOException failure){throw promoteReadFailure(failure);}}
 /** Small JPEG structural walk, including entropy framing/EOI. Pixel decoding remains Android's job. */
 static int[] jpegDimensions(File file,boolean allowTail)throws IOException{try(InputStream in=new FileInputStream(file)){return jpegDimensions(in,allowTail);}}
 static final class JpegFormatException extends IOException {JpegFormatException(String message){super(message);}}
 private static int requiredByte(InputStream in)throws IOException{int value=in.read();if(value<0)throw new JpegFormatException("JPEG truncated structure");return value;}
 private static int requiredShort(InputStream in)throws IOException{return (requiredByte(in)<<8)|requiredByte(in);}
 static int[] jpegDimensions(InputStream stream,boolean allowTail)throws IOException{
  DataInputStream in=new DataInputStream(new BufferedInputStream(stream));if(requiredShort(in)!=0xffd8)throw new JpegFormatException("JPEG header");int width=0,height=0;boolean scan=false,entropy=false;
  while(true){int marker;if(entropy){int v;do{v=requiredByte(in);}while(v!=0xff);do{marker=requiredByte(in);}while(marker==0xff);if(marker==0||marker>=0xd0&&marker<=0xd7)continue;entropy=false;}else{if(requiredByte(in)!=0xff)throw new JpegFormatException("JPEG marker");do{marker=requiredByte(in);}while(marker==0xff);}
   if(marker==0xd9){if(!scan||width<=0||height<=0||(!allowTail&&in.read()!=-1))throw new JpegFormatException("JPEG end");return new int[]{width,height};}
   if(marker==0xd8||marker==0||marker>=0xd0&&marker<=0xd7)throw new JpegFormatException("JPEG marker order");if(marker==1)continue;int remaining=requiredShort(in)-2;if(remaining<0)throw new JpegFormatException("JPEG segment bounds");
   if(marker>=0xc0&&marker<=0xcf&&marker!=0xc4&&marker!=0xc8&&marker!=0xcc){if(remaining<6||width!=0)throw new JpegFormatException("JPEG frame");requiredByte(in);height=requiredShort(in);width=requiredShort(in);remaining-=5;}
   if(marker==0xda){scan=true;entropy=true;}while(remaining>0){int skip=in.skipBytes(remaining);if(skip==0){requiredByte(in);skip=1;}remaining-=skip;}
  }
 }
}
