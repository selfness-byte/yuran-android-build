package com.gongyo.orderly;
import java.io.IOException;
import java.util.*;
/** Archive contents only: operating pins and pending snapshots are never exported. */
public final class BackupManifest {
 public int formatVersion=1,stateVersion=PhotoLimits.SCHEMA_VERSION;
 public String stateSha256;
 public long stateBytes;
 public final LinkedHashMap<String,PhotoCatalog.Asset> media=new LinkedHashMap<>();
 public void validate()throws IOException {
  if(formatVersion!=1||stateVersion<10||stateVersion>PhotoLimits.SCHEMA_VERSION)throw new IOException("Unsupported backup version");PhotoCatalog.hash(stateSha256);
  if(stateBytes<8||stateBytes>PhotoLimits.MAX_METADATA_BYTES||media.size()>PhotoLimits.MAX_PHOTO_ASSETS||media.size()+2>20002)throw new IOException("Backup limits");
  for(Map.Entry<String,PhotoCatalog.Asset> e:media.entrySet()){PhotoCatalog.Asset a=e.getValue();if(a==null||!e.getKey().equals(a.hash))throw new IOException("Manifest media key");PhotoCatalog.hash(a.hash);if(a.byteLength<=0||a.byteLength>PhotoLimits.MAX_INPUT_BYTES||a.width<=0||a.height<=0||Math.max(a.width,a.height)>PhotoLimits.MAX_REGISTERED_EDGE)throw new IOException("Manifest media bounds");}
 }
}
