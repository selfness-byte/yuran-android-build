package com.gongyo.yuranphoto;
import android.content.*;import android.database.Cursor;import android.net.Uri;import android.os.ParcelFileDescriptor;import java.io.*;
/** Private temporary-grant routes. Registration inputs are read only; camera output is write only. */
public final class CaptureContentProvider extends ContentProvider {
 @Override public boolean onCreate(){return true;}
 @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{try{return PhotoClient.get(getContext()).open(uri,mode);}catch(FileNotFoundException e){throw e;}catch(IOException e){FileNotFoundException failure=new FileNotFoundException(e.getMessage());failure.initCause(e);throw failure;}}
 @Override public String getType(Uri uri){return "application/octet-stream";}
 @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException("Descriptor access only");}
 @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
 @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
