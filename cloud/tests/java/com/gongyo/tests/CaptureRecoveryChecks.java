package com.gongyo.tests;
import com.gongyo.photo.*;
import com.gongyo.orderly.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class CaptureRecoveryChecks {
 private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 private interface Attempt{void run()throws Exception;}
 private static void rejected(Attempt value,String message)throws Exception{try{value.run();throw new AssertionError(message);}catch(IOException expected){}}
 private static final String HASH=String.join("",Collections.nCopies(64,"a"));
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
 public static void main(String[] args)throws Exception{
  Disk io=new Disk();try{
   File directory=new File(io.root,"journals");CaptureRecovery first=new CaptureRecovery(directory,io,"intake");
   CaptureSession session=first.begin("generation-a","work-a");long capture=session.claim("QA100",1,true);first.reserve(session,capture);
   CaptureRecovery restart=new CaptureRecovery(directory,io,"intake");
   check(restart.session().generation.equals("generation-a")&&restart.session().targetWorkId.equals("work-a"),"recovery must freeze original work");
   check(restart.session().claim("QA100",100,true)==0,"interrupted reservation must block automatic retry");
   restart.reset(restart.session());capture=restart.session().claim("QA100",200,true);restart.reserve(restart.session(),capture);restart.saved(restart.session(),capture,"QA100");restart.session().release(capture);
   restart=new CaptureRecovery(directory,io,"intake");check(restart.session().claim("QA100",10000,false)==0,"durably saved label stays suppressed");
   restart.enqueue("generation-a","work-a",Arrays.asList("content://synthetic/first","content://synthetic/second"),new HashSet<>(Arrays.asList("content://synthetic/first")));
   CaptureRecovery.Batch before=restart.batch();final String id=before.items.get(0).id;long owner=restart.claimQueue();check(owner!=0&&restart.claimQueue()==0,"only one queue owner");
   final CaptureRecovery owned=restart;final long token=owner;
   rejected(()->owned.complete(token,id),"cannot discard unsaved photo");
   io.failCreate=true;rejected(()->owned.sourceHash(token,id,HASH),"failed journal write must fail operation");io.failCreate=false;
   check(restart.batch().items.get(0).hash.isEmpty(),"failed write must preserve in-memory pending item");
   restart.sourceHash(owner,id,HASH);String entry=UUID.randomUUID().toString();restart.saved(owner,id,entry);restart.releaseQueue(owner);
   restart=new CaptureRecovery(directory,io,"intake");
   check(restart.batch().items.size()==2&&restart.batch().items.get(0).id.equals(id)&&restart.batch().items.get(0).entryId.equals(entry)&&restart.batch().items.get(0).hash.equals(HASH),"restart preserves source and durable request identity");
   check(restart.batch().items.get(0).releaseGrant&&!restart.batch().items.get(1).releaseGrant,"grant ownership survives restart");
   check(restart.batch().targetWorkId.equals("work-a"),"queue never retargets to current work");
   owner=restart.claimQueue();restart.releaseQueue(owner+1);check(restart.claimQueue()==0,"foreign completion cannot unlock queue");restart.complete(owner,id);restart.releaseQueue(owner);
   check(new CaptureRecovery(directory,io,"intake").batch().items.get(0).uri.endsWith("second"),"only terminal saved head advances");
   restart.discardRemaining();check(new CaptureRecovery(directory,io,"intake").batch()==null,"explicit cancellation clears only queue");
   List<String> oversized=new ArrayList<>();String wide=String.join("",Collections.nCopies(4000,"한"));for(int i=0;i<20;i++)oversized.add("content://synthetic/"+i+wide);
   final CaptureRecovery bounded=restart;rejected(()->bounded.enqueue("generation-a","work-a",oversized),"serialized queue must fit reload bound");check(restart.batch()==null,"oversized publication must preserve previous queue");
   restart.enqueue("generation-a","work-a",Arrays.asList("content://synthetic/uncertain"));owner=restart.claimQueue();
   final long uncertainOwner=owner;final String uncertainId=restart.batch().items.get(0).id;io.uncertainRename=true;
   rejected(()->bounded.sourceHash(uncertainOwner,uncertainId,HASH),"uncertain mutation must throw");
   rejected(()->bounded.claimQueue(),"uncertain journal must fence claim");rejected(()->bounded.batch(),"uncertain journal must fence reads");rejected(()->bounded.reset(bounded.session()),"uncertain queue must fence camera mutations too");
   CaptureRecovery recovered=new CaptureRecovery(directory,io,"intake");check(recovered.batch().items.get(0).id.equals(uncertainId),"new process recovery retains stable identity");
   System.out.println("CaptureRecoveryChecks passed (synthetic restart, frozen identity, durable queue, faults, grants and bounds)");
  }finally{io.clean();}
 }
}
