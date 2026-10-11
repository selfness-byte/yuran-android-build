package com.gongyo.orderly;

import com.gongyo.photo.PhotoContract;
import com.gongyo.photo.RuntimeIds;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Single-activity, offline order sheet editor. No network or storage permissions. */
public final class MainActivity extends Activity {
    private static final int INK=0xff153e3a, TEAL=0xff17786b, BRAND_PINK=0xffff80ab, BG=0xfff4f6f2, MUTED=0xff647570, LINE=0xffdce5df, AMBER=0xff955c18;
    private static final int FIRST_DOCUMENT_REQUEST=1024, LAST_DOCUMENT_REQUEST=65535;
    // Never recycle an outstanding request code in this process; restore its high water mark too.
    private static int nextDocumentRequest=FIRST_DOCUMENT_REQUEST;
    /** Detached screen input; only repository commits make it confirmed. */
    private StateCodec.State state;
    private InventoryRepository repository;
    private byte[] screenBaseline;
    private DialogDraft dialogDraft;
    private AlertDialog activeEditor;
    private FrozenExportPreview.Handle exportPreview;
    private final List<AlertDialog> editorDialogs=new ArrayList<>();
    private boolean conflicted,transitioning;
    private Runnable captureDialogInput;
    private String expectedGeneration;
    private long expectedRevision;
    /** Small unconfirmed dialog values, including the original row/rule target. */
    private static final class DialogDraft {
        final String kind;final int index,selection;final String[] values;final boolean checked;
        DialogDraft(String kind,int index,int selection,String[] values,boolean checked){this.kind=kind;this.index=index;this.selection=selection;this.values=values.clone();this.checked=checked;}
    }
    private static final class UiDraft {
        final StateCodec.State state;final byte[] baseline;final String[] quickBaseline;final String generation;final long revision;final boolean pendingHistory,conflicted;final DialogDraft dialog;final BackupRestoreSession session;
        UiDraft(StateCodec.State state,byte[] baseline,String[] quickBaseline,String generation,long revision,boolean pendingHistory,DialogDraft dialog,boolean conflicted,BackupRestoreSession session){this.session=session;this.state=state;this.baseline=baseline;this.quickBaseline=quickBaseline;this.generation=generation;this.revision=revision;this.pendingHistory=pendingHistory;this.dialog=dialog;this.conflicted=conflicted;}
    }
    /** Process-local execution only; durable state and protection remain in the existing stores. */
    private static BackupRestoreSession processBackupSession;
    private BackupRestoreSession backupSession;
    private AlertDialog restoreConfirmation;
    private long confirmationOperation=-1;
    private static final class BackupRestoreSession {
        final Context application;
        final android.content.ContentResolver resolver;
        final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        final Handler notifications=new Handler(Looper.getMainLooper());
        volatile InventoryRepository repository;
        volatile JobStore store;
        volatile MediaStore media;
        volatile String phase="IDLE",kind="load";
        volatile long operation;
        volatile boolean busy,cancelRequested,discardInput,externalSaved;
        volatile byte[] pendingBackup;
        volatile long pendingBackupAt;
        volatile String fingerprint;
        volatile ExportSnapshot recoveredExport;
        volatile IntegratedBackup.PreparedRestore prepared;
        volatile InventoryRepository.CommitResult result;
        volatile InventoryRepository.Snapshot latest;
        volatile Exception failure;
        private java.lang.ref.WeakReference<MainActivity> attached=new java.lang.ref.WeakReference<>(null);
        private int pickerRequest=-1;
        private boolean callbackConsumed,terminalDelivered;
        interface Work {void run()throws Exception;}
        BackupRestoreSession(Context context){application=context.getApplicationContext();resolver=application.getContentResolver();}
        synchronized boolean needsLoad(){return !blocked()&&(phase.equals("IDLE")||terminalDelivered);}
        synchronized boolean blocked(){return busy||!(phase.equals("IDLE")||phase.equals("DONE")||phase.equals("FAILED"));}
        synchronized void attach(MainActivity screen){attached=new java.lang.ref.WeakReference<>(screen);notifyScreen();}
        synchronized void detach(MainActivity screen){if(attached.get()==screen)attached.clear();}
        synchronized boolean attached(MainActivity screen,long id){return attached.get()==screen&&operation==id;}
        synchronized boolean takeTerminal(MainActivity screen,long id){if(!attached(screen,id)||busy||!(phase.equals("DONE")||phase.equals("FAILED"))||terminalDelivered)return false;terminalDelivered=true;return true;}
        private synchronized boolean begin(String action){if(blocked())return false;operation++;kind=action;failure=null;result=null;latest=null;prepared=null;cancelRequested=false;discardInput=false;externalSaved=false;pickerRequest=-1;callbackConsumed=false;terminalDelivered=false;return true;}
        private void notifyScreen(){final long id=operation;notifications.post(()->{MainActivity screen;synchronized(this){screen=attached.get();if(id!=operation)return;}if(screen!=null)screen.backupSessionChanged(this,id);});}
        private void execute(String running,Work work){synchronized(this){phase=running;busy=true;}notifyScreen();worker.execute(()->{
            try{work.run();}catch(Exception caught){Exception actual=uncertain(caught);failure=actual;if(actual instanceof CommitUncertainException&&repository!=null)try{repository.exclusive(()->{throw (CommitUncertainException)actual;});}catch(IOException fenced){}
                phase=prepared==null?"FAILED":"FAILED_PREPARED";
            }finally{if(repository!=null&&latest==null)try{latest=repository.snapshot();}catch(IOException fenced){if(failure==null)failure=fenced;}synchronized(this){busy=false;}notifyScreen();}
        });}
        private static Exception uncertain(Exception failure){if(failure instanceof CommitUncertainException)return failure;for(Throwable suppressed:failure.getSuppressed())if(suppressed instanceof Exception){Exception found=uncertain((Exception)suppressed);if(found instanceof CommitUncertainException)return found;}return failure;}
        void initialize(String route){if(!begin("load"))return;execute("LOADING",()->{
            if(repository==null){repository=RepositoryOwner.get(application);store=new JobStore(application);AndroidFileOps io=new AndroidFileOps(application.getFilesDir(),application.getCacheDir());media=new MediaStore(application.getFilesDir(),io,backupJpegValidator(io));}
            repository.exclusive(()->{recoveredExport=null;pendingBackup=null;pendingBackupAt=0;
            try{byte[] bytes=store.readPending();if(ExportSnapshot.isEncoded(bytes)){recoveredExport=ExportSnapshot.decode(bytes);store.exportMedia(repository,media,recoveredExport).checkReady();}
                else{StateCodec.State frozen=StateCodec.decode(bytes);if("backup".equals(route)||frozen.lastBackupAt>0){pendingBackup=bytes;pendingBackupAt=frozen.lastBackupAt;if(pendingBackupAt<=0)throw new IOException("백업 시각이 없습니다.");store.recoverBackup(repository,media,bytes);}else{recoveredExport=store.recoverLegacyExport(repository,media,route);store.exportMedia(repository,media,recoveredExport).checkReady();}}
            }catch(CheckedAtomicFile.MissingFileException absent){}
            latest=repository.snapshot();});phase="DONE";
        });}
        private void verifyPending()throws IOException{repository.exclusive(()->{if(pendingBackup==null||!Arrays.equals(pendingBackup,store.readPending()))throw new IOException("진행 중인 백업 정보가 변경되었습니다.");store.backupMedia(repository,media,pendingBackup).checkReady();});fingerprint=pendingFingerprint(pendingBackup);}
        void prepareBackup(String generation,long revision,long at){if(!begin("backup"))return;recoveredExport=null;execute("BACKUP_PREPARING",()->{pendingBackup=store.prepareBackup(repository,media,generation,revision,at);pendingBackupAt=at;verifyPending();if(cancelRequested)finishCanceledBackup();else phase="BACKUP_READY";});}
        void resumeBackup(byte[] bytes,long at){if(!begin("backup"))return;recoveredExport=null;pendingBackup=bytes.clone();pendingBackupAt=at;execute("BACKUP_PREPARING",()->{verifyPending();if(cancelRequested)finishCanceledBackup();else phase="BACKUP_READY";});}
        synchronized boolean restorePicker(){if(!begin("restore"))return false;phase="RESTORE_READY";notifyScreen();return true;}
        synchronized int issuePicker(MainActivity screen,long id){if(!attached(screen,id)||!(phase.equals("BACKUP_READY")||phase.equals("RESTORE_READY")))return -1;if(nextDocumentRequest>LAST_DOCUMENT_REQUEST){failure=new IOException("파일 선택 횟수가 많습니다. 진행 정보를 보존했습니다.");phase="FAILED";notifyScreen();return -1;}pickerRequest=nextDocumentRequest++;callbackConsumed=false;phase=kind.equals("backup")?"BACKUP_PICKER":"RESTORE_PICKER";return pickerRequest;}
        synchronized boolean ownsRequest(int request){return request==pickerRequest&&pickerRequest>=0;}
        void pickerFailed(Exception cause){synchronized(this){if(callbackConsumed)return;callbackConsumed=true;}execute("CHECKING",()->{throw cause;});}
        void pickerResult(int request,int code,Uri uri){synchronized(this){if(request!=pickerRequest||callbackConsumed||!(phase.equals("BACKUP_PICKER")||phase.equals("RESTORE_PICKER")))return;callbackConsumed=true;}
            if(code==Activity.RESULT_CANCELED){if(kind.equals("backup"))execute("CANCELING",this::finishCanceledBackup);else execute("CANCELING",()->{phase="DONE";});return;}
            if(code!=Activity.RESULT_OK||uri==null){execute("CHECKING",()->{throw new IOException("파일이 선택되지 않았습니다. 진행 정보를 보존했습니다.");});return;}
            if(kind.equals("backup"))writeBackup(uri);else execute("PREPARING",()->{try(InputStream input=resolver.openInputStream(uri)){if(input==null)throw new IOException("백업 파일을 열 수 없습니다.");prepared=new IntegratedBackup(repository,media).prepare(input,new File(application.getFilesDir(),"restore-staging"),application.getFilesDir().getUsableSpace());}if(cancelRequested){new IntegratedBackup(repository,media).cancel(prepared);prepared=null;phase="DONE";}else phase="PREPARED";});
        }
        private void writeBackup(Uri uri){execute("BACKUP_WRITING",()->{verifyPending();StateCodec.State frozen=StateCodec.decode(pendingBackup);repository.exclusive(()->{for(String hash:IntegratedBackup.liveVersions(frozen))media.validateBackupJpeg(hash,frozen.catalog.assets.get(hash));});
            try(OutputStream output=resolver.openOutputStream(uri,"wt")){if(output==null)throw new IOException("파일을 열 수 없습니다.");new IntegratedBackup(repository,media).write(output,InventoryRepository.frozen(frozen),store.backupMedia(repository,media,pendingBackup));output.flush();}
            externalSaved=true;result=BackupStatus.recordSuccessfulExport(repository,pendingBackupAt);if(!result.committed)throw new IOException("백업 파일은 저장됐지만 백업 시각을 기록하지 못했습니다.");store.finishBackup(repository,media,pendingBackup);pendingBackup=null;pendingBackupAt=0;latest=result.latestSnapshot;phase="DONE";
        });}
        void cancelBackup(byte[] bytes,long at){if(!begin("backup"))return;recoveredExport=null;pendingBackup=bytes.clone();pendingBackupAt=at;execute("CANCELING",this::finishCanceledBackup);}
        private void finishCanceledBackup()throws IOException{verifyPending();store.finishBackup(repository,media,pendingBackup);pendingBackup=null;pendingBackupAt=0;phase="DONE";}
        synchronized boolean commit(MainActivity screen,long id){if(!attached(screen,id)||busy||prepared==null||!(phase.equals("PREPARED")||phase.equals("FAILED_PREPARED")))return false;failure=null;execute("COMMITTING",()->{result=new IntegratedBackup(repository,media).commitRestore(prepared,prepared.confirmedGeneration,prepared.confirmedRevision);latest=result.latestSnapshot;phase=result.committed?"DONE":"CONFLICT";});return true;}
        synchronized boolean cancel(MainActivity screen,long id,boolean discard){if(!attached(screen,id)||phase.equals("COMMITTING")||phase.equals("BACKUP_WRITING"))return false;if(busy){cancelRequested=true;notifyScreen();return true;}
            if(prepared!=null){discardInput=discard;failure=null;execute("CANCELING",()->{new IntegratedBackup(repository,media).cancel(prepared);prepared=null;latest=repository.snapshot();phase="DONE";});return true;}
            if(phase.equals("BACKUP_READY")||phase.equals("BACKUP_PICKER")){callbackConsumed=true;execute("CANCELING",this::finishCanceledBackup);return true;}if(phase.equals("RESTORE_READY")||phase.equals("RESTORE_PICKER")){callbackConsumed=true;execute("CANCELING",()->{phase="DONE";});return true;}return false;
        }
    }
    private boolean backupBlocked(){return backupSession!=null&&backupSession.blocked();}
    private void detachBackupSession(){if(restoreConfirmation!=null){restoreConfirmation.setOnCancelListener(null);restoreConfirmation.dismiss();restoreConfirmation=null;}confirmationOperation=-1;if(backupSession!=null)backupSession.detach(this);}
    private void showBackupProgress(){titleInput=null;sourceInput=null;LinearLayout progress=vertical();progress.setPadding(dp(24),dp(40),dp(24),dp(24));String phase=backupSession==null?"LOADING":backupSession.phase;progress.addView(heading("백업 · 복원",20));progress.addView(text(phase.equals("CONFLICT")?"확인 뒤 자료가 변경되었습니다. 입력과 복원 준비를 보존했습니다.":backupSession!=null&&backupSession.cancelRequested?"취소를 마무리하고 있습니다.":"자료를 안전하게 처리하고 있습니다.",16,INK));
        if(!storageUnavailable&&(phase.equals("PREPARED")||phase.equals("FAILED_PREPARED")))progress.addView(sessionButton("복원 자료 확인",()->showRestoreConfirmation(backupSession.operation)));
        if(phase.equals("CONFLICT"))progress.addView(sessionButton("복원 준비 취소 · 입력 폐기 후 최신 자료 보기",()->new AlertDialog.Builder(this).setTitle("입력과 복원 준비 취소").setMessage("현재 미저장 입력과 복원 준비를 취소하고 최신 자료를 볼까요?").setPositiveButton("취소 후 최신 자료 보기",(d,w)->backupSession.cancel(this,backupSession.operation,true)).setNegativeButton("입력 유지",null).show()));
        if(!storageUnavailable&&!phase.equals("LOADING")&&!phase.equals("COMMITTING")&&!phase.equals("BACKUP_WRITING")&&!phase.equals("CONFLICT"))progress.addView(sessionButton("준비 취소",()->backupSession.cancel(this,backupSession.operation,false)));setContentView(progress);
    }
    private Button sessionButton(String label,Runnable action){Button button=new Button(this);button.setText(label);button.setOnClickListener(v->action.run());return button;}
    private void showRestoreConfirmation(long id){if(!backupSession.attached(this,id)||backupSession.busy||backupSession.prepared==null||backupSession.cancelRequested||!(backupSession.phase.equals("PREPARED")||backupSession.phase.equals("FAILED_PREPARED")))return;if(restoreConfirmation!=null&&confirmationOperation==id)return;StateCodec.State imported=backupSession.prepared.stateCopy;confirmationOperation=id;restoreConfirmation=new AlertDialog.Builder(this).setTitle(backupSession.failure==null?"백업 자료 복원":"복원 실패 · 준비 보존").setMessage((backupSession.failure==null?"":backupSession.failure.getMessage()+"\n\n")+"보관 작업 "+imported.jobs.size()+"개와 색상 규칙을 불러옵니다. 현재 자료 전체를 백업 시점으로 교체합니다. 필요한 자료는 먼저 전체 백업해 주세요.").setPositiveButton("교체하여 복원",(d,w)->{restoreConfirmation=null;backupSession.commit(this,id);}).setNegativeButton("취소",(d,w)->{restoreConfirmation=null;backupSession.cancel(this,id,false);}).setOnCancelListener(d->{restoreConfirmation=null;backupSession.cancel(this,id,false);}).create();restoreConfirmation.show();}
    private void backupSessionChanged(BackupRestoreSession session,long id){if(backupSession!=session||!session.attached(this,id))return;repository=session.repository;store=session.store;exportMediaStore=session.media;if(session.blocked()){if(session.kind.equals("restore")){pendingExport=session.recoveredExport;pendingBackup=session.pendingBackup;pendingBackupAt=session.pendingBackupAt;}if(session.kind.equals("backup")&&session.pendingBackup!=null){pendingBackup=session.pendingBackup;pendingBackupAt=session.pendingBackupAt;exportKind="backup";}showBackupProgress();if(session.phase.equals("PREPARED")||session.phase.equals("FAILED_PREPARED")){if(session.failure!=null)storageFailure(session.failure);if(!storageUnavailable)showRestoreConfirmation(id);}if(session.phase.equals("BACKUP_READY")||session.phase.equals("RESTORE_READY")){int request=session.issuePicker(this,id);if(request>=0){Intent picker=new Intent(session.kind.equals("backup")?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT);picker.addCategory(Intent.CATEGORY_OPENABLE);picker.setType(session.kind.equals("backup")?"application/octet-stream":"*/*");if(session.kind.equals("backup")){picker.putExtra(Intent.EXTRA_TITLE,backupFileName(session.pendingBackupAt));documentRequest=request;documentExportToken="backup@"+session.pendingBackupAt;documentPendingFingerprint=session.fingerprint;}try{startActivityForResult(picker,request);}catch(ActivityNotFoundException failure){session.pickerFailed(failure);}}}return;}
        if(!session.takeTerminal(this,id))return;pendingBackup=session.pendingBackup;pendingBackupAt=session.pendingBackupAt;if(session.kind.equals("load")){pendingExport=session.recoveredExport;exportKind=pendingExport!=null?pendingExport.options.get("route"):pendingBackup!=null?"backup":null;}else if(session.kind.equals("restore")){pendingExport=session.recoveredExport;pendingBackup=session.pendingBackup;pendingBackupAt=session.pendingBackupAt;}else if(session.kind.equals("backup")){exportKind=pendingBackup==null?null:"backup";documentRequest=-1;documentExportToken=null;documentPendingFingerprint=null;}
        if(session.failure!=null)storageFailure(session.failure);InventoryRepository.Snapshot latest=session.latest;if(latest!=null){if(state==null){adopt(latest);ready=true;}else if(session.discardInput){replaceUnconfirmedInput(latest,encodedBaseline(latest.stateCopy));}else try{if(canRefreshAfterResume(conflicted,dialogDraft!=null,quickInputChanged(),state,screenBaseline))adopt(latest);else if(!expectedGeneration.equals(latest.generation)||expectedRevision!=latest.revision)markConflict();}catch(IOException failure){storageFailure(failure);}}
        if(!ready){fatal("저장 자료를 읽지 못했습니다. 기존 파일을 보존했습니다.\n"+(session.failure==null?"":session.failure.getMessage()));return;}if(session.result!=null&&session.result.committed&&session.kind.equals("restore")){pendingSourceHistory=false;resetAdjustmentForm();historyVisible=20;tab=2;}render();reopenDialog();if(session.failure!=null)alert(session.externalSaved?"백업 파일은 저장됐습니다":"백업 · 복원 실패",session.failure.getMessage());else if(!session.kind.equals("load"))toast(session.result!=null&&session.result.committed?session.kind.equals("restore")?"백업을 복원했습니다.":"파일을 저장했습니다.":"준비를 취소했습니다.");
    }

