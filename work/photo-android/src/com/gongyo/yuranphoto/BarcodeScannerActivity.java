package com.gongyo.yuranphoto;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera frames are decoded on this device. The result is identity text, never a stock operation. */
@SuppressWarnings("deprecation")
public final class BarcodeScannerActivity extends Activity implements SurfaceHolder.Callback {
 public static final String EXTRA_BARCODE="barcode";
 private static final int CAMERA_PERMISSION=31,MAX_PIXELS=1280*960;
 private final ExecutorService decoder=Executors.newSingleThreadExecutor();
 private final AtomicBoolean decoding=new AtomicBoolean();
 private SurfaceView surface;private FrameLayout frame;private TextView instruction;
 private Camera camera;private boolean resumed,surfaceReady,permissionPending,permissionAsked,completed;private int cameraEpoch,previewWidth,previewHeight,displayOrientation;private long lastDecode,lastFocus;
 @Override public void onCreate(Bundle state){super.onCreate(state);setResult(RESULT_CANCELED);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(16,16,16,16);instruction=new TextView(this);instruction.setTextSize(18);instruction.setText("바코드 전체가 화면 안에 보이도록 가까이 비춰 주세요. 스캔은 품번·색상 찾기에만 사용합니다.");body.addView(instruction);frame=new FrameLayout(this);surface=new SurfaceView(this);frame.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));body.addView(frame,new LinearLayout.LayoutParams(-1,0,1));Button retry=new Button(this);retry.setText("카메라 권한 / 다시 시작");retry.setOnClickListener(v->{permissionAsked=false;openCamera();});body.addView(retry);Button close=new Button(this);close.setText("취소");close.setOnClickListener(v->finish());body.addView(close);setContentView(body);surface.getHolder().addCallback(this);}
 @Override protected void onResume(){super.onResume();resumed=true;openCamera();}
 @Override protected void onPause(){resumed=false;closeCamera();super.onPause();}
 @Override protected void onDestroy(){closeCamera();decoder.shutdownNow();super.onDestroy();}
 @Override public void surfaceCreated(SurfaceHolder holder){surfaceReady=true;openCamera();}
 @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){fitPreview();}
 @Override public void surfaceDestroyed(SurfaceHolder holder){surfaceReady=false;closeCamera();}
 @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code!=CAMERA_PERMISSION)return;permissionPending=false;if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)openCamera();else instruction.setText("바코드 촬영에는 카메라 권한이 필요합니다. 권한을 허용하거나 취소해 주세요.");}
 private void openCamera(){if(!resumed||completed||isFinishing()||camera!=null)return;if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){if(!permissionPending&&!permissionAsked){permissionPending=true;permissionAsked=true;requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);}return;}if(!surfaceReady)return;Camera opened=null;try{int id=findCamera();Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(id,info);opened=Camera.open(id);Camera.Parameters params=opened.getParameters();Camera.Size size=chooseSize(params.getSupportedPreviewSizes());if(size==null)throw new IOException("지원되는 카메라 화면 크기를 찾지 못했습니다.");params.setPreviewSize(size.width,size.height);List<Integer> formats=params.getSupportedPreviewFormats();if(formats!=null&&!formats.contains(ImageFormat.NV21))throw new IOException("이 카메라의 바코드 촬영 형식을 지원하지 않습니다.");params.setPreviewFormat(ImageFormat.NV21);List<String> focus=params.getSupportedFocusModes();if(focus!=null){if(focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);else if(focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);else if(focus.contains(Camera.Parameters.FOCUS_MODE_AUTO))params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);}opened.setParameters(params);Camera.Size actual=opened.getParameters().getPreviewSize();previewWidth=actual.width;previewHeight=actual.height;if((long)previewWidth*previewHeight>MAX_PIXELS)throw new IOException("카메라 화면 크기가 너무 큽니다.");int rotation=getWindowManager().getDefaultDisplay().getRotation();int degrees=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;displayOrientation=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT?(360-(info.orientation+degrees)%360)%360:(info.orientation-degrees+360)%360;opened.setDisplayOrientation(displayOrientation);opened.setPreviewDisplay(surface.getHolder());camera=opened;cameraEpoch++;lastDecode=0;lastFocus=0;int bytes=previewWidth*previewHeight*3/2;opened.addCallbackBuffer(new byte[bytes]);opened.addCallbackBuffer(new byte[bytes]);opened.setPreviewCallbackWithBuffer(this::onFrame);opened.startPreview();fitPreview();instruction.setText("바코드 전체와 양쪽 여백이 보이도록 비춰 주세요. 앞자리 0도 그대로 읽습니다.");}catch(IOException|RuntimeException e){if(opened!=null&&opened!=camera)try{opened.release();}catch(RuntimeException ignored){}closeCamera();instruction.setText("카메라를 시작하지 못했습니다. 다시 시작하거나 취소해 주세요.");}}
 private int findCamera()throws IOException{int count=Camera.getNumberOfCameras();if(count==0)throw new IOException("카메라가 없습니다.");for(int i=0;i<count;i++){Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK)return i;}return 0;}
 private Camera.Size chooseSize(List<Camera.Size> sizes){Camera.Size best=null;long score=Long.MAX_VALUE;if(sizes!=null)for(Camera.Size size:sizes){long pixels=(long)size.width*size.height;if(size.width<=0||size.height<=0||pixels>MAX_PIXELS)continue;long candidate=Math.abs(pixels-640L*480);if(candidate<score){best=size;score=candidate;}}return best;}
 private void fitPreview(){if(camera==null||frame.getWidth()==0||frame.getHeight()==0)return;int width=displayOrientation%180==0?previewWidth:previewHeight,height=displayOrientation%180==0?previewHeight:previewWidth;float scale=Math.min((float)frame.getWidth()/width,(float)frame.getHeight()/height);int fittedWidth=Math.max(1,Math.round(width*scale)),fittedHeight=Math.max(1,Math.round(height*scale));FrameLayout.LayoutParams current=(FrameLayout.LayoutParams)surface.getLayoutParams();if(current.width!=fittedWidth||current.height!=fittedHeight)surface.setLayoutParams(new FrameLayout.LayoutParams(fittedWidth,fittedHeight,Gravity.CENTER));}
 private void closeCamera(){cameraEpoch++;Camera old=camera;camera=null;if(old!=null){try{old.setPreviewCallbackWithBuffer(null);}catch(RuntimeException ignored){}try{old.stopPreview();}catch(RuntimeException ignored){}try{old.release();}catch(RuntimeException ignored){}}}
 private void onFrame(byte[] bytes,Camera owner){if(owner!=camera||!resumed||completed)return;long now=SystemClock.elapsedRealtime();if(now-lastFocus>1800){lastFocus=now;try{if(Camera.Parameters.FOCUS_MODE_AUTO.equals(owner.getParameters().getFocusMode()))owner.autoFocus((success,current)->{});}catch(RuntimeException ignored){}}if(bytes==null||now-lastDecode<200||!decoding.compareAndSet(false,true)){recycle(owner,bytes);return;}lastDecode=now;int width=previewWidth,height=previewHeight,expected=cameraEpoch;byte[] luminance=Arrays.copyOf(bytes,width*height);recycle(owner,bytes);try{decoder.execute(()->{String value=null;try{value=decode(luminance,width,height);}finally{decoding.set(false);}final String decoded=value;if(decoded!=null)runOnUiThread(()->{if(resumed&&!completed&&!isFinishing()&&owner==camera&&expected==cameraEpoch){completed=true;setResult(RESULT_OK,new Intent().putExtra(EXTRA_BARCODE,decoded));closeCamera();finish();}});});}catch(RejectedExecutionException e){decoding.set(false);}}
 private void recycle(Camera owner,byte[] bytes){if(owner==camera&&bytes!=null)try{owner.addCallbackBuffer(bytes);}catch(RuntimeException ignored){}}
 private String decode(byte[] bytes,int width,int height){MultiFormatReader reader=new MultiFormatReader();EnumMap<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);hints.put(DecodeHintType.POSSIBLE_FORMATS,Arrays.asList(BarcodeFormat.EAN_13,BarcodeFormat.EAN_8,BarcodeFormat.UPC_E,BarcodeFormat.CODE_128,BarcodeFormat.CODE_39,BarcodeFormat.CODE_93,BarcodeFormat.ITF,BarcodeFormat.CODABAR,BarcodeFormat.RSS_14,BarcodeFormat.RSS_EXPANDED,BarcodeFormat.QR_CODE,BarcodeFormat.DATA_MATRIX,BarcodeFormat.PDF_417,BarcodeFormat.AZTEC));reader.setHints(hints);byte[] current=bytes;int w=width,h=height;for(int turn=0;turn<4&&!Thread.currentThread().isInterrupted();turn++){LuminanceSource source=new PlanarYUVLuminanceSource(current,w,h,0,0,w,h,false);for(int inverse=0;inverse<2;inverse++){try{Result result=reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(inverse==0?source:source.invert())));String text=result.getText();if(text!=null&&!text.isEmpty())return text;}catch(NotFoundException ignored){}finally{reader.reset();}}if(turn<3){byte[] rotated=new byte[current.length];for(int y=0;y<h;y++)for(int x=0;x<w;x++)rotated[x*h+h-1-y]=current[y*w+x];current=rotated;int swap=w;w=h;h=swap;}}return null;}
}
