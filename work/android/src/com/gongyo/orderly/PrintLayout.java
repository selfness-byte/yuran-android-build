package com.gongyo.orderly;

import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import java.io.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import java.util.ArrayList;
import java.util.List;

/** One measured layout shared by native print and direct PDF export. Units are PDF points. */
final class PrintLayout {
    private static final float PAD = 6f, LINE = 19f, ROW_PAD = 10f;
    private static final float PHOTO_BOX=mmToPoints(22f), MIN_ROW=mmToPoints(26f);
    static float mmToPoints(float mm) { return mm*72f/25.4f; }
    private static final int INK = Color.rgb(27, 39, 54);
    private final Inventory.Job job;
    private final Rect area;
    private final ExportSnapshot snapshot;
    private final MediaSource media;
    private final List<Integer> rowIndices=new ArrayList<Integer>();
    private final Paint photoPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint body = textPaint(14.5f, false);
    private final Paint bold = textPaint(14.5f, true);
    private final Paint title = textPaint(20f, true);
    private final Paint small = textPaint(10f, false);
    private final Paint background = new Paint();
    private final Paint border = new Paint();
    private final float[] edges = new float[9];
    private final List<String> titleLines;
    private final List<List<List<String>>> rows = new ArrayList<List<List<String>>>();
    private final List<List<String>> totalCells = new ArrayList<List<String>>();
    private final List<List<PagePlanner.RowPlacement>> pages;
    private final float tableTop, bodyTop, totalsTop, totalHeight;

    PrintLayout(ExportSnapshot snapshot,Rect contentRect,MediaSource media) {
        this(snapshot.jobCopy,contentRect,snapshot,media);
    }
    PrintLayout(Inventory.Job job, Rect contentRect) { this(job,contentRect,null,null); }
    private PrintLayout(Inventory.Job job,Rect contentRect,ExportSnapshot snapshot,MediaSource media) {
        this.snapshot=snapshot;this.media=media;
        XlsxWriter.validate(job);
        this.job = job; this.area = new Rect(contentRect);
        if (area.width() < 450 || area.height() < 250) throw new IllegalArgumentException("인쇄 영역이 너무 작습니다. A4 용지와 여백을 확인해 주세요.");
        edges[1]=PHOTO_BOX+2*PAD;
        edges[2]=edges[1]+area.width()*.28f;
        edges[3]=edges[2]+area.width()*.15f;
        float quantityWidth=(area.width()-edges[3])/5f;
        for(int c=3;c<8;c++)edges[c+1]=edges[c]+quantityWidth;
        edges[8]=area.width();
        titleLines = wrap(XlsxWriter.safeTitle(job), title, area.width()-2*PAD);
        if(titleLines.size()*25 > area.height()*.30f) throw new IllegalArgumentException("작업 제목이 너무 깁니다. 제목을 짧게 바꿔 주세요.");
        tableTop = titleLines.size()*25 + 27;
        bodyTop = tableTop+32;
        long[] totals = Inventory.totals(job.rows);
        totalCells.add(wrap("전체 합계",bold,edges[3]-2*PAD));
        int totalLines=1;
        for(int c=0;c<5;c++) {
            long value=c==4?Inventory.grandTotal(job.rows):totals[c];
            List<String> lines=wrap(Long.toString(value),bold,edges[c+4]-edges[c+3]-2*PAD);
            totalCells.add(lines); totalLines=Math.max(totalLines,lines.size());
        }
        totalHeight=Math.max(36,totalLines*LINE+ROW_PAD);
        totalsTop=area.height()-20-totalHeight;
        List<Float> heights=new ArrayList<Float>();
        for(int i=0;i<job.rows.size();i++) {
            Inventory.Row row=job.rows.get(i);
            if(row.total()==0)continue;
            List<List<String>> cells=new ArrayList<List<String>>();int lines=1;
            String[] values={"",row.sku,row.color,Long.toString(row.quantities[0]),Long.toString(row.quantities[1]),Long.toString(row.quantities[2]),Long.toString(row.quantities[3]),Long.toString(row.total())};
            for(int c=0;c<8;c++) { List<String> wrapped=wrap(values[c],body,edges[c+1]-edges[c]-2*PAD);cells.add(wrapped);lines=Math.max(lines,wrapped.size()); }
            rows.add(cells);rowIndices.add(i);heights.add(Math.max(MIN_ROW,lines*LINE+ROW_PAD));
        }
        if(rows.isEmpty())throw new IllegalArgumentException("출력할 품목이 없습니다.");
        float[] rowHeights=new float[heights.size()];for(int i=0;i<heights.size();i++)rowHeights[i]=heights.get(i);
        pages=PagePlanner.planRows(rowHeights,totalsTop-bodyTop-8);
        border.setColor(INK);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(1.05f);
    }

