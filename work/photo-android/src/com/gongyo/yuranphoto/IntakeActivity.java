package com.gongyo.yuranphoto;

import android.app.*;
import android.content.*;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.gongyo.photo.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** File intake and review inbox. A saved capture never silently implies confirmed stock. */
public final class IntakeActivity extends Activity {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Handler main=new Handler(Looper.getMainLooper());
 private PhotoClient client;private CaptureInbox inbox;private CaptureRecovery recovery;private String mode="intake",pendingGeneration="",pendingTarget="";
 private Runnable afterWork;private boolean busy,active,closed;private TextView status;private Button camera,file,resume;
 private final List<CaptureInbox.Entry> shown=new ArrayList<>();
 private final ContentObserver observer=new ContentObserver(main){public void onChange(boolean selfChange){if(active&&!busy)refresh();}};
 private interface Work{String run()throws Exception;}
 @Override public void onCreate(Bundle state){
  super.onCreate(state);mode="lookup".equals(getIntent().getStringExtra("mode"))?"lookup":"intake";
  if(state!=null){pendingGeneration=state.getString("pendingGeneration","");pendingTarget=state.getString("pendingTarget","");}
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);PhotoUi.insets(root,Math.round(14*getResources().getDisplayMetrics().density));
  TextView title=new TextView(this);title.setText(mode.equals("intake")?"새입고 등록":"재고 조회");title.setTextSize(25);root.addView(title);
  TextView hint=new TextView(this);hint.setText(mode.equals("intake")?"사진 옆 S·M·L·XL 수량을 읽어 입고합니다.\n수량이 불명확하거나 사진 연결이 남은 항목은 남은 사진 이어서 처리에서 확인하세요.":"품번이 읽히면 사진을 저장하고 현재 작업의 재고를 보여줍니다.");hint.setTextSize(16);root.addView(hint);
  camera=PhotoUi.button(root,"카메라 연속 촬영",v->{Intent intent=new Intent(this,LiveLabelActivity.class);intent.putExtra("mode",mode);startActivity(intent);});
  file=PhotoUi.button(root,"사진 파일 읽기",v->chooseFiles());
  resume=PhotoUi.button(root,"남은 사진 이어서 처리",v->resumeMenu());
  status=new TextView(this);status.setTextSize(16);root.addView(status);
  setContentView(root);
  getContentResolver().registerContentObserver(Uri.parse("content://"+RuntimeIds.CAPTURE_AUTHORITY+"/inbox"),false,observer);
  work(()->{client=PhotoClient.get(getApplicationContext());inbox=CaptureInbox.get(getApplicationContext());recovery=inbox.recovery(mode);client.compatibility();return recovery.batch()==null?"카메라 또는 사진 파일로 시작하세요. 보관한 사진은 남은 사진 이어서 처리에서 확인합니다.":"이전에 선택한 사진이 남아 있습니다. 남은 사진 이어서 처리를 눌러 주세요.";});
 }
 @Override protected void onResume(){super.onResume();active=true;if(inbox!=null&&!busy)refresh();}
 @Override protected void onPause(){active=false;super.onPause();}
 @Override protected void onSaveInstanceState(Bundle out){out.putString("pendingGeneration",pendingGeneration);out.putString("pendingTarget",pendingTarget);super.onSaveInstanceState(out);}
 private void work(Work action){
  if(busy||closed)return;busy=true;camera.setEnabled(false);file.setEnabled(false);resume.setEnabled(false);
  worker.execute(()->{String result;try{result=action.run();}catch(Exception e){result=e.getMessage()==null?"사진을 처리하지 못했습니다. 저장 목록을 확인해 주세요.":e.getMessage();}final String message=result;main.post(()->{if(closed)return;busy=false;camera.setEnabled(true);file.setEnabled(true);resume.setEnabled(true);status.setText(message);refresh();if(afterWork!=null){Runnable next=afterWork;afterWork=null;next.run();}});});
 }
 private void refresh(){
  if(closed||inbox==null||busy)return;
  worker.execute(()->{try{List<CaptureInbox.Entry> entries=inbox.entries();main.post(()->{if(closed)return;shown.clear();for(CaptureInbox.Entry e:entries)if(e.mode.equals(mode))shown.add(e);});}catch(IOException e){main.post(()->{if(!closed)status.setText(e.getMessage());});}});
 }
 private static String label(CaptureInbox.Entry e){
  long total=0;for(long q:e.quantities)total+=q;
  return (e.sku.isEmpty()?"품번 확인 필요":e.sku)+" · "+e.color+"\n"+
   (e.complete()?"수량·사진 저장 확인":e.committed?"수량 저장 확인 · 사진 연결 필요":e.submitted?"같은 입고 결과 확인 필요":e.ready?"입고 대기":"품번·수량 확인 필요")+(total>0?" · "+total+"개":"");
 }
 private void resumeMenu(){
  if(busy||inbox==null||recovery==null)return;
  work(()->{
   List<CaptureInbox.Entry> entries=inbox.entries();CaptureRecovery.Batch intakeBatch=inbox.recovery("intake").batch(),lookupBatch=inbox.recovery("lookup").batch();boolean manualPending=client.pendingRequest()!=null;
   main.post(()->{
    if(closed)return;List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
    if(intakeBatch!=null){labels.add("새입고 선택 사진 "+intakeBatch.items.size()+"장 이어서 처리");actions.add(()->work(()->resumeFiles("intake")));labels.add("새입고 남은 선택 취소");actions.add(()->cancelRemaining("intake"));}
    if(lookupBatch!=null){labels.add("이전 재고조회 선택 사진 "+lookupBatch.items.size()+"장 이어서 처리");actions.add(()->work(()->resumeFiles("lookup")));labels.add("이전 재고조회 남은 선택 취소");actions.add(()->cancelRemaining("lookup"));}
    for(CaptureInbox.Entry e:entries){labels.add(e.mode.equals("lookup")?"재고조회 저장 · "+e.sku+"\n"+e.message:label(e));actions.add(()->review(e));}
    if(manualPending){labels.add("기존 사진 등록 결과 확인");actions.add(()->startActivity(new Intent(this,PhotoActivity.class).putExtra("resumePending",true)));}
    if(labels.isEmpty()){status.setText("남은 사진이 없습니다. 카메라 또는 사진 파일로 시작하세요.");return;}
    new AlertDialog.Builder(this).setTitle("남은 사진 · 저장 결과 확인").setItems(labels.toArray(new String[0]),(dialog,which)->{if(!busy)actions.get(which).run();}).setNegativeButton("닫기",null).show();
   });
   return "사진별 수량과 사진 연결 상태를 확인하세요.";
  });
 }
 private void chooseFiles(){
  work(()->{
   if(recovery.batch()!=null)throw new IOException("남은 사진 이어서 처리를 먼저 눌러 주세요. 기존 작업과 사진은 유지됩니다.");
   if(mode.equals("intake")){Bundle context=client.beginIntake();pendingGeneration=context.getString("generation","");pendingTarget=context.getString("targetWorkId","");}
   else{pendingGeneration=client.compatibility().generation;pendingTarget="";}
   main.post(()->{if(closed)return;Intent choose=new Intent(Intent.ACTION_OPEN_DOCUMENT);choose.addCategory(Intent.CATEGORY_OPENABLE);choose.setType("image/*");choose.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);choose.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);try{startActivityForResult(choose,91);}catch(ActivityNotFoundException e){status.setText("사진 선택 앱을 열지 못했습니다.");}});
   return "품번과 옆의 사이즈별 수량이 보이는 사진을 선택하세요.";
  });
 }
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);if(request!=91||result!=RESULT_OK||data==null)return;
  LinkedHashSet<Uri> unique=new LinkedHashSet<>();if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)unique.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)unique.add(data.getData());
  if(unique.isEmpty())return;if(unique.size()>20){status.setText("사진은 한 번에 20장까지 선택해 주세요.");return;}
  if(pendingGeneration.isEmpty()){status.setText("입고할 작업을 다시 확인한 뒤 사진을 선택해 주세요.");return;}
  Set<String> acquired=new HashSet<>();
  try{
   inbox=CaptureInbox.get(getApplicationContext());recovery=inbox.recovery(mode);
   if(recovery.batch()!=null)throw new IOException("남은 사진을 먼저 처리하거나 선택 취소해 주세요.");
   CaptureRecovery.Batch other=inbox.recovery(mode.equals("intake")?"lookup":"intake").batch();
   List<String> sources=new ArrayList<>();Set<String> existing=new HashSet<>();
   for(android.content.UriPermission permission:getContentResolver().getPersistedUriPermissions())if(permission.isReadPermission())existing.add(permission.getUri().toString());
   for(Uri uri:unique){
    if(other!=null)for(CaptureRecovery.Item item:other.items)if(item.uri.equals(uri.toString()))throw new IOException("다른 모드에서 같은 사진을 처리 중입니다. 먼저 남은 처리를 완료해 주세요.");
    if(!existing.contains(uri.toString())){
     if((data.getFlags()&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)==0)throw new IOException("이 사진 제공 앱은 재시작 후 읽기를 허용하지 않습니다. 사진을 처리하지 않았습니다. 기기의 파일 앱에서 다시 선택해 주세요.");
     try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException e){throw new IOException("사진을 계속 읽을 권한을 저장하지 못했습니다. 파일 앱에서 다시 선택해 주세요.",e);}
     acquired.add(uri.toString());
    }
    sources.add(uri.toString());
   }
   recovery.enqueue(pendingGeneration,pendingTarget,sources,acquired);
   Runnable resume=()->{try{if(recovery.batch()!=null)work(this::resumeFiles);}catch(IOException e){status.setText(e.getMessage());}};
   if(busy)afterWork=resume;else resume.run();
  }catch(Exception e){
   boolean safeToRelease=false;try{safeToRelease=recovery==null||recovery.batch()==null;}catch(IOException uncertain){}
   if(safeToRelease)for(String uri:acquired)releaseGrant(uri);
   status.setText(e.getMessage()==null?"선택한 사진을 보관하지 못했습니다. 다시 선택해 주세요.":e.getMessage());
  }
 }
 private String resumeFiles()throws Exception{return resumeFiles(mode);}
 private String resumeFiles(String mode)throws Exception{
  CaptureRecovery recovery=inbox.recovery(mode);
  long owner=recovery.claimQueue();if(owner==0)return "이전 화면에서 사진을 처리하고 있습니다. 남은 사진은 유지됩니다.";
  int saved=0,confirmed=0;StringBuilder summaries=new StringBuilder();
  try{
   for(int processed=0;processed<20;processed++){
    CaptureRecovery.Batch batch=recovery.batch();if(batch==null)break;
    CaptureRecovery.Item item=batch.items.get(0);Bitmap image=null;
    try{
     CaptureInbox.Entry entry;
     if(!item.entryId.isEmpty())entry=inbox.entry(item.entryId);
     else{
      Uri uri=Uri.parse(item.uri);LabelRecognition.Source source=()->getApplicationContext().getContentResolver().openInputStream(uri);
      String sourceHash=CaptureInbox.sourceHash(source);recovery.sourceHash(owner,item.id,sourceHash);
      image=LabelRecognition.decode(source);String text;String ocrWarning="";
      try{text=LabelRecognition.readText(getApplicationContext(),source).text;}catch(LabelRecognition.OcrException unreadable){text="";ocrWarning="사진 저장됨 · 글자를 읽지 못했습니다. 품번·색상·수량을 직접 확인해 주세요.";}
      IntakeText.Result parsed=IntakeText.parse(text);
      if(parsed.sku.isEmpty()||parsed.color.isEmpty())try{
       Bundle match=LabelMatcher.match(client,text,new ArrayList<String>());String sku=match.getString("sku",""),color=match.getString("color","");
       if(parsed.sku.isEmpty()&&!sku.isEmpty())text="STYLE: "+sku+"\n"+text;
       if(parsed.color.isEmpty()&&!color.isEmpty())text="COLOR: "+color+"\n"+text;
      }catch(IOException ignored){}
      if(!sourceHash.equals(CaptureInbox.sourceHash(source)))throw new IOException("읽는 동안 원본 사진이 변경되었습니다. 남은 사진을 유지합니다.");
      entry=inbox.save(image,sourceHash,text,mode,batch.generation,batch.targetWorkId,"");
      if(!ocrWarning.isEmpty()&&!entry.submitted){entry.ready=false;entry.message=ocrWarning;inbox.update(entry);}
      recovery.saved(owner,item.id,entry.id);saved++;
      main.post(()->{if(active&&!closed)beep();});
     }
     if(mode.equals("intake")&&(entry.ready||entry.committed)){
      try{entry=inbox.submit(entry.id,client);if(entry.complete())confirmed++;}
      catch(IOException pending){entry=inbox.entry(entry.id);entry.message=(entry.committed?"수량 저장 확인 · 사진 연결 확인 필요: ":"입고 결과 확인 필요: ")+(pending.getMessage()==null?"원래 요청 유지":pending.getMessage());inbox.update(entry);}
     }
     else if(mode.equals("lookup")&&!entry.sku.isEmpty()){
      entry=inbox.lookupResult(entry.id,client.stockSummary(entry.sku));
      if(summaries.length()>0)summaries.append("\n\n");summaries.append(entry.sku).append('\n').append(entry.message);
     }
     // Saved entry identity is durable; replay no longer requires this source grant.
     recovery.complete(owner,item.id);if(item.releaseGrant)releaseGrant(item.uri);
    }catch(Exception e){
     String prefix=summaries.length()==0?"":summaries+"\n\n";
     return prefix+"처리를 멈췄습니다. 남은 사진과 원래 작업은 보관됩니다. 남은 사진 이어서 처리에서 확인하거나 남은 선택을 취소해 주세요.\n"+(e.getMessage()==null?"저장 목록을 확인해 주세요.":e.getMessage());
    }finally{if(image!=null)image.recycle();}
   }
   if(summaries.length()>0)return summaries.toString();
   return "사진 "+saved+"장 저장"+(mode.equals("intake")?" · "+confirmed+"장 수량·사진 저장 확인":"")+" · 남은 사진 이어서 처리에서 사진별 결과를 확인하세요.";
  }finally{recovery.releaseQueue(owner);}
 }
 private void releaseGrant(String uri){try{getContentResolver().releasePersistableUriPermission(Uri.parse(uri),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}}
 private void cancelRemaining(String selectedMode){
  new AlertDialog.Builder(this).setTitle("남은 사진 선택 취소").setMessage("아직 처리하지 않은 선택을 취소합니다. 저장된 사진과 확인 중인 입고 요청은 원래 작업으로 저장 목록에 남습니다. 원본 사진은 삭제하지 않습니다.")
   .setNegativeButton("계속 보관",null).setPositiveButton("남은 선택 취소",(dialog,which)->work(()->{
    inbox.recovery(selectedMode).discardRemaining(this::releaseGrant);
    return "남은 선택을 취소했습니다. 저장된 사진은 목록에서 확인하세요.";
   })).show();
 }
 private void review(CaptureInbox.Entry selected){
  if(inbox==null)return;
  final CaptureInbox.Entry entry;
  try{entry=inbox.entry(selected.id);}catch(IOException e){status.setText(e.getMessage());return;}
  ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(24,12,24,12);scroll.addView(body);
  ImageView picture=new ImageView(this);picture.setScaleType(ImageView.ScaleType.FIT_CENTER);body.addView(picture,new LinearLayout.LayoutParams(-1,Math.round(230*getResources().getDisplayMetrics().density)));
  EditText sku=field(body,"품번",entry.sku),color=field(body,"색상",entry.color);EditText[] quantities=new EditText[4];String[] sizes={"S","M","L","XL"};
  for(int i=0;i<4;i++){quantities[i]=field(body,sizes[i]+" 입고 수량",String.valueOf(entry.quantities[i]));quantities[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER);}
  TextView note=new TextView(this);note.setText(entry.message);body.addView(note);
  boolean editable=entry.mode.equals("intake")&&!entry.submitted;sku.setEnabled(editable);color.setEnabled(editable);for(EditText q:quantities)q.setEnabled(editable);
  AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(entry.complete()?"수량·사진 저장 확인":"저장 사진 확인").setView(scroll).setNegativeButton("닫기",null);
  if(entry.mode.equals("intake"))builder.setPositiveButton(entry.complete()?"저장 결과 확인":entry.committed?"사진 연결 결과 확인":entry.submitted?"같은 등록 결과 확인":"확인한 수량 입고",null);
  else if(entry.mode.equals("lookup"))builder.setPositiveButton("현재 재고 조회",null);
  AlertDialog dialog=builder.create();dialog.show();
  if(entry.mode.equals("intake"))dialog.getButton(-1).setOnClickListener(v->{
   try{
    if(!entry.submitted){entry.sku=PhotoContract.key(sku.getText().toString());entry.color=PhotoContract.key(color.getText().toString());StringJoiner q=new StringJoiner(",");for(EditText value:quantities)q.add(value.getText().toString().trim());entry.quantities=IntakeContract.counts(q.toString());IntakeContract.fingerprint(entry.generation,entry.targetWorkId,entry.sourceHash,entry.sku,entry.color,entry.quantities);entry.ready=true;entry.reviewVersion=2;}
    dialog.dismiss();work(()->{if(!entry.submitted)inbox.update(entry);CaptureInbox.Entry result=inbox.submit(entry.id,client);return result.sku+" · "+result.message;});
   }catch(Exception e){note.setText(e.getMessage());}
  });
  else if(entry.mode.equals("lookup"))dialog.getButton(-1).setOnClickListener(v->work(()->{String summary=client.stockSummary(entry.sku);inbox.lookupResult(entry.id,summary);main.post(()->{if(!closed&&dialog.isShowing())note.setText(summary);});return entry.sku+"\n"+summary;}));
  worker.execute(()->{try{Bitmap thumbnail=inbox.thumbnail(entry,512);main.post(()->{if(dialog.isShowing()&&!closed)picture.setImageBitmap(thumbnail);else thumbnail.recycle();});}catch(IOException e){main.post(()->{if(!closed&&dialog.isShowing())note.setText(e.getMessage());});}});
 }
 private EditText field(LinearLayout body,String label,String value){TextView title=new TextView(this);title.setText(label);body.addView(title);EditText input=new EditText(this);input.setSingleLine(true);input.setText(value);input.setMinHeight(PhotoUi.dp(body,48));body.addView(input);return input;}
 private void beep(){try{ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_MUSIC,80);tone.startTone(ToneGenerator.TONE_PROP_ACK,160);main.postDelayed(tone::release,250);}catch(RuntimeException ignored){}}
 @Override protected void onDestroy(){closed=true;active=false;getContentResolver().unregisterContentObserver(observer);worker.shutdown();super.onDestroy();}
}
