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

 private static final String ID="11111111-1111-1111-1111-111111111111",GEN="22222222-2222-2222-2222-222222222222",PRODUCT="33333333-3333-3333-3333-333333333333",VARIANT="44444444-4444-4444-4444-444444444444";
 private static final String HASH=String.join("",Collections.nCopies(64,"a"));
 private static PhotoContract.RegisterRequest request(long revision){return new PhotoContract.RegisterRequest(ID,GEN,revision,"","","QA123","BLACK",HASH,"content://synthetic/prepared/"+ID+".input");}
 private static final class Remote implements CaptureDelivery.Transport {
  final Map<String,String> receipts=new HashMap<>();int registrations;boolean loseReply;
  public PhotoContract.Reply receipt(PhotoContract.RegisterRequest r)throws IOException{
   String old=receipts.get(r.requestId);
   if(old==null)return new PhotoContract.Reply(PhotoContract.NOT_FOUND,r.requestId,r.generation,r.revision,"","","","missing");
   check(old.equals(PhotoContract.fingerprint(r)),"restart must retain the complete original fingerprint");
   return new PhotoContract.Reply(PhotoContract.OK,r.requestId,r.generation,r.revision+1,PRODUCT,VARIANT,HASH,"");
  }
  public PhotoContract.Reply register(PhotoContract.RegisterRequest r)throws IOException{
   registrations++;receipts.put(r.requestId,PhotoContract.fingerprint(r));
   if(loseReply){loseReply=false;throw new IOException("synthetic lost remote ACK");}
   return receipt(r);
  }
 }
 private static void deliveryRestart()throws Exception{
  Disk io=new Disk();try{
   File directory=new File(io.root,"journals");CaptureDelivery first=new CaptureDelivery(directory,io,ID);Remote remote=new Remote();
   io.failCreate=true;rejected(()->first.prepare(request(7)),"request must be durable before delivery");io.failCreate=false;
   check(first.request()==null,"failed prepare must not expose request");
   rejected(()->first.deliver(remote),"no remote call before durable preparation");check(remote.registrations==0,"failed preparation cannot register");
   first.prepare(request(7));remote.loseReply=true;
   rejected(()->first.deliver(remote),"lost remote response must preserve pending identity");
   CaptureDelivery restart=new CaptureDelivery(directory,io,ID);
   check(restart.request().revision==7&&PhotoContract.fingerprint(restart.request()).equals(PhotoContract.fingerprint(request(7))),"restart preserves revision and fingerprint");
   rejected(()->restart.prepare(request(8)),"revision cannot be silently refreshed");
   restart.deliver(remote);check(restart.acknowledged()&&remote.registrations==1,"receipt recovery cannot re-register old photo");
   List<String> order=new ArrayList<>();
   rejected(()->restart.finish(new CaptureDelivery.Completion(){public void publish()throws IOException{throw new IOException("synthetic entry ACK failure");}public void cleanup(PhotoContract.RegisterRequest r){order.add("cleanup");}}),"entry publication failure propagates");
   check(order.isEmpty(),"entry ACK must be durable before cleanup");
   CaptureDelivery again=new CaptureDelivery(directory,io,ID);
   again.deliver(new CaptureDelivery.Transport(){public PhotoContract.Reply receipt(PhotoContract.RegisterRequest r){throw new AssertionError("ACK must be local");}public PhotoContract.Reply register(PhotoContract.RegisterRequest r){throw new AssertionError("old photo must not overwrite newer photo");}});
   again.finish(new CaptureDelivery.Completion(){public void publish(){order.add("published");}public void cleanup(PhotoContract.RegisterRequest r){order.add("cleanup");}});
   check(order.equals(Arrays.asList("published","cleanup")),"successful completion order");
   check(new CaptureDelivery(directory,io,ID).request().revision==7,"cleanup retains immutable tombstone");
  }finally{io.clean();}
 }
 private static void ackWriteFailure(boolean uncertain)throws Exception{
  Disk io=new Disk();try{
   File directory=new File(io.root,"journals");CaptureDelivery delivery=new CaptureDelivery(directory,io,ID);delivery.prepare(request(9));Remote remote=new Remote();
   CaptureDelivery.Transport failing=new CaptureDelivery.Transport(){
    public PhotoContract.Reply receipt(PhotoContract.RegisterRequest r)throws IOException{return remote.receipt(r);}
    public PhotoContract.Reply register(PhotoContract.RegisterRequest r)throws IOException{PhotoContract.Reply reply=remote.register(r);if(uncertain)io.uncertainRename=true;else io.failCreate=true;return reply;}
   };
   rejected(()->delivery.deliver(failing),"ACK persistence failure propagates");io.failCreate=false;
   if(uncertain)rejected(()->delivery.request(),"uncertain ACK fences instance");
   CaptureDelivery restart=new CaptureDelivery(directory,io,ID);restart.deliver(remote);
   check(restart.acknowledged()&&remote.registrations==1,"ACK disk failure recovers same receipt exactly once");
  }finally{io.clean();}
 }
 private static void rejectedAck()throws Exception{
  Disk io=new Disk();try{
   CaptureDelivery delivery=new CaptureDelivery(new File(io.root,"journals"),io,ID);delivery.prepare(request(3));
   CaptureDelivery.Transport bad=new CaptureDelivery.Transport(){
    public PhotoContract.Reply receipt(PhotoContract.RegisterRequest r){return new PhotoContract.Reply(PhotoContract.OK,VARIANT,GEN,4,PRODUCT,VARIANT,HASH,"");}
    public PhotoContract.Reply register(PhotoContract.RegisterRequest r){throw new AssertionError("bad ACK must not register");}
   };
   rejected(()->delivery.deliver(bad),"foreign ACK must not complete entry");check(!delivery.acknowledged(),"foreign ACK remains unconfirmed");
   PhotoContract.Reply conflict=delivery.deliver(new CaptureDelivery.Transport(){
    public PhotoContract.Reply receipt(PhotoContract.RegisterRequest r){return new PhotoContract.Reply(PhotoContract.CONFLICT,r.requestId,GEN,4,"","","","GENERATION_CHANGED");}
    public PhotoContract.Reply register(PhotoContract.RegisterRequest r){throw new AssertionError("conflict must never retarget");}
   });check(PhotoContract.CONFLICT.equals(conflict.status)&&!delivery.acknowledged(),"conflict preserves original pending identity");
  }finally{io.clean();}
 }


 private static void lookupIdentity()throws Exception{
  Map<String,String> durable=new HashMap<>();List<String> visible=new ArrayList<>();
  CaptureLookup.Result old=CaptureLookup.publish("original","S: 2",durable::put);
  old.present("newer",visible::add);
  check(durable.get("original").equals("S: 2")&&!durable.containsKey("newer")&&visible.isEmpty(),"late lookup saves original entry without replacing current result");
  old.present("original",visible::add);check(visible.equals(Collections.singletonList("S: 2")),"current lookup displays durable summary");
  rejected(()->CaptureLookup.publish("original",String.join("",Collections.nCopies(12001,"x")),durable::put),"oversized result must be rejected before publication");
  check(durable.get("original").equals("S: 2"),"oversized result preserves prior stored summary");
  rejected(()->CaptureLookup.publish("failed","new",(id,value)->{throw new IOException("synthetic index failure");}),"lookup cannot report a result before durable publication");
 }
 public static void main(String[] args)throws Exception{
  cancellation(false);cancellation(true);successfulCancellation();deliveryRestart();ackWriteFailure(false);ackWriteFailure(true);rejectedAck();lookupIdentity();
  System.out.println("SyncRegressionChecks passed (actual durable queue with failed, uncertain and successful discard)");
 }
}
