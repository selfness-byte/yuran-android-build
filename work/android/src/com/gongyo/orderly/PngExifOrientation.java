package com.gongyo.orderly;

import java.io.*;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Bounded PNG framing and IFD0 orientation reader. Does not validate pixels or unrelated CRCs. */
public final class PngExifOrientation {
 private PngExifOrientation(){}
 public enum Reason {MALFORMED,UNSUPPORTED}
 public static final class ReadException extends IOException {
  public final Reason reason;
  public ReadException(Reason reason,String message){super(message);this.reason=reason;}
 }
 private static final byte[] SIGNATURE={(byte)137,80,78,71,13,10,26,10};
 public static int read(File stagedPng)throws IOException {
  try(RandomAccessFile in=new RandomAccessFile(stagedPng,"r")){
   long size=in.length();if(size<=0||size>PhotoLimits.MAX_INPUT_BYTES)throw malformed("PNG size");
   if(size<8)throw malformed("PNG signature truncated");byte[] signature=new byte[8];in.readFully(signature);if(!Arrays.equals(SIGNATURE,signature))throw malformed("PNG signature");
   boolean first=true,idat=false,exif=false;int orientation=1;
   while(in.getFilePointer()<size){long start=in.getFilePointer();if(size-start<12)throw malformed("PNG chunk truncated");long length=in.readInt()&0xffffffffL;byte[] type=new byte[4];in.readFully(type);long payload=in.getFilePointer();if(length>Integer.MAX_VALUE||length>size-payload-4)throw malformed("PNG chunk bounds");String name=new String(type,"US-ASCII");
    if(first){if(!name.equals("IHDR")||length!=13)throw malformed("PNG first IHDR");first=false;}
    else if(name.equals("IHDR"))throw malformed("duplicate IHDR");
    if(name.equals("eXIf")){
     if(exif)throw malformed("duplicate eXIf");exif=true;if(idat)throw unsupported("eXIf after IDAT");
     CRC32 crc=new CRC32();crc.update(type);byte[] buffer=new byte[32768];long remaining=length;while(remaining>0){int n=(int)Math.min(remaining,buffer.length);in.readFully(buffer,0,n);crc.update(buffer,0,n);remaining-=n;}
     long stored=in.readInt()&0xffffffffL;if(crc.getValue()!=stored)throw malformed("eXIf CRC");orientation=readTiff(in,payload,length);
    }
    if(name.equals("IDAT"))idat=true;
    in.seek(payload+length+4);
    if(name.equals("IEND")){if(length!=0||!idat||in.getFilePointer()!=size)throw malformed("PNG IEND/IDAT/end");return orientation;}
   }
   throw malformed("missing IEND");
  }
 }
 private static int readTiff(RandomAccessFile in,long base,long length)throws IOException{
  if(length<8)throw malformed("TIFF header truncated");in.seek(base);int a=in.readUnsignedByte(),b=in.readUnsignedByte();boolean little=a=='I'&&b=='I';if(!little&&!(a=='M'&&b=='M'))throw unsupported("TIFF byte order/header");if(u16(in,little)!=42)throw unsupported("TIFF magic");long offset=u32(in,little);if(offset<8||offset>length-2)throw malformed("IFD0 offset");in.seek(base+offset);int entries=u16(in,little);long span=2L+12L*entries+4;if(span>length-offset)throw malformed("IFD0 table bounds");int result=1;boolean found=false;
  for(int i=0;i<entries;i++){long entry=base+offset+2L+12L*i;in.seek(entry);int tag=u16(in,little);if(tag!=0x112)continue;if(found)throw malformed("duplicate orientation tag");found=true;int type=u16(in,little);long count=u32(in,little);if(type!=3||count!=1)throw unsupported("orientation representation");int value=u16(in,little);if(value>8)throw malformed("orientation range");result=value==0?1:value;}
  return result;
 }
 private static int u16(RandomAccessFile in,boolean le)throws IOException{int a=in.readUnsignedByte(),b=in.readUnsignedByte();return le?a|(b<<8):(a<<8)|b;}
 private static long u32(RandomAccessFile in,boolean le)throws IOException{long a=u16(in,le),b=u16(in,le);return le?a|(b<<16):(a<<16)|b;}
 private static ReadException malformed(String why){return new ReadException(Reason.MALFORMED,why);}
 private static ReadException unsupported(String why){return new ReadException(Reason.UNSUPPORTED,why);}
}
