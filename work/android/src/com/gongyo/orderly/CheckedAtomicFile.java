package com.gongyo.orderly;
import java.io.*;
import java.util.*;
/** Checked same-directory metadata publication. FileOps must report failed native operations. */
public final class CheckedAtomicFile {
 public enum Kind {MISSING,REGULAR,DIRECTORY,OTHER}
 public interface FileOps {
  File directoryAnchor(File directory)throws IOException;
  Kind inspect(File file)throws IOException;
  InputStream openRead(File file)throws IOException;
  OutputStream createExclusive(File file)throws IOException;
  void write(OutputStream stream,byte[] bytes,int offset,int length)throws IOException;
  void sync(OutputStream stream)throws IOException;
  void close(Closeable stream)throws IOException;
  void syncFile(File file)throws IOException;
  void rename(File source,File target)throws IOException;
  void unlink(File file)throws IOException;
  void syncDirectory(File directory)throws IOException;
  void mkdir(File directory)throws IOException;
  File[] list(File directory)throws IOException;
 }
 public interface Validator {void validate(byte[] bytes)throws IOException;}
 public static final class MissingFileException extends FileNotFoundException {public MissingFileException(File file){super(file.toString());}}
 private static final Map<String,Object> LOCKS=new HashMap<>();
 private final File base,fresh,rollback,legacy,parent;
 private final FileOps io;
 private final Object lock;
 private Validator validator;private int limit;private IOException unavailable,deferredCleanup;
 public CheckedAtomicFile(File base,FileOps io){this.base=Objects.requireNonNull(base).getAbsoluteFile();this.io=Objects.requireNonNull(io);parent=this.base.getParentFile();fresh=new File(this.base+".new");rollback=new File(this.base+".rollback");legacy=new File(this.base+".bak");try{String path=this.base.getCanonicalPath();synchronized(LOCKS){lock=LOCKS.computeIfAbsent(path,k->new Object());}}catch(IOException e){throw new IllegalArgumentException("Invalid storage path",e);}}
 public byte[] readFully(int limit,Validator validator)throws IOException {synchronized(lock){ready();if(limit<0)throw new IllegalArgumentException("limit");this.limit=limit;this.validator=Objects.requireNonNull(validator);return load();}}
 private byte[] load()throws IOException {
  directory(parent,io);Kind b=regular(base),bak=regular(legacy),r=regular(rollback),n=regular(fresh);
  File authority=bak==Kind.REGULAR?legacy:b==Kind.REGULAR?base:r==Kind.REGULAR?rollback:null;
  if(authority==null){if(n==Kind.REGULAR)throw new IOException("Unresolved staging file: "+fresh);io.syncDirectory(parent);throw new MissingFileException(base);}
  byte[] bytes=bounded(authority);validator.validate(bytes);
  if(!authority.equals(base)){
   // Authority remains intact throughout recovery. Obsolete staging is not promoted.
   remove(fresh);copy(authority,fresh);try{io.rename(fresh,base);io.syncDirectory(parent);}catch(IOException failure){throw fence(failure,null);}
  }
  // A decode alone cannot renew readiness after an uncertain prior session.
  io.syncFile(base);io.syncDirectory(parent);
  cleanupArtifacts();return bytes;
 }
 public void write(byte[] bytes)throws IOException {synchronized(lock){ready();if(validator==null)throw new IOException("Validated load required before write");if(bytes.length>limit)throw new IOException("Metadata limit");validator.validate(bytes);
  boolean existed;try{load();existed=true;}catch(MissingFileException missing){existed=false;}
  boolean promoted=false;
  try{
   writeCandidate(bytes);if(existed){copy(base,rollback);io.syncDirectory(parent);}
   try{io.rename(fresh,base);promoted=true;}catch(IOException renameFailure){
    // Contract: a checked rename error leaves source intact. Any ambiguous decorator/OS outcome fences.
    try{if(regular(fresh)!=Kind.REGULAR)throw fence(renameFailure,null);}catch(CommitUncertainException uncertain){throw uncertain;}catch(IOException inspection){throw fence(renameFailure,inspection);}throw renameFailure;
   }
   io.syncDirectory(parent);
  }catch(IOException error){if(promoted){try{if(existed){remove(fresh);copy(rollback,fresh);io.rename(fresh,base);io.syncDirectory(parent);}else{remove(base);io.syncDirectory(parent);}}catch(IOException restore){throw fence(error,restore);}}
   if(error instanceof CommitUncertainException){unavailable=error;throw error;}
   try{remove(fresh);if(!promoted)remove(rollback);io.syncDirectory(parent);}catch(IOException cleanup){error.addSuppressed(cleanup);}throw error;
  }
  // Already committed: a cleanup problem must not change the outcome into failed publication.
  try{remove(rollback);io.syncDirectory(parent);deferredCleanup=null;}catch(IOException cleanup){deferredCleanup=cleanup;}
 }}
 public void delete()throws IOException {synchronized(lock){ready();directory(parent,io);regular(base);regular(legacy);regular(rollback);regular(fresh);
  // Remove all possible resurrection sources before authoritative pending data.
  remove(fresh);remove(rollback);remove(legacy);io.syncDirectory(parent);
  try{remove(base);io.syncDirectory(parent);}catch(IOException failure){throw fence(failure,null);}
 }}
 private void writeCandidate(byte[] bytes)throws IOException {OutputStream out=io.createExclusive(fresh);IOException failure=null;try{io.write(out,bytes,0,bytes.length);io.sync(out);}catch(IOException e){failure=e;throw e;}finally{close(out,failure);}}
 private void copy(File source,File target)throws IOException {OutputStream out=io.createExclusive(target);IOException failure=null;try(InputStream in=io.openRead(source)){byte[] block=new byte[32768];int total=0;for(int n;(n=in.read(block))!=-1;){if(n>limit-total)throw new IOException("Metadata copy limit");total+=n;io.write(out,block,0,n);}io.sync(out);}catch(IOException e){failure=e;throw e;}finally{close(out,failure);}}
 private byte[] bounded(File source)throws IOException {try(InputStream in=io.openRead(source);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] block=new byte[8192];for(int n;(n=in.read(block))!=-1;){if(n>limit-out.size())throw new IOException("Metadata limit");out.write(block,0,n);}return out.toByteArray();}}
 private void close(Closeable stream,IOException primary)throws IOException {try{io.close(stream);}catch(IOException e){if(primary==null)throw e;primary.addSuppressed(e);}}
 private Kind regular(File file)throws IOException {Kind kind=io.inspect(file);if(kind!=Kind.MISSING&&kind!=Kind.REGULAR)throw new IOException("Unexpected storage type: "+file);return kind;}
 private void remove(File file)throws IOException {if(regular(file)==Kind.REGULAR)io.unlink(file);}
 private void cleanupArtifacts()throws IOException {try{remove(fresh);remove(rollback);remove(legacy);io.syncDirectory(parent);deferredCleanup=null;}catch(IOException cleanup){if(deferredCleanup!=null)cleanup.addSuppressed(deferredCleanup);throw cleanup;}}
 private IOException fence(IOException primary,IOException restore){CommitUncertainException e=new CommitUncertainException("Storage publication uncertain",primary,restore);unavailable=e;return e;}
 private void ready()throws IOException {if(unavailable!=null)throw unavailable;}
 /** Re-establish every descendant entry from the fixed trusted anchor, including on reopen. */
 public static void directory(File dir,FileOps io)throws IOException {
  if(dir==null||!dir.isAbsolute())throw new IOException("Absolute directory required");
  for(String part:dir.getPath().split("/"))if(part.equals(".")||part.equals(".."))throw new IOException("Dot directory component");
  File anchor=io.directoryAnchor(dir);if(anchor==null||!anchor.isAbsolute())throw new IOException("Established directory anchor required");
  for(String part:anchor.getPath().split("/"))if(part.equals(".")||part.equals(".."))throw new IOException("Dot anchor component");
  String root=anchor.getPath(),target=dir.getPath();if(root.equals(File.separator)||(!target.equals(root)&&!target.startsWith(root+File.separator)))throw new IOException("Invalid directory anchor relation");
  String suffix=target.equals(root)?"":target.substring(root.length()+1);List<File> children=new ArrayList<>();File parent=anchor;
  if(!suffix.isEmpty())for(String component:suffix.split("/")){if(component.isEmpty()||component.equals(".")||component.equals(".."))throw new IOException("Invalid directory suffix");parent=new File(parent,component);children.add(parent);}
  // The full finite relation is fixed before any mkdir or barrier.
  if(io.inspect(anchor)!=Kind.DIRECTORY)throw new IOException("Established directory anchor unavailable");io.syncDirectory(anchor);parent=anchor;
  for(File child:children){Kind kind=io.inspect(child);if(kind==Kind.MISSING){io.mkdir(child);if(io.inspect(child)!=Kind.DIRECTORY)throw new IOException("Created directory changed type");}else if(kind!=Kind.DIRECTORY)throw new IOException("Unexpected directory type: "+child);
   io.syncDirectory(parent);io.syncDirectory(child);parent=child;
  }
 }
}
