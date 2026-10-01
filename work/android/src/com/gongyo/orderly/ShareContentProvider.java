package com.gongyo.orderly;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Read-only access to generated PDF/XLSX files in this app's private share cache. */
public final class ShareContentProvider extends ContentProvider {
    public static final String AUTHORITY = com.gongyo.photo.RuntimeIds.SHARE_AUTHORITY;
    // Preserve this directory so previously issued temporary PDF URIs remain readable.
    static final String CACHE_DIRECTORY = "shared-pdfs";

    public static Uri uriForFile(Context context, File file) throws IOException {
        if (context == null || file == null) throw new IOException("Share file unavailable");
        File root = new File(context.getCacheDir(), CACHE_DIRECTORY);
        File allowed = ShareFileAccess.resolve(root, file.getName(), "r");
        if (!allowed.equals(file.getCanonicalFile())) throw new IOException("Share file outside cache");
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(allowed.getName()).build();
    }

    public static Uri uriForFile(Context context,File file,String displayName)throws IOException {
        Uri base=uriForFile(context,file);validateDisplayName(displayName,file.getName());
        return base.buildUpon().appendQueryParameter("displayName",displayName).build();
    }
    private static void validateDisplayName(String displayName,String actual)throws IOException {
        String suffix=actual.endsWith(".xlsx")?".xlsx":".pdf";
        if(displayName==null||displayName.length()>200||!displayName.endsWith(suffix)||displayName.length()<=suffix.length())throw new IOException("Invalid share display name");
        for(int i=0;i<displayName.length();i++){char c=displayName.charAt(i);if(Character.isISOControl(c)||"\\/:*?\"<>|".indexOf(c)>=0)throw new IOException("Invalid share display name");}
    }
    private static String displayName(Uri uri,File file) {
        List<String> names=uri.getQueryParameters("displayName");if(names.isEmpty())return file.getName();
        if(names.size()!=1)throw new IllegalArgumentException("Ambiguous share display name");
        try{validateDisplayName(names.get(0),file.getName());}catch(IOException failed){throw new IllegalArgumentException("Invalid share display metadata",failed);}return names.get(0);
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        try { return ShareFileAccess.mimeType(fileFor(uri, "r").getName()); }
        catch (IOException failure) { throw new IllegalArgumentException("Unknown share file", failure); }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        final File file;
        try { file = fileFor(uri, "r"); }
        catch (IOException failure) { throw new IllegalArgumentException("Unknown share file", failure); }
        String[] columns = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) values[index] = displayName(uri,file);
            else if (OpenableColumns.SIZE.equals(columns[index])) values[index] = file.length();
            else throw new IllegalArgumentException("Unsupported share metadata");
        }
        MatrixCursor result = new MatrixCursor(columns, 1);
        result.addRow(values);
        return result;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try { return ParcelFileDescriptor.open(fileFor(uri, mode), ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (IOException failure) {
            FileNotFoundException denied = new FileNotFoundException("Share file access denied");
            denied.initCause(failure);
            throw denied;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only provider"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only provider"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only provider"); }

    private File fileFor(Uri uri, String mode) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())) {
            throw new IOException("Unknown share URI");
        }
        List<String> paths = uri.getPathSegments();
        if (paths.size() != 1) throw new IOException("Unknown share path");
        Context context = getContext();
        if (context == null) throw new IOException("Share provider unavailable");
        return ShareFileAccess.resolve(new File(context.getCacheDir(), CACHE_DIRECTORY), paths.get(0), mode);
    }
}
