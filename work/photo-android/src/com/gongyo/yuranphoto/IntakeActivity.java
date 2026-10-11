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
 private PhotoClient client;private CaptureInbox inbox;private String mode="intake",pendingGeneration="",pendingTarget="";
 private Runnable afterWork;private boolean busy,active,closed;private TextView status;private Button camera,file;private ListView list;
 private final List<CaptureInbox.Entry> shown=new ArrayList<>();
 private final ContentObserver observer=new ContentObserver(main){public void onChange(boolean selfChange){if(active&&!busy)refresh();}};
 private interface Work{String run()throws Exception;}
 @Override public void onCreate(Bundle state){
  super.onCreate(state);mode="lookup".equals(getIntent().getStringExtra("mode"))?"lookup":"intake";
  if(state!=null){pendingGeneration=state.getString("pendingGeneration","");pendingTarget=state.getString("pendingTarget","");}
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);PhotoUi.insets(root,Math.round(14*getResources().getDisplayMetrics().density));
  TextView title=new TextView(this);title.setText(mode.equals("intake")?"새입고 등록":"재고 조회");title.setTextSize(25);root.addView(title);
  TextView hint=new TextView(this);hint.setText(mode.equals("intake")?"사진 옆 S·M·L·XL 수량을 읽어 입고합니다.\n수량이 불명확한 사진은 아래 목록에서 확인하세요.":"품번이 읽히면 사진을 저장하고 현재 작업의 재고를 보여줍니다.");hint.setTextSize(16);root.addView(hint);
  camera=PhotoUi.button(root,"카메라 연속 촬영",v->{Intent intent=new Intent(this,LiveLabelActivity.class);intent.putExtra("mode",mode);startActivity(intent);});
  file=PhotoUi.button(root,"사진 파일 읽기",v->chooseFiles());
  status=new TextView(this);status.setTextSize(16);root.addView(status);
  list=new ListView(this);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));list.setOnItemClickListener((parent,view,position,id)->{if(!busy&&position<shown.size())review(shown.get(position));});
  PhotoUi.button(root,"돌아가기",v->finish());setContentView(root);
  getContentResolver().registerContentObserver(Uri.parse("content://"+RuntimeIds.CAPTURE_AUTHORITY+"/inbox"),false,observer);
  work(()->{client=PhotoClient.get(getApplicationContext());inbox=CaptureInbox.get(getApplicationContext());client.compatibility();return "저장된 사진을 눌러 확인하거나 이어서 등록하세요.";});
 }
 @Override protected void onResume(){super.onResume();active=true;if(inbox!=null&&!busy)refresh();}
 @Override protected void onPause(){active=false;super.onPause();}
 @Override protected void onSaveInstanceState(Bundle out){out.putString("pendingGeneration",pendingGeneration);out.putString("pendingTarget",pendingTarget);super.onSaveInstanceState(out);}
 private void work(Work action){
  if(busy||closed)return;busy=true;camera.setEnabled(false);file.setEnabled(false);
  worker.execute(()->{String result;try{result=action.run();}catch(Exception e){result=e.getMessage()==null?"사진을 처리하지 못했습니다. 저장 목록을 확인해 주세요.":e.getMessage();}final String message=result;main.post(()->{if(closed)return;busy=false;camera.setEnabled(true);file.setEnabled(true);status.setText(message);refresh();if(afterWork!=null){Runnable next=afterWork;afterWork=null;next.run();}});});
 }
 private void refresh(){
  if(closed||inbox==null||busy)return;
  worker.execute(()->{try{List<CaptureInbox.Entry> entries=inbox.entries();main.post(()->{if(closed)return;shown.clear();List<String> labels=new ArrayList<>();for(CaptureInbox.Entry e:entries)if(e.mode.equals(mode)){shown.add(e);long total=0;for(long q:e.quantities)total+=q;labels.add((e.sku.isEmpty()?"품번 확인 필요":e.sku)+" · "+e.color+"\n"+(e.committed?"입고 완료":e.submitted?"등록 결과 확인 필요":mode.equals("lookup")?"사진 저장됨":e.ready?"입고 대기":"수량 확인 필요")+(total>0?" · "+total+"개":""));}list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,labels));});}catch(IOException e){main.post(()->{if(!closed)status.setText(e.getMessage());});}});
 }
 private void chooseFiles(){
  work(()->{
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
  final List<Uri> inputs=new ArrayList<>(unique);final String generation=pendingGeneration,target=pendingTarget;final boolean mayPersist=(data.getFlags()&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0;
  Work importWork=()->{
   int confirmed=0,saved=0,failed=0;String failure="";
   for(Uri uri:inputs){boolean persisted=false;Bitmap image=null;
    try{
     if(mayPersist)try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);persisted=true;}catch(SecurityException ignored){}
     LabelRecognition.Source source=()->getApplicationContext().getContentResolver().openInputStream(uri);
     String sourceHash=CaptureInbox.sourceHash(source);LabelRecognition.Result resultText=LabelRecognition.readText(getApplicationContext(),source);
     String text=resultText.text;IntakeText.Result parsed=IntakeText.parse(text);
     if(parsed.sku.isEmpty()||parsed.color.isEmpty())try{
      Bundle match=LabelMatcher.match(client,text,new ArrayList<String>());String sku=match.getString("sku",""),color=match.getString("color","");
      if(parsed.sku.isEmpty()&&!sku.isEmpty())text="STYLE: "+sku+"\n"+text;
      if(parsed.color.isEmpty()&&!color.isEmpty())text="COLOR: "+color+"\n"+text;
     }catch(IOException ignored){/* Save for explicit review instead of guessing. */}
     image=LabelRecognition.decode(source);CaptureInbox.Entry entry=inbox.save(image,sourceHash,text,mode,generation,target,"");saved++;
     main.post(()->{if(active&&!closed)beep();});
     if(mode.equals("intake")&&entry.ready){entry=inbox.submit(entry.id,client);if(entry.committed)confirmed++;}
     else if(mode.equals("lookup")&&!entry.sku.isEmpty()){final String summary=entry.sku+"\n"+client.stockSummary(entry.sku);main.post(()->{if(!closed)status.setText(summary);});}
    }catch(Exception e){failed++;failure=e.getMessage()==null?"처리 실패":e.getMessage();}
    finally{if(image!=null)image.recycle();if(persisted)try{getContentResolver().releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}}
   }
   return "사진 "+saved+"장 저장"+(mode.equals("intake")?" · "+confirmed+"장 입고 완료":"")+(failed>0?"\n확인 필요 "+failed+"장 · "+failure:"");
  };if(busy)afterWork=()->work(importWork);else work(importWork);
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
  boolean editable=mode.equals("intake")&&!entry.submitted;sku.setEnabled(editable);color.setEnabled(editable);for(EditText q:quantities)q.setEnabled(editable);
  AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(entry.committed?"입고 완료 사진":"저장 사진 확인").setView(scroll).setNegativeButton("닫기",null);
  if(mode.equals("intake")&&!entry.committed)builder.setPositiveButton(entry.submitted?"같은 등록 결과 확인":"확인한 수량 입고",null);
  else if(mode.equals("lookup"))builder.setPositiveButton("현재 재고 조회",null);
  AlertDialog dialog=builder.create();dialog.show();
  if(mode.equals("intake")&&!entry.committed)dialog.getButton(-1).setOnClickListener(v->{
   try{
    if(!entry.submitted){entry.sku=PhotoContract.key(sku.getText().toString());entry.color=PhotoContract.key(color.getText().toString());StringJoiner q=new StringJoiner(",");for(EditText value:quantities)q.add(value.getText().toString().trim());entry.quantities=IntakeContract.counts(q.toString());IntakeContract.fingerprint(entry.generation,entry.targetWorkId,entry.sourceHash,entry.sku,entry.color,entry.quantities);entry.ready=true;}
    dialog.dismiss();work(()->{if(!entry.submitted)inbox.update(entry);CaptureInbox.Entry result=inbox.submit(entry.id,client);return result.sku+" · "+result.message;});
   }catch(Exception e){note.setText(e.getMessage());}
  });
  else if(mode.equals("lookup"))dialog.getButton(-1).setOnClickListener(v->work(()->{String summary=client.stockSummary(entry.sku);main.post(()->{if(dialog.isShowing())note.setText(summary);});return summary;}));
  worker.execute(()->{try{Bitmap thumbnail=inbox.thumbnail(entry,512);main.post(()->{if(dialog.isShowing()&&!closed)picture.setImageBitmap(thumbnail);else thumbnail.recycle();});}catch(IOException e){main.post(()->{if(dialog.isShowing())note.setText(e.getMessage());});}});
 }
 private EditText field(LinearLayout body,String label,String value){TextView title=new TextView(this);title.setText(label);body.addView(title);EditText input=new EditText(this);input.setSingleLine(true);input.setText(value);input.setMinHeight(PhotoUi.dp(body,48));body.addView(input);return input;}
 private void beep(){try{ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_MUSIC,80);tone.startTone(ToneGenerator.TONE_PROP_ACK,160);main.postDelayed(tone::release,250);}catch(RuntimeException ignored){}}
 @Override protected void onDestroy(){closed=true;active=false;getContentResolver().unregisterContentObserver(observer);worker.shutdown();super.onDestroy();}
}
