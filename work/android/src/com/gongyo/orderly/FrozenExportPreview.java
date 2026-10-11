package com.gongyo.orderly;

import android.app.*;
import android.graphics.*;
import android.os.*;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Shows exactly the row/photo snapshot that the subsequent XLSX writer consumes. */
final class FrozenExportPreview {
 private FrozenExportPreview() {}
 static final class Handle implements AutoCloseable {
  private Runnable cleanup;
  Handle(Runnable cleanup){this.cleanup=cleanup;}
  public void close(){if(cleanup!=null){Runnable action=cleanup;cleanup=null;action.run();}}
 }
 static Handle show(Activity activity,ExportSnapshot input,MediaSource media,Runnable proceed,Runnable cancel)throws IOException {
  final ExportSnapshot frozen=ExportSnapshot.decode(ExportSnapshot.encode(input));
  final ExecutorService worker=Executors.newSingleThreadExecutor();
  final Handler main=new Handler(Looper.getMainLooper());
  final boolean[] closed={false};
  final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(4*1024*1024){
   @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}
  };
  final Set<String> loading=new HashSet<>(),failed=new HashSet<>();
  final List<Integer> rows=new ArrayList<>();
  for(int i=0;i<frozen.jobCopy.rows.size();i++)if(frozen.jobCopy.rows.get(i).total()>0)rows.add(i);
  LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);
  int padding=Math.round(12*activity.getResources().getDisplayMetrics().density);root.setPadding(padding,padding,padding,padding);
  TextView summary=new TextView(activity);summary.setTextSize(16);
  summary.setText(rows.size()+"행 · 엑셀에 들어갈 사진을 확인하고 있습니다.");root.addView(summary);
  ListView list=new ListView(activity);
  root.addView(list,new LinearLayout.LayoutParams(-1,Math.round(380*activity.getResources().getDisplayMetrics().density)));
  AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("엑셀 사진 미리보기").setView(root)
   .setPositiveButton("계속",(d,w)->proceed.run()).setNegativeButton("취소",(d,w)->cancel.run())
   .setOnCancelListener(d->cancel.run()).create();
  BaseAdapter adapter=new BaseAdapter(){
   public int getCount(){return rows.size();}
   public Object getItem(int position){return frozen.jobCopy.rows.get(rows.get(position));}
   public long getItemId(int position){return position;}
   public View getView(int position,View recycled,ViewGroup parent){
    int index=rows.get(position);Inventory.Row row=frozen.jobCopy.rows.get(index);String hash=frozen.photoVersions.get(index);
    LinearLayout item=new LinearLayout(activity);item.setPadding(0,padding,0,padding);item.setGravity(Gravity.CENTER_VERTICAL);
    ImageView picture=new ImageView(activity);picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
    int edge=padding*7;item.addView(picture,new LinearLayout.LayoutParams(edge,edge));
    TextView text=new TextView(activity);text.setPadding(padding,0,0,0);text.setTextSize(16);
    String photoState;
    if(hash.isEmpty())photoState=row.binding!=null&&row.binding.needsConfirmation?"사진 연결 확인 필요":"등록된 사진 없음";
    else if(failed.contains(hash))photoState="사진을 읽지 못했습니다.";
    else {Bitmap bitmap=cache.get(hash);if(bitmap!=null){picture.setImageBitmap(bitmap);photoState="사진 포함";}
     else {photoState="사진 불러오는 중";if(loading.add(hash))worker.execute(()->{
      Bitmap result=null;
      try(InputStream in=media.open(hash)){
       PhotoCatalog.Asset asset=frozen.assets.get(hash);BitmapFactory.Options options=new BitmapFactory.Options();
       options.inSampleSize=1;while(Math.max(asset.width,asset.height)/options.inSampleSize>320)options.inSampleSize*=2;
       result=BitmapFactory.decodeStream(in,null,options);if(result==null)throw new IOException("사진 해독 실패");
      }catch(Exception|OutOfMemoryError problem){result=null;}
      final Bitmap decoded=result;
      main.post(()->{if(closed[0]){if(decoded!=null)decoded.recycle();return;}loading.remove(hash);
       if(decoded==null){failed.add(hash);summary.setText("사진을 읽지 못했습니다. 사진 연결을 확인한 뒤 다시 내보내 주세요.");dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);}
       else cache.put(hash,decoded);notifyDataSetChanged();
      });
     });}
    }
    text.setText(row.sku+" · "+row.color+"\nS "+row.quantities[0]+"   M "+row.quantities[1]+"   L "+row.quantities[2]+"   XL "+row.quantities[3]+"\n합계 "+row.total()+" · "+photoState);
    item.addView(text,new LinearLayout.LayoutParams(0,-2,1));return item;
   }
  };
  list.setAdapter(adapter);
  Handle handle=new Handle(()->{closed[0]=true;worker.shutdownNow();cache.evictAll();dialog.dismiss();});
  dialog.setOnDismissListener(d->handle.close());
  dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
  worker.execute(()->{
   String error=null;try{frozen.verifyMedia(media);}catch(Exception problem){error=problem.getMessage();}
   final String failure=error;main.post(()->{if(closed[0])return;
    if(failure==null&&failed.isEmpty()){summary.setText(rows.size()+"행 · 이 사진과 수량으로 엑셀을 만듭니다.");dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);}
    else {summary.setText("사진 확인 필요: "+(failure==null?"사진을 읽지 못했습니다.":failure));dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);}
   });
  });
  return handle;
 }
}
