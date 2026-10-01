package com.gongyo.orderly;

import java.io.IOException;
import java.io.OutputStream;

/** Keeps export-time bookkeeping separate from the current editable state. */
public final class BackupStatus {
 private BackupStatus(){}

 public static void writeSnapshot(OutputStream out,StateCodec.State current,long exportedAt)throws IOException {
  out.write(StateCodec.encode(timestampedCopy(current,exportedAt)));
 }

 public static InventoryRepository.CommitResult recordSuccessfulExport(InventoryRepository repository,long exportedAt)throws IOException {
  if(exportedAt<=0)throw new IOException("백업 시각이 올바르지 않습니다.");
  InventoryRepository.Snapshot latest=repository.snapshot();
  return repository.commit(latest.generation,latest.revision,(candidate,revision)->candidate.lastBackupAt=exportedAt);
 }

 private static StateCodec.State timestampedCopy(StateCodec.State current,long exportedAt)throws IOException {
  if(exportedAt<=0)throw new IOException("백업 시각이 올바르지 않습니다.");
  StateCodec.State candidate=StateCodec.decode(StateCodec.encode(current));
  candidate.lastBackupAt=exportedAt;
  return candidate;
 }
}
