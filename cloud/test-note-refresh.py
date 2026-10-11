#!/usr/bin/env python3
"""Exercise the actual quick-adjustment/refresh method bodies without Android widgets.

The facade replaces widgets/notifications only; quantities and commit/conflict/failure
use the real repository. This is not a device or Activity lifecycle test.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "work/android/src/com/gongyo/orderly/MainActivity.java"
METHODS = ("captureQuick", "quickValues", "quickInputChanged", "quickAmount",
           "applyQuickAdjustment", "encodedBaseline", "hasPendingInput",
           "canRefreshAfterResume", "commitChange", "commitTableChange", "applyScreen", "adopt")

def method(source, name):
    match = re.search(r"(?m)^    private (?:static )?[^\n{;]+\b" + re.escape(name) + r"\(", source)
    if not match:
        raise AssertionError("Missing production method: " + name)
    start = source.index("{", match.start())
    depth, quote, escaped, line_comment, block_comment = 0, "", False, False, False
    index = start
    while index < len(source):
        char, nxt = source[index], source[index:index + 2]
        if line_comment:
            if char == "\n": line_comment = False
        elif block_comment:
            if nxt == "*/": block_comment = False; index += 1
        elif quote:
            if escaped: escaped = False
            elif char == "\\": escaped = True
            elif char == quote: quote = ""
        elif nxt == "//": line_comment = True; index += 1
        elif nxt == "/*": block_comment = True; index += 1
        elif char in "\"'": quote = char
        elif char == "{": depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0: return source[match.start():index + 1]
        index += 1
    raise AssertionError("Unclosed production method: " + name)

FACADE = r'''
package com.gongyo.orderly;
import java.io.*;
import java.util.*;
public final class NoteInputRefreshChecks {
    static final class Input { String value; Input(String s){value=s;} String getText(){return value;} }
    static final class Selection { int getSelectedItemPosition(){return 0;} }
    static final class Disk implements InventoryRepository.Persistence {
        boolean fail;
        public void save(StateCodec.State s)throws IOException { if(fail)throw new IOException("injected disk failure"); }
    }
    final Disk disk=new Disk();
    InventoryRepository repository;
    StateCodec.State state;
    byte[] screenBaseline;
    String expectedGeneration,quickSkuValue="",quickColorValue="",quickQtyValue="1",quickFeedback="";
    long expectedRevision;
    int quickSizeValue;
    String[] quickBaseline;
    Input quickSku=new Input("QA-REFRESH"),quickColor=new Input("WH"),quickQuantity=new Input("1");
    Selection quickSize=new Selection();
    boolean ready=true,storageUnavailable,transitioning,conflicted;
    NoteInputRefreshChecks()throws Exception {
        StateCodec.State s=new StateCodec.State();
        s.draft.id=UUID.randomUUID().toString();s.draft.stockSessionId=UUID.randomUUID().toString();s.draft.updatedAt=1;
        Inventory.Row r=new Inventory.Row();r.sku="QA-REFRESH";r.sourceColor="WHITE";r.color="WH";r.quantities[0]=10;
        s.draft.rows.add(r);s.jobs.add(s.draft.copy());
        repository=new InventoryRepository(s,disk);adopt(repository.snapshot());
        captureQuick();quickBaseline=quickValues();
    }
    boolean backupBlocked(){return false;}
    boolean editable(){return !state.draft.completed;}
    String title(){return state.draft.title;}
    Inventory.Job copy(Inventory.Job job){return job.copy();}
    void hideKeyboard(){}
    void render(){}
    void alert(String title,String text){}
    void storageFailure(Exception e){storageUnavailable=true;}
    void showConflictNotice(){}
    boolean accept(InventoryRepository.CommitResult result){
        if(!result.committed){conflicted=true;return false;}
        adopt(result.latestSnapshot);return true; // Android change notification is intentionally outside this facade.
    }
    void externalIntake()throws Exception {
        InventoryRepository.Snapshot before=repository.snapshot();
        check(repository.commit(before.generation,before.revision,(s,r)->s.draft.rows.get(0).quantities[0]+=5).committed,"external intake commits");
    }
    boolean mayRefresh()throws IOException {
        captureQuick();
        return canRefreshAfterResume(conflicted,false,quickInputChanged(),state,screenBaseline);
    }
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void successThenExternal()throws Exception {
        NoteInputRefreshChecks f=new NoteInputRefreshChecks();f.quickQuantity.value="2";f.applyQuickAdjustment(false);
        check(f.repository.snapshot().stateCopy.draft.rows.get(0).quantities[0]==12,"actual quick adjustment adds exactly two");
        f.externalIntake();
        check(f.mayRefresh(),"successful quick adjustment must permit external revision refresh");
        f.adopt(f.repository.snapshot());
        check(f.state.draft.rows.get(0).quantities[0]==17,"next snapshot includes external intake");
        check("2".equals(f.quickQuantity.value),"selection remains reusable");
    }
    static void unsavedFailureAndConflict()throws Exception {
        NoteInputRefreshChecks unsaved=new NoteInputRefreshChecks();unsaved.quickQuantity.value="2";unsaved.externalIntake();
        check(!unsaved.mayRefresh(),"unsent input still blocks replacement");
        NoteInputRefreshChecks failed=new NoteInputRefreshChecks();failed.quickQuantity.value="2";failed.disk.fail=true;failed.applyQuickAdjustment(false);
        check(failed.repository.snapshot().stateCopy.draft.rows.get(0).quantities[0]==10,"failed save changes no stock");
        check(!failed.mayRefresh()&&failed.quickInputChanged(),"failed input remains unsaved");
        NoteInputRefreshChecks conflict=new NoteInputRefreshChecks();conflict.quickQuantity.value="2";conflict.externalIntake();conflict.applyQuickAdjustment(false);
        check(conflict.conflicted&&!conflict.mayRefresh()&&conflict.quickInputChanged(),"conflict keeps original input protected");
        check(conflict.repository.snapshot().stateCopy.draft.rows.get(0).quantities[0]==15,"conflict cannot overwrite external intake");
        NoteInputRefreshChecks invalid=new NoteInputRefreshChecks();invalid.quickQuantity.value="0";invalid.applyQuickAdjustment(false);
        check(!invalid.mayRefresh(),"invalid amount never becomes saved input");
    }
    public static void main(String[] args)throws Exception {
        successThenExternal();unsavedFailureAndConflict();
        System.out.println("PASS production quick-input methods: saved adjustment refresh, unsent input, disk failure and revision conflict");
    }
    // INSERT_PRODUCTION_METHODS
}
'''

def run(source, folder):
    java = folder / "NoteInputRefreshChecks.java"
    bodies = "\n".join(method(source, name) for name in METHODS)
    java.write_text(FACADE.replace("// INSERT_PRODUCTION_METHODS", bodies))
    classes = folder / "classes"
    classes.mkdir(exist_ok=True)
    jdk = Path(os.environ.get("JAVA_HOME_17_X64") or os.environ["JAVA_HOME"])
    subprocess.run([str(jdk / "bin/javac"), "-encoding", "UTF-8", "--release", "8",
                    "-sourcepath", "cloud/tests/java:work/android/src:work/photo-contract/src",
                    "-d", str(classes), str(java)], cwd=ROOT, check=True)
    return subprocess.run([str(jdk / "bin/java"), "-cp", str(classes),
                           "com.gongyo.orderly.NoteInputRefreshChecks"], cwd=ROOT,
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

def main():
    source=SOURCE.read_text()
    with tempfile.TemporaryDirectory(prefix="yuran-note-refresh-") as temporary:
        folder=Path(temporary)
        current=run(source,folder)
        print(current.stdout,end="")
        if current.returncode:
            raise SystemExit(current.returncode)
        actual=method(source,"applyQuickAdjustment")
        broken,count=re.subn(r"(?m)^\s*quickBaseline=quickValues\(\);\s*$","",actual)
        if count != 1:
            raise AssertionError("Expected one successful-commit baseline update")
        old=run(source.replace(actual,broken,1),folder)
        if old.returncode == 0 or "successful quick adjustment must permit external revision refresh" not in old.stdout:
            raise AssertionError("Regression did not reproduce the removed baseline update:\n"+old.stdout)
        print("PASS regression sensitivity: removing the commit-baseline fix reproduces the original refresh failure")

if __name__ == "__main__":
    main()