    private JobStore store;
    private LinearLayout root,body;
    private EditText titleInput,sourceInput;
    private int tab=0;
    private int historyVisible=20;
    private int stockHistoryVisible=20;
    private String tableQuery="",changeQuery="";
    private String changeSkuFilter="",changeColorFilter="";
    private boolean legacySourcesExpanded;
    private boolean lowStockOnly;
    private AutoCompleteTextView quickSku,quickColor;
    private EditText quickQuantity;
    private Spinner quickSize;
    private TextView quickPreview,quickSkuTotal;
    private String quickSkuValue="",quickColorValue="",quickQtyValue="1",quickFeedback="";
    private int quickSizeValue=0;
    private String[] quickBaseline;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Runnable saveRunnable;
    private ExportSnapshot pendingExport;
    private MediaStore exportMediaStore;
    private byte[] pendingBackup;
    private long pendingBackupAt;
    private String exportKind;
    private String documentExportToken,documentPendingFingerprint;
    private int documentRequest=-1;
    private boolean ready=false;
    private boolean pendingSourceHistory=false;
    private final android.util.LruCache<String,android.graphics.Bitmap> rowPhotoCache=new android.util.LruCache<>(64);
    private boolean observingPhotos;
    private final android.database.ContentObserver photoObserver=new android.database.ContentObserver(handler){
        @Override public void onChange(boolean selfChange){if(observingPhotos)refreshPhotoState();}
    };
    private static final String SAMPLE="EY2UTS924 白色S2 M11 L9 XL10 黑色S2 M6 L9 XL11\nEY2UTS930 白色S2 M3 XL2 浅蓝色M2 XL1\nEY2UTS706 蓝色S1 M2 L3 白色M1 L1 XL1\nEY2UTS922 粉色S1 M2 L2 XL2 黑色M2 L3 XL2\nEY2UTS904 乳白色S1 L3 XL4\nEY2UTS908 黑色M8 L2 XL8 米色M1 L5 XL2\nEY2UTS709 米色M1 L1 碳灰色L1 XL1\nEY2UTS911 白色M1 XL1\nEY2UTS701 黑色L1 XL2\nEY5UTS901 白色M1 L1 XL1 黑色XL3\nEY5UTS904 黑色S1 XL1 白色M1";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(INK); getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        Object retained=getLastNonConfigurationInstance();
        UiDraft draft=retained instanceof UiDraft?(UiDraft)retained:null;
        synchronized(MainActivity.class){if(draft!=null&&draft.session!=null)processBackupSession=draft.session;if(processBackupSession==null)processBackupSession=new BackupRestoreSession(getApplicationContext());backupSession=processBackupSession;}
        repository=backupSession.repository;store=backupSession.store;exportMediaStore=backupSession.media;
        if(draft!=null){restoreRetained(draft);ready=true;}
        if(saved!=null) {
            tab=saved.getInt("tab",0); exportKind=saved.getString("exportKind");documentExportToken=saved.getString("documentExportToken");
            documentRequest=saved.getInt("documentRequest",-1);documentPendingFingerprint=saved.getString("documentPendingFingerprint");
            nextDocumentRequest=Math.max(nextDocumentRequest,saved.getInt("nextDocumentRequest",FIRST_DOCUMENT_REQUEST));
            tableQuery=saved.getString("tableQuery","");changeQuery=saved.getString("changeQuery","");lowStockOnly=saved.getBoolean("lowStockOnly",false);
            changeSkuFilter=saved.getString("changeSkuFilter","");changeColorFilter=saved.getString("changeColorFilter","");
            quickSkuValue=saved.getString("quickSku","");quickColorValue=saved.getString("quickColor","");quickQtyValue=saved.getString("quickQty","1");quickSizeValue=saved.getInt("quickSize",0);
        }
        if(backupSession.needsLoad())backupSession.initialize(exportKind);
        showBackupProgress();backupSession.attach(this);
    }
    @Override protected void onSaveInstanceState(Bundle out){capture();if(!backupBlocked()){flushSourceHistory();persist();}out.putInt("tab",tab);out.putString("tableQuery",tableQuery);out.putString("changeQuery",changeQuery);out.putString("changeSkuFilter",changeSkuFilter);out.putString("changeColorFilter",changeColorFilter);out.putBoolean("lowStockOnly",lowStockOnly);captureQuick();out.putString("quickSku",quickSkuValue);out.putString("quickColor",quickColorValue);out.putString("quickQty",quickQtyValue);out.putInt("quickSize",quickSizeValue);out.putString("exportKind",exportKind);out.putString("documentExportToken",documentExportToken);out.putInt("documentRequest",documentRequest);out.putString("documentPendingFingerprint",documentPendingFingerprint);out.putInt("nextDocumentRequest",nextDocumentRequest);if(pendingExport!=null||pendingBackup!=null)out.putString("pending","yes");super.onSaveInstanceState(out);}
    @Override public Object onRetainNonConfigurationInstance(){capture();if(captureDialogInput!=null)captureDialogInput.run();return ready?new UiDraft(state,screenBaseline,quickBaseline,expectedGeneration,expectedRevision,pendingSourceHistory,dialogDraft,conflicted,backupSession):null;}
    @Override protected void onStart(){super.onStart();registerPhotoObserver();}
    private void registerPhotoObserver(){
        if(observingPhotos)return;
        getContentResolver().registerContentObserver(Uri.parse("content://"+RuntimeIds.PHOTO_AUTHORITY),true,photoObserver);observingPhotos=true;
    }
    @Override protected void onResume(){
        super.onResume();registerPhotoObserver();if(backupSession!=null)backupSession.attach(this);refreshPhotoState();
    }
    private void refreshPhotoState(){
        if(!ready||storageUnavailable||backupBlocked()||transitioning)return;
        capture();captureQuick();
        try{
            boolean photoEditor=dialogDraft!=null&&("photo".equals(dialogDraft.kind)||"photo-confirm".equals(dialogDraft.kind));
            if(!canRefreshAfterResume(conflicted,dialogDraft!=null&&!photoEditor,quickInputChanged(),state,screenBaseline))return;
            InventoryRepository.Snapshot latest=repository.snapshot();
            if(!expectedGeneration.equals(latest.generation)||expectedRevision!=latest.revision){
                if(photoEditor&&captureDialogInput!=null)captureDialogInput.run();
                String rowId=photoEditor?dialogDraft.values[0]:null;
                transitioning=true;
                try{
                    if(photoEditor){if(activeEditor!=null){activeEditor.setOnDismissListener(null);editorDialogs.remove(activeEditor);activeEditor.dismiss();}activeEditor=null;captureDialogInput=null;dialogDraft=null;}
                    adopt(latest);render();
                }finally{transitioning=false;}
                if(rowId!=null&&!state.draft.completed){for(int i=0;i<state.draft.rows.size();i++)if(rowId.equals(state.draft.rows.get(i).rowId)){showPhotoBinding(i);break;}}
            }
        }catch(Exception failure){storageFailure(failure);toast("최신 자료를 읽지 못했습니다: "+failure.getMessage());}
    }
    @Override protected void onPause(){super.onPause();if(ready){capture();if(!backupBlocked()){flushSourceHistory();persist();}}}
    @Override protected void onStop(){if(observingPhotos){getContentResolver().unregisterContentObserver(photoObserver);observingPhotos=false;}detachBackupSession();super.onStop();}
    @Override protected void onDestroy(){if(exportPreview!=null){exportPreview.close();exportPreview=null;}detachBackupSession();handler.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public void onBackPressed(){if(backupBlocked()){backupSession.cancel(this,backupSession.operation,false);return;}if(tab!=0){navigate(0);}else super.onBackPressed();}
    private void fatal(String message){TextView v=text(message,18,INK);v.setPadding(dp(24),dp(40),dp(24),dp(24));setContentView(v);}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("tnum");return t;}
    private TextView heading(String value,int size){TextView t=text(value,size,INK);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private GradientDrawable background(int color,int border,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(border!=0)g.setStroke(dp(1),border);return g;}
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void space(LinearLayout layout,int height){View v=new View(this);layout.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private LinearLayout card(){LinearLayout c=vertical();c.setPadding(dp(17),dp(17),dp(17),dp(17));c.setBackground(background(Color.WHITE,LINE,16));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);body.addView(c,lp);return c;}
    private Button button(String label,boolean primary,Runnable run){Button b=new Button(this);b.setText(label);b.setTextSize(15);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextColor(primary?Color.WHITE:INK);b.setBackground(background(primary?TEAL:0xffeaf0eb,0,12));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(8),dp(8),dp(8),dp(8));b.setOnClickListener(v->run.run());return b;}
    private void fullButton(LinearLayout parent,String label,boolean primary,Runnable run){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(10);parent.addView(button(label,primary,run),lp);}
    private void buttons(LinearLayout parent,String[] names,boolean firstPrimary,Runnable... runs){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);for(int i=0;i<names.length;i++){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.leftMargin=dp(8);row.addView(button(names[i],firstPrimary&&i==0,runs[i]),lp);}LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(10);parent.addView(row,lp);}
    private EditText input(LinearLayout parent,String label,String value,boolean multiline){parent.addView(text(label,13,MUTED));EditText e=new EditText(this);e.setText(value);e.setTextSize(16);e.setTextColor(INK);e.setHintTextColor(0xff89958f);e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setBackground(background(BG,LINE,9));e.setSelectAllOnFocus(false);e.setSingleLine(!multiline);e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(multiline?android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE:0)|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(6);lp.bottomMargin=dp(13);parent.addView(e,lp);return e;}
    private void capture(){
        if(!ready||backupBlocked()||transitioning||state.draft.completed)return;
        if(titleInput!=null)state.draft.title=titleInput.getText().toString();
        if(sourceInput!=null){
            String value=sourceInput.getText().toString();
            if(state.draft.hasCurrentTable()&&state.draft.inputCleared)state.draft.updateInputBuffer(value,System.currentTimeMillis());
            else{
                String previous=state.draft.sourceText;
                state.draft.updateSourceInput(value,System.currentTimeMillis());
                if(!previous.equals(state.draft.sourceText))pendingSourceHistory=true;
            }
        }
    }
    private void restoreRetained(UiDraft draft){state=draft.state;screenBaseline=draft.baseline;quickBaseline=draft.quickBaseline;dialogDraft=draft.dialog;conflicted=draft.conflicted;expectedGeneration=draft.generation;expectedRevision=draft.revision;pendingSourceHistory=draft.pendingHistory;}
    private void adopt(InventoryRepository.Snapshot snapshot){state=snapshot.stateCopy;screenBaseline=encodedBaseline(snapshot.stateCopy);expectedGeneration=snapshot.generation;expectedRevision=snapshot.revision;}
    private static byte[] encodedBaseline(StateCodec.State confirmed){try{return StateCodec.encode(confirmed);}catch(IOException impossible){throw new IllegalStateException(impossible);}}
    private static boolean hasPendingInput(StateCodec.State edited,byte[] baseline)throws IOException{return !Arrays.equals(StateCodec.encode(edited),baseline);}
    private static boolean canRefreshAfterResume(boolean conflict,boolean hasEditor,boolean quickDirty,StateCodec.State edited,byte[] baseline)throws IOException{return !conflict&&!hasEditor&&!quickDirty&&!hasPendingInput(edited,baseline);}
    private static boolean shouldAutoSave(boolean conflict,StateCodec.State edited,byte[] baseline)throws IOException{return !conflict&&hasPendingInput(edited,baseline);}
    private void retainDialog(AlertDialog dialog,Runnable capture){activeEditor=dialog;editorDialogs.add(dialog);captureDialogInput=capture;capture.run();dialog.setOnDismissListener(ignored->{editorDialogs.remove(dialog);if(activeEditor==dialog){activeEditor=null;captureDialogInput=null;dialogDraft=null;}});}
    private void reopenDialog(){
        if(dialogDraft==null)return;
        DialogDraft pending=dialogDraft;
        if("row".equals(pending.kind))editRow(pending.index);
        else if("add".equals(pending.kind)||"manual-add".equals(pending.kind))showAddRow();
        else if("rule".equals(pending.kind))ruleDialog(pending.values[0],pending.values[1],pending.values[2]);
        else if("name".equals(pending.kind))saveNamed();
        else if("source-name".equals(pending.kind))renameSource(pending.values[0]);
        else if("job-name".equals(pending.kind))renameJob(pending.values[0]);
        else if("photo".equals(pending.kind))showPhotoBinding(pending.index);
        else if("photo-confirm".equals(pending.kind))showPhotoConfirmation(pending.index,pending.values);
        else if("manual-quick".equals(pending.kind)){if(quickSku==null||quickColor==null){tab=1;render();}manualQuickInput(pending.checked);}
    }
    private boolean accept(InventoryRepository.CommitResult result){
        if(!result.committed){markConflict();showConflictNotice();return false;}
        adopt(result.latestSnapshot);
        try{getContentResolver().notifyChange(Uri.parse("content://"+RuntimeIds.PHOTO_AUTHORITY),null);}catch(RuntimeException notificationFailure){toast("사진 화면에 갱신 알림을 보내지 못했습니다.");}
        return true;
    }
    private void markConflict(){conflicted=true;if(saveRunnable!=null){handler.removeCallbacks(saveRunnable);saveRunnable=null;}}
    private void showConflictNotice(){
        new AlertDialog.Builder(this).setTitle("자료가 변경되었습니다")
            .setMessage("다른 화면에서 자료를 먼저 저장했습니다. 현재 입력은 저장되지 않았으며 화면에 보존했습니다. 최신 자료 보기를 선택하면 미저장 입력을 버립니다.")
            .setNegativeButton("입력 유지",null)
            .setPositiveButton("미저장 입력을 버리고 최신 자료 보기",(dialog,button)->discardPendingInput()).show();
    }
    private void discardPendingInput(){
        if(backupBlocked())return;
        final InventoryRepository.Snapshot latest;final byte[] baseline;
        try{latest=repository.snapshot();baseline=encodedBaseline(latest.stateCopy);}
        catch(Exception failure){storageFailure(failure);alert("최신 자료를 열지 못했습니다","입력은 보존했습니다.\n\n"+failure.getMessage());return;}
        transitioning=true;
        try{replaceUnconfirmedInput(latest,baseline);render();}finally{transitioning=false;}
    }
    private void replaceUnconfirmedInput(InventoryRepository.Snapshot latest,byte[] baseline){
        if(saveRunnable!=null){handler.removeCallbacks(saveRunnable);saveRunnable=null;}
        for(AlertDialog editor:new ArrayList<>(editorDialogs)){editor.setOnDismissListener(null);editor.dismiss();}editorDialogs.clear();
        activeEditor=null;captureDialogInput=null;dialogDraft=null;pendingSourceHistory=false;conflicted=false;
        state=latest.stateCopy;screenBaseline=baseline;expectedGeneration=latest.generation;expectedRevision=latest.revision;
        resetAdjustmentForm();
    }
    /** Applies a detached screen only under its original expected token. No retry on conflict. */
    private String storageBytesLabel(){if(backupBlocked())return "확인 중";try{final long[] bytes={0};repository.exclusive(()->bytes[0]=store.storageBytes());return String.format(Locale.KOREA,"%,d",bytes[0])+"바이트";}catch(IOException failure){storageFailure(failure);return "확인할 수 없음";}}
    private boolean storageUnavailable;
    private void storageFailure(Exception failure){if(failure instanceof CommitUncertainException){storageUnavailable=true;if(saveRunnable!=null){handler.removeCallbacks(saveRunnable);saveRunnable=null;}}}
    private boolean persist(){
        if(!ready||backupBlocked()||storageUnavailable||conflicted||transitioning)return false;
        try{repository.exclusive(()->{});if(!shouldAutoSave(conflicted,state,screenBaseline))return true;}catch(IOException invalid){storageFailure(invalid);alert("입력을 저장하지 못했습니다",invalid.getMessage());return false;}
        return commitChange(()->{});
    }
    private boolean commitChange(Runnable mutation){
        if(!ready||backupBlocked()||storageUnavailable||transitioning)return false;
        if(conflicted){showConflictNotice();return false;}
        StateCodec.State previous=state;
        try{
            state=StateCodec.decode(StateCodec.encode(previous));
            mutation.run();
            final StateCodec.State edited=state;
            state=previous;
            InventoryRepository.CommitResult result=repository.commit(expectedGeneration,expectedRevision,(candidate,revision)->applyScreen(candidate,edited));
            return accept(result);
        }catch(IllegalArgumentException invalid){state=previous;alert("작업을 변경하지 않았습니다",invalid.getMessage());return false;}
        catch(Exception e){state=previous;storageFailure(e);alert("변경을 저장하지 못했습니다","입력은 화면에 보존했습니다. 저장 상태를 확인하지 못했습니다. 다시 저장하지 말고 제작자에게 문의해 주세요.\n\n"+e.getMessage());return false;}
    }
    private static void applyScreen(StateCodec.State candidate,StateCodec.State edited){
        candidate.draft=edited.draft;candidate.jobs=edited.jobs;candidate.recentJobs=edited.recentJobs;candidate.changes=edited.changes;candidate.stockHistory=edited.stockHistory;
        candidate.undoSnapshot=edited.undoSnapshot;candidate.lastBackupAt=edited.lastBackupAt;candidate.globalRules=edited.globalRules;candidate.skuRules=edited.skuRules;
    }
    private boolean commitTableChange(Runnable mutation,String ruleSku,String sourceColor,String replacementCode){
        return commitChange(()->{UndoManager.capture(state,ruleSku,sourceColor,replacementCode);mutation.run();});
    }
    private boolean editable(){
        if(backupBlocked())return false;
        if(!state.draft.completed)return true;
        toast("완료된 작업입니다. ‘완료 취소’ 후 수정해 주세요.");return false;
    }
    private boolean sameRows(List<Inventory.Row> before,List<Inventory.Row> after){if(before.size()!=after.size())return false;for(int i=0;i<before.size();i++)if(!ChangeHistory.sameRow(before.get(i),after.get(i)))return false;return true;}
    private void recordRuleChanges(StateCodec.State previous){
        recordRuleScope("공통",previous.globalRules,state.globalRules);
        Set<String> skus=new LinkedHashSet<>(previous.skuRules.keySet());skus.addAll(state.skuRules.keySet());
        for(String sku:skus)recordRuleScope(sku,previous.skuRules.get(sku),state.skuRules.get(sku));
    }
    private void recordRuleScope(String scope,Map<String,String> before,Map<String,String> after){
        if(before==null)before=Collections.emptyMap();if(after==null)after=Collections.emptyMap();Set<String> keys=new LinkedHashSet<>(before.keySet());keys.addAll(after.keySet());
        for(String key:keys){String a=before.get(key),b=after.get(key);if(Objects.equals(a,b))continue;ChangeHistory.record(state,"색상 규칙",a==null?"색상 규칙 추가":b==null?"색상 규칙 삭제":"색상 규칙 수정",scope+" · "+key+": "+(a==null?"미지정":a)+" → "+(b==null?"미지정":b)+"\n다음 자동 정리부터 적용");}
    }
    private void watch(EditText e){e.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int n){}public void onTextChanged(CharSequence s,int a,int b,int c){if(conflicted||transitioning||(e!=sourceInput&&e!=titleInput))return;if(saveRunnable!=null)handler.removeCallbacks(saveRunnable);saveRunnable=()->{capture();flushSourceHistory();persist();};handler.postDelayed(saveRunnable,700);}public void afterTextChanged(Editable e){}});}
    private void navigate(int next){if(backupBlocked())return;capture();flushSourceHistory();if(!persist())return;hideKeyboard();tab=next;render();}
    private void hideKeyboard(){View f=getCurrentFocus();if(f!=null)((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(f.getWindowToken(),0);}
    private void render(){
        if(backupBlocked()||!ready){showBackupProgress();return;}
        captureQuick();quickSku=null;quickColor=null;quickQuantity=null;quickSize=null;quickPreview=null;quickSkuTotal=null;
        titleInput=null;sourceInput=null;
        root=vertical();root.setBackgroundColor(BG);
        LinearLayout header=vertical();header.setPadding(dp(22),dp(19),dp(22),dp(18));header.setBackgroundColor(INK);
        TextView micro=text("QUANTITY NOTE   ·   OFFLINE",11,0xffb4d6c9);micro.setLetterSpacing(.12f);header.addView(micro);
        TextView brand=text("유란노트",28,BRAND_PINK);brand.setTypeface(Typeface.DEFAULT,Typeface.BOLD);header.addView(brand);
        header.addView(text("붙여넣고, 확인하고, 한 장으로.",14,0xffd8e7df));root.addView(header);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);body=vertical();body.setPadding(dp(16),dp(18),dp(16),dp(20));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(conflicted){LinearLayout warning=card();warning.addView(text("저장 안 됨 · 다른 화면에서 자료가 변경되었습니다. 입력을 보존했습니다.",14,AMBER));fullButton(warning,"입력 유지 또는 최신 자료 보기",false,()->showConflictNotice());}
        if(tab==0)showInput();else if(tab==1)showTable();else if(tab==2)showHistory();else if(tab==3)showRules();else showChanges();
        LinearLayout nav=new LinearLayout(this);nav.setPadding(dp(6),dp(6),dp(6),dp(8));nav.setBackgroundColor(Color.WHITE);String[] labels={"입력","정리표","보관함","색상 규칙","변경 내역"};
        for(int i=0;i<labels.length;i++){final int n=i;Button b=button(labels[i],tab==i,()->navigate(n));b.setTextSize(12);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(3),dp(7),dp(3),dp(7));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(52),1);lp.setMargins(dp(2),0,dp(2),0);nav.addView(b,lp);}showStockFooter();root.addView(nav);setContentView(root);
    }
    private String quantityText(long value){return String.format(Locale.KOREA,"%,d",value)+"개";}
    private String stockDelta(long initial,long remaining){if(initial<0)return "—";long delta=remaining-initial;return (delta>0?"+":"")+quantityText(delta);}
    private void showStockFooter(){
        Inventory.Job job=state.draft;boolean active=job.hasCurrentTable();long remaining=Inventory.grandTotal(job.rows);
        LinearLayout footer=new LinearLayout(this);footer.setTag("stock-footer");footer.setGravity(Gravity.CENTER_VERTICAL);footer.setPadding(dp(10),dp(9),dp(12),dp(9));footer.setBackground(background(0xffeaf3ed,LINE,0));
        LinearLayout details=vertical();footer.addView(details,new LinearLayout.LayoutParams(-1,-2));
        TextView label=heading("작업별 재고 · 기록 보기  ›",13);details.addView(label);space(details,4);
        LinearLayout values=new LinearLayout(this);String[] names={"최초 수량","변동","남은 재고"},tags={"stock-initial","stock-delta","stock-remaining"};
        String[] numbers={active?(job.initialQuantity<0?"기록 없음":quantityText(job.initialQuantity)):"—",active?stockDelta(job.initialQuantity,remaining):"—",active?quantityText(remaining):"—"};
        for(int i=0;i<3;i++){LinearLayout cell=vertical();cell.addView(text(names[i],11,MUTED));TextView amount=heading(numbers[i],i==2?21:17);amount.setTag(tags[i]);amount.setTextColor(i==2?TEAL:INK);amount.setSingleLine();amount.setAutoSizeTextTypeUniformWithConfiguration(11,i==2?21:17,1,android.util.TypedValue.COMPLEX_UNIT_SP);cell.addView(amount,new LinearLayout.LayoutParams(-1,dp(28)));values.addView(cell,new LinearLayout.LayoutParams(0,-2,1));}details.addView(values);
        TextView time=text(active?"마지막 수량 변경: "+TimeLabels.format(job.stockUpdatedAt):"새 작업 · 목록을 정리하면 수량이 기록됩니다.",11,MUTED);time.setTag("stock-updated");details.addView(time);
        String previous=state.stockHistory.isEmpty()?"새 작업의 재고는 별도로 계산합니다.":"직전 작업 마지막 재고: "+quantityText(state.stockHistory.get(0).finalQuantity);
        TextView previousView=text(previous,11,INK);previousView.setTag("stock-previous");details.addView(previousView);
        details.setContentDescription("작업별 재고 기록 보기");details.setOnClickListener(v->showStockHistory());footer.setOnClickListener(v->showStockHistory());root.addView(footer,new LinearLayout.LayoutParams(-1,-2));
    }
    private void stockRecordView(LinearLayout parent,String name,long initial,long remaining,long inputAt,long changedAt,long closedAt,String sessionId,long expectedCount){
        LinearLayout item=vertical();item.setPadding(dp(14),dp(12),dp(14),dp(12));item.setBackground(background(BG,LINE,10));
        item.addView(heading(name.trim().isEmpty()?"수량 정리표":name,17));space(item,8);
        item.addView(text("최초 "+(initial<0?"기록 없음":quantityText(initial))+"  ·  변동 "+stockDelta(initial,remaining),15,INK));space(item,4);item.addView(heading((closedAt>0?"마지막 재고 ":"남은 재고 ")+quantityText(remaining),22));space(item,10);
        item.addView(text("최초 입력: "+TimeLabels.format(inputAt),13,MUTED));space(item,4);item.addView(text("마지막 수량 변경: "+TimeLabels.format(changedAt),13,MUTED));
        if(closedAt>0){space(item,4);item.addView(text("작업 마감: "+TimeLabels.format(closedAt),13,MUTED));}
        LinearLayout details=vertical();details.setTag("stock-details");details.setVisibility(View.GONE);
        Button toggle=button("변동 내역 보기",false,()->{details.setVisibility(details.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);});
        toggle.setTag("stock-details-toggle");item.addView(toggle);item.addView(details);
        if(sessionId==null||sessionId.isEmpty()||expectedCount<0)details.addView(text("이전 버전의 작업이라 변동 내역을 확인할 수 없습니다.",13,MUTED));
        else{
            List<ChangeHistory.Entry> changes=StockHistory.changesForSession(state.changes,sessionId);
            long missing=StockHistory.missingChangeCount(expectedCount,changes.size());
            if(missing>0)details.addView(text("전체 "+expectedCount+"건 중 보관 "+changes.size()+"건 · "+missing+"건은 변경내역 초기화 또는 최근 500건 보관 한도로 제외되었습니다.",13,AMBER));
            if(changes.isEmpty()&&expectedCount==0)details.addView(text("아직 수정한 행이 없습니다.",13,MUTED));
            for(ChangeHistory.Entry change:changes){space(details,8);details.addView(text(TimeLabels.format(change.timestamp)+" · "+change.action,13,MUTED));details.addView(text(change.detail,14,INK));}
        }
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);parent.addView(item,lp);
    }
    private void showStockHistory(){
        LinearLayout content=vertical();content.setPadding(dp(18),dp(10),dp(18),dp(12));
        content.addView(text("새 작업으로 넘어갈 때 이전 작업의 마지막 수량을 자동 기록합니다. 최근 500건을 보관합니다.",14,MUTED));space(content,14);
        content.addView(heading("현재 작업",17));space(content,8);
        Inventory.Job current=state.draft;
        if(current.hasCurrentTable())stockRecordView(content,title(),current.initialQuantity,Inventory.grandTotal(current.rows),current.sourceRecordedAt,current.stockUpdatedAt,0,current.stockSessionId,current.stockChangeCount);
        else{content.addView(text("아직 시작한 재고 작업이 없습니다.",14,MUTED));space(content,16);}
        content.addView(heading("이전 작업의 마지막 기록",17));space(content,8);
        if(state.stockHistory.isEmpty())content.addView(text("새 작업을 시작하면 여기에 이전 재고가 남습니다.",14,MUTED));
        int shown=Math.min(stockHistoryVisible,state.stockHistory.size());for(int i=0;i<shown;i++){StockHistory.Entry entry=state.stockHistory.get(i);stockRecordView(content,entry.jobTitle,entry.initialQuantity,entry.finalQuantity,entry.sourceRecordedAt,entry.stockUpdatedAt,entry.closedAt,entry.stockSessionId,entry.changeCount);}
        ScrollView scroll=new ScrollView(this);scroll.addView(content);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("작업별 재고 기록").setView(scroll).setPositiveButton("닫기",null).create();
        if(shown<state.stockHistory.size())fullButton(content,"이전 재고 기록 더 보기",false,()->{stockHistoryVisible+=20;dialog.dismiss();showStockHistory();});dialog.show();
    }
    private void clearSourceInput(){
        if(!editable())return;
        if(sourceInput==null)return;
        if(sourceInput.getText().toString().isEmpty()){toast("입력란이 이미 비어 있습니다.");return;}
        String message=state.draft.hasCurrentTable()?"새 목록 입력칸을 비웁니다. 현재 정리표와 최초 원문은 보관됩니다. 새 목록을 입력한 뒤 ‘새 작업’ 또는 ‘기존 수량에 추가’를 선택하세요.":"새 목록 입력란의 내용을 비웁니다. 보관한 작업과 이전 재고 기록은 유지됩니다.";
        new AlertDialog.Builder(this).setTitle("입력 내용 지우기").setMessage(message).setPositiveButton("지우기",(d,w)->{
            if(sourceInput==null)return;
            capture();String previous=state.draft.sourceText;
            if(!commitChange(()->{if(state.draft.hasCurrentTable()){state.draft.inputCleared=true;state.draft.updateInputBuffer("",System.currentTimeMillis());}else{rememberSource(previous);state.draft.updateSourceInput("",System.currentTimeMillis());state.draft.inputCleared=false;}}))return;
            hideKeyboard();render();
        }).setNegativeButton("취소",null).show();
    }
    private void replaceDraft(Inventory.Job next,int nextTab){
        if(!commitChange(()->{long now=System.currentTimeMillis();WorkArchive.retainCurrent(state,now);Inventory.Job loaded=next;if(!next.id.isEmpty()){loaded=null;for(Inventory.Job saved:state.jobs)if(saved.id.equals(next.id)){loaded=copy(saved);break;}if(loaded==null)throw new IllegalArgumentException("보관 작업을 찾을 수 없습니다.");}boolean switching=state.draft.id.isEmpty()||!state.draft.id.equals(loaded.id);if(switching)StockHistory.finish(state,state.draft,now);retainReceiptCount(loaded);state.draft=loaded;if(switching)UndoManager.clear(state);} ))return;
        pendingSourceHistory=false;
        resetAdjustmentForm();tableQuery="";lowStockOnly=false;tab=nextTab;render();
    }
    private void retainReceiptCount(Inventory.Job job){
        if(job.stockSessionId==null||job.stockSessionId.isEmpty())return;
        for(StockHistory.Entry receipt:state.stockHistory)
            if(job.stockSessionId.equals(receipt.stockSessionId))job.stockChangeCount=Math.max(job.stockChangeCount,receipt.changeCount);
    }
    private void section(String label,String description){body.addView(heading(label,24));space(body,5);body.addView(text(description,14,MUTED));space(body,18);}
    private String title(){String s=state.draft.title.trim();return s.isEmpty()?"수량 정리표":s;}
    private void showInput(){
        boolean active=state.draft.hasCurrentTable();
        // A table built entirely by hand has no original text to protect, so its blank source field can accept a new list.
        if(active&&!state.draft.completed&&state.draft.sourceText.trim().isEmpty()&&!state.draft.inputCleared){state.draft.inputCleared=true;persist();}
        boolean staging=active&&state.draft.inputCleared;
        section(active?(staging?"새 목록을 입력하세요":"현재 작업을 이어서 하세요"):"목록을 붙여넣으세요",active?(staging?"현재 정리표와 최초 원문은 그대로 보관됩니다.":"수정한 표를 유지합니다. 다른 목록은 ‘새 작업’에서 시작하세요."):"줄바꿈이 없어도 품번과 색상별로 정리합니다.");
        LinearLayout c=card();titleInput=input(c,"작업 이름",state.draft.title,false);titleInput.setHint("예: 9월 28일 입고 목록");titleInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        Button clear=button("지우기",false,()->clearSourceInput());clear.setTag("input-clear");clear.setTextSize(13);clear.setTextColor(0xff9c4930);LinearLayout.LayoutParams clearLP=new LinearLayout.LayoutParams(dp(76),dp(48));clearLP.bottomMargin=dp(8);clearLP.gravity=Gravity.LEFT;c.addView(clear,clearLP);
        sourceInput=input(c,active?(staging?"새 목록 입력란":"최초 입력 목록 · 읽기 전용"):"중국어 원본 목록",staging?state.draft.inputBuffer:active?state.draft.sourceText:state.draft.sourceText,true);sourceInput.setHint(active?(staging?"새 목록을 붙여넣거나 직접 입력하세요":"지우기를 누르면 새 목록을 입력할 수 있습니다."):"EY2UTS924 白色S2 M11 L9 XL10 黑色S2 M6 L9 XL11");sourceInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Inventory.MAX_SOURCE_CHARS)});sourceInput.setMinLines(7);sourceInput.setGravity(Gravity.TOP);sourceInput.setMaxLines(14);watch(titleInput);
        if(state.draft.completed){titleInput.setEnabled(false);sourceInput.setEnabled(false);clear.setEnabled(false);space(c,6);c.addView(text("완료된 작업 · 편집 잠금. 정리표의 ‘완료 취소’ 후 입력을 수정할 수 있습니다.",14,AMBER));}
        final EditText watchedSource=sourceInput;
        if(active&&!staging){sourceInput.setKeyListener(null);sourceInput.setTextIsSelectable(true);sourceInput.setShowSoftInputOnFocus(false);}else if(staging){watch(sourceInput);sourceInput.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence old,int start,int count,int after){}public void onTextChanged(CharSequence value,int start,int before,int count){}public void afterTextChanged(Editable value){if(sourceInput!=watchedSource||transitioning)return;capture();}});}else{watch(sourceInput);sourceInput.addTextChangedListener(new TextWatcher(){
            private boolean bulk;
            public void beforeTextChanged(CharSequence old,int start,int count,int after){if(sourceInput!=watchedSource||transitioning)return;bulk=after>1;if(bulk&&old.length()>0)rememberSource(old.toString());}
            public void onTextChanged(CharSequence value,int start,int before,int count){}
            public void afterTextChanged(Editable value){if(sourceInput!=watchedSource||transitioning)return;capture();if(bulk){rememberSource(value.toString());persist();}}
        });}
        fullButton(c,active?(staging?"목록 자동 정리하기  →":"수정한 정리표 이어보기  →"):"목록 자동 정리하기  →",true,()->parse());
        if(state.draft.completed){Button parseButton=(Button)c.getChildAt(c.getChildCount()-1);parseButton.setEnabled(false);}
        if(active){buttons(c,new String[]{"새 작업","기존 수량에 추가"},false,()->startNew(),()->addStagedInput());}
        else buttons(c,new String[]{"실제 예시 넣기","새 작업"},false,()->{capture();if(!state.draft.sourceText.trim().isEmpty())confirm("예시 목록 넣기","입력 중인 원문을 예시 목록으로 바꿉니다.",()->{sourceInput.setText(SAMPLE);});else sourceInput.setText(SAMPLE);},()->startNew());
        if(!active)fullButton(c,"기존 수량에 추가",false,()->addStagedInput());
        fullButton(c,"작업 보관함 · "+state.jobs.size()+"개",false,()->navigate(2));
        LinearLayout note=card();note.addView(heading("이렇게 읽습니다",16));space(note,8);note.addView(text("白色S2 M3 XL2 → WH / S 2 · M 3 · L 0 · XL 2\n\n색상이 없으면 ‘미지정’, 없는 사이즈 수량은 0으로 보관합니다. 수량이 모두 0인 행도 편집표에 남습니다. 잘못된 숫자나 읽을 수 없는 내용은 오류로 알려드립니다.",14,MUTED));
        if(active)fullButton(note,"현재 정리표 보기 · "+state.draft.rows.size()+"행",false,()->navigate(1));
        body.addView(text("현재 작업은 자동 저장됩니다. 새 작업을 누르면 별도 작업으로 시작합니다.",12,MUTED));
    }
    private void parse(){
        if(!editable())return;
        capture();flushSourceHistory();if(!persist())return;hideKeyboard();
        if(state.draft.hasCurrentTable()&&state.draft.inputCleared){new AlertDialog.Builder(this).setTitle("목록 반영 방법").setItems(new String[]{"새 작업","기존 수량에 추가"},(dialog,index)->{if(index==0)parseStagedInput();else addStagedInput();}).setNegativeButton("취소",null).show();return;}
        // Re-entering input is continuation, even when the last remaining row was deleted.
        if(state.draft.hasCurrentTable()){tab=1;render();return;}
        rememberSource(state.draft.sourceText);if(!persist())return;
        Inventory.ParseResult result=Parser.parse(state.draft.sourceText,state.globalRules,state.skuRules);
        if(!result.isValid()){String message=joinLimited(result.errors,12);if(message.isEmpty())message="정리할 수량이 없습니다. 입력 예시를 확인해 주세요.";alert("입력을 확인해 주세요",message+"\n\n기존 정리표는 그대로 유지됩니다.");return;}
        Runnable accept=()->{Inventory.Job before=state.draft;if(!commitChange(()->{WorkArchive.retainCurrent(state,System.currentTimeMillis());UndoManager.clear(state);state.draft.rows=new ArrayList<>(result.rows);state.draft.updatedAt=System.currentTimeMillis();StockHistory.initializeParsed(state.draft,state.draft.updatedAt);if(!sameRows(before.rows,result.rows))ChangeHistory.record(state,title(),"목록 자동 정리","행 수: "+before.rows.size()+" → "+result.rows.size()+"행\n전체 수량: "+Inventory.grandTotal(before.rows)+" → "+Inventory.grandTotal(result.rows)+"개\n붙여넣은 원문으로 표를 정리했습니다.");}))return;resetAdjustmentForm();tab=1;render();if(!result.warnings.isEmpty())alert("정리 후 확인할 내용",joinLimited(result.warnings,15));};
        accept.run();
    }
    private void parseStagedInput(){
        final String text=state.draft.inputBuffer;final long inputAt=state.draft.inputBufferRecordedAt;
        if(text.trim().isEmpty()){toast("새 목록을 먼저 붙여넣거나 입력해 주세요.");return;}
        final Inventory.ParseResult result=Parser.parse(text,state.globalRules,state.skuRules);
        if(!result.isValid()){String message=joinLimited(result.errors,12);if(message.isEmpty())message="정리할 수량이 없습니다. 입력 예시를 확인해 주세요.";alert("새 목록을 확인해 주세요",message+"\n\n현재 정리표와 입력 내용은 그대로 유지됩니다.");return;}
        beforeReplace(()->{
            final long now=System.currentTimeMillis();final Inventory.Job next=new Inventory.Job();next.title="";next.sourceText=text;next.sourceRecordedAt=inputAt>0?inputAt:now;next.rows=new ArrayList<>();for(Inventory.Row row:result.rows)next.rows.add(row.copy());next.updatedAt=now;StockHistory.initializeParsed(next,now);
            if(!commitChange(()->{WorkArchive.retainCurrent(state,now);StockHistory.finish(state,state.draft,now);if(state.draft.inputCleared&&state.draft.inputBuffer.equals(text)){state.draft.inputBuffer="";state.draft.inputBufferRecordedAt=0;WorkArchive.retainCurrent(state,now);}WorkArchive.createNew(state,next,now);UndoManager.clear(state);ChangeHistory.record(state,next.title,"목록 자동 정리","새 작업을 시작했습니다.\n행 수: 0 → "+result.rows.size()+"행\n전체 수량: 0 → "+Inventory.grandTotal(result.rows)+"개\n새 입력란의 목록을 정리했습니다.");}))return;
            resetAdjustmentForm();tableQuery="";lowStockOnly=false;tab=1;render();if(!result.warnings.isEmpty())alert("정리 후 확인할 내용",joinLimited(result.warnings,15));
        });
    }
    private void addStagedInput(){
        if(backupBlocked())return;
        capture();flushSourceHistory();if(!persist())return;hideKeyboard();
        final String raw=state.draft.inputCleared?state.draft.inputBuffer:!state.draft.hasCurrentTable()?state.draft.sourceText:"";
        final long inputAt=state.draft.inputCleared?state.draft.inputBufferRecordedAt:state.draft.sourceRecordedAt;
        final List<Inventory.Job> targets=new ArrayList<>();final List<String> labels=new ArrayList<>();
        for(Inventory.Job saved:state.jobs){targets.add(saved);labels.add((state.draft.id.equals(saved.id)?"현재 작업 · ":"보관 작업 · ")+(saved.title.trim().isEmpty()?"수량 정리표":saved.title)+" · "+saved.rows.size()+"행"+(saved.completed?" · 완료·잠김":""));}
        if(targets.isEmpty()){alert("보관 작업이 없습니다","입력에서 ‘새 작업’을 눌러 먼저 작업을 시작해 주세요.");return;}
        new AlertDialog.Builder(this).setTitle("불러와 수량을 추가할 작업 선택").setItems(labels.toArray(new String[0]),(dialog,index)->{
            Inventory.Job chosen=targets.get(index);
            addInputToJob(state.draft.id.equals(chosen.id),chosen.id,raw,inputAt);
        }).setNegativeButton("취소",null).show();
    }
    private void addInputToJob(boolean current,String targetId,String raw,long inputAt){
        Inventory.Job selected=current?state.draft:null;
        if(!current)for(Inventory.Job saved:state.jobs)if(saved.id.equals(targetId)){selected=saved;break;}
        if(selected==null){toast("추가할 작업을 찾을 수 없습니다. 다시 선택해 주세요.");return;}
        if(raw.trim().isEmpty()){
            beforeReplace(()->{Inventory.Job latest=null;for(Inventory.Job saved:state.jobs)if(saved.id.equals(targetId)){latest=saved;break;}if(latest==null){toast("보관 작업을 찾을 수 없습니다.");return;}replaceDraft(copy(latest),1);});return;
        }
        if(selected.completed){toast("완료된 작업에는 수량을 추가할 수 없습니다.");return;}
        final Inventory.ParseResult result=Parser.parse(raw,state.globalRules,state.skuRules);
        if(!result.isValid()){alert("추가할 목록을 확인해 주세요",joinLimited(result.errors,12)+"\n현재 정리표와 입력 내용은 그대로 유지됩니다.");return;}
        final Inventory.Job before=selected,changed=copy(before);
        final LinkedHashMap<String,Inventory.Row> originals=new LinkedHashMap<>(),updated=new LinkedHashMap<>();
        try{
            for(Inventory.Row incoming:result.rows){
                Inventory.Row target=null;
                for(Inventory.Row existing:changed.rows){
                    if(!PhotoContract.sameKey(existing.sku,incoming.sku))continue;
                    if(!PhotoContract.sameKey(existing.color,incoming.color)
                            &&!PhotoContract.sameKey(existing.sourceColor,incoming.sourceColor))continue;
                    if(target!=null)throw new IllegalArgumentException(incoming.sku+" "+incoming.color+"에 해당하는 행이 여러 개입니다. 먼저 정리표에서 해당 행을 정리해 주세요.");
                    target=existing;
                }
                if(target==null&&!Parser.MISSING_COLOR.equals(incoming.sourceColor)){
                    for(Inventory.Row existing:changed.rows){
                        if(!PhotoContract.sameKey(existing.sku,incoming.sku)||existing.total()!=0
                                ||!Parser.MISSING_COLOR.equals(existing.sourceColor)||!Parser.MISSING_COLOR.equals(existing.color))continue;
                        if(target!=null)throw new IllegalArgumentException(incoming.sku+"에 색상 미지정 행이 여러 개입니다. 먼저 색상을 지정해 주세요.");
                        target=existing;
                    }
                }
                if(target==null){
                    if(changed.rows.size()>=Inventory.MAX_ROWS)throw new IllegalArgumentException("한 작업은 2,000행까지 지원합니다.");
                    target=incoming.copy();changed.rows.add(target);originals.put(target.rowId,null);
                }else{
                    if(!originals.containsKey(target.rowId))originals.put(target.rowId,target.copy());
                    if(target.total()==0&&Parser.MISSING_COLOR.equals(target.sourceColor)&&Parser.MISSING_COLOR.equals(target.color)
                            &&!Parser.MISSING_COLOR.equals(incoming.sourceColor)){
                        PhotoBindings.identityEdited(target,incoming.sku,incoming.sourceColor);
                        target.sku=incoming.sku;target.sourceColor=incoming.sourceColor;target.color=incoming.color;
                    }
                    for(int i=0;i<Inventory.SIZES.length;i++){
                        if(incoming.quantities[i]>Inventory.MAX_QUANTITY-target.quantities[i])throw new IllegalArgumentException(incoming.sku+" "+incoming.color+" "+Inventory.SIZES[i]+" 수량이 999,999를 넘습니다.");
                        target.quantities[i]+=incoming.quantities[i];
                    }
                }
                updated.put(target.rowId,target);
            }
            final long now=System.currentTimeMillis();changed.updatedAt=now;
            for(Map.Entry<String,Inventory.Row> entry:updated.entrySet())StockHistory.noteRowChange(before,changed,originals.get(entry.getKey()),entry.getValue(),now);
            if(current&&changed.inputCleared){changed.inputBuffer="";changed.inputBufferRecordedAt=0;}
            if(!commitChange(()->{
                WorkArchive.retainCurrent(state,now);
                Inventory.Job latest=null;for(Inventory.Job saved:state.jobs)if(saved.id.equals(targetId)){latest=saved;break;}
                if(latest==null)throw new IllegalArgumentException("보관 작업을 찾을 수 없습니다.");
                if(latest.completed)throw new IllegalArgumentException("완료된 작업에는 수량을 추가할 수 없습니다.");
                if(!current){
                    StockHistory.finish(state,state.draft,now);
                    if(state.draft.inputCleared&&state.draft.inputBuffer.equals(raw)){state.draft.inputBuffer="";state.draft.inputBufferRecordedAt=0;}
                    else if(!state.draft.hasCurrentTable()&&state.draft.sourceText.equals(raw)){state.draft.sourceText="";state.draft.sourceRecordedAt=0;}
                    WorkArchive.retainCurrent(state,now);
                }
                state.draft=copy(latest);retainReceiptCount(state.draft);if(!current)UndoManager.clear(state);
                UndoManager.capture(state,null,null,null);changed.stockChangeCount=Math.max(changed.stockChangeCount,state.draft.stockChangeCount);state.draft=changed;
                for(Map.Entry<String,Inventory.Row> entry:updated.entrySet())ChangeHistory.recordRow(state,changed,changed.title,"목록 수량 추가",originals.get(entry.getKey()),entry.getValue());
                if(!current){boolean closed=false;for(StockHistory.Entry receipt:state.stockHistory)if(receipt.stockSessionId.equals(changed.stockSessionId)){closed=true;break;}if(closed)StockHistory.finish(state,changed,now);}
            }))return;
            resetAdjustmentForm();tableQuery="";lowStockOnly=false;tab=1;render();toast((changed.title.trim().isEmpty()?"수량 정리표":changed.title)+"에 목록 수량을 추가했습니다.");
            if(!result.warnings.isEmpty())alert("추가 후 확인할 내용",joinLimited(result.warnings,15));
        }catch(IllegalArgumentException failure){alert("수량을 추가하지 않았습니다",failure.getMessage());}
    }
    private String joinLimited(List<String> items,int count){StringBuilder s=new StringBuilder();for(int i=0;i<Math.min(items.size(),count);i++)s.append("• ").append(items.get(i)).append('\n');if(items.size()>count)s.append("외 ").append(items.size()-count).append("건");return s.toString();}
    private boolean unresolved(Inventory.Row r){return !r.color.matches("[A-Z][A-Z0-9_-]{0,15}");}
    private int unresolvedCount(){int n=0;for(Inventory.Row r:state.draft.rows)if(unresolved(r))n++;return n;}
    private void showTable(){
        Button original=button("최초 입력 목록 보기",false,()->showOriginalSource());
        LinearLayout.LayoutParams originalLP=new LinearLayout.LayoutParams(-1,-2);originalLP.bottomMargin=dp(16);body.addView(original,originalLP);
        section(title(),state.draft.completed?"완료 · 편집 잠금. 조회와 출력은 계속할 수 있습니다.":"위에서 수량을 조정하거나, 아래 행을 눌러 수정하세요.");
        if(state.draft.completed){LinearLayout notice=card();notice.addView(heading("완료 · 편집 잠금",17));notice.addView(text("수정하려면 ‘완료 취소’를 눌러 주세요.",13,MUTED));}
        showQuickAdjustment();
        if(state.draft.rows.isEmpty()){LinearLayout empty=card();boolean active=state.draft.hasCurrentTable();empty.addView(heading(active?"현재 정리표가 비어 있습니다":"아직 정리된 목록이 없어요",19));if(active){space(empty,7);empty.addView(text("마지막 행을 지웠다면 ‘되돌리기’로 복구할 수 있습니다. 새 목록은 ‘새 작업’에서 시작하세요.",14,MUTED));showTableActions(empty);fullButton(empty,"새 작업",true,()->startNew());}else fullButton(empty,"목록 붙여넣기",true,()->navigate(0));Button add=button("직접 행 추가",false,()->editRow(-1));add.setEnabled(!state.draft.completed);LinearLayout.LayoutParams addLP=new LinearLayout.LayoutParams(-1,-2);addLP.topMargin=dp(10);empty.addView(add,addLP);return;}
        LinearLayout summary=card();summary.addView(text("전체 수량",13,MUTED));TextView number=heading(String.format(Locale.KOREA,"%,d",Inventory.grandTotal(state.draft.rows))+" 개",36);summary.addView(number);Set<String> skus=new HashSet<>();for(Inventory.Row r:state.draft.rows)skus.add(PhotoContract.key(r.sku));summary.addView(text(skus.size()+"개 품번  ·  "+state.draft.rows.size()+"개 색상 행",14,MUTED));space(summary,12);quantityStrip(summary,Inventory.totals(state.draft.rows),Inventory.grandTotal(state.draft.rows),true);
        int unknown=unresolvedCount();if(unknown>0){space(summary,12);summary.addView(text("색상 코드 확인 "+unknown+"행 · 노란색 행을 눌러 지정해 주세요.",14,AMBER));}
        buttons(summary,new String[]{"작업 저장","행 추가"},true,()->saveNamed(),()->editRow(-1));
        if(state.draft.completed)((Button)((LinearLayout)summary.getChildAt(summary.getChildCount()-1)).getChildAt(1)).setEnabled(false);
        showTableActions(summary);
        LinearLayout export=card();export.addView(heading("파일로 저장 · A4 인쇄",16));space(export,5);export.addView(text("큰 글씨를 유지하며 긴 목록은 페이지를 나눕니다.",13,MUTED));buttons(export,new String[]{"엑셀","PDF","인쇄","공유"},false,()->prepareExport("xlsx"),()->prepareExport("pdf"),()->prepareExport("print"),()->prepareExport("share"));
        LinearLayout search=card();search.addView(heading("품번 검색 · 저재고",17));
        EditText skuSearch=input(search,"품번 일부 입력",tableQuery,false);skuSearch.setTag("sku-search");skuSearch.setHint("예: EY2UTS924");skuSearch.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        skuSearch.setOnEditorActionListener((view,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){applyTableSearch(skuSearch.getText().toString());return true;}return false;});
        Button searchButton=button("검색",false,()->applyTableSearch(skuSearch.getText().toString()));search.addView(searchButton,new LinearLayout.LayoutParams(-1,-2));
        CheckBox low=new CheckBox(this);low.setTag("low-stock-filter");low.setText("저재고만 보기 · 사이즈 수량 1~5개");low.setTextColor(INK);low.setTextSize(14);low.setChecked(lowStockOnly);search.addView(low);
        low.setOnCheckedChangeListener((view,checked)->{lowStockOnly=checked;tableQuery=skuSearch.getText().toString().trim();hideKeyboard();render();});
        List<Inventory.Row> visibleRows=TableSearch.filterRows(state.draft.rows,tableQuery,lowStockOnly);
        TextView shown=text(visibleRows.size()+" / "+state.draft.rows.size()+"개 색상 행 · 전체 합계는 모든 행 기준",13,MUTED);shown.setTag("filtered-count");search.addView(shown);
        if(visibleRows.isEmpty()){space(search,7);search.addView(text("검색 결과가 없습니다. 품번이나 저재고 조건을 확인해 주세요.",14,AMBER));}
        if(!tableQuery.isEmpty()||lowStockOnly)fullButton(search,"필터 초기화",false,()->{tableQuery="";lowStockOnly=false;render();});
        for(Inventory.Row row:visibleRows){
            final int index=state.draft.rows.indexOf(row);LinearLayout c=card();if(unresolved(row))c.setBackground(background(0xfffff7e8,0xffe8cf9f,14));
            PhotoCatalog.Binding displayPhoto=PhotoBindings.displayBinding(state,row);
            LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);TextView sku=heading(row.sku,18);head.addView(sku,new LinearLayout.LayoutParams(0,-2,1));head.addView(rowPhotoThumbnail(row,displayPhoto));TextView code=heading(row.color,17);code.setTextColor(unresolved(row)?AMBER:TEAL);head.addView(code);c.addView(head);space(c,4);c.addView(text(row.sourceColor+"   ·   "+(index+1)+"번째 행   ·   눌러서 수정",12,MUTED));space(c,13);quantityStrip(c,row.quantities,row.total(),false,true);
            TextView photoStatus=text(row.binding!=null&&row.binding.needsConfirmation?"사진 재확인 필요":displayPhoto==null?"사진 미등록 · 등록 사진 또는 연결을 확인하세요":row.binding==null?"등록 사진 표시 중":"사진 연결됨",13,row.binding!=null&&row.binding.needsConfirmation?AMBER:MUTED);c.addView(photoStatus);
            Button photo=button("사진 연결 확인",false,()->showPhotoBinding(index));photo.setTag("photo-binding-"+row.rowId);photo.setEnabled(!state.draft.completed);c.addView(photo);
            c.setOnClickListener(v->editRow(index));c.setContentDescription(row.sku+" "+row.color+" 합계 "+row.total()+". 눌러서 수정");
        }
    }
    private View rowPhotoThumbnail(Inventory.Row row,PhotoCatalog.Binding binding){
        android.graphics.Bitmap bitmap=null;
        if(binding!=null){
            bitmap=rowPhotoCache.get(binding.photoVersionId);
            if(bitmap==null)try{
                PhotoCatalog.Asset asset=state.catalog.assets.get(binding.photoVersionId);
                if(asset!=null){
                    android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inSampleSize=1;int edge=Math.max(asset.width,asset.height);
                    while(edge/options.inSampleSize>128)options.inSampleSize*=2;
                    try(InputStream stream=exportMediaStore.open(binding.photoVersionId)){bitmap=android.graphics.BitmapFactory.decodeStream(stream,null,options);}
                    if(bitmap!=null)rowPhotoCache.put(binding.photoVersionId,bitmap);
                }
            }catch(IOException failure){bitmap=null;}
        }
        View thumbnail;
        if(bitmap!=null){ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setContentDescription(row.sku+" "+row.color+" 등록 사진");thumbnail=image;}
        else{TextView missing=text(binding==null?"사진\n미등록":"사진\n없음",11,MUTED);missing.setGravity(Gravity.CENTER);missing.setBackground(background(BG,LINE,8));thumbnail=missing;}
        thumbnail.setTag("row-photo-"+row.rowId);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(56),dp(56));params.rightMargin=dp(10);thumbnail.setLayoutParams(params);return thumbnail;
    }
    private void showTableActions(LinearLayout parent){
        LinearLayout actions=new LinearLayout(this);
        Button undo=button("되돌리기",false,()->undoLastChange());undo.setTag("undo-last-change");undo.setEnabled(UndoManager.canUndo(state));actions.addView(undo,new LinearLayout.LayoutParams(0,-2,1));
        Button complete=button(state.draft.completed?"완료 취소":"작업 완료",false,()->toggleCompleted());complete.setTag("complete-toggle");LinearLayout.LayoutParams completeLP=new LinearLayout.LayoutParams(0,-2,1);completeLP.leftMargin=dp(8);actions.addView(complete,completeLP);
        LinearLayout.LayoutParams actionsLP=new LinearLayout.LayoutParams(-1,-2);actionsLP.topMargin=dp(10);parent.addView(actions,actionsLP);
    }
    private void applyTableSearch(String query){tableQuery=query.trim();hideKeyboard();render();}
    private void undoLastChange(){
        if(!editable()||!UndoManager.canUndo(state))return;
        boolean photo=state.undoSnapshot.kind==UndoSnapshot.PHOTO_BINDING;
        if(photo){if(!commitPhotoChange((candidate,revision)->{if(!UndoManager.undoPhoto(candidate,revision))throw new IllegalArgumentException("사진 연결 뒤 행이나 규칙이 변경되어 되돌릴 수 없습니다.");}))return;}
        else if(!commitChange(()->UndoManager.undo(state,title())))return;
        resetAdjustmentForm();render();toast(photo?"마지막 사진 변경을 되돌렸습니다.":"마지막 표 변경을 되돌렸습니다.");
    }
    private void toggleCompleted(){
        boolean next=!state.draft.completed;
        confirm(next?"작업 완료":"완료 취소",next?"현재 정리표를 완료하고 입력과 표 수정을 잠급니다.":"완료 잠금을 해제하고 표를 다시 수정합니다.",()->{
            if(!commitChange(()->{WorkArchive.setCompleted(state,next);}))return;
            render();toast(next?"작업을 완료하고 잠갔습니다.":"편집 잠금을 해제했습니다.");
        });
    }
    private void showOriginalSource(){
        LinearLayout content=vertical();content.setPadding(dp(20),dp(12),dp(20),dp(16));
        content.addView(heading("최초 입력 시각",15));space(content,5);content.addView(heading(state.draft.sourceRecordedAt==0?"기록 없음 (이전 버전 자료 또는 직접 입력)":TimeLabels.format(state.draft.sourceRecordedAt),16));space(content,14);
        content.addView(text("처음 붙여넣은 원문입니다. 조회만 가능하며 현재 표의 수정 내용은 원문에 반영되지 않습니다.",13,MUTED));space(content,14);
        String source=state.draft.sourceText;
        TextView original=text(source.trim().isEmpty()?"직접 입력한 작업이라 붙여넣은 원문이 없습니다.":source,16,INK);
        original.setTextIsSelectable(true);original.setPadding(0,0,0,dp(8));content.addView(original,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("최초 입력 목록").setView(scroll).setPositiveButton("닫기",null).show();
    }
    private void captureQuick(){
        if(quickSku!=null)quickSkuValue=quickSku.getText().toString();
        if(quickColor!=null)quickColorValue=quickColor.getText().toString();
        if(quickQuantity!=null)quickQtyValue=quickQuantity.getText().toString();
        if(quickSize!=null)quickSizeValue=quickSize.getSelectedItemPosition();
    }
    private String[] quickValues(){return new String[]{quickSkuValue,quickColorValue,quickQtyValue,String.valueOf(quickSizeValue)};}
    private boolean quickInputChanged(){return quickBaseline!=null&&!Arrays.equals(quickBaseline,quickValues());}
    private void resetAdjustmentForm(){quickBaseline=null;quickSku=null;quickColor=null;quickQuantity=null;quickSize=null;quickSkuValue="";quickColorValue="";quickQtyValue="1";quickSizeValue=0;quickFeedback="";}
    private AutoCompleteTextView menuInput(LinearLayout parent,String label,String value){
        parent.addView(text(label+"  ▾",13,MUTED));AutoCompleteTextView view=new AutoCompleteTextView(this);
        view.setText(value);view.setTextSize(16);view.setTextColor(INK);view.setHint("선택하세요");view.setHintTextColor(MUTED);
        view.setSingleLine();view.setFocusable(false);view.setCursorVisible(false);view.setPadding(dp(12),dp(12),dp(10),dp(12));
        view.setBackground(background(BG,LINE,9));view.setMinHeight(dp(48));view.setContentDescription(label+" 선택 메뉴");
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(6);parent.addView(view,lp);return view;
    }
    private void showQuickAdjustment(){
        if(state.draft.completed){LinearLayout locked=card();locked.addView(heading("수량 바로 조정 · 잠김",17));locked.addView(text("완료 취소 후 수량을 변경할 수 있습니다.",13,MUTED));return;}
        if(quickSkuValue.isEmpty()&&!state.draft.rows.isEmpty()){quickSkuValue=state.draft.rows.get(0).sku;quickColorValue=state.draft.rows.get(0).color;}
        LinearLayout c=card();LinearLayout quickHeader=new LinearLayout(this);quickHeader.setGravity(Gravity.CENTER_VERTICAL);quickHeader.addView(heading("수량 바로 조정",20),new LinearLayout.LayoutParams(0,-2,1));quickSkuTotal=text("",15,TEAL);quickSkuTotal.setGravity(Gravity.END);quickSkuTotal.setPadding(dp(8),0,0,0);quickHeader.addView(quickSkuTotal,new LinearLayout.LayoutParams(-2,-2));c.addView(quickHeader);space(c,5);c.addView(text("품번과 색상을 선택한 뒤 수량을 더하거나 빼세요.",13,MUTED));space(c,12);
        LinearLayout selectors=new LinearLayout(this);LinearLayout skuBox=vertical(),colorBox=vertical();
        selectors.addView(skuBox,new LinearLayout.LayoutParams(0,-2,1.5f));LinearLayout.LayoutParams colorLP=new LinearLayout.LayoutParams(0,-2,1);colorLP.leftMargin=dp(8);selectors.addView(colorBox,colorLP);c.addView(selectors);
        quickSku=menuInput(skuBox,"품번",quickSkuValue);quickColor=menuInput(colorBox,"색상 코드",quickColorValue);
        quickSku.setOnClickListener(v->chooseQuickSku());quickColor.setOnClickListener(v->chooseQuickColor());space(c,12);
        LinearLayout numbers=new LinearLayout(this);LinearLayout sizeBox=vertical(),qtyBox=vertical();
        numbers.addView(sizeBox,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams qtyLP=new LinearLayout.LayoutParams(0,-2,1.5f);qtyLP.leftMargin=dp(8);numbers.addView(qtyBox,qtyLP);c.addView(numbers);
        sizeBox.addView(text("사이즈",13,MUTED));quickSize=new Spinner(this);ArrayAdapter<String> sizes=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,Inventory.SIZES);sizes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);quickSize.setAdapter(sizes);quickSize.setSelection(Math.max(0,Math.min(3,quickSizeValue)));quickSize.setBackground(background(BG,LINE,9));quickSize.setPadding(dp(8),0,dp(8),0);LinearLayout.LayoutParams sizeLP=new LinearLayout.LayoutParams(-1,dp(50));sizeLP.topMargin=dp(6);sizeBox.addView(quickSize,sizeLP);
        quickQuantity=input(qtyBox,"변경 수량",quickQtyValue,false);quickQuantity.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);quickQuantity.setSelectAllOnFocus(true);quickQuantity.setHint("예: 2");
        quickPreview=text("",14,TEAL);c.addView(quickPreview);
        if(quickBaseline==null){captureQuick();quickBaseline=quickValues();}
        buttons(c,new String[]{"+ 추가","− 차감"},true,()->applyQuickAdjustment(false),()->applyQuickAdjustment(true));
        if(!quickFeedback.isEmpty()){space(c,9);c.addView(text(quickFeedback,14,TEAL));}
        quickQuantity.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){quickQtyValue=s.toString();updateQuickPreview();}public void afterTextChanged(Editable e){}});
        quickSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> parent,View view,int position,long id){quickSizeValue=position;updateQuickPreview();}public void onNothingSelected(AdapterView<?> parent){}});
        updateQuickPreview();
    }
    private void chooseQuickSku(){
        captureQuick();LinkedHashSet<String> unique=new LinkedHashSet<>();for(Inventory.Row row:state.draft.rows)unique.add(row.sku);
        ArrayList<String> choices=new ArrayList<>(unique);choices.add("＋ 새 품번 직접 입력");
        new AlertDialog.Builder(this).setTitle("불러온 품번 선택").setItems(choices.toArray(new String[0]),(d,index)->{
            if(index==choices.size()-1){manualQuickInput(true);return;}quickSkuValue=choices.get(index);quickSku.setText(quickSkuValue);
            quickColorValue="";for(Inventory.Row row:state.draft.rows)if(PhotoContract.sameKey(row.sku,quickSkuValue)){quickColorValue=row.color;break;}
            quickColor.setText(quickColorValue);quickFeedback="";updateQuickPreview();
        }).setNegativeButton("취소",null).show();
    }
    private void chooseQuickColor(){
        captureQuick();if(quickSkuValue.trim().isEmpty()){chooseQuickSku();return;}
        LinkedHashMap<String,String> available=new LinkedHashMap<>();for(Inventory.Row row:state.draft.rows)if(PhotoContract.sameKey(row.sku,quickSkuValue))available.put(row.color,row.color+(row.sourceColor.equals(row.color)?"":" · "+row.sourceColor));
        for(String code:state.globalRules.values())if(!available.containsKey(code))available.put(code,code+" · 공통 코드");
        ArrayList<String> keys=new ArrayList<>(available.keySet()),labels=new ArrayList<>(available.values());labels.add("＋ 새 색상 직접 입력");
        new AlertDialog.Builder(this).setTitle(quickSkuValue+" 색상 선택").setItems(labels.toArray(new String[0]),(d,index)->{
            if(index==labels.size()-1){manualQuickInput(false);return;}quickColorValue=keys.get(index);quickColor.setText(quickColorValue);quickFeedback="";updateQuickPreview();
        }).setNegativeButton("취소",null).show();
    }
    private DialogDraft manualQuickDraft(boolean sku,String entered){
        return new DialogDraft("manual-quick",-1,quickSizeValue,new String[]{entered,quickSkuValue,quickColorValue,quickQtyValue},sku);
    }
    private void manualQuickInput(boolean sku){
        DialogDraft restored=dialogDraft!=null&&"manual-quick".equals(dialogDraft.kind)?dialogDraft:null;
        if(restored!=null){
            quickSkuValue=restored.values[1];quickColorValue=restored.values[2];quickQtyValue=restored.values[3];quickSizeValue=restored.selection;
            quickSku.setText(quickSkuValue);quickColor.setText(quickColorValue);quickQuantity.setText(quickQtyValue);quickSize.setSelection(quickSizeValue);
        }
        LinearLayout content=vertical();content.setPadding(dp(20),dp(8),dp(20),dp(8));
        EditText entry=input(content,sku?"새 품번":"새 색상 코드 또는 중국어 색상",restored==null?"":restored.values[0],false);
        TextView error=text(restored==null?"":"저장 안 됨 · 입력을 보존했습니다.",13,0xffac352a);content.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(sku?"품번 직접 입력":"색상 직접 입력").setView(content).setPositiveButton("선택",null).setNegativeButton("취소",null).create();
        retainDialog(dialog,()->{captureQuick();dialogDraft=manualQuickDraft(sku,entry.getText().toString());});
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(button->{
            if(activeEditor!=dialog||transitioning)return;
            String value=entry.getText().toString().trim();if(value.isEmpty()||value.length()>(sku?80:60)){error.setText("내용을 입력하고 허용 길이를 확인해 주세요.");return;}
            if(sku){quickSkuValue=value;quickSku.setText(value);quickColorValue="";quickColor.setText("");}else{quickColorValue=value;quickColor.setText(value);}quickFeedback="";updateQuickPreview();dialog.dismiss();
        }));dialog.show();
    }
    private long quickAmount(){String amount=quickQuantity.getText().toString().trim();if(!amount.matches("[0-9]{1,6}")||Long.parseLong(amount)==0)throw new IllegalArgumentException("변경 수량은 1~999999의 정수로 입력해 주세요.");return Long.parseLong(amount);}
    private void updateQuickPreview(){
        if(quickPreview==null||quickQuantity==null||quickSku==null||quickColor==null||quickSize==null)return;
        captureQuick();long total=Inventory.grandTotal(state.draft.rows);
        if(quickSkuTotal!=null)quickSkuTotal.setText("선택 품번 전체 "+Inventory.totalForSku(state.draft.rows,quickSkuValue)+"개");
        if(quickSkuValue.trim().isEmpty()||quickColorValue.trim().isEmpty()){quickPreview.setText("현재 전체 "+total+"개 · 품번과 색상을 선택하세요.");return;}
        try{long amount=quickAmount();Inventory.Job plus=copy(state.draft);QuantityAdjuster.Result next=QuantityAdjuster.adjust(plus,quickSkuValue,quickColorValue,quickSizeValue,amount,false,state.globalRules,state.skuRules);
            String minus;try{Inventory.Job less=copy(state.draft);QuantityAdjuster.adjust(less,quickSkuValue,quickColorValue,quickSizeValue,amount,true,state.globalRules,state.skuRules);minus=String.valueOf(Inventory.grandTotal(less.rows))+"개";}catch(IllegalArgumentException e){minus="불가";}
            quickPreview.setText("현재 "+Inventory.SIZES[quickSizeValue]+" "+next.previous+"개 · 전체 "+total+"개\n전체 미리보기  + "+Inventory.grandTotal(plus.rows)+"개 / − "+minus);
        }catch(IllegalArgumentException e){quickPreview.setText("전체 "+total+"개 · "+e.getMessage());}
    }
    private void applyQuickAdjustment(boolean subtract){
        if(!editable())return;
        captureQuick();try{
            long amount=quickAmount();Inventory.Job changed=copy(state.draft);QuantityAdjuster.Result result=QuantityAdjuster.adjust(changed,quickSkuValue,quickColorValue,quickSizeValue,amount,subtract,state.globalRules,state.skuRules);changed.updatedAt=System.currentTimeMillis();
            Inventory.Job previous=state.draft;Inventory.Row before=result.created?null:previous.rows.get(result.rowIndex);StockHistory.noteRowChange(previous,changed,before,result.rowRemoved?null:result.row,changed.updatedAt);
            if(!commitTableChange(()->{state.draft=changed;ChangeHistory.recordRow(state,state.draft,title(),subtract?"수량 차감":"수량 추가",before,result.rowRemoved?null:result.row);},null,null,null))return;
            quickFeedback=quickSkuValue+" / "+quickColorValue+" / "+Inventory.SIZES[quickSizeValue]+"  "+result.previous+" → "+result.current+"개"+(result.rowRemoved?" · 빈 행 제외":"")+" · 자동 저장됨";
            hideKeyboard();render();
        }catch(IllegalArgumentException e){alert("수량을 변경하지 않았습니다",e.getMessage());}
    }
    /** Flush actual initial-input edits once; viewing unchanged work cannot restore a deleted card. */
    private void flushSourceHistory(){
        pendingSourceHistory=false;
    }
    private void rememberSource(String raw){
        // Legacy source cards remain readable; current input belongs to its one actual job.
    }
    private void rememberInputSource(Inventory.Job source){
        // Do not create a separate archive identity for each source/save.
    }
    private void showRecentSources(){
        body.addView(heading("이전 원문 기록",18));space(body,5);body.addView(text("이전 버전에서 보관한 원문입니다. 원문을 입력란으로 불러오거나 이름을 수정할 수 있습니다.",13,MUTED));space(body,12);
        if(state.recentJobs.isEmpty()){LinearLayout empty=card();empty.addView(text("목록을 붙여넣으면 여기에 자동으로 남습니다.",14,MUTED));}
        for(Inventory.Job recent:new ArrayList<>(state.recentJobs)){
            LinearLayout c=card();c.addView(heading(recent.title.trim().isEmpty()?"작업 입력":recent.title,17));space(c,5);
            c.addView(text("최초 입력: "+TimeLabels.format(recent.sourceRecordedAt),14,INK));space(c,4);c.addView(text("최근 사용: "+TimeLabels.format(recent.updatedAt)+" · "+recent.sourceText.length()+"자",12,MUTED));
            String preview=recent.sourceText.replaceAll("\\s+"," ").trim();if(preview.length()>100)preview=preview.substring(0,100)+"…";space(c,7);c.addView(text(preview,14,MUTED));
            buttons(c,new String[]{"불러오기","작업명","기록 삭제"},false,()->beforeReplace(()->{Inventory.Job next=new Inventory.Job();next.title=recent.title;next.sourceText=recent.sourceText;next.sourceRecordedAt=recent.sourceRecordedAt;replaceDraft(next,0);}),()->renameSource(recent.id),()->confirm("작업 입력 기록 삭제","이 입력 원문 기록을 삭제합니다. 현재 표와 저장한 작업은 유지됩니다.",()->{if(!commitChange(()->state.recentJobs.removeIf(job->job.sourceText.equals(recent.sourceText))))return;render();}));
        }
    }
    private void renameSource(String sourceId){
        Inventory.Job source=null;for(Inventory.Job recent:state.recentJobs)if(recent.id.equals(sourceId)){source=recent;break;}
        if(source==null){toast("작업 입력 기록을 찾을 수 없습니다.");return;}
        EditText name=new EditText(this);name.setSingleLine();name.setPadding(dp(20),dp(12),dp(20),dp(12));name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        name.setText(dialogDraft!=null&&"source-name".equals(dialogDraft.kind)?dialogDraft.values[1]:source.title);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("작업 입력 이름").setView(name).setPositiveButton("저장",null).setNegativeButton("취소",null).create();
        retainDialog(dialog,()->dialogDraft=new DialogDraft("source-name",-1,0,new String[]{sourceId,name.getText().toString()},false));
        dialog.setOnShowListener(ignored->dialog.getButton(-1).setOnClickListener(view->{if(activeEditor!=dialog||transitioning)return;String value=name.getText().toString().trim();if(value.isEmpty()){name.setError("작업명을 입력해 주세요.");return;}if(!commitChange(()->{for(Inventory.Job recent:state.recentJobs)if(recent.id.equals(sourceId)){recent.title=value;return;}throw new IllegalArgumentException("작업 입력 기록을 찾을 수 없습니다.");}))return;dialog.dismiss();render();}));dialog.show();
    }
    private void quantityStrip(LinearLayout parent,long[] quantities,Long total,boolean big){quantityStrip(parent,quantities,total,big,false);}
    private void quantityStrip(LinearLayout parent,long[] quantities,Long total,boolean big,boolean highlightLow){LinearLayout strip=new LinearLayout(this);String[] names={"S","M","L","XL","합계"};for(int i=0;i<(total==null?4:5);i++){LinearLayout cell=vertical();cell.setGravity(Gravity.CENTER);TextView name=text(names[i],12,MUTED);name.setGravity(Gravity.CENTER);cell.addView(name,new LinearLayout.LayoutParams(-1,-2));TextView val=heading(String.valueOf(i==4?total:quantities[i]),big?23:19);if(i==4)val.setTextColor(TEAL);else if(highlightLow&&quantities[i]>=1&&quantities[i]<=5){val.setTextColor(AMBER);val.setBackground(background(0xffffedcf,0xffe8ca8e,7));val.setTag("low-stock-quantity");}val.setSingleLine();val.setAutoSizeTextTypeUniformWithConfiguration(11,big?23:19,1,android.util.TypedValue.COMPLEX_UNIT_SP);cell.addView(val,new LinearLayout.LayoutParams(-1,dp(30)));val.setGravity(Gravity.CENTER);strip.addView(cell,new LinearLayout.LayoutParams(0,-2,1));}parent.addView(strip);}
    private static final class RowEntryFields {
        AutoCompleteTextView sku,color;
        Spinner size;
        EditText quantity;
        TextView error;
    }
    /** Current rows take precedence over archived values and remembered color rules. */
    private List<Inventory.Job> rowEntryJobs(){
        List<Inventory.Job> jobs=new ArrayList<>(state.jobs);
        Collections.sort(jobs,(a,b)->Long.compare(b.updatedAt,a.updatedAt));jobs.add(0,state.draft);return jobs;
    }
    private ArrayList<String> rowEntrySkus(){
        LinkedHashSet<String> choices=new LinkedHashSet<>();
        for(Inventory.Job job:rowEntryJobs())for(Inventory.Row row:job.rows)if(!row.sku.trim().isEmpty())choices.add(row.sku);
        choices.addAll(state.skuRules.keySet());return new ArrayList<>(choices);
    }
    private LinkedHashMap<String,String> rowEntryColors(String sku,boolean includeGlobals){
        LinkedHashMap<String,String> choices=new LinkedHashMap<>();
        for(Inventory.Job job:rowEntryJobs())for(Inventory.Row row:job.rows)
            if(PhotoContract.sameKey(sku,row.sku)&&!choices.containsKey(row.color))choices.put(row.color,row.sourceColor);
        Map<String,String> scoped=null;for(Map.Entry<String,Map<String,String>> entry:state.skuRules.entrySet())if(PhotoContract.sameKey(entry.getKey(),sku)){scoped=entry.getValue();break;}
        if(scoped!=null)for(Map.Entry<String,String> rule:scoped.entrySet())if(!choices.containsKey(rule.getValue()))choices.put(rule.getValue(),rule.getKey());
        if(includeGlobals)for(Map.Entry<String,String> rule:state.globalRules.entrySet())if(!choices.containsKey(rule.getValue()))choices.put(rule.getValue(),rule.getKey());
        return choices;
    }
    private void setRowEntrySku(RowEntryFields fields,String sku){
        fields.sku.setText(sku);LinkedHashMap<String,String> colors=rowEntryColors(sku,false);
        fields.color.setText(colors.isEmpty()?"":colors.keySet().iterator().next());fields.error.setText("");
    }
    private void chooseRowEntrySku(RowEntryFields fields){
        ArrayList<String> choices=rowEntrySkus();choices.add("＋ 새 품번 직접 입력");
        new AlertDialog.Builder(this).setTitle("저장된 품번 선택").setItems(choices.toArray(new String[0]),(d,index)->{
            if(index==choices.size()-1)manualRowEntry(fields,true);else setRowEntrySku(fields,choices.get(index));
        }).setNegativeButton("취소",null).show();
    }
    private void chooseRowEntryColor(RowEntryFields fields){
        String sku=fields.sku.getText().toString().trim();if(sku.isEmpty()){chooseRowEntrySku(fields);return;}
        LinkedHashMap<String,String> available=rowEntryColors(sku,true);
        ArrayList<String> keys=new ArrayList<>(available.keySet()),labels=new ArrayList<>();
        for(String code:keys){String source=available.get(code);labels.add(code+(source.equals(code)?"":" · "+source));}
        labels.add("＋ 새 색상 직접 입력");
        new AlertDialog.Builder(this).setTitle(sku+" 색상 선택").setItems(labels.toArray(new String[0]),(d,index)->{
            if(index==keys.size())manualRowEntry(fields,false);else{fields.color.setText(keys.get(index));fields.error.setText("");}
        }).setNegativeButton("취소",null).show();
    }
    private void manualRowEntry(RowEntryFields fields,boolean sku){
        LinearLayout content=vertical();content.setPadding(dp(20),dp(8),dp(20),dp(8));
        EditText entry=input(content,sku?"새 품번":"새 색상 코드 또는 중국어 색상",dialogDraft!=null&&"manual-add".equals(dialogDraft.kind)?dialogDraft.values[3]:"",false);entry.setTag("row-add-manual-value");
        TextView error=text("",13,0xffac352a);content.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(sku?"품번 직접 입력":"색상 직접 입력").setView(content).setPositiveButton("선택",null).setNegativeButton("취소",null).create();
        AlertDialog parent=activeEditor;Runnable parentCapture=captureDialogInput;
        activeEditor=dialog;editorDialogs.add(dialog);
        captureDialogInput=()->dialogDraft=new DialogDraft("manual-add",-1,fields.size.getSelectedItemPosition(),new String[]{fields.sku.getText().toString(),fields.color.getText().toString(),fields.quantity.getText().toString(),entry.getText().toString()},sku);
        captureDialogInput.run();
        dialog.setOnDismissListener(ignored->{editorDialogs.remove(dialog);if(!transitioning){activeEditor=parent;captureDialogInput=parentCapture;if(parentCapture!=null)parentCapture.run();}});
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(b->{
            if(activeEditor!=dialog||transitioning)return;
            String value=entry.getText().toString().trim();
            if(value.isEmpty()||value.length()>(sku?80:60)){error.setText(sku?"품번을 1~80자로 입력해 주세요.":"색상을 1~60자로 입력해 주세요.");return;}
            if(sku)setRowEntrySku(fields,value);else{fields.color.setText(value);fields.error.setText("");}dialog.dismiss();
        }));dialog.show();
    }
    private void showAddRow(){
        if(!editable())return;
        RowEntryFields fields=new RowEntryFields();
        LinearLayout content=vertical();content.setPadding(dp(20),dp(12),dp(20),dp(8));
        content.addView(text("저장된 품번·색상을 선택하세요. 같은 품번·색상이 있으면 해당 사이즈 수량에 더합니다.",14,MUTED));space(content,14);
        fields.sku=menuInput(content,"품번","");fields.sku.setTag("row-add-sku");space(content,12);
        fields.color=menuInput(content,"색상 코드","");fields.color.setTag("row-add-color");space(content,14);
        LinearLayout numbers=new LinearLayout(this),sizeBox=vertical(),qtyBox=vertical();
        numbers.addView(sizeBox,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams qtyLP=new LinearLayout.LayoutParams(0,-2,1.5f);qtyLP.leftMargin=dp(8);numbers.addView(qtyBox,qtyLP);content.addView(numbers);
        sizeBox.addView(text("사이즈",13,MUTED));fields.size=new Spinner(this);fields.size.setTag("row-add-size");
        ArrayAdapter<String> sizes=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,Inventory.SIZES);sizes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);fields.size.setAdapter(sizes);
        fields.size.setBackground(background(BG,LINE,9));fields.size.setPadding(dp(8),0,dp(8),0);LinearLayout.LayoutParams sizeLP=new LinearLayout.LayoutParams(-1,dp(50));sizeLP.topMargin=dp(6);sizeBox.addView(fields.size,sizeLP);
        fields.quantity=input(qtyBox,"추가 수량","1",false);fields.quantity.setTag("row-add-quantity");fields.quantity.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);fields.quantity.setSelectAllOnFocus(true);
        fields.error=text("",13,0xffac352a);content.addView(fields.error);
        ArrayList<String> skus=rowEntrySkus();if(!skus.isEmpty())setRowEntrySku(fields,skus.get(0));
        fields.sku.setOnClickListener(v->chooseRowEntrySku(fields));fields.color.setOnClickListener(v->chooseRowEntryColor(fields));
        ScrollView scroll=new ScrollView(this);scroll.addView(content);scroll.setFocusableInTouchMode(true);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("행 추가").setView(scroll).setPositiveButton("추가",null).setNegativeButton("취소",null).create();
        DialogDraft restored=dialogDraft!=null&&("add".equals(dialogDraft.kind)||"manual-add".equals(dialogDraft.kind))?dialogDraft:null;
        if(restored!=null){fields.sku.setText(restored.values[0]);fields.color.setText(restored.values[1]);fields.quantity.setText(restored.values[2]);fields.size.setSelection(restored.selection);fields.error.setText("저장 안 됨 · 입력을 보존했습니다.");}
        retainDialog(dialog,()->dialogDraft=new DialogDraft("add",-1,fields.size.getSelectedItemPosition(),new String[]{fields.sku.getText().toString(),fields.color.getText().toString(),fields.quantity.getText().toString()},false));
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(b->{if(activeEditor!=dialog||transitioning)return;try{
            String sku=fields.sku.getText().toString().trim(),color=fields.color.getText().toString().trim(),raw=fields.quantity.getText().toString().trim();
            if(!raw.matches("[0-9]{1,6}")||Long.parseLong(raw)==0)throw new IllegalArgumentException("추가 수량은 1~999999의 정수로 입력해 주세요.");
            long amount=Long.parseLong(raw);int size=fields.size.getSelectedItemPosition();Inventory.Job changed=copy(state.draft);
            QuantityAdjuster.Result result=QuantityAdjuster.adjust(changed,sku,color,size,amount,false,state.globalRules,state.skuRules);
            if(result.created){String source=rowEntryColors(sku,true).get(color);if(source!=null)result.row.sourceColor=source;}
            Inventory.Row before=result.created?null:state.draft.rows.get(result.rowIndex);changed.updatedAt=System.currentTimeMillis();StockHistory.noteRowChange(state.draft,changed,before,result.row,changed.updatedAt);
            if(!commitTableChange(()->{state.draft=changed;ChangeHistory.recordRow(state,state.draft,title(),result.created?"행 추가":"수량 추가",before,result.row);},null,null,null))return;
            resetAdjustmentForm();quickSkuValue=result.row.sku;quickColorValue=result.row.color;quickSizeValue=size;quickQtyValue=raw;
            quickFeedback=sku+" / "+result.row.color+" / "+Inventory.SIZES[size]+"  "+result.previous+" → "+result.current+"개 · 자동 저장됨";
            hideKeyboard();dialog.dismiss();render();
        }catch(IllegalArgumentException error){fields.error.setText(error.getMessage());}}));dialog.show();
        if(restored!=null&&"manual-add".equals(restored.kind)){dialogDraft=restored;manualRowEntry(fields,restored.checked);}
    }
    private void editRow(int index){
        if(!editable())return;
        if(index<0){showAddRow();return;}
        Inventory.Row old=index>=0?state.draft.rows.get(index):new Inventory.Row();LinearLayout content=vertical();content.setPadding(dp(20),dp(12),dp(20),dp(8));
        EditText sku=input(content,"품번",old.sku,false), original=input(content,"중국어 색상 (원문)",old.sourceColor,false), code=input(content,"출력할 색상 코드",old.color,false);code.setHint("WH, BK, CC, LB, LE 등");
        LinearLayout qrow=new LinearLayout(this);EditText[] qs=new EditText[4];String[] labels={"S","M","L","XL"};for(int i=0;i<4;i++){LinearLayout cell=vertical();LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.leftMargin=dp(6);qs[i]=input(cell,labels[i],String.valueOf(old.quantities[i]),false);qs[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER);qrow.addView(cell,lp);}content.addView(qrow);
        CheckBox remember=new CheckBox(this);remember.setText("이 품번의 색상 규칙으로 기억");remember.setTextSize(14);remember.setTextColor(INK);remember.setChecked(unresolved(old)&&!Parser.MISSING_COLOR.equals(old.sourceColor));content.addView(remember);content.addView(text("해당 품번의 다음 목록에 적용합니다. 다른 상품의 규칙은 바뀌지 않습니다.",12,MUTED));
        TextView error=text("",13,0xffac352a);content.addView(error);ScrollView scroll=new ScrollView(this);scroll.addView(content);
        DialogDraft restored=dialogDraft!=null&&"row".equals(dialogDraft.kind)&&dialogDraft.index==index?dialogDraft:null;
        if(restored!=null){sku.setText(restored.values[0]);original.setText(restored.values[1]);code.setText(restored.values[2]);for(int i=0;i<4;i++)qs[i].setText(restored.values[i+3]);remember.setChecked(restored.checked);error.setText("저장 안 됨 · 입력을 보존했습니다.");}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(index<0?"행 추가":"행 수정").setView(scroll).setPositiveButton("저장",null).setNegativeButton("취소",null).setNeutralButton(index>=0?"행 삭제":"",null).create();dialog.setOnShowListener(v->{
            dialog.getButton(-1).setOnClickListener(b->{if(activeEditor!=dialog||transitioning)return;try{
                Inventory.Row r=old.copy();PhotoBindings.identityEdited(r,PhotoContract.key(sku.getText().toString()),original.getText().toString().trim());r.sku=PhotoContract.key(sku.getText().toString());r.sourceColor=original.getText().toString().trim();r.color=PhotoContract.key(code.getText().toString());r.sourceLine=old.sourceLine;
                if(r.sku.isEmpty()||r.sku.length()>80)throw new IllegalArgumentException("품번을 1~80자로 입력해 주세요.");if(r.sourceColor.isEmpty()||r.sourceColor.length()>60)throw new IllegalArgumentException("원문 색상을 1~60자로 입력해 주세요.");if(r.color.isEmpty()||r.color.length()>30)throw new IllegalArgumentException("색상 코드를 1~30자로 입력해 주세요.");
                for(int i=0;i<4;i++){String q=qs[i].getText().toString().trim();if(q.isEmpty())q="0";if(!q.matches("[0-9]{1,6}"))throw new IllegalArgumentException("수량은 0~999999의 정수로 입력해 주세요.");r.quantities[i]=Long.parseLong(q);}
                if(remember.isChecked()&&!r.color.matches("[A-Z][A-Z0-9_-]{0,15}"))throw new IllegalArgumentException("기억할 코드는 영문으로 시작하는 1~16자 영문·숫자로 입력해 주세요.");
                StateCodec.State before=state;
                boolean willChangeRow=!ChangeHistory.sameRow(old,r);
                if(!commitChange(()->{
                    if(willChangeRow)UndoManager.capture(state,remember.isChecked()?r.sku:null,remember.isChecked()?r.sourceColor:null,remember.isChecked()?r.color:null);
                    if(remember.isChecked()){Map<String,String> m=state.skuRules.get(r.sku);if(m==null){m=new LinkedHashMap<>();state.skuRules.put(r.sku,m);}m.put(r.sourceColor,r.color);}
                    if(index<0)state.draft.rows.add(r);else state.draft.rows.set(index,r);
                    boolean rowChanged=ChangeHistory.recordRow(state,state.draft,title(),index<0?"행 추가":"행 수정",index<0?null:old,r);if(rowChanged){state.draft.updatedAt=System.currentTimeMillis();StockHistory.noteRowChange(before.draft,state.draft,old,r,state.draft.updatedAt);}recordRuleChanges(before);
                }))return;
                if(r.total()==0)toast("수량 0인 행을 보관했습니다.");dialog.dismiss();render();
            }catch(Exception e){error.setText(e.getMessage());}});
            if(index>=0)dialog.getButton(-3).setOnClickListener(b->confirm("행 삭제",old.sku+" / "+old.color+" 행을 삭제할까요?",()->{if(activeEditor!=dialog||transitioning)return;if(!commitTableChange(()->{state.draft.rows.remove(index);state.draft.updatedAt=System.currentTimeMillis();StockHistory.noteRowChange(state.draft,state.draft,old,null,state.draft.updatedAt);ChangeHistory.recordRow(state,state.draft,title(),"행 삭제",old,null);},null,null,null))return;dialog.dismiss();render();}));
        });
        retainDialog(dialog,()->{String[] values=new String[7];values[0]=sku.getText().toString();values[1]=original.getText().toString();values[2]=code.getText().toString();for(int i=0;i<4;i++)values[i+3]=qs[i].getText().toString();dialogDraft=new DialogDraft("row",index,0,values,remember.isChecked());});
        dialog.show();
    }
    /** Uses the detached input and its original token, with the Repository's fresh revision. */
    private boolean commitPhotoChange(InventoryRepository.Mutation photoMutation){
        if(!ready||backupBlocked()||storageUnavailable||transitioning)return false;
        if(conflicted){showConflictNotice();return false;}
        capture();
        try{
            final StateCodec.State edited=StateCodec.decode(StateCodec.encode(state));
            return accept(repository.commit(expectedGeneration,expectedRevision,(candidate,revision)->{
                applyScreen(candidate,edited);photoMutation.apply(candidate,revision);
            }));
        }catch(Exception failure){storageFailure(failure);alert("사진 변경을 저장하지 못했습니다","선택과 입력은 화면에 보존했습니다.\n\n"+failure.getMessage());return false;}
    }
    private void showPhotoBinding(int index){
        if(!editable()||storageUnavailable)return;
        if(index<0||index>=state.draft.rows.size())return;
        Inventory.Row row=state.draft.rows.get(index);
        final List<PhotoCatalog.Variant> choices=new ArrayList<>();final List<String> labels=new ArrayList<>();labels.add("사진을 선택해 주세요");
        for(PhotoCatalog.Variant variant:PhotoBindings.candidates(state,row)){
            PhotoCatalog.Product product=state.catalog.products.get(variant.productId);
            if(product!=null&&!variant.currentPhotoVersionId.isEmpty()
                    &&PhotoBindings.matchesRow(state,row,product,variant)){
                choices.add(variant);labels.add(product.sku+" · "+variant.sourceColor
                    +(photoMatchLabel(row,product,variant).isEmpty()?"":" ("+photoMatchLabel(row,product,variant)+")")
                    +" · "+variant.currentPhotoVersionId.substring(0,8));
            }
        }
        LinearLayout content=vertical();content.setPadding(dp(20),dp(12),dp(20),dp(8));
        content.addView(text("행 품번: "+row.sku+"\n실제 원문 색상: "+row.sourceColor+"\n출력 색상 코드: "+row.color,16,INK));space(content,10);
        content.addView(text(row.binding==null?"현재 사진 미연결":row.binding.needsConfirmation?"품번 또는 실제 색상이 바뀌어 사진을 다시 확인해야 합니다.":"현재 사진: "+row.binding.photoVersionId.substring(0,8),14,MUTED));
        content.addView(text("S·M·L·XL은 같은 상품·실제 색상의 사진을 공유합니다. 새 사진은 확인한 행에만 적용됩니다.",13,MUTED));
        Spinner selection=new Spinner(this);selection.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));selection.setTag("photo-candidate");content.addView(selection);
        if(choices.size()==1)selection.setSelection(1);
        if(choices.isEmpty())content.addView(text("일치하는 등록 사진이 없습니다. 유란사진에서 해당 품번과 실제 색상을 등록하거나 확인된 별칭을 선택해 주세요.",14,AMBER));
        DialogDraft restored=dialogDraft!=null&&"photo".equals(dialogDraft.kind)&&dialogDraft.values[0].equals(row.rowId)?dialogDraft:null;
        if(restored!=null)for(int i=0;i<choices.size();i++)if(choices.get(i).id.equals(restored.values[1]))selection.setSelection(i+1);
        TextView error=text("",13,AMBER);content.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("행 사진 연결 확인").setView(content).setPositiveButton("선택한 사진 연결",null).setNeutralButton("사진 연결 해제",null).setNegativeButton("취소",null).create();
        dialog.setOnShowListener(ignored->{
            dialog.getButton(-1).setOnClickListener(view->{
                if(activeEditor!=dialog||transitioning)return;
                int selected=selection.getSelectedItemPosition();if(selected<=0){error.setText("품번과 실제 색상에 맞는 사진을 직접 선택해 주세요.");return;}
                PhotoCatalog.Variant variant=choices.get(selected-1);PhotoCatalog.Product product=state.catalog.products.get(variant.productId);
                String[] values={row.rowId,variant.productId,variant.id,variant.currentPhotoVersionId,"link",
                    "행 품번: "+row.sku+"\n실제 원문 색상: "+row.sourceColor+"\n선택 상품: "+product.sku+"\n선택 실제 색상: "+variant.sourceColor+"\n사진: "+variant.currentPhotoVersionId.substring(0,8)
                    +(photoMatchLabel(row,product,variant).isEmpty()?"":"\n연결 기준: "+photoMatchLabel(row,product,variant))};
                dialog.dismiss();showPhotoConfirmation(index,values);
            });
            dialog.getButton(-3).setEnabled(row.binding!=null);
            dialog.getButton(-3).setOnClickListener(view->{if(activeEditor!=dialog||transitioning)return;String[] values={row.rowId,"","","","unlink","행 품번: "+row.sku+"\n실제 원문 색상: "+row.sourceColor+"\n이 행의 사진 연결을 해제합니다."};dialog.dismiss();showPhotoConfirmation(index,values);});
        });
        retainDialog(dialog,()->{int selected=selection.getSelectedItemPosition();dialogDraft=new DialogDraft("photo",index,selected,new String[]{row.rowId,selected<=0?"":choices.get(selected-1).id},false);});dialog.show();
    }
    private String photoMatchLabel(Inventory.Row row,PhotoCatalog.Product product,PhotoCatalog.Variant variant){
        if(PhotoContract.sameKey(row.sku,product.sku)&&PhotoContract.sameKey(row.sourceColor,variant.sourceColor))return "";
        return PhotoBindings.matches(row.sourceColor,variant.sourceColor,variant.confirmedColorAliases)?"확인된 별칭":"색상 규칙 일치";
    }
    private void showPhotoConfirmation(int index,String[] values){
        LinearLayout content=vertical();content.setPadding(dp(20),dp(12),dp(20),dp(8));
        content.addView(text(values[5]+"\n\n확인한 사진 연결만 변경합니다.",16,INK));
        if(conflicted)content.addView(text("저장 안 됨 · 확인한 선택을 보존했습니다.",14,AMBER));
        boolean previewReady=true;
        if("link".equals(values[4])){
            try{
                PhotoCatalog.Asset asset=state.catalog.assets.get(values[3]);if(asset==null)throw new IOException("선택한 사진 정보를 찾을 수 없습니다.");
                android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();int edge=Math.max(asset.width,asset.height);options.inSampleSize=1;while(edge/options.inSampleSize>384)options.inSampleSize*=2;
                android.graphics.Bitmap bitmap;
                try(InputStream in=new MediaStore(getFilesDir(),new AndroidFileOps(getFilesDir(),getCacheDir())).open(values[3])){bitmap=android.graphics.BitmapFactory.decodeStream(in,null,options);}
                if(bitmap==null)throw new IOException("선택한 사진을 표시할 수 없습니다.");
                int longest=Math.max(bitmap.getWidth(),bitmap.getHeight());
                if(longest>192){int width=Math.max(1,Math.round(bitmap.getWidth()*192f/longest)),height=Math.max(1,Math.round(bitmap.getHeight()*192f/longest));android.graphics.Bitmap scaled=android.graphics.Bitmap.createScaledBitmap(bitmap,width,height,true);if(scaled!=bitmap)bitmap.recycle();bitmap=scaled;}
                ImageView preview=new ImageView(this);preview.setTag("photo-preview");preview.setContentDescription("확인할 상품 사진");preview.setAdjustViewBounds(true);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setImageBitmap(bitmap);content.addView(preview,new LinearLayout.LayoutParams(-1,dp(192)));
            }catch(Exception failure){storageFailure(failure);previewReady=false;content.addView(text("사진 미리보기를 열지 못했습니다. 선택은 보존했습니다.\n"+failure.getMessage(),14,AMBER));}
        }
        final boolean canApplyPreview=previewReady;

        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("사진 변경 확인").setView(content).setPositiveButton("확인하여 적용",null).setNegativeButton("취소",null).create();
        dialog.setOnShowListener(ignored->{dialog.getButton(-1).setEnabled(canApplyPreview&&!storageUnavailable);dialog.getButton(-1).setOnClickListener(view->{
            if(activeEditor!=dialog||transitioning||!editable())return;
            if(!commitPhotoChange((candidate,revision)->{if("unlink".equals(values[4]))PhotoBindings.unlink(candidate,values[0],revision);else PhotoBindings.link(candidate,values[0],values[1],values[2],values[3],revision);}))return;
            dialog.dismiss();render();toast("사진 변경을 저장했습니다.");
        });});
        retainDialog(dialog,()->dialogDraft=new DialogDraft("photo-confirm",index,0,values,true));dialog.show();
    }
    private void saveNamed(){
        capture();if(state.draft.completed){if(saveJob())render();return;}
        EditText name=new EditText(this);name.setText(dialogDraft!=null&&"name".equals(dialogDraft.kind)?dialogDraft.values[0]:title());name.setSingleLine();name.setPadding(dp(20),dp(12),dp(20),dp(12));name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("보관함에 저장").setView(name).setMessage("같은 작업을 열어 수정했다면 해당 보관 내용을 갱신합니다.").setPositiveButton("저장",null).setNegativeButton("취소",null).create();
        retainDialog(dialog,()->dialogDraft=new DialogDraft("name",-1,0,new String[]{name.getText().toString()},false));
        dialog.setOnShowListener(ignored->dialog.getButton(-1).setOnClickListener(button->{if(activeEditor!=dialog||transitioning)return;state.draft.title=name.getText().toString().trim();if(state.draft.title.isEmpty())state.draft.title="수량 정리표";if(saveJob()){dialog.dismiss();render();}}));dialog.show();
    }
    private boolean saveJob(){
        if(state.draft.id.isEmpty()&&!state.draft.hasCurrentTable()&&state.draft.sourceText.trim().isEmpty()){toast("입력에서 ‘새 작업’을 눌러 작업을 시작해 주세요.");return false;}
        if(!state.draft.id.isEmpty()){boolean present=false;for(Inventory.Job job:state.jobs)if(job.id.equals(state.draft.id)){present=true;break;}if(!present){alert("보관 작업이 삭제되었습니다","현재 표는 유지됩니다. 별도 작업을 시작하려면 입력에서 ‘새 작업’을 선택해 주세요.");return false;}}
        if(!commitChange(()->WorkArchive.retainCurrent(state,System.currentTimeMillis())))return false;
        toast("현재 작업을 저장했습니다.");return true;
    }
    private static Inventory.Job copy(Inventory.Job j){Inventory.Job n=new Inventory.Job();n.id=j.id;n.title=j.title;n.sourceText=j.sourceText;n.updatedAt=j.updatedAt;n.sourceRecordedAt=j.sourceRecordedAt;n.initialQuantity=j.initialQuantity;n.stockUpdatedAt=j.stockUpdatedAt;n.stockSessionId=j.stockSessionId;n.stockChangeCount=j.stockChangeCount;n.inputCleared=j.inputCleared;n.inputBuffer=j.inputBuffer;n.inputBufferRecordedAt=j.inputBufferRecordedAt;n.completed=j.completed;for(Inventory.Row r:j.rows)n.rows.add(r.copy());return n;}
    private void beforeReplace(Runnable action){capture();flushSourceHistory();if(!persist())return;action.run();}
    private void startNew(){capture();if(state.draft.hasCurrentTable()&&state.draft.inputCleared&&!state.draft.inputBuffer.trim().isEmpty()){if(editable())parseStagedInput();return;}beforeReplace(()->{Inventory.Job next=new Inventory.Job();next.title="";if(!commitChange(()->{long now=System.currentTimeMillis();WorkArchive.retainCurrent(state,now);StockHistory.finish(state,state.draft,now);WorkArchive.createNew(state,next,now);UndoManager.clear(state);} ))return;pendingSourceHistory=false;resetAdjustmentForm();tableQuery="";lowStockOnly=false;tab=0;render();});}
    private void showHistory(){
        section("내 작업 보관함","저장한 작업을 열고, 지난 목록을 다시 여세요.");
        LinearLayout backup=card();backup.setTag("backup-status");backup.addView(heading("휴대폰 밖에도 안전하게",17));space(backup,7);backup.addView(text("앱을 삭제하면 자료가 지워집니다. 작업과 색상 규칙을 백업 파일로 저장해 두세요.",14,MUTED));space(backup,10);backup.addView(text("마지막 백업 저장 시각: "+TimeLabels.format(state.lastBackupAt),14,INK));backup.addView(text("앱 내부 수량 자료: "+storageBytesLabel(),14,INK));backup.addView(text("참조 사진 자료: "+photoStorageBytesLabel(),14,INK));space(backup,7);backup.addView(text("외부 백업 파일의 존재 여부와 휴대폰 전체 저장공간은 이 값에 포함되지 않습니다.",12,MUTED));buttons(backup,new String[]{"전체 백업","백업 복원"},false,()->backup(),()->restore());
        body.addView(heading("날짜별 작업",18));space(body,10);
        if(state.jobs.isEmpty()){LinearLayout c=card();c.addView(text("아직 보관 작업이 없습니다.\n입력에서 ‘새 작업’을 눌러 시작하세요.",16,MUTED));}
        List<Inventory.Job> ordered=new ArrayList<>(state.jobs);Collections.sort(ordered,(a,b)->Long.compare(b.sourceRecordedAt,a.sourceRecordedAt));
        LinkedHashMap<String,List<Inventory.Job>> days=new LinkedHashMap<>();for(Inventory.Job job:ordered){String day=job.sourceRecordedAt>0?new SimpleDateFormat("yyyy-MM-dd",Locale.KOREA).format(new Date(job.sourceRecordedAt)):!job.hasCurrentTable()&&job.sourceText.trim().isEmpty()?"입력 대기":"날짜 기록 없음";if(!days.containsKey(day))days.put(day,new ArrayList<>());days.get(day).add(job);}
        for(Map.Entry<String,List<Inventory.Job>> day:days.entrySet()){
            space(body,8);body.addView(heading(day.getKey(),18));space(body,10);
            for(Inventory.Job j:day.getValue()){
                LinearLayout c=card();c.addView(heading(j.title.isEmpty()?"수량 정리표":j.title,19));if(j.completed){space(c,4);c.addView(text("완료 · 편집 잠금",13,AMBER));}
                space(c,5);c.addView(text("최초 입력: "+TimeLabels.format(j.sourceRecordedAt)+" · "+j.rows.size()+"행",14,MUTED));space(c,5);c.addView(text("마지막 수량 변경: "+TimeLabels.format(j.stockUpdatedAt),13,MUTED));space(c,7);c.addView(text("전체 "+String.format(Locale.KOREA,"%,d",Inventory.grandTotal(j.rows))+"개",19,TEAL));
                buttons(c,new String[]{"열기","작업명","삭제"},false,()->beforeReplace(()->{Inventory.Job latest=null;for(Inventory.Job candidate:state.jobs)if(candidate.id.equals(j.id))latest=candidate;if(latest==null){toast("보관 작업을 찾을 수 없습니다.");return;}replaceDraft(copy(latest),latest.hasCurrentTable()?1:0);}),()->renameJob(j.id),()->confirm("보관 작업 삭제","‘"+j.title+"’을 보관함에서 삭제합니다. 현재 열려 있는 표는 유지됩니다.",()->{if(!commitChange(()->state.jobs.removeIf(job->job.id.equals(j.id))))return;render();}));
            }
        }
        fullButton(body,(legacySourcesExpanded?"접기":"펼치기")+" · 이전 원문 기록 "+state.recentJobs.size()+"개",false,()->{legacySourcesExpanded=!legacySourcesExpanded;render();});
        if(legacySourcesExpanded)showRecentSources();
    }
    private void renameJob(String jobId){
        Inventory.Job job=null;for(Inventory.Job saved:state.jobs)if(saved.id.equals(jobId)){job=saved;break;}
        if(job==null){toast("보관 작업을 찾을 수 없습니다.");return;}
        if(job.completed){toast("완료된 작업입니다. 완료를 취소한 뒤 작업명을 수정해 주세요.");return;}
        EditText name=new EditText(this);name.setSingleLine();name.setPadding(dp(20),dp(12),dp(20),dp(12));name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});name.setText(dialogDraft!=null&&"job-name".equals(dialogDraft.kind)?dialogDraft.values[1]:job.title);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("작업명 수정").setView(name).setPositiveButton("저장",null).setNegativeButton("취소",null).create();
        retainDialog(dialog,()->dialogDraft=new DialogDraft("job-name",-1,0,new String[]{jobId,name.getText().toString()},false));
        dialog.setOnShowListener(ignored->dialog.getButton(-1).setOnClickListener(view->{if(activeEditor!=dialog||transitioning)return;String value=name.getText().toString().trim();if(value.isEmpty()){name.setError("작업명을 입력해 주세요.");return;}if(!commitChange(()->{for(Inventory.Job saved:state.jobs)if(saved.id.equals(jobId)){saved.title=value;if(state.draft.id.equals(jobId))state.draft.title=value;return;}throw new IllegalArgumentException("보관 작업을 찾을 수 없습니다.");}))return;dialog.dismiss();render();}));dialog.show();
    }
    private void showChanges(){
        Button reset=button("초기화",false,()->clearChangeHistory());reset.setTag("change-history-reset");LinearLayout.LayoutParams resetLP=new LinearLayout.LayoutParams(dp(96),dp(48));resetLP.gravity=Gravity.LEFT;resetLP.bottomMargin=dp(12);body.addView(reset,resetLP);
        section("수정·변경 내역","색상 규칙 옆 메뉴에서 시간과 변경 내용을 따로 확인합니다.");
        body.addView(text("최신순 · 최근 500건 자동 보관 · 전체 백업에 포함",13,MUTED));space(body,12);
        LinearLayout search=card();EditText changeSearch=input(search,"품번·작업명·동작 검색",changeQuery,false);changeSearch.setTag("change-search");changeSearch.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        AutoCompleteTextView skuChoice=menuInput(search,"품번 선택",changeSkuFilter.isEmpty()?"전체 품번":changeSkuFilter);skuChoice.setTag("change-sku-choice");
        AutoCompleteTextView colorChoice=menuInput(search,"색상 선택",changeColorFilter.isEmpty()?"전체 색상":changeColorFilter);colorChoice.setTag("change-color-choice");
        skuChoice.setOnClickListener(view->{changeQuery=changeSearch.getText().toString().trim();chooseChangeSku();});
        colorChoice.setOnClickListener(view->{changeQuery=changeSearch.getText().toString().trim();chooseChangeColor();});
        changeSearch.setOnEditorActionListener((view,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){applyChangeSearch(changeSearch.getText().toString());return true;}return false;});
        fullButton(search,"검색",false,()->applyChangeSearch(changeSearch.getText().toString()));
        if(!changeQuery.isEmpty()||!changeSkuFilter.isEmpty()||!changeColorFilter.isEmpty())fullButton(search,"검색 지우기",false,()->{changeQuery="";changeSkuFilter="";changeColorFilter="";historyVisible=20;render();});
        List<String> colorAliases=changeColorAliases();
        List<ChangeHistory.Entry> matched=new ArrayList<>();for(ChangeHistory.Entry entry:state.changes){
            if(!TableSearch.matchesChange(entry,changeQuery)||!matchesChangeToken(entry.detail,changeSkuFilter))continue;
            boolean colorMatch=changeColorFilter.isEmpty();for(String alias:colorAliases)if(matchesChangeToken(entry.detail,alias)){colorMatch=true;break;}
            if(colorMatch)matched.add(entry);
        }
        search.addView(text("검색 결과 "+matched.size()+" / "+state.changes.size()+"건",13,MUTED));
        if(matched.isEmpty()){LinearLayout empty=card();empty.addView(text(state.changes.isEmpty()?"아직 기록된 변경이 없습니다.\n수량 추가·차감, 행 수정·삭제, 색상 규칙 변경이 여기에 남습니다.\n이전 버전의 수정 기록은 없으며, 이번 업데이트 이후부터 기록합니다.":"검색 결과가 없습니다. 검색어를 지워 다시 확인해 주세요.",14,MUTED));return;}
        int shown=Math.min(historyVisible,matched.size());
        for(int i=0;i<shown;i++){ChangeHistory.Entry entry=matched.get(i);LinearLayout c=card();c.addView(heading(entry.action,17));space(c,5);c.addView(heading("변경 시각: "+TimeLabels.format(entry.timestamp),16));space(c,6);c.addView(text("작업: "+(entry.jobTitle.trim().isEmpty()?"수량 정리표":entry.jobTitle),13,MUTED));space(c,9);TextView detail=text(entry.detail,16,INK);detail.setTextIsSelectable(true);c.addView(detail);}
        if(shown<matched.size()){fullButton(body,"이전 변경 더 보기 · "+(matched.size()-shown)+"건 남음",false,()->{historyVisible+=20;render();});space(body,16);}
    }
    private void clearChangeHistory(){
        if(backupBlocked())return;
        new AlertDialog.Builder(this).setTitle("변경내역 초기화")
            .setMessage("수정·변경 내역만 삭제합니다. 재고 수량, 작업별 재고 요약, 저장한 작업, 원문과 사진은 유지됩니다.")
            .setPositiveButton("변경내역만 삭제",(dialog,which)->{if(!commitChange(()->state.changes.clear()))return;changeQuery="";changeSkuFilter="";changeColorFilter="";historyVisible=20;render();toast("변경내역을 초기화했습니다.");})
            .setNegativeButton("취소",null).show();
    }
    private void applyChangeSearch(String query){changeQuery=query.trim();changeSkuFilter="";changeColorFilter="";historyVisible=20;hideKeyboard();render();}
    private void chooseChangeSku(){
        LinkedHashSet<String> unique=new LinkedHashSet<>();for(String sku:rowEntrySkus())unique.add(PhotoContract.key(sku));for(PhotoCatalog.Product product:state.catalog.products.values())unique.add(PhotoContract.key(product.sku));
        ArrayList<String> choices=new ArrayList<>();choices.add("전체 품번");choices.addAll(unique);
        new AlertDialog.Builder(this).setTitle("변경내역 품번 선택").setItems(choices.toArray(new String[0]),(dialog,index)->{changeSkuFilter=index==0?"":choices.get(index);changeColorFilter="";historyVisible=20;render();}).setNegativeButton("취소",null).show();
    }
    private void chooseChangeColor(){
        LinkedHashSet<String> unique=new LinkedHashSet<>();for(Inventory.Job job:rowEntryJobs())for(Inventory.Row row:job.rows)if(changeSkuFilter.isEmpty()||PhotoContract.sameKey(changeSkuFilter,row.sku))unique.add(PhotoContract.key(row.color));
        for(String code:state.globalRules.values())unique.add(PhotoContract.key(code));
        for(PhotoCatalog.Variant variant:state.catalog.variants.values()){PhotoCatalog.Product product=state.catalog.products.get(variant.productId);if(product!=null&&(changeSkuFilter.isEmpty()||PhotoContract.sameKey(changeSkuFilter,product.sku)))unique.add(PhotoContract.key(Parser.mappedColor(product.sku,variant.sourceColor,state.globalRules,state.skuRules)));}
        ArrayList<String> choices=new ArrayList<>();choices.add("전체 색상");choices.addAll(unique);
        new AlertDialog.Builder(this).setTitle("변경내역 색상 선택").setItems(choices.toArray(new String[0]),(dialog,index)->{changeColorFilter=index==0?"":choices.get(index);historyVisible=20;render();}).setNegativeButton("취소",null).show();
    }
    private List<String> changeColorAliases(){
        LinkedHashSet<String> aliases=new LinkedHashSet<>();if(changeColorFilter.isEmpty())return new ArrayList<>(aliases);aliases.add(changeColorFilter);
        for(Inventory.Job job:rowEntryJobs())for(Inventory.Row row:job.rows)if((changeSkuFilter.isEmpty()||PhotoContract.sameKey(changeSkuFilter,row.sku))&&PhotoContract.sameKey(changeColorFilter,row.color))aliases.add(row.sourceColor);
        for(Map.Entry<String,String> rule:state.globalRules.entrySet())if(PhotoContract.sameKey(changeColorFilter,rule.getValue()))aliases.add(rule.getKey());
        for(Map.Entry<String,Map<String,String>> scope:state.skuRules.entrySet())if(changeSkuFilter.isEmpty()||PhotoContract.sameKey(changeSkuFilter,scope.getKey()))for(Map.Entry<String,String> rule:scope.getValue().entrySet())if(PhotoContract.sameKey(changeColorFilter,rule.getValue()))aliases.add(rule.getKey());
        return new ArrayList<>(aliases);
    }
    private boolean matchesChangeToken(String detail,String token){
        if(token.isEmpty())return true;
        return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}._/-])"+java.util.regex.Pattern.quote(PhotoContract.key(token))+"(?![\\p{L}\\p{N}._/-])").matcher(PhotoContract.key(detail)).find();
    }
    private void showRules(){
        section("한 번 정하면, 다음에도","품번별 규칙을 공통 규칙보다 먼저 적용합니다.");LinearLayout c=card();c.addView(text("규칙을 바꾸면 다음 ‘자동 정리’부터 적용됩니다. 이미 수정한 표의 색상은 그대로 유지됩니다.",14,MUTED));fullButton(c,"색상 규칙 추가",true,()->ruleDialog(null,null,null));
        body.addView(heading("공통 규칙",17));space(body,10);for(Map.Entry<String,String> e:state.globalRules.entrySet())ruleCard("",e.getKey(),e.getValue());
        if(!state.skuRules.isEmpty()){space(body,12);body.addView(heading("품번별 예외",17));space(body,10);for(Map.Entry<String,Map<String,String>> sku:state.skuRules.entrySet())for(Map.Entry<String,String> e:sku.getValue().entrySet())ruleCard(sku.getKey(),e.getKey(),e.getValue());}
    }
    private void ruleCard(String sku,String original,String code){LinearLayout c=card();c.addView(heading(original+"  →  "+code,18));space(c,4);c.addView(text(sku.isEmpty()?"모든 품번에 적용":sku+"에만 적용",13,MUTED));c.setOnClickListener(v->ruleDialog(sku,original,code));}
    private void ruleDialog(String oldSku,String oldColor,String oldCode){
        boolean editing=oldColor!=null;LinearLayout content=vertical();content.setPadding(dp(20),dp(10),dp(20),dp(8));
        DialogDraft restored=dialogDraft!=null&&"rule".equals(dialogDraft.kind)?dialogDraft:null;
        EditText sku=input(content,"특정 품번 (비워두면 공통 규칙)",restored==null?(oldSku==null?"":oldSku):restored.values[3],false);
        EditText color=input(content,"중국어 색상",restored==null?(oldColor==null?"":oldColor):restored.values[4],false);
        EditText code=input(content,"색상 코드",restored==null?(oldCode==null?"":oldCode):restored.values[5],false);
        TextView error=text(restored==null?"":"저장 안 됨 · 입력을 보존했습니다.",13,0xffac352a);content.addView(error);ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(editing?"색상 규칙 수정":"색상 규칙 추가").setView(scroll).setPositiveButton("저장",null).setNegativeButton("취소",null).setNeutralButton(editing?"삭제":"",null).create();
        retainDialog(dialog,()->dialogDraft=new DialogDraft("rule",-1,0,new String[]{oldSku,oldColor,oldCode,sku.getText().toString(),color.getText().toString(),code.getText().toString()},false));
        dialog.setOnShowListener(v->{
            dialog.getButton(-1).setOnClickListener(button->{
                if(activeEditor!=dialog||transitioning)return;
                String product=sku.getText().toString().trim(),source=color.getText().toString().trim(),replacement=code.getText().toString().trim().toUpperCase(Locale.ROOT);
                if(product.length()>80||source.isEmpty()||source.length()>60||!replacement.matches("[A-Z][A-Z0-9_-]{0,15}")){error.setText("색상은 1~60자, 코드는 영문으로 시작하는 1~16자 영문·숫자로 입력해 주세요.");return;}
                StateCodec.State before=state;
                if(!commitChange(()->{if(editing)removeRule(oldSku,oldColor);if(product.isEmpty())state.globalRules.put(source,replacement);else{Map<String,String> rules=state.skuRules.get(product);if(rules==null){rules=new LinkedHashMap<>();state.skuRules.put(product,rules);}rules.put(source,replacement);}recordRuleChanges(before);}))return;
                dialog.dismiss();render();
            });
            if(editing)dialog.getButton(-3).setOnClickListener(button->confirm("규칙 삭제",oldColor+" → "+oldCode+" 규칙을 삭제합니다.",()->{if(activeEditor!=dialog||transitioning)return;StateCodec.State before=state;if(!commitChange(()->{removeRule(oldSku,oldColor);recordRuleChanges(before);}))return;dialog.dismiss();render();}));
        });dialog.show();
    }
    private void removeRule(String sku,String color){if(sku.isEmpty())state.globalRules.remove(color);else{Map<String,String> m=state.skuRules.get(sku);if(m!=null){m.remove(color);if(m.isEmpty())state.skuRules.remove(sku);}}}
    private void prepareExport(String kind){
        if(backupBlocked())return;
        if(pendingExport!=null){final ExportSnapshot frozen=pendingExport;new AlertDialog.Builder(this).setTitle("진행 중인 출력")
            .setMessage("고정된 작업 ‘"+frozen.jobCopy.title+"’의 출력을 이어갑니다.")
            .setPositiveButton("이어서 출력",(d,w)->executeExport(frozen))
            .setNegativeButton("출력 취소",(d,w)->clearFrozenExport(frozen)).show();return;}
        if(pendingBackup!=null){alert("진행 중인 백업","파일 선택 화면에서 백업을 완료하거나 취소해 주세요.");return;}
        try{repository.exclusive(()->{});}catch(IOException failure){storageFailure(failure);alert("저장 상태를 확인하지 못했습니다","입력을 보존했습니다.\n\n"+failure.getMessage());return;}
        capture();flushSourceHistory();if(!persist())return;
        if(state.draft.rows.isEmpty()){toast("출력할 정리표가 없습니다.");return;}
        if(kind.equals("share")){
            // Capture now; the later format/confirmation callback cannot choose newer stock.
            final ExportSnapshot frozen;try{frozen=store.prepareExport(repository,exportMediaStore,null,expectedGeneration,expectedRevision,"pdf","share-pdf");pendingExport=frozen;exportKind="share-pdf";}
            catch(Exception failure){storageFailure(failure);alert("파일 준비 오류",failure.getMessage());return;}
            new AlertDialog.Builder(this).setTitle("공유할 파일 선택")
                .setItems(new String[]{"PDF (A4)","엑셀 (.xlsx)"},(dialog,selected)->{
                    try{if(selected==1){ExportSnapshot next=ExportSnapshot.decode(ExportSnapshot.encode(frozen));next.format="xlsx";next.options.put("route","share-xlsx");String oldName=next.options.get("displayName");next.options.put("displayName",oldName.substring(0,oldName.length()-4)+".xlsx");
                        repository.exclusive(()->{byte[] current=store.readPending();if(!Arrays.equals(current,ExportSnapshot.encode(frozen)))throw new IOException("진행 중인 출력이 변경되었습니다.");store.writePending(ExportSnapshot.encode(next));});pendingExport=next;exportKind="share-xlsx";}
                        confirmFrozenExport(pendingExport);
                    }catch(Exception failure){storageFailure(failure);alert("파일 준비 오류",failure.getMessage());}
                }).setNegativeButton("취소",(d,w)->clearFrozenExport(frozen)).setOnCancelListener(d->clearFrozenExport(frozen)).show();return;
        }
        try{String format=kind.startsWith("share-")?kind.substring(6):kind;pendingExport=store.prepareExport(repository,exportMediaStore,null,expectedGeneration,expectedRevision,format,kind);exportKind=kind;confirmFrozenExport(pendingExport);}
        catch(Exception failure){storageFailure(failure);alert("파일 준비 오류",failure.getMessage());}
    }
    private void confirmFrozenExport(ExportSnapshot frozen){
        int unknown=0;for(Inventory.Row row:frozen.jobCopy.rows)if(row.color.equals(row.sourceColor))unknown++;
        if(unknown>0)new AlertDialog.Builder(this).setTitle("색상 코드 "+unknown+"행 미확정")
            .setMessage("아직 코드가 정해지지 않은 색상은 중국어 원문 그대로 출력됩니다. 정리표의 노란색 행에서 먼저 지정할 수 있습니다.")
            .setPositiveButton("원문 그대로 출력",(d,w)->executeExport(frozen)).setNegativeButton("돌아가서 수정",(d,w)->clearFrozenExport(frozen)).setOnCancelListener(d->clearFrozenExport(frozen)).show();
        else executeExport(frozen);
    }
    private void executeExport(ExportSnapshot frozen){
        if(!"xlsx".equals(frozen.format)){executeExportContents(frozen);return;}
        try{
            MediaSource source=store.exportMedia(repository,exportMediaStore,frozen);source.checkReady();
            if(exportPreview!=null)exportPreview.close();
            exportPreview=FrozenExportPreview.show(this,frozen,source,()->executeExportContents(frozen),()->clearFrozenExport(frozen));
        }catch(Exception failure){storageFailure(failure);alert("사진 미리보기 오류",failure.getMessage());}
    }
    private void executeExportContents(ExportSnapshot frozen){
        try{
            MediaSource source=store.exportMedia(repository,exportMediaStore,frozen);source.checkReady();
            String route=frozen.options.get("route");
            if(route.startsWith("share-")){shareFile(frozen,source);}
            else if(route.equals("print")){
                new AlertDialog.Builder(this).setTitle("A4 인쇄 미리보기")
                    .setMessage(Exporter.printSummary(frozen)+"\n\n프린터를 선택하거나 ‘PDF로 저장’을 선택하세요.")
                    .setPositiveButton("미리보기 열기",(d,w)->{try{source.checkReady();Exporter.print(this,frozen,source,()->handler.post(()->clearFrozenExport(frozen)));}catch(Exception failure){storageFailure(failure);alert("인쇄를 열지 못했습니다",failure.getMessage());}})
                    .setNegativeButton("취소",(d,w)->clearFrozenExport(frozen)).setOnCancelListener(d->clearFrozenExport(frozen)).show();
            }else if(!chooseFile(route.equals("xlsx")?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"application/pdf",frozen.options.get("displayName")))clearFrozenExport(frozen);
        }catch(Exception failure){storageFailure(failure);alert("파일 준비 오류",failure.getMessage());}
    }
    private void shareFile(ExportSnapshot frozen,MediaSource source){
        try{
            source.checkReady();Intent send=FileSharing.createSendIntent(this,frozen,source);
            Intent chooser=Intent.createChooser(send,frozen.format.equals("pdf")?"PDF 공유":"엑셀 공유");
            chooser.setClipData(send.getClipData());chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(chooser);
            clearFrozenExport(frozen);
        }catch(Exception failure){storageFailure(failure);alert("공유 준비 오류","선택한 파일을 만들거나 읽을 수 없습니다. 진행 정보를 보존했습니다.\n\n"+failure.getMessage());}
    }
    private String safeName(String name){String n=name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();return n.isEmpty()?"수량노트":n;}
    private byte[] documentPendingBytes()throws IOException {
        if(pendingExport!=null)return ExportSnapshot.encode(pendingExport);
        if(pendingBackup!=null)return pendingBackup;
        throw new IOException("진행 중인 파일 정보가 없습니다.");
    }
    private static String pendingFingerprint(byte[] bytes)throws IOException {
        try{return Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IOException(impossible);}
    }
    private boolean chooseFile(String mime,String name)throws IOException{
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);i.putExtra(Intent.EXTRA_TITLE,name);
        try{
            if(nextDocumentRequest>LAST_DOCUMENT_REQUEST)throw new IOException("파일 선택 횟수가 많습니다. 진행 정보를 보존한 뒤 앱을 다시 열어 주세요.");
            final String fingerprint=pendingFingerprint(documentPendingBytes());
            repository.exclusive(()->{if(!fingerprint.equals(pendingFingerprint(store.readPending())))throw new IOException("진행 중인 파일 정보가 변경되었습니다.");});
            documentRequest=nextDocumentRequest++;documentPendingFingerprint=fingerprint;
            documentExportToken=pendingExport==null?"backup@"+pendingBackupAt:pendingExport.pinToken;
            startActivityForResult(i,documentRequest);return true;
        }catch(ActivityNotFoundException e){documentRequest=-1;documentExportToken=null;documentPendingFingerprint=null;alert("파일 저장을 열 수 없습니다","이 휴대폰에서 파일 선택 앱을 사용할 수 있는지 확인해 주세요.");return false;}
    }
    /** Actual Android decoder shared by strict backup and native checks with the same adapter. */
    public static MediaStore.JpegValidator backupJpegValidator(CheckedAtomicFile.FileOps io){
        class ObservedRead extends FilterInputStream {
            IOException observed;ObservedRead(InputStream in){super(in);}
            private IOException remember(IOException next){if(observed==null)observed=next;else if(next instanceof CommitUncertainException&&!(observed instanceof CommitUncertainException)){next.addSuppressed(observed);observed=next;}else if(observed!=next)observed.addSuppressed(next);return next;}
            public int read()throws IOException{try{return in.read();}catch(IOException e){throw remember(e);}}
            public int read(byte[] bytes,int off,int len)throws IOException{try{return in.read(bytes,off,len);}catch(IOException e){throw remember(e);}}
            public long skip(long n)throws IOException{try{return in.skip(n);}catch(IOException e){throw remember(e);}}
            public int available()throws IOException{try{return in.available();}catch(IOException e){throw remember(e);}}
            public void close()throws IOException{try{in.close();}catch(IOException e){throw remember(e);}}
        }
        class Decode {
            android.graphics.Bitmap run(File file,android.graphics.BitmapFactory.Options options)throws IOException {
                ObservedRead input=null;android.graphics.Bitmap bitmap=null;IOException failure=null;
                try{input=new ObservedRead(io.openRead(file));bitmap=android.graphics.BitmapFactory.decodeStream(input,null,options);if(input.observed!=null)throw input.observed;}
                catch(IOException e){failure=e;}catch(OutOfMemoryError e){failure=new IOException("JPEG 해독 메모리가 부족합니다.",e);}
                finally{if(input!=null)try{input.close();}catch(IOException close){if(close instanceof CommitUncertainException){if(failure!=null&&failure!=close)close.addSuppressed(failure);failure=close;}else if(failure==null)failure=close;else if(failure!=close)failure.addSuppressed(close);}}
                if(failure!=null){if(bitmap!=null)bitmap.recycle();throw failure;}return bitmap;
            }
        }
        Decode decode=new Decode();return (file,asset)->{
            android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.Bitmap unexpected=decode.run(file,bounds);if(unexpected!=null)unexpected.recycle();
            long pixels=(long)bounds.outWidth*(long)bounds.outHeight;
            if(!"image/jpeg".equals(bounds.outMimeType)||bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth!=asset.width||bounds.outHeight!=asset.height||Math.max(bounds.outWidth,bounds.outHeight)>PhotoLimits.MAX_REGISTERED_EDGE||pixels<=0||pixels>PhotoLimits.MAX_INPUT_PIXELS)throw new IOException("백업 JPEG 크기가 올바르지 않습니다.");
            android.graphics.BitmapFactory.Options full=new android.graphics.BitmapFactory.Options();full.inSampleSize=1;full.inScaled=false;full.inDensity=0;full.inTargetDensity=0;
            android.graphics.Bitmap bitmap=null;try{bitmap=decode.run(file,full);if(bitmap==null||bitmap.getWidth()!=asset.width||bitmap.getHeight()!=asset.height)throw new IOException("백업 JPEG를 해독할 수 없습니다.");}finally{if(bitmap!=null)bitmap.recycle();}
        };
    }
    private String photoStorageBytesLabel(){try{long bytes=0;for(String hash:IntegratedBackup.liveVersions(state)){long n=state.catalog.assets.get(hash).byteLength;if(n>Long.MAX_VALUE-bytes)throw new IOException("사진 크기 한도");bytes+=n;}return android.text.format.Formatter.formatFileSize(this,bytes);}catch(IOException failure){return "확인할 수 없음";}}
    private String backupFileName(long at){return "수량노트_백업_"+new SimpleDateFormat("yyyyMMdd_HHmm",Locale.KOREA).format(new Date(at))+".snote";}
    private void freezeBackupInput(){if(saveRunnable!=null){handler.removeCallbacks(saveRunnable);saveRunnable=null;}if(captureDialogInput!=null)captureDialogInput.run();for(AlertDialog editor:new ArrayList<>(editorDialogs)){editor.setOnDismissListener(null);editor.dismiss();}editorDialogs.clear();activeEditor=null;captureDialogInput=null;}
    private void backup(){
        if(backupBlocked())return;
        if(pendingExport!=null){alert("진행 중인 파일 저장","기존 출력을 완료하거나 취소해 주세요.");return;}
        capture();flushSourceHistory();if(!persist())return;
        freezeBackupInput();
        if(pendingBackup!=null)backupSession.resumeBackup(pendingBackup,pendingBackupAt);
        else backupSession.prepareBackup(expectedGeneration,expectedRevision,System.currentTimeMillis());
    }
    private void restore(){
        if(backupBlocked()||storageUnavailable)return;if(conflicted){showConflictNotice();return;}
        capture();flushSourceHistory();if(!persist())return;
        if(pendingExport!=null&&documentRequest>=0){alert("진행 중인 파일 저장","파일 선택을 먼저 완료하거나 취소해 주세요.");return;}
        freezeBackupInput();
        backupSession.recoveredExport=pendingExport;backupSession.pendingBackup=pendingBackup;backupSession.pendingBackupAt=pendingBackupAt;
        backupSession.restorePicker();
    }
    private boolean clearFrozenExport(ExportSnapshot frozen){if(pendingExport==null||!pendingExport.pinToken.equals(frozen.pinToken))return false;return clearPendingExport();}
    private boolean clearPendingExport(){try{if(pendingExport!=null)store.finishExport(repository,exportMediaStore,pendingExport);else if(pendingBackup!=null){if(!backupBlocked())backupSession.cancelBackup(pendingBackup,pendingBackupAt);return false;}else repository.exclusive(store::clearPending);pendingBackup=null;pendingBackupAt=0;pendingExport=null;exportKind=null;documentExportToken=null;documentPendingFingerprint=null;documentRequest=-1;return true;}catch(Exception failure){storageFailure(failure);alert("진행 중인 파일 정보를 지우지 못했습니다","진행 정보를 보존했습니다.\n\n"+failure.getMessage());return false;}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(backupSession!=null&&backupSession.ownsRequest(request)){backupSession.pickerResult(request,result,data==null?null:data.getData());return;}
        if(backupBlocked()||"backup".equals(exportKind)||pendingBackup!=null)return;
        if(request>=FIRST_DOCUMENT_REQUEST&&request<=LAST_DOCUMENT_REQUEST){
            // The request belongs to the launch, never to whichever operation is current now.
            if(request!=documentRequest)return;
            String callbackToken=pendingExport==null?"backup@"+pendingBackupAt:pendingExport.pinToken;
            if(documentExportToken==null||!documentExportToken.equals(callbackToken)||documentPendingFingerprint==null)return;
            try{repository.exclusive(()->{
                if(!documentPendingFingerprint.equals(pendingFingerprint(documentPendingBytes()))||!documentPendingFingerprint.equals(pendingFingerprint(store.readPending())))throw new IOException("진행 중인 파일 정보가 변경되었습니다.");
            });}catch(IOException failure){storageFailure(failure);alert("저장 상태를 확인하지 못했습니다","입력과 진행 정보를 보존했습니다.\n\n"+failure.getMessage());return;}
            if(result!=RESULT_OK||data==null||data.getData()==null){clearPendingExport();return;}
            boolean externalSaved=false;
            try{
                if(!"backup".equals(exportKind)){if(pendingExport==null)throw new IOException("저장을 다시 시도해 주세요.");pendingExport.verifyMedia(store.exportMedia(repository,exportMediaStore,pendingExport));}
                Uri preparedUri=null;
                if(pendingExport!=null){Intent prepared=FileSharing.createSendIntent(this,pendingExport,store.exportMedia(repository,exportMediaStore,pendingExport));preparedUri=prepared.getParcelableExtra(Intent.EXTRA_STREAM);}
                Uri uri=data.getData();
                // Render/read all protected media before opening a potentially existing destination.
                try(InputStream prepared=preparedUri==null?null:getContentResolver().openInputStream(preparedUri)){
                if(preparedUri!=null&&prepared==null)throw new IOException("준비한 출력 파일을 열 수 없습니다.");
                try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                    if(out==null)throw new IOException("파일을 열 수 없습니다.");
                        if(pendingExport==null)throw new IOException("저장을 다시 시도해 주세요.");
                        byte[] block=new byte[32768];for(int n;(n=prepared.read(block))!=-1;)out.write(block,0,n);
                    out.flush();
                }
                }
                externalSaved=true;
                if(clearPendingExport())toast("파일을 저장했습니다.");
            }catch(Exception e){storageFailure(e);
                if(externalSaved&&"backup".equals(exportKind))alert("백업 파일은 저장됐습니다","앱 안의 마지막 백업 시각은 기록하지 못했습니다.\n\n"+e.getMessage());
                else alert("파일을 저장하지 못했습니다",e.getMessage());
            }
            return;
        }
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
    }
    private static StateCodec.State decodeLegacyBackup(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length<8||bytes.length>PhotoLimits.MAX_LEGACY_BYTES)throw new IOException("백업 형식이 올바르지 않습니다.");
        DataInputStream header=new DataInputStream(new ByteArrayInputStream(bytes));header.readInt();int version=header.readInt();
        if(version<1||version>9)throw new IOException("사진 자료는 통합 백업 파일로 복원해야 합니다.");
        return StateCodec.decode(bytes);
    }
    private static byte[] readLimited(InputStream in,int max)throws IOException{if(in==null)throw new IOException("파일을 열 수 없습니다.");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("파일이 너무 큽니다.");out.write(b,0,n);}return out.toByteArray();}
    private void alert(String title,String message){new AlertDialog.Builder(this).setTitle(title).setMessage(message==null?"오류가 발생했습니다.":message).setPositiveButton("확인",null).show();}
    private void confirm(String title,String message,Runnable action){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("확인",(d,w)->action.run()).setNegativeButton("취소",null).show();}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
}
