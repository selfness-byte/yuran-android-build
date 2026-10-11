package com.gongyo.yuranphoto;
import android.os.Bundle;
import com.gongyo.photo.PhotoContract;
import java.io.IOException;
import java.util.*;
/** Synthetic catalogue boundary; no phone, network, photographs or inventory. */
public final class PhotoClient {
 private final ArrayList<Bundle> items=new ArrayList<>();
 public PhotoClient(String[][] rows){
  int i=0;
  for(String[] row:rows){Bundle b=new Bundle();b.putString("sku",row[0]);b.putString("sourceColor",row[1]);b.putString("colorAliases",row.length>2?row[2]:"");b.putString("productId","product-"+row[0]);b.putString("variantId","variant-"+i++);b.putString("photoVersionId","");items.add(b);}
 }
 public PhotoContract.Compatibility compatibility(){return new PhotoContract.Compatibility(1,"00000000-0000-0000-0000-000000000001",7,23);}
 private Bundle reply(String status){Bundle b=new Bundle();b.putString("status",status);b.putString("generation",compatibility().generation);b.putLong("revision",7);return b;}
 public Bundle labelCatalogPage(String query,int offset,String generation,long revision){Bundle b=reply(PhotoContract.OK);b.putParcelableArrayList("items",new ArrayList<>(items.subList(offset,Math.min(offset+50,items.size()))));b.putInt("nextOffset",offset+50<items.size()?offset+50:-1);return b;}
 public Bundle lookupBarcode(String barcode){return reply(PhotoContract.NOT_FOUND);}
 public static String barcodeText(String input)throws IOException {if(input==null||input.trim().isEmpty()||input.length()>128)throw new IOException("Invalid synthetic barcode");return input.trim();}
}
