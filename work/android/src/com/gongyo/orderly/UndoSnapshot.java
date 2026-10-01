package com.gongyo.orderly;

import java.util.ArrayList;
import java.util.List;

/** A bounded value snapshot for undoing one change to the active draft. */
public final class UndoSnapshot {
    public static final byte TABLE_CHANGE=0, PHOTO_BINDING=1;
    public byte kind=TABLE_CHANGE;
    public String targetRowId="";
    public PhotoCatalog.Binding previousBinding,expectedAfterBinding;
    /** Expected non-photo row contents, compared before photo-only undo. */
    public Inventory.Row expectedRow;
    public String draftJobId = "";
    public List<Inventory.Row> rows = new ArrayList<>();
    public long initialQuantity = -1;
    public long stockUpdatedAt;
    public long updatedAt;

    /** TABLE: optional single SKU rule mutation. PHOTO: nullable effective configured
     * target-row rule expectation, with real SKU/source lookup keys and equal before/after
     * values. Empty keys and null values mean expected rule absence, never skip checking. */
    public String ruleSku = "";
    public String sourceColor = "";
    /** TABLE: null means no old SKU rule. PHOTO: equals writtenRuleValue, or both null. */
    public String previousRuleValue;
    /** TABLE: rule written by this change. PHOTO: expected configured value; no rule write. */
    public String writtenRuleValue;

    public UndoSnapshot copy() {
        UndoSnapshot copy = new UndoSnapshot();
        copy.kind=kind;copy.targetRowId=targetRowId;
        copy.previousBinding=previousBinding==null?null:previousBinding.copy();
        copy.expectedAfterBinding=expectedAfterBinding==null?null:expectedAfterBinding.copy();
        copy.expectedRow=expectedRow==null?null:expectedRow.copy();
        copy.draftJobId = draftJobId;
        for (Inventory.Row row : rows) copy.rows.add(row.copy());
        copy.initialQuantity = initialQuantity;
        copy.stockUpdatedAt = stockUpdatedAt;
        copy.updatedAt = updatedAt;
        copy.ruleSku = ruleSku;
        copy.sourceColor = sourceColor;
        copy.previousRuleValue = previousRuleValue;
        copy.writtenRuleValue = writtenRuleValue;
        return copy;
    }
}
