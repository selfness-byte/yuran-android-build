package com.gongyo.yuranphoto;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import com.gongyo.photo.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera2 live label capture. No cloud OCR; sound only follows checked inbox publication. */
public final class LiveLabelActivity extends Activity {
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService storage=Executors.newSingleThreadExecutor();
 private final CaptureGate gate=new CaptureGate();
 private final AtomicBoolean analysing=new AtomicBoolean();
 private final List<ImageReader> retiring=new ArrayList<>();
 private HandlerThread cameraThread;private Handler cameraHandler;
 private TextRecognizer recognizer;private CameraDevice camera;private CameraCaptureSession session;private ImageReader reader;private Surface previewSurface;
 private TextureView preview;private TextView status;private Button next,manual;
 private volatile boolean active,closing,saving,ready,manualRequested,openingCamera;private volatile int epoch;private long lastAnalysis;
 private Size previewSize;private int sensorOrientation;private String cameraId="",mode="intake",generation="",targetWorkId="",lastSavedId="";
 private PhotoClient client;private CaptureInbox inbox;private final Set<String> knownSkus=new HashSet<>();
 @Override public void onCreate(Bundle state){
  super.onCreate(state);mode="lookup".equals(getIntent().getStringExtra("mode"))?"lookup":"intake";
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);int pad=Math.round(12*getResources().getDisplayMetrics().density);PhotoUi.insets(root,pad);
  TextView title=new TextView(this);title.setText(mode.equals("intake")?"새입고 연속 촬영":"재고 조회 연속 촬영");title.setTextSize(23);root.addView(title);
  status=new TextView(this);status.setTextSize(16);status.setText("품번이 선명하게 보이도록 택을 비춰 주세요.");root.addView(status);
  preview=new TextureView(this);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
  manual=PhotoUi.button(root,"지금 사진 저장",v->{if(ready&&!saving)manualRequested=true;});
  next=PhotoUi.button(root,"같은 품번 다음 장 준비",v->{if(!saving){synchronized(gate){gate.reset();}status.setText("다음 택을 비춰 주세요.");}});
  PhotoUi.button(root,"촬영 끝내기",v->finish());setContentView(root);
  recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
  cameraThread=new HandlerThread("yuran-label-camera");cameraThread.start();cameraHandler=new Handler(cameraThread.getLooper());
  preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
   public void onSurfaceTextureAvailable(SurfaceTexture texture,int w,int h){openCamera();}
   public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int w,int h){transform();}
   public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){closeCamera();return true;}
   public void onSurfaceTextureUpdated(SurfaceTexture texture){}
  });
  storage.execute(()->{
   try{
    client=PhotoClient.get(getApplicationContext());inbox=CaptureInbox.get(getApplicationContext());
    if(mode.equals("intake")){Bundle context=client.beginIntake();generation=context.getString("generation","");targetWorkId=context.getString("targetWorkId","");}
    else generation=client.compatibility().generation;
    // Exact full-line catalogue fallback supports labels whose Korean markers were not read.
    try{int offset=0;String g="";long revision=-1;Set<String> loaded=new HashSet<>();
     do{Bundle page=client.labelCatalogPage("",offset,g,revision);if(!PhotoContract.OK.equals(page.getString("status")))break;g=page.getString("generation","");revision=page.getLong("revision",-1);ArrayList<Bundle> rows=page.getParcelableArrayList("items");if(rows==null)break;
      for(Bundle row:rows){String sku=PhotoContract.key(row.getString("sku"));if(sku.length()>=5&&sku.matches(".*[A-Z].*")&&sku.matches(".*[0-9].*"))loaded.add(sku);}
      int next=page.getInt("nextOffset",-1);if(next<=offset)break;offset=next;
     }while(offset<20000);
     synchronized(knownSkus){knownSkus.addAll(loaded);}
    }catch(Exception ignored){/* Explicit printed product numbers still work without catalogue hints. */}
    ready=true;message("품번을 읽으면 사진을 저장하고 완료음이 납니다.");
   }catch(Exception e){message(e.getMessage());}
  });
 }
 @Override protected void onResume(){super.onResume();active=true;if(preview.isAvailable())openCamera();}
 @Override protected void onPause(){active=false;closeCamera();super.onPause();}
 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==81&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)openCamera();else message("카메라 권한이 필요합니다. 사진 파일로도 입고할 수 있습니다.");}
 private void openCamera(){
  if(!active||closing||camera!=null||openingCamera||!preview.isAvailable())return;
  if(checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.CAMERA},81);return;}
  openingCamera=true;final int opening=++epoch;
  try{
   CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);CameraCharacteristics chosen=null;
   for(String id:manager.getCameraIdList()){CameraCharacteristics c=manager.getCameraCharacteristics(id);Integer facing=c.get(CameraCharacteristics.LENS_FACING);if(facing!=null&&facing==CameraCharacteristics.LENS_FACING_BACK){cameraId=id;chosen=c;break;}}
   if(chosen==null)throw new IOException("후면 카메라를 찾지 못했습니다.");
   Integer orientation=chosen.get(CameraCharacteristics.SENSOR_ORIENTATION);sensorOrientation=orientation==null?90:orientation;
   StreamConfigurationMap formats=chosen.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);if(formats==null)throw new IOException("카메라 크기를 확인하지 못했습니다.");
   Set<Size> textures=new HashSet<>(Arrays.asList(formats.getOutputSizes(SurfaceTexture.class)));Size best=null;
   for(Size size:formats.getOutputSizes(ImageFormat.YUV_420_888))if(textures.contains(size)&&size.getWidth()<=1920&&size.getHeight()<=1920){if(best==null||(long)size.getWidth()*size.getHeight()>(long)best.getWidth()*best.getHeight())best=size;}
   if(best==null)throw new IOException("지원되는 라벨 촬영 크기가 없습니다. 사진 파일 선택을 사용해 주세요.");
   previewSize=best;transform();
   reader=ImageReader.newInstance(best.getWidth(),best.getHeight(),ImageFormat.YUV_420_888,2);
   final ImageReader ownedReader=reader;reader.setOnImageAvailableListener(source->analyse(source,opening),cameraHandler);
   final int[] modes=chosen.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
   manager.openCamera(cameraId,new CameraDevice.StateCallback(){
    public void onOpened(CameraDevice opened){
     if(!active||opening!=epoch){opened.close();return;}openingCamera=false;camera=opened;
     try{
      SurfaceTexture texture=preview.getSurfaceTexture();if(texture==null)throw new IOException("미리보기가 닫혔습니다.");texture.setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());previewSurface=new Surface(texture);
      CaptureRequest.Builder request=opened.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);request.addTarget(previewSurface);request.addTarget(ownedReader.getSurface());
      request.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
      if(modes!=null)for(int af:modes)if(af==CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE){request.set(CaptureRequest.CONTROL_AF_MODE,af);break;}
      request.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
      opened.createCaptureSession(Arrays.asList(previewSurface,ownedReader.getSurface()),new CameraCaptureSession.StateCallback(){
       public void onConfigured(CameraCaptureSession configured){if(!active||opening!=epoch){configured.close();return;}session=configured;try{configured.setRepeatingRequest(request.build(),null,cameraHandler);}catch(CameraAccessException e){message(e.getMessage());}}
       public void onConfigureFailed(CameraCaptureSession failed){message("카메라 미리보기를 열지 못했습니다. 사진 파일 선택을 사용해 주세요.");}
      },cameraHandler);
     }catch(Exception e){message(e.getMessage());closeCamera();}
    }
    public void onDisconnected(CameraDevice disconnected){openingCamera=false;disconnected.close();if(camera==disconnected)camera=null;message("카메라 연결이 끊겼습니다.");}
    public void onError(CameraDevice failed,int error){openingCamera=false;failed.close();if(camera==failed)camera=null;message("카메라를 열지 못했습니다. 코드 "+error);}
   },cameraHandler);
  }catch(Exception e){message(e.getMessage());closeCamera();}
 }
 private void transform(){
  if(previewSize==null||preview.getWidth()==0||preview.getHeight()==0)return;
  int rotation=getWindowManager().getDefaultDisplay().getRotation();float w=preview.getWidth(),h=preview.getHeight(),cx=w/2,cy=h/2;Matrix m=new Matrix();
  if(rotation==Surface.ROTATION_90||rotation==Surface.ROTATION_270){
   RectF view=new RectF(0,0,w,h),buffer=new RectF(0,0,previewSize.getHeight(),previewSize.getWidth());buffer.offset(cx-buffer.centerX(),cy-buffer.centerY());m.setRectToRect(view,buffer,Matrix.ScaleToFit.FILL);float scale=Math.max(h/previewSize.getHeight(),w/previewSize.getWidth());m.postScale(scale,scale,cx,cy);m.postRotate(rotation==Surface.ROTATION_90?-90:90,cx,cy);
  }else if(rotation==Surface.ROTATION_180)m.postRotate(180,cx,cy);
  preview.setTransform(m);
 }
 private void analyse(ImageReader source,int cameraEpoch){
  Image image;try{image=source.acquireLatestImage();}catch(IllegalStateException closed){return;}
  if(image==null)return;long now=SystemClock.elapsedRealtime();
  if(!active||!ready||saving||cameraEpoch!=epoch||now-lastAnalysis<300||!analysing.compareAndSet(false,true)){image.close();return;}
  lastAnalysis=now;int rotation=(sensorOrientation-getWindowManager().getDefaultDisplay().getRotation()*90+360)%360;
  try{
   recognizer.process(InputImage.fromMediaImage(image,rotation)).addOnCompleteListener(Runnable::run,task->{
    try{
     if(!active||closing||cameraEpoch!=epoch)return;
     if(!task.isSuccessful()){message("초점을 맞춰 다시 비춰 주세요.");return;}
     String text=LabelRecognition.intakeText(task.getResult());if(text.length()>LabelRecognition.MAX_TEXT)return;
     String sku=resolveSku(text);boolean capture;
     synchronized(gate){capture=gate.observe(sku,SystemClock.elapsedRealtime());}
     if(manualRequested){capture=true;manualRequested=false;}
     if(!capture)return;
     saving=true;byte[] jpeg=jpeg(image);final String found=sku;final String recognized=text;
     storage.execute(()->saveFrame(jpeg,rotation,recognized,found));
    }catch(Exception e){saving=false;message(e.getMessage());}
    finally{image.close();analysing.set(false);closeRetired();if(closing)recognizer.close();}
   });
  }catch(Exception e){image.close();analysing.set(false);closeRetired();message(e.getMessage());}
 }
 private String resolveSku(String text){
  String sku=IntakeText.parse(text).sku;if(!sku.isEmpty())return sku;Set<String> found=new HashSet<>();
  synchronized(knownSkus){for(String line:IntakeText.normalize(text).split("\\r?\\n")){String word=line.trim();if(knownSkus.contains(word))found.add(word);}}
  return found.size()==1?found.iterator().next():"";
 }
 private void saveFrame(byte[] bytes,int rotation,String text,String sku){
  Bitmap frame=null,upright=null;
  try{
   frame=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(frame==null)throw new IOException("촬영 사진을 읽지 못했습니다.");
   if(rotation==0)upright=frame;else{Matrix matrix=new Matrix();matrix.postRotate(rotation);upright=Bitmap.createBitmap(frame,0,0,frame.getWidth(),frame.getHeight(),matrix,true);}
   CaptureInbox.Entry entry=inbox.save(upright,null,text,mode,generation,targetWorkId,sku);
   synchronized(gate){gate.saved(sku);}lastSavedId=entry.id;
   main.post(()->{if(active&&!closing){status.setText((entry.sku.isEmpty()?"사진":entry.sku)+" 저장 완료 · 다음 택을 비춰 주세요.");beep();}});
   saving=false;
   if(mode.equals("intake")&&entry.ready){CaptureInbox.Entry result=inbox.submit(entry.id,client);if(lastSavedId.equals(result.id))message(result.sku+" · "+result.message);}
   else if(mode.equals("lookup")&&!entry.sku.isEmpty()){String stock=client.stockSummary(entry.sku);if(lastSavedId.equals(entry.id))message(entry.sku+"\n"+stock+"\n다음 택을 비춰 주세요.");}
   else message("사진 저장됨 · 사이즈별 수량은 저장 목록에서 확인해 주세요.");
  }catch(Exception e){message("사진 또는 입고 확인 필요: "+(e.getMessage()==null?"다시 시도해 주세요.":e.getMessage()));}
  finally{saving=false;if(upright!=null&&upright!=frame)upright.recycle();if(frame!=null)frame.recycle();}
 }
 private static byte[] jpeg(Image image)throws IOException{
  int width=image.getWidth(),height=image.getHeight();byte[] nv21=new byte[width*height*3/2];Image.Plane[] planes=image.getPlanes();int cursor=0;
  ByteBuffer y=planes[0].getBuffer();int yStart=y.position();
  for(int row=0;row<height;row++)for(int col=0;col<width;col++)nv21[cursor++]=y.get(yStart+row*planes[0].getRowStride()+col*planes[0].getPixelStride());
  ByteBuffer u=planes[1].getBuffer(),v=planes[2].getBuffer();int us=u.position(),vs=v.position();
  for(int row=0;row<height/2;row++)for(int col=0;col<width/2;col++){nv21[cursor++]=v.get(vs+row*planes[2].getRowStride()+col*planes[2].getPixelStride());nv21[cursor++]=u.get(us+row*planes[1].getRowStride()+col*planes[1].getPixelStride());}
  ByteArrayOutputStream out=new ByteArrayOutputStream();if(!new YuvImage(nv21,ImageFormat.NV21,width,height,null).compressToJpeg(new Rect(0,0,width,height),90,out))throw new IOException("촬영 사진을 저장하지 못했습니다.");return out.toByteArray();
 }
 private void beep(){try{ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_MUSIC,80);tone.startTone(ToneGenerator.TONE_PROP_ACK,160);main.postDelayed(tone::release,250);}catch(RuntimeException ignored){/* The visible saved state remains available with audio muted/unavailable. */}}
 private void message(String text){main.post(()->{if(!closing&&!isFinishing())status.setText(text==null?"다시 확인해 주세요.":text);});}
 private synchronized void closeCamera(){
  openingCamera=false;epoch++;if(session!=null){session.close();session=null;}if(camera!=null){camera.close();camera=null;}
  if(previewSurface!=null){previewSurface.release();previewSurface=null;}
  if(reader!=null){retiring.add(reader);reader=null;}if(!analysing.get())closeRetired();
 }
 private synchronized void closeRetired(){for(ImageReader old:retiring)old.close();retiring.clear();}
 @Override protected void onDestroy(){closing=true;active=false;closeCamera();storage.shutdown();if(!analysing.get())recognizer.close();cameraThread.quitSafely();super.onDestroy();}
}
