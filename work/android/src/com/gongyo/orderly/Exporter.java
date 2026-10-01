package com.gongyo.orderly;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.print.pdf.PrintedPdfDocument;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public final class Exporter {
    private Exporter() {}
    public static void writeXlsx(OutputStream output,Inventory.Job job) throws IOException { XlsxWriter.write(output,snapshot(job)); }

    public static void writePdf(Context context,OutputStream output,Inventory.Job job) throws IOException {
        Inventory.Job copy=snapshot(job);
        PrintedPdfDocument pdf=new PrintedPdfDocument(context,defaultAttributes());
        try {
            PrintLayout layout=new PrintLayout(copy,pdf.getPageContentRect());
            for(int index=0;index<layout.pageCount();index++) drawPage(pdf,layout,index);
            pdf.writeTo(output);
        } finally { pdf.close(); }
    }

    public static void writeXlsx(OutputStream output,ExportSnapshot frozen,MediaSource source)throws IOException {XlsxWriter.write(output,frozen,source);}
    public static void writePdf(Context context,OutputStream output,ExportSnapshot frozen,MediaSource source)throws IOException {
        frozen=ExportSnapshot.decode(ExportSnapshot.encode(frozen));frozen.verifyMedia(source);
        PrintedPdfDocument pdf=new PrintedPdfDocument(context,defaultAttributes());
        try {
            PrintLayout layout=new PrintLayout(frozen,pdf.getPageContentRect(),source);
            for(int index=0;index<layout.pageCount();index++)drawPage(pdf,layout,index);
            pdf.writeTo(output);
        } finally { pdf.close(); }
    }
    public static String printSummary(ExportSnapshot frozen){return printSummary(frozen.jobCopy);}
    public static void print(Activity activity,ExportSnapshot frozen,MediaSource source){print(activity,frozen,source,()->{});}
    public static void print(Activity activity,ExportSnapshot frozen,MediaSource source,Runnable finished) {
        try{frozen=ExportSnapshot.decode(ExportSnapshot.encode(frozen));frozen.verifyMedia(source);}catch(IOException failure){throw new IllegalStateException(failure);}
        PrintManager manager=(PrintManager)activity.getSystemService(Context.PRINT_SERVICE);
        if(manager==null)throw new IllegalStateException("이 기기에서 인쇄 기능을 사용할 수 없습니다.");
        manager.print(XlsxWriter.safeTitle(frozen.jobCopy),new Adapter(activity,frozen,source,finished),defaultAttributes());
    }

    public static String printSummary(Inventory.Job job) {
        Inventory.Job copy=snapshot(job);
        int pages=new PrintLayout(copy,new Rect(18,18,577,824)).pageCount();
        return "A4 세로 약 "+pages+"쪽 · 본문 14.5pt\n긴 품번은 줄바꿈됩니다. 실제 쪽수는 인쇄 설정에 따라 달라집니다.";
    }

    public static void print(Activity activity,Inventory.Job job) {
        Inventory.Job copy=snapshot(job);
        PrintManager manager=(PrintManager)activity.getSystemService(Context.PRINT_SERVICE);
        if(manager==null)throw new IllegalStateException("이 기기에서 인쇄 기능을 사용할 수 없습니다.");
        manager.print(XlsxWriter.safeTitle(copy),new Adapter(activity,copy),defaultAttributes());
    }

    private static PrintAttributes defaultAttributes() {
        return new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(new PrintAttributes.Resolution("pdf","PDF",300,300))
                .setMinMargins(new PrintAttributes.Margins(250,250,250,250))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR).build();
    }

    private static Inventory.Job snapshot(Inventory.Job job) {
        XlsxWriter.validate(job);
        return job.copy();
    }

    private static void drawPage(PrintedPdfDocument pdf,PrintLayout layout,int index) throws IOException {
        // A full-page canvas applies the selected printer content rectangle exactly once in draw().
        PdfDocument.PageInfo info=new PdfDocument.PageInfo.Builder(pdf.getPageWidth(),pdf.getPageHeight(),index).create();
        PdfDocument.Page page=pdf.startPage(info);
        try { layout.draw(page.getCanvas(),index); } finally { pdf.finishPage(page); }
    }

    private static final class Adapter extends PrintDocumentAdapter {
        private final Context context;
        private final Inventory.Job job;
        private final ExportSnapshot frozen;private final MediaSource source;private final Runnable finished;private boolean failed;
        private PrintAttributes attributes;
        private PrintLayout layout;
        Adapter(Context context,Inventory.Job job) { this.context=context;this.job=job;this.frozen=null;this.source=null;this.finished=()->{}; }
        Adapter(Context context,ExportSnapshot frozen,MediaSource source,Runnable finished){this.context=context;this.job=frozen.jobCopy;this.frozen=frozen;this.source=source;this.finished=finished;}
        private void ready()throws IOException {if(frozen!=null)frozen.verifyMedia(source);}
        @Override public void onFinish(){if(!failed)finished.run();}

        @Override public void onLayout(PrintAttributes oldAttributes,PrintAttributes newAttributes,CancellationSignal cancellation,LayoutResultCallback callback,Bundle extras) {
            if(cancellation.isCanceled()){callback.onLayoutCancelled();return;}
            PrintedPdfDocument measurement=null;
            try {
                ready();failed=false;
                measurement=new PrintedPdfDocument(context,newAttributes);
                PrintLayout nextLayout=frozen==null?new PrintLayout(job,measurement.getPageContentRect()):new PrintLayout(frozen,measurement.getPageContentRect(),source);
                if(cancellation.isCanceled()){callback.onLayoutCancelled();return;}
                attributes=newAttributes;layout=nextLayout;
                PrintDocumentInfo info=new PrintDocumentInfo.Builder(XlsxWriter.safeTitle(job)+".pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(layout.pageCount()).build();
                callback.onLayoutFinished(info,!newAttributes.equals(oldAttributes));
            } catch(IOException | RuntimeException error) { failed=true;layout=null;callback.onLayoutFailed(message(error)); }
            finally { if(measurement!=null)measurement.close(); }
        }
        @Override public void onWrite(PageRange[] requested,ParcelFileDescriptor destination,CancellationSignal cancellation,WriteResultCallback callback) {
            if(cancellation.isCanceled()){callback.onWriteCancelled();return;}
            if(layout==null || attributes==null){failed=true;callback.onWriteFailed("인쇄 레이아웃을 다시 선택해 주세요.");return;}
            PrintedPdfDocument pdf=new PrintedPdfDocument(context,attributes);
            List<Integer> written=new ArrayList<Integer>();
            try {
                ready();failed=false;
                for(int index=0;index<layout.pageCount();index++) {
                    if(cancellation.isCanceled()){callback.onWriteCancelled();return;}
                    if(inRanges(index,requested)){drawPage(pdf,layout,index);written.add(index);}
                }
                if(written.isEmpty()){failed=true;callback.onWriteFailed("선택한 페이지가 없습니다.");return;}
                try(FileOutputStream file=new FileOutputStream(destination.getFileDescriptor())) {
                    OutputStream checked=new OutputStream() {
                        @Override public void write(int value)throws IOException {if(cancellation.isCanceled())throw new IOException("cancelled");file.write(value);}
                        @Override public void write(byte[] bytes,int offset,int length)throws IOException {if(cancellation.isCanceled())throw new IOException("cancelled");file.write(bytes,offset,length);}
                    };
                    pdf.writeTo(checked);
                }
                if(cancellation.isCanceled())callback.onWriteCancelled();else callback.onWriteFinished(ranges(written));
            } catch(IOException | RuntimeException error) {
                failed=true;
                if(cancellation.isCanceled())callback.onWriteCancelled();else callback.onWriteFailed(message(error));
            } finally { pdf.close(); }
        }
    }
    private static String message(Exception error) { return error.getMessage()==null?"파일을 만들지 못했습니다.":error.getMessage(); }
    private static boolean inRanges(int page,PageRange[] ranges) { if(ranges==null)return true;for(PageRange range:ranges)if(page>=range.getStart() && page<=range.getEnd())return true;return false; }
    private static PageRange[] ranges(List<Integer> pages) {
        List<PageRange> result=new ArrayList<PageRange>();int start=pages.get(0),end=start;
        for(int i=1;i<pages.size();i++) {int page=pages.get(i);if(page==end+1)end=page;else{result.add(new PageRange(start,end));start=end=page;}}
        result.add(new PageRange(start,end));return result.toArray(new PageRange[result.size()]);
    }
}
