package com.gongyo.orderly;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.TimeZone;

/** Generates the selected current-table file and hands Android a temporary read grant. */
public final class FileSharing {
    private static final long MAX_AGE_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_FILES = 10;

    private FileSharing() {}

    public static Intent createSendIntent(Context context, Inventory.Job job, String format) throws IOException {
        return createSendIntent(context,job,format,ExportFileName.create(job.title,System.currentTimeMillis(),format,TimeZone.getDefault()),null,null);
    }
    public static Intent createSendIntent(Context context,ExportSnapshot frozen,MediaSource source)throws IOException {
        return createSendIntent(context,frozen.jobCopy,frozen.format,frozen.options.get("displayName"),frozen,source);
    }
    private static Intent createSendIntent(Context context,Inventory.Job job,String format,String displayName,ExportSnapshot frozen,MediaSource source)throws IOException {
        if(frozen!=null)frozen.verifyMedia(source);
        if (context == null) throw new IOException("Share context unavailable");
        if (!"pdf".equals(format) && !"xlsx".equals(format)) throw new IOException("Unsupported share format");
        File root = new File(context.getCacheDir(), ShareContentProvider.CACHE_DIRECTORY);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Share cache unavailable");
        File file = new File(root, UUID.randomUUID().toString() + "." + format);
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                if(frozen!=null){if("xlsx".equals(format))Exporter.writeXlsx(output,frozen,source);else Exporter.writePdf(context,output,frozen,source);}
                else if ("xlsx".equals(format)) Exporter.writeXlsx(output, job);
                else Exporter.writePdf(context, output, job);
            }
            Uri uri = ShareContentProvider.uriForFile(context, file,displayName);
            prune(root, file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(ShareFileAccess.mimeType(file.getName()));
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newRawUri(displayName, uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return send;
        } catch (IOException | RuntimeException failure) {
            file.delete();
            throw failure;
        }
    }

    private static void prune(File root, File current) {
        File[] files = root.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        List<File> retained = new ArrayList<File>();
        for (File file : files) {
            try {
                ShareFileAccess.resolve(root, file.getName(), "r");
                if (!file.equals(current) && file.lastModified() < cutoff) file.delete();
                else retained.add(file);
            } catch (IOException ignored) { /* Leave unrelated cache entries untouched. */ }
        }
        Collections.sort(retained, new Comparator<File>() {
            @Override public int compare(File left, File right) {
                if (left.equals(current)) return -1;
                if (right.equals(current)) return 1;
                int age = Long.compare(right.lastModified(), left.lastModified());
                return age != 0 ? age : right.getName().compareTo(left.getName());
            }
        });
        int kept = 0;
        for (File file : retained) {
            if (kept < MAX_FILES) kept++;
            else file.delete();
        }
    }
}
