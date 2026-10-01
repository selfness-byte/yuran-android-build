package com.gongyo.orderly;

import android.graphics.*;
import android.media.ExifInterface;
import java.io.*;

/** Sequential caller-worker normalization. Copies the whole raw stream before reading metadata/pixels. */
public final class PhotoNormalizer {
 public PreparedPhoto normalize(InputStream input,File staging)throws IOException{
  if(input==null||staging==null)throw new IOException("missing photo input");MediaStore.directory(staging);if(staging.getUsableSpace()<PhotoLimits.MAX_INPUT_BYTES+12L*1024*1024)throw new IOException("photo storage space unavailable");File rawDir=new File(staging,"staging");MediaStore.directory(rawDir);File raw=File.createTempFile("raw-",".input",rawDir);
  Throwable primary=null;try{long length=0;try(FileOutputStream out=new FileOutputStream(raw)){byte[] buffer=new byte[32768];for(int n;(n=input.read(buffer))!=-1;){if(n==0){int v=input.read();if(v==-1)break;buffer[0]=(byte)v;n=1;}if(n>PhotoLimits.MAX_INPUT_BYTES-length)throw invalid(InvalidPhotoSourceException.Reason.LIMIT_EXCEEDED,"photo exceeds 25MiB");length+=n;out.write(buffer,0,n);}out.flush();out.getFD().sync();}if(length==0)throw invalid(InvalidPhotoSourceException.Reason.MALFORMED,"empty camera/photo input");
   // A caller DigestInputStream has now seen every byte including decoder-ignored JPEG tails.
   int orientation;boolean png;try(DataInputStream header=new DataInputStream(new FileInputStream(raw))){if(length<8)throw invalid(InvalidPhotoSourceException.Reason.MALFORMED,"photo header truncated");long signature=header.readLong();png=signature==0x89504e470d0a1a0aL;if(!png&&(signature>>>48)!=0xffd8)throw invalid(InvalidPhotoSourceException.Reason.UNSUPPORTED,"choose a JPEG or PNG photo");}
   if(png){try{orientation=PngExifOrientation.read(raw);}catch(PngExifOrientation.ReadException e){throw new InvalidPhotoSourceException(e.reason==PngExifOrientation.Reason.MALFORMED?InvalidPhotoSourceException.Reason.MALFORMED:InvalidPhotoSourceException.Reason.UNSUPPORTED,"PNG metadata rejected",e);}}else{try{MediaStore.jpegDimensions(raw,true);}catch(MediaStore.JpegFormatException e){throw new InvalidPhotoSourceException(InvalidPhotoSourceException.Reason.MALFORMED,"JPEG framing rejected",e);}ExifInterface exif=new ExifInterface(raw.getPath());orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,0);if(orientation==0)orientation=1;if(orientation<1||orientation>8)throw invalid(InvalidPhotoSourceException.Reason.MALFORMED,"invalid JPEG orientation");}
   return encode(raw,staging,orientation,PhotoLimits.MAX_REGISTERED_EDGE,true);
  }catch(IOException|RuntimeException|Error failure){primary=failure;throw failure;}finally{cleanup(raw,primary,"raw staging cleanup failed");}
 }
 /** Derived preview only: input is the registered upright JPEG; never reapply input EXIF. */
 public PreparedPhoto thumbnail(File uprightJpeg,File staging)throws IOException{
  if(uprightJpeg==null||uprightJpeg.length()<=0||uprightJpeg.length()>PhotoLimits.MAX_INPUT_BYTES)throw new IOException("thumbnail input bounds");int[] dimensions=MediaStore.jpegDimensions(uprightJpeg,false);if(Math.max(dimensions[0],dimensions[1])>PhotoLimits.MAX_REGISTERED_EDGE)throw new IOException("thumbnail requires registered JPEG");MediaStore.directory(staging);return encode(uprightJpeg,staging,1,PhotoLimits.MAX_THUMBNAIL_EDGE,false);
 }
 private PreparedPhoto encode(File raw,File staging,int orientation,int edge,boolean incoming)throws IOException{
  BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;bounds.inScaled=false;BitmapFactory.decodeFile(raw.getPath(),bounds);int width=bounds.outWidth,height=bounds.outHeight;if(width<=0||height<=0)throw new IOException("photo bounds cannot decode");if((long)width>Long.MAX_VALUE/height||(long)width*height>PhotoLimits.MAX_INPUT_PIXELS){if(incoming)throw invalid(InvalidPhotoSourceException.Reason.LIMIT_EXCEEDED,"photo dimensions exceed 40MP");throw new IOException("owned photo dimensions exceed limit");}
  BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inDensity=0;options.inTargetDensity=0;options.inPreferredConfig=Bitmap.Config.ARGB_8888;options.inSampleSize=1;while(Math.max(width,height)/(options.inSampleSize*2L)>=edge)options.inSampleSize*=2;
  Bitmap decoded=null,upright=null,white=null;File output=null;Throwable primary=null;try{
   decoded=BitmapFactory.decodeFile(raw.getPath(),options);if(decoded==null)throw new IOException("photo cannot decode");Matrix matrix=orientationMatrix(orientation);upright=Bitmap.createBitmap(decoded,0,0,decoded.getWidth(),decoded.getHeight(),matrix,true);
   float scale=Math.min(1f,(float)edge/Math.max(upright.getWidth(),upright.getHeight()));int w=Math.max(1,Math.round(upright.getWidth()*scale)),h=Math.max(1,Math.round(upright.getHeight()*scale));white=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(white);canvas.drawColor(Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);canvas.drawBitmap(upright,null,new Rect(0,0,w,h),paint);
   output=File.createTempFile(edge==PhotoLimits.MAX_THUMBNAIL_EDGE?"preview-":"prepared-",".jpg",staging);try(FileOutputStream out=new FileOutputStream(output)){if(!white.compress(Bitmap.CompressFormat.JPEG,PhotoLimits.JPEG_QUALITY,out))throw new IOException("JPEG encoding failed");out.flush();out.getFD().sync();}long bytes=output.length();if(bytes<=0||bytes>PhotoLimits.MAX_INPUT_BYTES)throw new IOException("normalized JPEG size");PreparedPhoto photo=new PreparedPhoto(output,MediaStore.hash(output),bytes,w,h);output=null;return photo;
  }catch(OutOfMemoryError error){IOException failure=new IOException("photo memory unavailable",error);primary=failure;throw failure;}catch(IOException|RuntimeException|Error failure){primary=failure;throw failure;}finally{if(white!=null)white.recycle();if(upright!=null&&upright!=decoded)upright.recycle();if(decoded!=null)decoded.recycle();if(output!=null)cleanup(output,primary,"prepared cleanup failed");}
 }
 private static InvalidPhotoSourceException invalid(InvalidPhotoSourceException.Reason reason,String message){return new InvalidPhotoSourceException(reason,message);}
 private static void cleanup(File file,Throwable primary,String message)throws IOException{try{if(!file.delete()&&file.exists())throw new IOException(message);}catch(IOException|RuntimeException|Error failure){
  if(primary==null)throw failure;
  if(failure instanceof Error&&!(primary instanceof Error)&&!(primary instanceof CommitUncertainException)){if(!hasEvidence(primary,failure))failure.addSuppressed(primary);throw(Error)failure;}
  if(!hasEvidence(failure,primary))primary.addSuppressed(failure);
 }}
 /** Check both cause and suppression links before attaching evidence in the opposite direction. */
 private static boolean hasEvidence(Throwable source,Throwable target){java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());java.util.ArrayDeque<Throwable> pending=new java.util.ArrayDeque<>();pending.add(source);while(!pending.isEmpty()){Throwable failure=pending.removeFirst();if(failure==target)return true;if(!seen.add(failure))continue;if(failure.getCause()!=null)pending.add(failure.getCause());for(Throwable suppressed:failure.getSuppressed())pending.add(suppressed);}return false;}
 private static Matrix orientationMatrix(int orientation){float[][] values={{1,0,0,0,1,0,0,0,1},{-1,0,0,0,1,0,0,0,1},{-1,0,0,0,-1,0,0,0,1},{1,0,0,0,-1,0,0,0,1},{0,1,0,1,0,0,0,0,1},{0,-1,0,1,0,0,0,0,1},{0,-1,0,-1,0,0,0,0,1},{0,1,0,-1,0,0,0,0,1}};Matrix matrix=new Matrix();matrix.setValues(values[orientation-1]);return matrix;}
}
