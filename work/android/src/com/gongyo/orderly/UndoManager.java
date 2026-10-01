package com.gongyo.orderly;

import java.util.ArrayList;
import java.util.Map;

/** One-step undo for the active draft's supported table changes. */
public final class UndoManager {
    private UndoManager() {}

    /** Capture the state immediately before a table change; rule arguments describe its write. */
    public static void capture(StateCodec.State state, String ruleSku, String sourceColor, String replacementCode) {
        if (state == null || state.draft == null) return;
        UndoSnapshot snapshot = new UndoSnapshot();
        snapshot.draftJobId = safe(state.draft.id);
        for (Inventory.Row row : state.draft.rows) snapshot.rows.add(row.copy());
        snapshot.initialQuantity = state.draft.initialQuantity;
        snapshot.stockUpdatedAt = state.draft.stockUpdatedAt;
        snapshot.updatedAt = state.draft.updatedAt;
        if (hasText(ruleSku) && hasText(sourceColor) && hasText(replacementCode)) {
            snapshot.ruleSku = ruleSku;
            snapshot.sourceColor = sourceColor;
            Map<String, String> rules = state.skuRules.get(ruleSku);
            snapshot.previousRuleValue = rules == null ? null : rules.get(sourceColor);
            snapshot.writtenRuleValue = replacementCode;
        }
        state.undoSnapshot = snapshot;
    }

    public static boolean canUndo(StateCodec.State state) {
        UndoSnapshot snapshot = state == null ? null : state.undoSnapshot;
        Inventory.Job draft = state == null ? null : state.draft;
        return snapshot != null && draft != null && !draft.completed
                && safe(snapshot.draftJobId).equals(safe(draft.id));
    }

    /** Restore the captured draft and record the undo without removing existing history. */
    public static boolean undo(StateCodec.State state, String jobTitle) {
        if (!canUndo(state) || state.undoSnapshot.kind != UndoSnapshot.TABLE_CHANGE) return false;
        UndoSnapshot snapshot = state.undoSnapshot;
        Inventory.Job draft = state.draft;
        Inventory.Job before = new Inventory.Job();
        before.updatedAt = snapshot.updatedAt;
        before.rows = draft.rows;
        Inventory.Row changedBefore = null, changedAfter = null;
        int start=0;
        while(start<draft.rows.size()&&start<snapshot.rows.size()
                &&ChangeHistory.sameRow(draft.rows.get(start),snapshot.rows.get(start)))start++;
        if(start<draft.rows.size()||start<snapshot.rows.size()){
            if(draft.rows.size()>snapshot.rows.size())changedBefore=draft.rows.get(start);
            else if(draft.rows.size()<snapshot.rows.size())changedAfter=snapshot.rows.get(start);
            else{changedBefore=draft.rows.get(start);changedAfter=snapshot.rows.get(start);}
        }
        draft.rows = new ArrayList<>();
        for (Inventory.Row row : snapshot.rows) draft.rows.add(row.copy());
        draft.initialQuantity = snapshot.initialQuantity;
        draft.stockUpdatedAt = snapshot.stockUpdatedAt;
        draft.updatedAt = snapshot.updatedAt;
        if(changedBefore!=null||changedAfter!=null){
            long now=System.currentTimeMillis();
            StockHistory.noteRowChange(before,draft,changedBefore,changedAfter,now);
            ChangeHistory.recordRow(state,draft,jobTitle == null ? draft.title : jobTitle,
                    "되돌리기",changedBefore,changedAfter);
        }
        if (hasText(snapshot.ruleSku) && hasText(snapshot.sourceColor)
                && hasText(snapshot.writtenRuleValue)) {
            Map<String, String> rules = state.skuRules.get(snapshot.ruleSku);
            String current = rules == null ? null : rules.get(snapshot.sourceColor);
            if (snapshot.writtenRuleValue.equals(current)) {
                if (snapshot.previousRuleValue == null) {
                    if (rules != null) {
                        rules.remove(snapshot.sourceColor);
                        if (rules.isEmpty()) state.skuRules.remove(snapshot.ruleSku);
                    }
                } else {
                    if (rules == null) {
                        rules = new java.util.LinkedHashMap<>();
                        state.skuRules.put(snapshot.ruleSku, rules);
                    }
                    rules.put(snapshot.sourceColor, snapshot.previousRuleValue);
                }
            }
        }
        state.undoSnapshot = null;
        if(changedBefore==null&&changedAfter==null)ChangeHistory.record(state, jobTitle == null ? draft.title : jobTitle,
                "되돌리기", "되돌리기: 마지막 표 변경을 되돌렸습니다.");
        return true;
    }

    /** Nullable configured rule only; built-in/raw output fallbacks are not rules. */
    static String effectivePhotoRule(StateCodec.State state,Inventory.Row row){
        String key=row.sourceColor.trim();Map<String,String> local=state.skuRules.get(row.sku);
        String value=local==null?null:local.get(key);
        if(value==null||value.trim().isEmpty())value=state.globalRules.get(key);
        return value==null||value.trim().isEmpty()?null:value.trim().toUpperCase(java.util.Locale.ROOT);
    }
    static boolean validPhotoRuleExpectation(UndoSnapshot u){
        if(u==null||u.expectedRow==null)return false;
        if(u.ruleSku==null||u.ruleSku.isEmpty())return "".equals(u.ruleSku)&&"".equals(u.sourceColor)&&u.previousRuleValue==null&&u.writtenRuleValue==null;
        return u.ruleSku.equals(u.expectedRow.sku)&&u.sourceColor!=null&&u.sourceColor.equals(u.expectedRow.sourceColor.trim())
                &&u.previousRuleValue!=null&&u.previousRuleValue.matches("[A-Z][A-Z0-9_-]{0,15}")
                &&u.previousRuleValue.equals(u.writtenRuleValue);
    }

    /** Photo-only undo never restores stock/table/rule values. */
    public static boolean undoPhoto(StateCodec.State candidate,long commitRevision) {
        if(!canUndo(candidate)||candidate.undoSnapshot.kind!=UndoSnapshot.PHOTO_BINDING||commitRevision<=candidate.revision)return false;
        UndoSnapshot u=candidate.undoSnapshot;Inventory.Row row=PhotoBindings.findRow(candidate,u.targetRowId);
        if(row==null||!validPhotoRuleExpectation(u)||!java.util.Objects.equals(effectivePhotoRule(candidate,row),u.writtenRuleValue)
                ||!java.util.Objects.equals(row.binding,u.expectedAfterBinding)
                ||!ChangeHistory.sameRow(row,u.expectedRow)||row.sourceLine!=u.expectedRow.sourceLine
                ||candidate.draft.initialQuantity!=u.initialQuantity||candidate.draft.stockUpdatedAt!=u.stockUpdatedAt||candidate.draft.updatedAt!=u.updatedAt)return false;
        row.binding=u.previousBinding==null?null:u.previousBinding.copy();
        if(row.binding!=null)row.binding.bindingRevision=commitRevision;
        candidate.undoSnapshot=null;
        ChangeHistory.record(candidate,candidate.draft.title,"사진 되돌리기",row.sku+" · "+row.sourceColor+"\n마지막 사진 연결 변경을 되돌렸습니다.");
        return true;
    }

    public static void clear(StateCodec.State state) {
        if (state != null) state.undoSnapshot = null;
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static boolean hasText(String value) { return value != null && !value.isEmpty(); }
}
