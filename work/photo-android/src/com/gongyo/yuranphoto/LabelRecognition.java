package com.gongyo.yuranphoto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Reads a label locally. It never prepares or registers a product photograph. */
public final class LabelRecognition {
    public static final int MAX_TEXT = 12000;
    public static final int MAX_BARCODES = 8;
    public interface Source { InputStream open() throws IOException; }
    public static final class Result {
        public final String text;
        public final ArrayList<String> barcodes;
        Result(String text, ArrayList<String> barcodes) { this.text=text;this.barcodes=barcodes; }
    }
    private LabelRecognition() {}

    /** Native OCR could not finish. Retaining callers must independently decode the picture first. */
    public static final class OcrException extends IOException {
        private static final long serialVersionUID = 1L;
        public OcrException(String message) { super(message); }
        public OcrException(String message, Throwable cause) { super(message, cause); }
    }
    private static final java.util.concurrent.atomic.AtomicBoolean FILE_READING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final long READ_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(12);

    /** Call on a worker; retain the existing bundled Chinese/Latin model compatibility. */
    public static Result read(Context context, Source source) throws IOException { return read(context,source,true); }
    public static Result readText(Context context, Source source) throws IOException { return read(context,source,false); }
    private static Result read(Context context, Source source,boolean scanBarcodes) throws IOException {
        // A timed-out native task keeps this gate until its completion callback releases its bitmap.
        if (!FILE_READING.compareAndSet(false,true))
            throw new OcrException("이전 사진 읽기가 아직 끝나지 않았습니다. 사진을 직접 확인해 주세요.");
        final Bitmap bitmap;
        try { bitmap=decode(source); }
        catch (IOException | RuntimeException | Error failure) { FILE_READING.set(false);throw failure; }
        TextRecognizer recognizer = null;
        Task<Text> task = null;
        long deadline=System.nanoTime()+READ_BUDGET_NANOS;
        try {
            recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            ArrayList<String> barcodes=new ArrayList<>();
            ArrayList<String> candidates=new ArrayList<>();
            for(int angle:com.gongyo.photo.OcrReadPolicy.rotationAngles()){
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)throw new java.util.concurrent.TimeoutException();
                task=recognizer.process(InputImage.fromBitmap(bitmap,angle));
                if(scanBarcodes&&angle==0)barcodes=readBarcodes(bitmap);
                remaining=deadline-System.nanoTime();
                if(remaining<=0)throw new java.util.concurrent.TimeoutException();
                Text recognized=Tasks.await(task,remaining,TimeUnit.NANOSECONDS);
                String text=intakeText(recognized);
                if(text.length()>MAX_TEXT)
                    throw new OcrException("라벨의 글자가 너무 많습니다. 상품 라벨만 가까이 촬영해 주세요.");
                candidates.add(text);
                // A clear first pass remains fast; only incomplete file reads try another orientation.
                if(scanBarcodes?(!text.trim().isEmpty()||!barcodes.isEmpty()):com.gongyo.photo.IntakeText.parse(text).ready)break;
            }
            String text=com.gongyo.photo.OcrReadPolicy.selectText(candidates);
            if(text.length()>MAX_TEXT)
                throw new OcrException("라벨의 글자가 너무 많습니다. 상품 라벨만 가까이 촬영해 주세요.");
            if(text.trim().isEmpty()&&barcodes.isEmpty())
                throw new OcrException("라벨의 글자와 바코드를 읽지 못했습니다. 사진을 직접 확인해 주세요.");
            return new Result(text,barcodes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("라벨 읽기가 중단됐습니다. 다시 촬영해 주세요.",e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new OcrException("라벨 읽기 시간이 초과됐습니다. 사진을 직접 확인해 주세요.",e);
        } catch (java.util.concurrent.ExecutionException | RuntimeException e) {
            throw new OcrException("라벨을 읽지 못했습니다. 사진을 직접 확인해 주세요.",e);
        } finally {
            final TextRecognizer ownedRecognizer=recognizer;
            if(task!=null&&!task.isComplete()){
                // InputImage rotations share this bitmap; no fallback bitmap may be released early.
                task.addOnCompleteListener(Runnable::run,ignored->releaseFileRead(bitmap,ownedRecognizer));
            }else releaseFileRead(bitmap,ownedRecognizer);
        }
    }

    private static void releaseFileRead(Bitmap bitmap,TextRecognizer recognizer){
        try { if(recognizer!=null)recognizer.close(); }
        finally { try { bitmap.recycle(); } finally { FILE_READING.set(false); } }
    }

    static Bitmap decode(Source source) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = source.open()) { BitmapFactory.decodeStream(input, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || (long) bounds.outWidth * bounds.outHeight > 240000000L || bounds.outWidth>30000 || bounds.outHeight>30000) {
            throw new IOException("읽을 수 있는 2억4천만 화소 이하의 라벨 사진을 선택해 주세요.");
        }
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream input = source.open()) {
            orientation = new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) { /* A missing JPEG orientation does not invalidate PNG text. */ }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = com.gongyo.photo.OcrReadPolicy.sampleSize(bounds.outWidth, bounds.outHeight);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap image;
        try (InputStream input = source.open()) { image = BitmapFactory.decodeStream(input, null, options); }
        if (image == null) throw new IOException("라벨 사진을 열지 못했습니다.");
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
            default: return image;
        }
        Bitmap rotated = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), matrix, true);
        if (rotated != image) image.recycle();
        return rotated;
    }


    /** Convert ML Kit lines without depending on Android geometry in the shared assembler. */
    static String intakeText(Text recognized) {
        java.util.List<com.gongyo.photo.LabelTextLayout.Line> lines=new ArrayList<>();
        for(Text.TextBlock block:recognized.getTextBlocks())for(Text.Line line:block.getLines()){
            android.graphics.Rect box=line.getBoundingBox();
            lines.add(box==null?new com.gongyo.photo.LabelTextLayout.Line(line.getText())
                    :new com.gongyo.photo.LabelTextLayout.Line(line.getText(),box.left,box.top,box.right,box.bottom));
        }
        return com.gongyo.photo.LabelTextLayout.intakeText(lines);
    }

    private static ArrayList<String> readBarcodes(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        byte[] luminance = new RGBLuminanceSource(width, height, pixels).getMatrix();
        pixels = null;
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        for (int turn = 0; turn < 4; turn++) {
            LuminanceSource source = new PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false);
            for (int inverse = 0; inverse < 2; inverse++) {
                MultiFormatReader reader = new MultiFormatReader();
                try {
                    BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(inverse == 0 ? source : source.invert()));
                    com.google.zxing.Result[] results = new GenericMultipleBarcodeReader(reader).decodeMultiple(binary, hints);
                    for (com.google.zxing.Result result : results) {
                        try { values.add(PhotoClient.barcodeText(result.getText())); }
                        catch (IOException ignored) { /* Unsupported payload remains unregistered. */ }
                        if (values.size() >= MAX_BARCODES) return new ArrayList<>(values);
                    }
                } catch (NotFoundException ignored) { /* Another orientation may expose the printed barcode. */ }
                finally { reader.reset(); }
            }
            byte[] rotated = new byte[luminance.length];
            for (int y=0;y<height;y++) for (int x=0;x<width;x++) rotated[x*height+(height-1-y)] = luminance[y*width+x];
            luminance = rotated;
            int previousWidth = width; width = height; height = previousWidth;
        }
        return new ArrayList<>(values);
    }
}
