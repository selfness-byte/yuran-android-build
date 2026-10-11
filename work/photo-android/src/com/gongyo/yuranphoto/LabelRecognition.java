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

    /** Call on a worker; the bundled Chinese recognizer also reads Latin label text. */
    public static Result read(Context context, Source source) throws IOException { return read(context,source,true); }
    public static Result readText(Context context, Source source) throws IOException { return read(context,source,false); }
    private static Result read(Context context, Source source,boolean scanBarcodes) throws IOException {
        Bitmap bitmap = decode(source);
        TextRecognizer recognizer = null;
        Task<Text> task = null;
        try {
            recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            task = recognizer.process(InputImage.fromBitmap(bitmap, 0));
            ArrayList<String> barcodes = scanBarcodes?readBarcodes(bitmap):new ArrayList<>();
            Text recognized = Tasks.await(task, 40, TimeUnit.SECONDS);
            String text = intakeText(recognized);
            if (text.length() > MAX_TEXT) throw new IOException("라벨의 글자가 너무 많습니다. 상품 라벨만 가까이 촬영해 주세요.");
            if (text.trim().isEmpty() && barcodes.isEmpty()) throw new IOException("라벨의 글자와 바코드를 읽지 못했습니다. 초점을 맞춰 다시 촬영해 주세요.");
            return new Result(text, barcodes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("라벨 읽기가 중단됐습니다. 다시 촬영해 주세요.", e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("라벨 읽기가 오래 걸립니다. 다시 읽거나 촬영해 주세요.", e);
        } catch (java.util.concurrent.ExecutionException | RuntimeException e) {
            throw new IOException("라벨을 읽지 못했습니다. 다시 촬영하거나 새로운 등록에서 직접 입력해 주세요.", e);
        } finally {
            final Bitmap ownedBitmap = bitmap;
            final TextRecognizer ownedRecognizer = recognizer;
            if (task != null && !task.isComplete()) {
                // A timed-out native request still owns its input until it completes.
                task.addOnCompleteListener(Runnable::run, ignored -> {
                    if (ownedRecognizer != null) ownedRecognizer.close();
                    ownedBitmap.recycle();
                });
            } else {
                if (ownedRecognizer != null) ownedRecognizer.close();
                ownedBitmap.recycle();
            }
        }
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
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2;
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


    /** Rejoin a size and a count only when they occupy the same visual row. */
    static String intakeText(Text recognized) {
        java.util.List<Text.Line> lines=new ArrayList<>();
        for(Text.TextBlock block:recognized.getTextBlocks())lines.addAll(block.getLines());
        java.util.Map<Integer,Integer> pairs=new java.util.HashMap<>();
        java.util.Set<Integer> claimed=new java.util.HashSet<>();
        for(int i=0;i<lines.size();i++){
            String size=lines.get(i).getText().trim().toUpperCase(java.util.Locale.ROOT);
            android.graphics.Rect a=lines.get(i).getBoundingBox();
            if(!size.matches("S|M|L|XL")||a==null)continue;
            int choice=-1;
            for(int j=0;j<lines.size();j++){
                if(i==j||!lines.get(j).getText().trim().matches("[0-9]{1,6}"))continue;
                android.graphics.Rect b=lines.get(j).getBoundingBox();if(b==null)continue;
                int overlap=Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top);
                if(b.left<a.right||b.left-a.right>Math.max(a.height(),b.height())*3||overlap*2<Math.min(a.height(),b.height()))continue;
                if(choice>=0){choice=-2;break;}choice=j;
            }
            if(choice>=0&&!claimed.contains(choice)){pairs.put(i,choice);claimed.add(choice);}
        }
        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.size();i++){if(claimed.contains(i))continue;if(out.length()>0)out.append('\n');out.append(lines.get(i).getText());Integer count=pairs.get(i);if(count!=null)out.append(' ').append(lines.get(count).getText());}
        return out.toString();
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
