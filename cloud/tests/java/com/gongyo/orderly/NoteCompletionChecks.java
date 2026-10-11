package com.gongyo.orderly;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

/** Synthetic catalogue/state only: completion must preserve displayed export photos. */
public final class NoteCompletionChecks {
    private static String id() { return UUID.randomUUID().toString(); }
    private static String hash(char c) { char[] value=new char[64]; Arrays.fill(value,c); return new String(value); }
    private static final String A=hash('a'), B=hash('b'), C=hash('c');
    private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    private static StateCodec.State sample(boolean bound,boolean hidden) {
        StateCodec.State state=new StateCodec.State();state.revision=4;
        String product=id(),variant=id();
        state.catalog.products.put(product,new PhotoCatalog.Product(product,"QA-COMP001",Collections.emptyList()));
        state.catalog.variants.put(variant,new PhotoCatalog.Variant(variant,product,"WHITE",Collections.emptyList(),B));
        for(String h:new String[]{A,B,C})state.catalog.assets.put(h,new PhotoCatalog.Asset(h,8,2,2));
        Inventory.Job job=new Inventory.Job();job.id=id();job.title="Synthetic completion";job.updatedAt=1;
        Inventory.Row row=new Inventory.Row();row.sku="QA-COMP001";row.sourceColor="WHITE";row.color="WH";row.quantities[1]=8;
        if(bound)row.binding=new PhotoCatalog.Binding(product,variant,A,row.sku,row.sourceColor,2,hidden);
        job.rows.add(row);state.draft=job;state.jobs.add(job.copy());return state;
    }
    private static void complete(InventoryRepository repo,boolean value)throws Exception {
        InventoryRepository.Snapshot old=repo.snapshot();
        check(repo.commit(old.generation,old.revision,(s,r)->WorkArchive.setCompleted(s,value)).committed,"completion commits");
    }
    private static void verifyFrozen(boolean originallyBound)throws Exception {
        InventoryRepository repo=new InventoryRepository(sample(originallyBound,false),s->{});
        check(B.equals(PhotoBindings.displayBinding(repo.snapshot().stateCopy,repo.snapshot().stateCopy.draft.rows.get(0)).photoVersionId),"active work displays current B");
        complete(repo,true);
        StateCodec.State reopened=StateCodec.decode(StateCodec.encode(repo.snapshot().stateCopy));
        repo=new InventoryRepository(reopened,s->{});
        InventoryRepository.Snapshot done=repo.snapshot();
        check(B.equals(ExportSnapshot.capture(done,null).photoVersions.get(0)),"completed draft must retain displayed photo B after restart");
        check(B.equals(ExportSnapshot.capture(done,done.stateCopy.draft.id).photoVersions.get(0)),"archived completed export must retain B");
        check(done.stateCopy.draft.rows.get(0).quantities[1]==8,"completion leaves stock unchanged");
        check(done.stateCopy.jobs.get(0).completed,"archive completion matches");
        repo.commit(done.generation,done.revision,(s,r)->s.catalog.variants.values().iterator().next().currentPhotoVersionId=C);
        check(B.equals(ExportSnapshot.capture(repo.snapshot(),null).photoVersions.get(0)),"later photo C cannot alter completed B");
        complete(repo,false);
        check(C.equals(ExportSnapshot.capture(repo.snapshot(),null).photoVersions.get(0)),"reopened active work follows latest C");
    }
    private static void hiddenStaysHidden()throws Exception {
        InventoryRepository repo=new InventoryRepository(sample(true,true),s->{});
        complete(repo,true);
        InventoryRepository.Snapshot done=repo.snapshot();
        PhotoCatalog.Binding b=done.stateCopy.draft.rows.get(0).binding;
        check(b.needsConfirmation&&A.equals(b.photoVersionId),"explicit hidden binding stays hidden");
        check(ExportSnapshot.capture(done,null).photoVersions.get(0).isEmpty(),"hidden binding is never exported");
    }
    private static void saveFailureLeavesActive()throws Exception {
        InventoryRepository repo=new InventoryRepository(sample(false,false),s->{throw new IOException("injected disk failure");});
        try{complete(repo,true);throw new AssertionError("expected save failure");}catch(IOException expected){}
        check(!repo.snapshot().stateCopy.draft.completed,"failed persistence leaves active work");
        check(B.equals(ExportSnapshot.capture(repo.snapshot(),null).photoVersions.get(0)),"failed completion retains active photo");
    }
    public static void main(String[] ignored)throws Exception {
        verifyFrozen(false);verifyFrozen(true);hiddenStaysHidden();saveFailureLeavesActive();
        System.out.println("PASS completion/restart/export photo freezing, hidden references and failed persistence");
    }
}
