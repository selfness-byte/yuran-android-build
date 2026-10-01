package com.gongyo.yuranphoto;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A label-only camera session; its output is text, never a product photo request. */
public final class LabelScanActivity extends Activity {
    public static final String EXTRA_TEXT = "labelText";
    public static final String EXTRA_BARCODES = "labelBarcodes";
    private static final int CAMERA_PERMISSION=43;
    private static final int IDLE=0,PREPARING=1,READY_EXTERNAL=2,LAUNCHED=3,READY_READ=4,READING=5,DONE=6,FAILED=7;
    private static final class Holder {
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final Handler main=new Handler(Looper.getMainLooper());
        WeakReference<LabelScanActivity> owner=new WeakReference<>(null);
        PhotoClient client;
        volatile Uri uri;
        volatile int phase=IDLE;
        boolean camera,persisted,permissionPending,cameraAfterPermission,closed;
        int externalCode=1000;
        String text="",message="라벨의 품번·색상·호칭과 바코드가 선명하게 보이도록 촬영하세요.";
        ArrayList<String> barcodes=new ArrayList<>();
    }
    private Holder holder;
    private TextView status;
    private Button cameraButton,fileButton,retryButton;
    private boolean resumed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Object previous=getLastNonConfigurationInstance();
        holder=previous instanceof Holder?(Holder)previous:new Holder();
        holder.owner=new WeakReference<>(this);
        if (!(previous instanceof Holder) && state!=null) {
            String uri=state.getString("uri","");
            holder.uri=uri.isEmpty()?null:Uri.parse(uri);
            holder.camera=state.getBoolean("camera");holder.persisted=state.getBoolean("persisted");
            holder.phase=state.getInt("phase",IDLE);holder.message=state.getString("message",holder.message);
            holder.text=state.getString("text","");ArrayList<String> values=state.getStringArrayList("barcodes");
            if(values!=null)holder.barcodes=values;
            holder.permissionPending=state.getBoolean("permissionPending");
            holder.cameraAfterPermission=state.getBoolean("cameraAfterPermission");holder.externalCode=state.getInt("externalCode",1000);
            if(holder.phase==READING)holder.phase=holder.uri==null?FAILED:READY_READ;
            if(holder.phase==PREPARING)holder.phase=holder.uri==null?IDLE:READY_EXTERNAL;
        }
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(28,28,28,28);
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));setContentView(scroll);
        TextView title=new TextView(this);title.setText("사진 구별");title.setTextSize(26);title.setTextColor(0xffd63d79);content.addView(title);
        TextView hint=new TextView(this);hint.setText("상품 라벨을 읽어 저장된 품번·색상을 찾습니다.\n라벨 사진은 옷 사진으로 등록되지 않습니다.");hint.setTextSize(17);hint.setPadding(0,20,0,20);content.addView(hint);
        status=new TextView(this);status.setTextSize(17);status.setPadding(0,12,0,24);content.addView(status);
        cameraButton=button(content,"라벨 촬영",v->requestCamera());
        fileButton=button(content,"라벨 사진 파일 선택",v->begin(false));
        retryButton=button(content,"같은 라벨 다시 읽기",v->{if(holder.phase==FAILED&&holder.uri!=null){holder.phase=READY_READ;drain();}});
        button(content,"돌아가기",v->{setResult(RESULT_CANCELED);finish();});
        try {holder.client=PhotoClient.get(getApplicationContext());}
        catch(IOException e){holder.message=e.getMessage();holder.phase=FAILED;}
        display();
    }
    private Button button(LinearLayout parent,String label,View.OnClickListener action){Button b=new Button(this);b.setText(label);b.setOnClickListener(action);parent.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    @Override public Object onRetainNonConfigurationInstance(){return holder;}
    @Override protected void onSaveInstanceState(Bundle state){
        state.putString("uri",holder.uri==null?"":holder.uri.toString());state.putBoolean("camera",holder.camera);state.putBoolean("persisted",holder.persisted);
        state.putInt("phase",holder.phase);state.putString("text",holder.text);state.putStringArrayList("barcodes",holder.barcodes);
        state.putString("message",holder.message);state.putBoolean("permissionPending",holder.permissionPending);state.putBoolean("cameraAfterPermission",holder.cameraAfterPermission);state.putInt("externalCode",holder.externalCode);super.onSaveInstanceState(state);
    }
    @Override protected void onResume(){super.onResume();resumed=true;drain();}
    @Override protected void onPause(){resumed=false;super.onPause();}
    private boolean available(){return resumed&&!isFinishing()&&!holder.closed&&holder.client!=null&&(holder.phase==IDLE||holder.phase==FAILED);}
    private void display(){
        status.setText(holder.message==null?"라벨 읽기를 준비하고 있습니다.":holder.message);
        boolean ready=available();cameraButton.setEnabled(ready);fileButton.setEnabled(ready);
        retryButton.setVisibility(holder.phase==FAILED&&holder.uri!=null?View.VISIBLE:View.GONE);retryButton.setEnabled(ready);
    }
    private void requestCamera(){
        if(!available())return;
        if(checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){holder.permissionPending=true;requestPermissions(new String[]{android.Manifest.permission.CAMERA},CAMERA_PERMISSION);return;}
        begin(true);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==CAMERA_PERMISSION){holder.permissionPending=false;if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED){holder.cameraAfterPermission=true;drain();}else{holder.message="라벨 촬영에는 카메라 허용이 필요합니다. 사진 파일을 선택할 수도 있습니다.";display();}}
    }
    private void begin(boolean camera){
        if(!available())return;holder.phase=PREPARING;holder.message="라벨 사진을 준비하고 있습니다.";display();final Holder h=holder;
        h.worker.execute(()->{
            try{if(h.client.pendingRequest()!=null)throw new IOException("대기 중인 상품 사진을 먼저 등록하거나 취소해 주세요.");cleanupSource(h);h.camera=camera;h.text="";h.barcodes.clear();h.client.compatibility();if(camera)h.uri=h.client.reserveCamera();h.phase=READY_EXTERNAL;}
            catch(Exception e){h.message=message(e);h.phase=FAILED;}
            refresh(h);
        });
    }
    private static String message(Exception e){return e.getMessage()==null?"라벨 사진을 처리하지 못했습니다. 다시 촬영해 주세요.":e.getMessage();}
    private void drain(){
        if(!resumed||holder.closed||isFinishing())return;
        if(holder.cameraAfterPermission&&available()){holder.cameraAfterPermission=false;begin(true);return;}
        if(holder.phase==DONE){Intent result=new Intent();result.putExtra(EXTRA_TEXT,holder.text);result.putStringArrayListExtra(EXTRA_BARCODES,holder.barcodes);setResult(RESULT_OK,result);finish();return;}
        if(holder.phase==READY_EXTERNAL)launchExternal();
        else if(holder.phase==READY_READ)read();
        display();
    }
    private void launchExternal(){
        Holder h=holder;h.phase=LAUNCHED;
        try{
            if(h.externalCode>=65534)throw new IOException("촬영 연결을 다시 준비하려면 사진 구별 화면을 다시 열어 주세요.");
            h.externalCode++;
            if(h.camera){
                if(h.uri==null)throw new IOException("라벨 촬영 주소를 준비하지 못했습니다.");
                Intent camera=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);camera.putExtra(MediaStore.EXTRA_OUTPUT,h.uri);
                camera.setClipData(ClipData.newRawUri("label camera output",h.uri));camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                List<ResolveInfo> targets=getPackageManager().queryIntentActivities(camera,PackageManager.MATCH_DEFAULT_ONLY);
                if(targets.isEmpty())throw new ActivityNotFoundException("카메라 앱을 열 수 없습니다. 라벨 사진 파일을 선택해 주세요.");
                for(ResolveInfo target:targets)grantUriPermission(target.activityInfo.packageName,h.uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startActivityForResult(camera,h.externalCode);
            }else{
                Intent file=new Intent(Intent.ACTION_OPEN_DOCUMENT);file.addCategory(Intent.CATEGORY_OPENABLE);file.setType("image/*");file.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(file,h.externalCode);
            }
            h.message="라벨 사진을 촬영하거나 선택한 뒤 돌아오면 글자와 바코드를 읽습니다.";
        }catch(IOException|ActivityNotFoundException|SecurityException e){h.phase=FAILED;h.message=message(e);}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(holder.phase!=LAUNCHED||request!=holder.externalCode)return;
        if(!holder.camera){holder.uri=data==null?null:data.getData();if(holder.uri!=null&&result==RESULT_OK&&data!=null&&(data.getFlags()&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0)try{getContentResolver().takePersistableUriPermission(holder.uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);holder.persisted=true;}catch(SecurityException ignored){}}
        if(result!=RESULT_OK||holder.uri==null){final Holder h=holder;h.phase=PREPARING;h.worker.execute(()->{try{cleanupSource(h);h.phase=IDLE;h.message="라벨 촬영을 취소했습니다.";}catch(Exception e){h.phase=FAILED;h.message=message(e);}refresh(h);});}
        else{holder.phase=READY_READ;holder.message="라벨의 글자와 바코드를 읽고 있습니다.";drain();}
    }
    private void read(){
        final Holder h=holder;final Uri input=h.uri;final boolean camera=h.camera;h.phase=READING;h.message="라벨의 글자와 바코드를 읽고 있습니다.";display();
        h.worker.execute(()->{
            try{
                LabelRecognition.Source source=camera?()->h.client.openLabelCamera(input):()->getApplicationContext().getContentResolver().openInputStream(input);
                LabelRecognition.Result result=LabelRecognition.read(getApplicationContext(),source);h.text=result.text;h.barcodes=result.barcodes;cleanupSource(h);h.phase=DONE;h.message="라벨을 읽었습니다.";
            }catch(Exception e){h.phase=FAILED;h.message=message(e);}
            refresh(h);
        });
    }
    private static void refresh(Holder h){h.main.post(()->{LabelScanActivity activity=h.owner.get();if(activity!=null&&!h.closed)activity.drain();});}
    private void cleanupSource(Holder h)throws IOException{
        Uri source=h.uri;if(source==null)return;
        if(h.camera)h.client.finishCamera(source);
        else if(h.persisted)try{getApplicationContext().getContentResolver().releasePersistableUriPermission(source,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
        h.uri=null;h.persisted=false;
    }
    @Override protected void onDestroy(){
        if(!isChangingConfigurations()){
            final Holder h=holder;h.closed=true;
            h.worker.execute(()->{try{cleanupSource(h);}catch(IOException ignored){/* Retain the file if cleanup could not be confirmed. */}});
            h.worker.shutdown();
        }
        if(holder.owner.get()==this)holder.owner=new WeakReference<>(null);super.onDestroy();
    }
}
