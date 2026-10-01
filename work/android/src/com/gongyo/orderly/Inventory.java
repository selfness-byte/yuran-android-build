package com.gongyo.orderly;

import java.util.ArrayList;
import java.util.List;

public final class Inventory {
    public static final String[] SIZES = {"S", "M", "L", "XL"};
    public static final long MAX_QUANTITY = 999999L;
    public static final int MAX_ROWS = 2000;
    public static final int MAX_SOURCE_CHARS = 200000;
    private Inventory() {}

    public static final class Row {
        public String rowId = java.util.UUID.randomUUID().toString();
        public PhotoCatalog.Binding binding;
        public String sku = "";
        public String sourceColor = "";
        public String color = "";
        public long[] quantities = new long[4];
        public int sourceLine;
        public long total() { long total = 0; for (long q : quantities) total += q; return total; }
        public Row copy() { Row row = new Row(); row.rowId=rowId; row.binding=binding==null?null:binding.copy(); row.sku=sku; row.sourceColor=sourceColor; row.color=color; row.quantities=quantities.clone(); row.sourceLine=sourceLine; return row; }
    }
    public static final class Job {
        public String id = "";
        public String title = "새 주문";
        public String sourceText = "";
        public long updatedAt;
        public long sourceRecordedAt;
        /** -1 means no trustworthy quantity baseline is available. */
        public long initialQuantity=-1;
        /** Last actual size-quantity change; archive saves and identity edits do not alter it. */
        public long stockUpdatedAt;
        /** Hide the active input display while preserving its fixed original and stock. */
        public boolean inputCleared;
        /** Editable next-order text shown after clearing an active job's input field. */
        public String inputBuffer="";
        /** First character/paste time for the separate next-order input buffer. */
        public long inputBufferRecordedAt;
        /** A completed job remains viewable but its draft/table is locked. */
        public boolean completed;
        /** Stable identity for one stock-work timeline, separate from the archive ID. */
        public String stockSessionId="";
        /** -1 means a migrated active job has no trustworthy historical count. */
        public long stockChangeCount;
        public List<Row> rows = new ArrayList<>();
        public Job copy() {
            Job c=new Job(); c.id=id;c.title=title;c.sourceText=sourceText;c.updatedAt=updatedAt;
            c.sourceRecordedAt=sourceRecordedAt;c.initialQuantity=initialQuantity;c.stockUpdatedAt=stockUpdatedAt;
            c.inputCleared=inputCleared;c.inputBuffer=inputBuffer;c.inputBufferRecordedAt=inputBufferRecordedAt;
            c.completed=completed;c.stockSessionId=stockSessionId;c.stockChangeCount=stockChangeCount;
            for(Row row:rows)c.rows.add(row.copy());return c;
        }
        /** A saved work ID alone does not lock the blank work's input field. */
        public boolean hasCurrentTable() { return updatedAt!=0 || !rows.isEmpty(); }
        /** Initial input time is independent of subsequent table edits and archive saves. */
        public void updateSourceInput(String value,long now) {
            if(hasCurrentTable()||sourceText.equals(value))return;
            if(value.trim().isEmpty())sourceRecordedAt=0;
            else if(sourceText.trim().isEmpty()){if(now<=0)throw new IllegalArgumentException("입력 시각이 올바르지 않습니다.");sourceRecordedAt=now;}
            sourceText=value;
        }
        /** Update only the staged next-order text; the current table and original remain fixed. */
        public void updateInputBuffer(String value,long now) {
            if(!inputCleared)return;
            if(value==null||value.length()>MAX_SOURCE_CHARS)throw new IllegalArgumentException("입력 내용이 너무 깁니다.");
            if(value.trim().isEmpty())inputBufferRecordedAt=0;
            else if(inputBuffer.trim().isEmpty()&&inputBufferRecordedAt==0){if(now<=0)throw new IllegalArgumentException("입력 시각이 올바르지 않습니다.");inputBufferRecordedAt=now;}
            inputBuffer=value;
        }
    }
    public static final class ParseResult {
        public List<Row> rows = new ArrayList<>();
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public boolean isValid() { return errors.isEmpty(); }
    }
    public static long[] totals(List<Row> rows) { long[] sums = new long[4]; for(Row row : rows) for(int i=0;i<4;i++) sums[i]+=row.quantities[i]; return sums; }
    public static long grandTotal(List<Row> rows) { long total=0; for(Row row : rows) total+=row.total(); return total; }
}