    int pageCount() { return pages.size(); }

    void draw(Canvas canvas,int pageIndex) throws IOException {
        canvas.save();
        try {
            canvas.translate(area.left,area.top);
            canvas.clipRect(0,0,area.width(),area.height());
            float y=2;
            for(String line:titleLines) { drawLine(canvas,line,PAD,y,area.width()-2*PAD,title,false);y+=25; }
            List<PagePlanner.RowPlacement> placements=pages.get(pageIndex);
            String detail="전체 "+rows.size()+"행 · "+(pageIndex+1)+" / "+pages.size()+"쪽";
            drawLine(canvas,detail,PAD,tableTop-19,area.width()-2*PAD,small,false);
            drawGrid(canvas,tableTop,32,Color.rgb(38,53,72),false);
            String[] labels={"사진","품번","색상","S","M","L","XL","합계"};
            bold.setColor(Color.WHITE);
            for(int c=0;c<8;c++) drawLine(canvas,labels[c],edges[c]+PAD,tableTop+(32-LINE)/2,edges[c+1]-edges[c]-2*PAD,bold,true);
            bold.setColor(INK);
            y=bodyTop;
            for(PagePlanner.RowPlacement placement:placements) {
                drawGrid(canvas,y,placement.height,placement.row%2==0?Color.WHITE:Color.rgb(230,235,241),false);
                List<List<String>> cells=rows.get(placement.row);
                for(int c=1;c<8;c++) {
                    List<String> lines=cells.get(c);
                    float textTop=y+(placement.height-lines.size()*LINE)/2;
                    for(int n=0;n<lines.size();n++)drawLine(canvas,lines.get(n),edges[c]+PAD,textTop+n*LINE,edges[c+1]-edges[c]-2*PAD,body,c>=2);
                }
                drawPhoto(canvas,rowIndices.get(placement.row),y,placement.height);
                y+=placement.height;
            }
            drawGrid(canvas,totalsTop,totalHeight,Color.rgb(207,218,231),true);
            for(int cell=0;cell<6;cell++) {
                float left=cell==0?0:edges[cell+2];
                float right=cell==0?edges[3]:edges[cell+3];
                List<String> lines=totalCells.get(cell);
                float top=totalsTop+(totalHeight-lines.size()*LINE)/2;
                for(int n=0;n<lines.size();n++) drawLine(canvas,lines.get(n),left+PAD,top+n*LINE,right-left-2*PAD,bold,true);
            }
            drawLine(canvas,"전체 합계는 모든 페이지의 품목을 합산한 값입니다.",PAD,area.height()-15,area.width()-2*PAD,small,false);
        } finally { canvas.restore(); }
    }
    private void drawPhoto(Canvas canvas,int row,float top,float height)throws IOException {
        if(snapshot==null)return;
        String hash=snapshot.photoVersions.get(row);if(hash.isEmpty())return;
        if(media==null)throw new IOException("Missing pinned media source");
        media.checkReady();PhotoCatalog.Asset asset=snapshot.assets.get(hash);
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
        // Decode one registered image at a time near its 300dpi printed size, never a bitmap list.
        int desired=(int)Math.ceil(PHOTO_BOX*300f/72f);
        while(Math.max(asset.width,asset.height)/(options.inSampleSize*2)>=desired)options.inSampleSize*=2;
        Bitmap bitmap=null;MessageDigest digest=MediaStore.digest();
        try(InputStream raw=media.open(hash)) {
            if(raw==null)throw new IOException("Missing export photo");
            InputStream bounded=new FilterInputStream(raw) {
                private long count;
                private void counted(int n)throws IOException { if(n>0){count+=n;if(count>asset.byteLength)throw new IOException("Export photo length");} }
                @Override public int read()throws IOException { int value=in.read();if(value>=0)counted(1);return value; }
                @Override public int read(byte[] b,int offset,int length)throws IOException { int n=in.read(b,offset,length);counted(n);return n; }
                @Override public long skip(long n)throws IOException { long skipped=0;byte[] buffer=new byte[4096];while(skipped<n){int read=read(buffer,0,(int)Math.min(buffer.length,n-skipped));if(read<0)break;skipped+=read;}return skipped; }
            };
            try(DigestInputStream input=new DigestInputStream(bounded,digest)) {
                bitmap=BitmapFactory.decodeStream(input,null,options);
                byte[] drain=new byte[32768];while(input.read(drain)!=-1){}
            }
            if(bitmap==null || !hash.equals(MediaStore.hex(digest.digest())) ||
               bitmap.getWidth()!=(asset.width+options.inSampleSize-1)/options.inSampleSize ||
               bitmap.getHeight()!=(asset.height+options.inSampleSize-1)/options.inSampleSize)
                throw new IOException("Export photo decode/hash/dimensions");
            float scale=Math.min(PHOTO_BOX/asset.width,PHOTO_BOX/asset.height);
            float w=asset.width*scale,h=asset.height*scale;
            float left=(edges[1]-w)/2,photoTop=top+(height-h)/2;
            canvas.drawBitmap(bitmap,null,new RectF(left,photoTop,left+w,photoTop+h),photoPaint);
        } finally { if(bitmap!=null)bitmap.recycle(); }
    }
    private void drawGrid(Canvas canvas,float top,float height,int fill,boolean mergeFirst) {
        background.setColor(fill);background.setStyle(Paint.Style.FILL);
        canvas.drawRect(.6f,top,area.width()-.6f,top+height,background);
        canvas.drawRect(.6f,top,area.width()-.6f,top+height,border);
        for(int c=1;c<8;c++) if(!mergeFirst || c>2) canvas.drawLine(edges[c],top,edges[c],top+height,border);
    }
    private static Paint textPaint(float size,boolean bold) {
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(INK);paint.setTextSize(size);paint.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        return paint;
    }
    private static void drawLine(Canvas canvas,String text,float x,float top,float width,Paint paint,boolean center) {
        float offset=center?Math.max(0,(width-paint.measureText(text))/2):0;
        canvas.drawText(text,x+offset,top-paint.ascent(),paint);
    }
    private static List<String> wrap(String value,Paint paint,float width) {
        String text=value==null?"":value.replace("\r\n","\n").replace('\r','\n').replace('\t',' ');
        List<String> result=new ArrayList<String>();
        for(String paragraph:text.split("\n",-1)) {
            if(paragraph.length()==0) { result.add("");continue; }
            int start=0;
            while(start<paragraph.length()) {
                int end=start;
                while(end<paragraph.length()) {
                    int next=end+Character.charCount(paragraph.codePointAt(end));
                    if(paint.measureText(paragraph,start,next)>width && end>start)break;
                    end=next;
                }
                result.add(paragraph.substring(start,end));start=end;
            }
        }
        return result;
    }
}
