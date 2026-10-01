package com.gongyo.orderly;
import android.os.Build;
import android.system.*;
import java.io.*;
import java.util.*;
/** Checked public Android syscalls confined to resolved owner-private anchors. */
public final class AndroidFileOps implements CheckedAtomicFile.FileOps {
 private final File[] original,resolved;private final long[] anchorDevices,anchorInodes;
 public AndroidFileOps(File... ownerAnchors){if(ownerAnchors.length==0)throw new IllegalArgumentException("Owner-private anchor required");original=new File[ownerAnchors.length];resolved=new File[ownerAnchors.length];anchorDevices=new long[ownerAnchors.length];anchorInodes=new long[ownerAnchors.length];try{for(int i=0;i<ownerAnchors.length;i++){original[i]=lexical(Objects.requireNonNull(ownerAnchors[i]));if(original[i].getPath().equals(File.separator))throw new IOException("Filesystem root is not an owner anchor");resolved[i]=original[i].getCanonicalFile();StructStat trusted=Os.lstat(resolved[i].getPath());if(!OsConstants.S_ISDIR(trusted.st_mode))throw new IOException("Owner anchor must be a directory");anchorDevices[i]=trusted.st_dev;anchorInodes[i]=trusted.st_ino;}}catch(IOException|ErrnoException e){throw new IllegalArgumentException("Invalid owner anchor",e);}}
 public CheckedAtomicFile.Kind inspect(File file)throws IOException {File f=path(file);try{int mode=Os.lstat(f.getPath()).st_mode;return OsConstants.S_ISREG(mode)?CheckedAtomicFile.Kind.REGULAR:OsConstants.S_ISDIR(mode)?CheckedAtomicFile.Kind.DIRECTORY:CheckedAtomicFile.Kind.OTHER;}catch(ErrnoException e){if(e.errno==OsConstants.ENOENT)return CheckedAtomicFile.Kind.MISSING;throw error(e);}}
 public InputStream openRead(File file)throws IOException {File f=path(file);StructStat before=regular(f);FileDescriptor fd=open(f,OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0);try{identity(before,Os.fstat(fd),false);return new OwnedInput(fd);}catch(IOException|ErrnoException e){IOException failure=e instanceof IOException?(IOException)e:error((ErrnoException)e);closeDescriptor(fd,failure);throw failure;}}
 public OutputStream createExclusive(File file)throws IOException {File f=path(file);FileDescriptor fd=open(f,OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0600);try{if(!OsConstants.S_ISREG(Os.fstat(fd).st_mode))throw new IOException("Regular output required");return new OwnedOutput(fd);}catch(IOException|ErrnoException e){IOException failure=e instanceof IOException?(IOException)e:error((ErrnoException)e);closeDescriptor(fd,failure);throw failure;}}
 public void write(OutputStream out,byte[] bytes,int offset,int count)throws IOException {out.write(bytes,offset,count);}
 public void sync(OutputStream out)throws IOException {if(!(out instanceof FileOutputStream))throw new IOException("File descriptor required");try{Os.fsync(((FileOutputStream)out).getFD());}catch(ErrnoException e){throw error(e);}}
 public void close(Closeable stream)throws IOException {stream.close();}
 public void syncFile(File file)throws IOException {File f=path(file);barrier(f,regular(f),false);}
 public void syncDirectory(File file)throws IOException {File f=path(file);StructStat before=stat(f);if(!OsConstants.S_ISDIR(before.st_mode))throw new IOException("Directory required: "+f);barrier(f,before,true);}
 private void barrier(File f,StructStat before,boolean directory)throws IOException {FileDescriptor fd=open(f,OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0);IOException primary=null;try{identity(before,Os.fstat(fd),directory);Os.fsync(fd);identity(before,Os.lstat(f.getPath()),directory);}catch(IOException|ErrnoException e){primary=e instanceof IOException?(IOException)e:error((ErrnoException)e);throw primary;}finally{closeDescriptor(fd,primary);}}
 public void rename(File source,File target)throws IOException {File from=path(source),to=path(target);if(!from.getParentFile().equals(to.getParentFile()))throw new IOException("Same-directory rename required");regular(from);CheckedAtomicFile.Kind kind=inspect(to);if(kind==CheckedAtomicFile.Kind.OTHER)throw new IOException("Unexpected rename destination");try{Os.rename(from.getPath(),to.getPath());}catch(ErrnoException e){throw error(e);}}
 public void unlink(File file)throws IOException {File f=path(file);regular(f);try{Os.remove(f.getPath());}catch(ErrnoException e){throw error(e);}}
 public void mkdir(File file)throws IOException {File f=path(file);try{Os.mkdir(f.getPath(),0700);}catch(ErrnoException e){throw error(e);}}
 public File[] list(File file)throws IOException {File directory=path(file);if(inspect(directory)!=CheckedAtomicFile.Kind.DIRECTORY)throw new IOException("Directory required");File[] files=directory.listFiles();if(files==null)throw new IOException("Directory listing failed: "+directory);return files;}
 private FileDescriptor open(File f,int flags,int mode)throws IOException {try{return Os.open(f.getPath(),flags|(Build.VERSION.SDK_INT>=27?Api27.closeOnExec():0),mode);}catch(ErrnoException e){throw error(e);}}
 /** Isolated guarded field reference: API26 never loads this class; no subprocess/exec here. */
 private static final class Api27 {static int closeOnExec(){return OsConstants.O_CLOEXEC;}}
 public File directoryAnchor(File directory)throws IOException {File request=lexical(directory);int index=anchorIndex(request);checkAnchor(index);return relative(request.getPath(),original[index].getPath())!=null?original[index]:resolved[index];}
 private File path(File file)throws IOException {
  File request=lexical(file);int index=anchorIndex(request);checkAnchor(index);String suffix=relative(request.getPath(),original[index].getPath());if(suffix==null)suffix=relative(request.getPath(),resolved[index].getPath());File mapped=suffix.isEmpty()?resolved[index]:new File(resolved[index],suffix);File current=resolved[index];String[] parts=suffix.split("/");
  for(int p=0;p<parts.length-1;p++){current=new File(current,parts[p]);try{StructStat entry=Os.lstat(current.getPath());if(!OsConstants.S_ISDIR(entry.st_mode))throw new IOException("Unexpected private path component: "+current);}catch(ErrnoException e){if(e.errno==OsConstants.ENOENT)break;throw error(e);}}return mapped;
 }
 private int anchorIndex(File request)throws IOException {int match=-1;for(int i=0;i<original.length;i++)if(relative(request.getPath(),original[i].getPath())!=null||relative(request.getPath(),resolved[i].getPath())!=null){if(match>=0&&!resolved[match].equals(resolved[i]))throw new IOException("Ambiguous owner anchors");match=i;}if(match<0)throw new IOException("Outside owner-private storage: "+request);return match;}
 private void checkAnchor(int index)throws IOException {StructStat current=stat(resolved[index]);if(!OsConstants.S_ISDIR(current.st_mode)||current.st_dev!=anchorDevices[index]||current.st_ino!=anchorInodes[index])throw new IOException("Owner anchor identity changed");}
 private static File lexical(File file)throws IOException {if(file==null||!file.isAbsolute())throw new IOException("Absolute owner-private path required");for(String part:file.getPath().split("/"))if(part.equals(".")||part.equals(".."))throw new IOException("Dot private path component");return file;}
 private static String relative(String candidate,String anchor){if(candidate.equals(anchor))return "";return candidate.startsWith(anchor+File.separator)?candidate.substring(anchor.length()+1):null;}
 private static StructStat regular(File file)throws IOException {StructStat s=stat(file);if(!OsConstants.S_ISREG(s.st_mode))throw new IOException("Regular file required: "+file);return s;}
 private static StructStat stat(File f)throws IOException {try{return Os.lstat(f.getPath());}catch(ErrnoException e){throw error(e);}}
 private static void identity(StructStat before,StructStat after,boolean directory)throws IOException {if((directory?!OsConstants.S_ISDIR(after.st_mode):!OsConstants.S_ISREG(after.st_mode))||before.st_dev!=after.st_dev||before.st_ino!=after.st_ino)throw new IOException("Storage descriptor/path identity changed");}
 /** Public Android streams do not own a descriptor supplied by Os.open. */
 private static final class OwnedInput extends FileInputStream {
  private final FileDescriptor owned;private boolean closed;
  OwnedInput(FileDescriptor fd){super(fd);owned=fd;}
  @Override public synchronized void close()throws IOException {if(closed)return;closed=true;Throwable primary=null;try{super.close();}catch(IOException|RuntimeException|Error failure){primary=failure;throw failure;}finally{closeDescriptor(owned,primary);}}
 }
 private static final class OwnedOutput extends FileOutputStream {
  private final FileDescriptor owned;private boolean closed;
  OwnedOutput(FileDescriptor fd){super(fd);owned=fd;}
  @Override public synchronized void close()throws IOException {if(closed)return;closed=true;Throwable primary=null;try{super.close();}catch(IOException|RuntimeException|Error failure){primary=failure;throw failure;}finally{closeDescriptor(owned,primary);}}
 }
 private static void closeDescriptor(FileDescriptor fd,Throwable primary)throws IOException {try{Os.close(fd);}catch(ErrnoException e){IOException close=error(e);if(primary==null)throw close;primary.addSuppressed(close);}}
 private static IOException error(ErrnoException e){return new IOException(e.getMessage(),e);}
}
