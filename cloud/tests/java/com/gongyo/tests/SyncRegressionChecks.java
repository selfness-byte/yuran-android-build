package com.gongyo.tests;
import com.gongyo.photo.*;
import com.gongyo.orderly.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class SyncRegressionChecks {
 private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 private interface Attempt{void run()throws Exception;}
 private static void rejected(Attempt value,String message)throws Exception{try{value.run();throw new AssertionError(message);}catch(IOException expected){}}
 private static final class Disk implements CheckedAtomicFile.FileOps {
  final File root;boolean failCreate,uncertainRename;
  Disk()throws IOException{root=Files.createTempDirectory("yuran-synthetic-recovery-").toFile();}
  public File directoryAnchor(File d){return root;}
  public CheckedAtomicFile.Kind inspect(File f){return !f.exists()?CheckedAtomicFile.Kind.MISSING:f.isDirectory()?CheckedAtomicFile.Kind.DIRECTORY:f.isFile()?CheckedAtomicFile.Kind.REGULAR:CheckedAtomicFile.Kind.OTHER;}
  public InputStream openRead(File f)throws IOException{return new FileInputStream(f);}
  public OutputStream createExclusive(File f)throws IOException{if(failCreate)throw new IOException("synthetic create failure");if(!f.createNewFile())throw new IOException("already exists");return new FileOutputStream(f);}
  public void write(OutputStream out,byte[] b,int off,int n)throws IOException{out.write(b,off,n);}
  public void sync(OutputStream out)throws IOException{out.flush();((FileOutputStream)out).getFD().sync();}
  public void close(Closeable c)throws IOException{c.close();}
  public void syncFile(File f)throws IOException{try(RandomAccessFile file=new RandomAccessFile(f,"rw")){file.getFD().sync();}}
  public void rename(File from,File to)throws IOException{Files.move(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING);if(uncertainRename){uncertainRename=false;throw new IOException("synthetic uncertain rename after mutation");}}
  public void unlink(File f)throws IOException{Files.delete(f.toPath());}
  public void syncDirectory(File f){}
  public void mkdir(File f)throws IOException{Files.createDirectory(f.toPath());}
  public File[] list(File f)throws IOException{File[] values=f.listFiles();if(values==null)throw new IOException("list failed");return values;}
  void clean()throws IOException{try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}}
 }

 private static void cancellation(boolean uncertain)throws Exception{
  Disk io=new Disk();try{
   File directory=new File(io.root,"journals");CaptureRecovery recovery=new CaptureRecovery(directory,io,"intake");
   String owned="content://synthetic/owned",borrowed="content://synthetic/borrowed";
   recovery.enqueue("generation","work",Arrays.asList(owned,borrowed),Collections.singleton(owned));
   List<String> released=new ArrayList<>();
   if(uncertain)io.uncertainRename=true;else io.failCreate=true;
   rejected(()->recovery.discardRemaining(released::add),"failed discard must throw");
   check(released.isEmpty(),"failed or uncertain discard must release zero source grants");
   io.failCreate=false;
   CaptureRecovery restarted=new CaptureRecovery(directory,io,"intake");
   if(!uncertain)check(restarted.batch()!=null&&restarted.batch().items.size()==2,"failed discard preserves readable pending sources");
  }finally{io.clean();}
 }
 private static void successfulCancellation()throws Exception{
  Disk io=new Disk();try{
   File directory=new File(io.root,"journals");CaptureRecovery recovery=new CaptureRecovery(directory,io,"intake");
   String owned="content://synthetic/owned",borrowed="content://synthetic/borrowed";
   recovery.enqueue("generation","work",Arrays.asList(owned,borrowed),Collections.singleton(owned));
   List<String> released=new ArrayList<>();
   recovery.discardRemaining(uri->{try{check(new CaptureRecovery(directory,io,"intake").batch()==null,"discard must be durable before revocation");}catch(IOException e){throw new AssertionError(e);}released.add(uri);});
   check(released.equals(Collections.singletonList(owned)),"release only grants acquired for this batch");
  }finally{io.clean();}
 }
 public static void main(String[] args)throws Exception{
  cancellation(false);cancellation(true);successfulCancellation();
  System.out.println("SyncRegressionChecks passed (actual durable queue with failed, uncertain and successful discard)");
 }
}
