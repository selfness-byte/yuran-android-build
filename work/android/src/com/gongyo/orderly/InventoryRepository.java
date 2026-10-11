package com.gongyo.orderly;

import java.io.IOException;
import java.util.Objects;

/** The single confirmed state. Publication follows a successful atomic persistence write. */
public final class InventoryRepository {
 public interface Persistence {void save(StateCodec.State candidate)throws IOException;}
 public interface Mutation {void apply(StateCodec.State candidate,long commitRevision)throws IOException;}
 public interface CheckedAction {void run()throws IOException;}
 public static final class Snapshot {
  public final String generation;
  public final long revision;
  public final StateCodec.State stateCopy;
  private Snapshot(StateCodec.State copy){generation=copy.generation;revision=copy.revision;stateCopy=copy;}
 }
 public static final class CommitResult {
  public final boolean committed;
  public final Snapshot latestSnapshot;
  public final String conflictReason;
  private CommitResult(boolean committed,Snapshot snapshot,String reason){this.committed=committed;latestSnapshot=snapshot;conflictReason=reason;}
 }
 private final Object lock=new Object();
 private final Persistence persistence;
 private StateCodec.State confirmed;
 private CommitUncertainException unavailable;
 public InventoryRepository(StateCodec.State initial,Persistence persistence)throws IOException {
  this.persistence=Objects.requireNonNull(persistence,"persistence");
  confirmed=copy(initial);
 }
 static Snapshot frozen(StateCodec.State state)throws IOException{return new Snapshot(copy(state));}
 public Snapshot snapshot()throws IOException{synchronized(lock){ready();return snapshotLocked();}}
 public CommitResult commit(String expectedGeneration,long expectedRevision,Mutation mutation)throws IOException {
  synchronized(lock){ready();try{
   if(!confirmed.generation.equals(expectedGeneration))return new CommitResult(false,snapshotLocked(),"GENERATION_CHANGED");
   if(confirmed.revision!=expectedRevision)return new CommitResult(false,snapshotLocked(),"REVISION_CHANGED");
   long next=confirmed.nextRevision();
   StateCodec.State candidate=copy(confirmed);
   mutation.apply(candidate,next);
   ready();
   candidate.revision=next;
   WorkArchive.refreshTrackedDraft(candidate);
   // Validate and detach before saving so no escaped callback or adapter reference owns memory.
   StateCodec.State publication=copy(candidate);
   persistence.save(candidate);
   ready();
   confirmed=publication;
   return new CommitResult(true,snapshotLocked(),null);
   }catch(CommitUncertainException e){if(unavailable==null)unavailable=e;throw unavailable;}
  }
 }
 public CommitResult restore(String expectedGeneration,long expectedRevision,StateCodec.State imported)throws IOException {
  synchronized(lock){ready();try{
   if(!confirmed.generation.equals(expectedGeneration))return new CommitResult(false,snapshotLocked(),"GENERATION_CHANGED");
   if(confirmed.revision!=expectedRevision)return new CommitResult(false,snapshotLocked(),"REVISION_CHANGED");
   StateCodec.State candidate=copy(imported); // Validate imported receipts before discarding them.
   candidate.generation=java.util.UUID.randomUUID().toString();candidate.catalog.receipts.clear();candidate.intakeReceipts.clear();
   StateCodec.State publication=copy(candidate);persistence.save(candidate);ready();confirmed=publication;
   return new CommitResult(true,snapshotLocked(),null);
  }catch(CommitUncertainException e){if(unavailable==null)unavailable=e;throw unavailable;}}
 }
 /** Pin changes and reference-aware file deletion share the same lock as commits. */
 public void exclusive(CheckedAction action)throws IOException {synchronized(lock){ready();try{action.run();ready();}catch(CommitUncertainException e){if(unavailable==null)unavailable=e;throw unavailable;}}}
 private void ready()throws IOException{if(unavailable!=null)throw unavailable;}
 private Snapshot snapshotLocked(){try{return new Snapshot(copy(confirmed));}catch(IOException impossible){throw new IllegalStateException("Confirmed state failed validation",impossible);}}
 private static StateCodec.State copy(StateCodec.State value)throws IOException {return StateCodec.decode(StateCodec.encode(value));}
}
