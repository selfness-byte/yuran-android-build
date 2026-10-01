package com.gongyo.orderly;

import android.content.Context;
import java.io.IOException;

/** One process owner shared by the inventory Activity and photo provider. */
public final class RepositoryOwner {
 private static InventoryRepository instance;
 private RepositoryOwner(){}
 public static synchronized InventoryRepository get(Context context)throws IOException {
  if(instance==null){
   Context application=context.getApplicationContext();
   Context owner=application==null?context:application;
   JobStore store=new JobStore(owner);
   // Protection and pending metadata must recover before a fresh owner can be ready.
   new MediaStore(owner.getFilesDir(),new AndroidFileOps(owner.getFilesDir(),owner.getCacheDir())).pins();
   try{store.readPending();}catch(CheckedAtomicFile.MissingFileException absent){}
   StateCodec.State loaded=store.load();
   InventoryRepository.Persistence persistence=store::save;
   // Legacy-generated generation/row IDs and first-run identities must reach disk first.
   if(store.requiresInitialization())persistence.save(loaded);
   InventoryRepository initialized=new InventoryRepository(loaded,persistence);
   instance=initialized;
  }
  return instance;
 }
}
