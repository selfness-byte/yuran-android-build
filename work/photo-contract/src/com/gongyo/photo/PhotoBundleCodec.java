package com.gongyo.photo;

import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import java.util.*;

/** Strict bounded Android wire adapter. No image bytes or arbitrary Parcelable enter this protocol. */
public final class PhotoBundleCodec {
 public static final int MAX_BUNDLE_BYTES=65536;
 private PhotoBundleCodec(){}
 public static void request(Bundle b,String method){
  String fields;
  switch(method){
   case "getCompatibility":fields="protocolVersion";break;
   case "getCatalogPage":fields="protocolVersion,generation,revision,query,offset,pageSize,scope,details";break;
   case "getReceipt":fields="protocolVersion,requestId,fingerprint,generation";break;
   case "registerPhoto":fields="protocolVersion,requestId,generation,revision,productId,variantId,sku,sourceColor,sourceSha256,sourceUri";break;
   case "registerBarcode":fields="protocolVersion,generation,revision,productId,variantId,sku,sourceColor,barcode";break;
   case "lookupBarcode":fields="protocolVersion,barcode";break;
   case "beginIntake":fields="protocolVersion";break;
   case "registerIntake":fields="protocolVersion,generation,targetWorkId,requestId,sourceSha256,sku,sourceColor,quantities";break;
   case "getPhotoTarget":fields="protocolVersion,generation,revision,targetWorkId,sku,sourceColor";break;
   case "getStock":fields="protocolVersion,sku";break;
   default:throw new IllegalArgumentException("Unknown photo method");
  }
  validate(b,fields,false);
 }
 public static void response(Bundle b){validate(b,"protocolVersion,generation,targetWorkId,revision,status,reason,noteVersionCode,requestId,productId,variantId,photoVersionId,sku,sourceColor,barcode,barcodes,items,nextOffset,stockSummary",true);}
 private static void validate(Bundle b,String fields,boolean response){
  if(b==null)throw new IllegalArgumentException("Missing photo bundle");
  Parcel p=Parcel.obtain();try{p.writeBundle(b);if(p.dataSize()>MAX_BUNDLE_BYTES)throw new IllegalArgumentException("Photo bundle exceeds 64KiB");}finally{p.recycle();}
  Set<String> keys=new HashSet<>(Arrays.asList(fields.split(",")));
  for(String key:b.keySet()){
   if(!keys.contains(key))throw new IllegalArgumentException("Unknown photo field");Object value=b.get(key);
   if(key.equals("items")&&response){
    if(!(value instanceof ArrayList)||((ArrayList<?>)value).size()>50)throw new IllegalArgumentException("Catalogue page bound");
    for(Object item:(ArrayList<?>)value){if(!(item instanceof Bundle))throw new IllegalArgumentException("Catalogue item type");validate((Bundle)item,"productId,variantId,sku,sourceColor,photoVersionId,barcodes,colorAliases",false);}continue;
   }
   boolean valid=key.equals("sourceUri")?value instanceof Uri:key.equals("revision")?value instanceof Long:Arrays.asList("protocolVersion","noteVersionCode","offset","pageSize","nextOffset").contains(key)?value instanceof Integer:value instanceof String;
   if(!valid)throw new IllegalArgumentException("Photo field type");
   if(value instanceof String&&((String)value).length()>((key.equals("barcodes")||key.equals("colorAliases"))?2048:1024))throw new IllegalArgumentException("Photo scalar bound");
  }
 }
 public static Bundle registerRequest(PhotoContract.RegisterRequest r){Bundle b=new Bundle();b.putInt("protocolVersion",1);b.putString("requestId",r.requestId);b.putString("generation",r.generation);b.putLong("revision",r.revision);b.putString("productId",r.productId);b.putString("variantId",r.variantId);b.putString("sku",r.sku);b.putString("sourceColor",r.sourceColor);b.putString("sourceSha256",r.sourceSha256);b.putParcelable("sourceUri",Uri.parse(r.sourceUri));request(b,"registerPhoto");return b;}
 public static PhotoContract.RegisterRequest readRegisterRequest(Bundle b){request(b,"registerPhoto");return new PhotoContract.RegisterRequest(string(b,"requestId"),string(b,"generation"),number(b,"revision"),string(b,"productId"),string(b,"variantId"),string(b,"sku"),string(b,"sourceColor"),string(b,"sourceSha256"),uri(b).toString());}
 public static Bundle compatibility(PhotoContract.Compatibility c){Bundle b=base(PhotoContract.OK,c.generation,c.revision,"");b.putInt("protocolVersion",c.protocolVersion);b.putInt("noteVersionCode",c.noteVersionCode);response(b);return b;}
 public static PhotoContract.Compatibility readCompatibility(Bundle b){response(b);return new PhotoContract.Compatibility(integer(b,"protocolVersion"),string(b,"generation"),number(b,"revision"),integer(b,"noteVersionCode"));}
 public static Bundle reply(PhotoContract.Reply r){Bundle b=base(r.status,r.generation,r.revision,r.reason);b.putString("requestId",safe(r.requestId));b.putString("productId",safe(r.productId));b.putString("variantId",safe(r.variantId));b.putString("photoVersionId",safe(r.photoVersionId));response(b);return b;}
 public static PhotoContract.Reply readReply(Bundle b){response(b);return new PhotoContract.Reply(string(b,"status"),string(b,"requestId"),string(b,"generation"),number(b,"revision"),string(b,"productId"),string(b,"variantId"),string(b,"photoVersionId"),string(b,"reason"));}
 public static Bundle base(String status,String generation,long revision,String reason){Bundle b=new Bundle();b.putInt("protocolVersion",1);b.putString("status",safe(status));b.putString("generation",safe(generation));b.putLong("revision",revision);b.putString("reason",safe(reason));return b;}
 public static String string(Bundle b,String key){Object v=b.get(key);if(!(v instanceof String))throw new IllegalArgumentException("Missing string field: "+key);return (String)v;}
 public static long number(Bundle b,String key){Object v=b.get(key);if(!(v instanceof Long))throw new IllegalArgumentException("Missing revision");return (Long)v;}
 public static int integer(Bundle b,String key){Object v=b.get(key);if(!(v instanceof Integer))throw new IllegalArgumentException("Missing integer: "+key);return (Integer)v;}
 public static Uri uri(Bundle b){Object v=b.get("sourceUri");if(!(v instanceof Uri))throw new IllegalArgumentException("Missing source URI");return (Uri)v;}
 private static String safe(String s){return s==null?"":s;}
}
