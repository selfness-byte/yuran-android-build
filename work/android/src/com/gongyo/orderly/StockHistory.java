package com.gongyo.orderly;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/** Independent job stock baselines and bounded, immutable records of completed work. */
public final class StockHistory {
    public static final int MAX_ENTRIES=500;
    public static final long MAX_TOTAL=(long)Inventory.MAX_ROWS*Inventory.SIZES.length*Inventory.MAX_QUANTITY;
    private StockHistory(){}

    public static final class Entry {
        public final String id,jobTitle,stockSessionId;
        public final long initialQuantity,finalQuantity,sourceRecordedAt,stockUpdatedAt,closedAt,changeCount;
        public Entry(String id,String jobTitle,long initialQuantity,long finalQuantity,long sourceRecordedAt,long stockUpdatedAt,long closedAt){
            this(id,jobTitle,initialQuantity,finalQuantity,sourceRecordedAt,stockUpdatedAt,closedAt,"",-1);
        }
        public Entry(String id,String jobTitle,long initialQuantity,long finalQuantity,long sourceRecordedAt,long stockUpdatedAt,long closedAt,String stockSessionId,long changeCount){
            this.id=id;this.jobTitle=jobTitle;this.initialQuantity=initialQuantity;this.finalQuantity=finalQuantity;
            this.sourceRecordedAt=sourceRecordedAt;this.stockUpdatedAt=stockUpdatedAt;this.closedAt=closedAt;this.stockSessionId=stockSessionId;this.changeCount=changeCount;
        }
    }

    public static String ensureSession(Inventory.Job job){
        if(job==null)throw new IllegalArgumentException("작업이 없습니다.");
        if(job.stockSessionId==null)throw new IllegalArgumentException("작업 식별자가 올바르지 않습니다.");
        if(job.stockSessionId.isEmpty())job.stockSessionId=UUID.randomUUID().toString();
        if(job.stockChangeCount<0)job.stockChangeCount=0;
        return job.stockSessionId;
    }

    public static List<ChangeHistory.Entry> changesForSession(List<ChangeHistory.Entry> entries,String stockSessionId){
        List<ChangeHistory.Entry> result=new ArrayList<>();
        if(entries==null||stockSessionId==null||stockSessionId.isEmpty())return result;
        for(int i=entries.size()-1;i>=0;i--){ChangeHistory.Entry entry=entries.get(i);if(stockSessionId.equals(entry.stockSessionId))result.add(entry);}
        return result;
    }

    public static long missingChangeCount(long expectedCount,int retainedCount){
        if(expectedCount<0)return -1;
        return Math.max(0,expectedCount-retainedCount);
    }

    /** Recover only a numerical source baseline. Never infer a historical event time. */
    public static boolean recoverBaseline(Inventory.Job job,Map<String,String> globals,Map<String,Map<String,String>> scoped){
        if(job==null||job.initialQuantity>=0||!job.hasCurrentTable()||job.sourceText==null||job.sourceText.trim().isEmpty())return false;
        Inventory.ParseResult original=Parser.parse(job.sourceText,globals,scoped);
        if(!original.isValid())return false;
        long total=Inventory.grandTotal(original.rows);validQuantity(total,false);job.initialQuantity=total;return true;
    }

    /** Called only after the first accepted parse has populated this job's rows. */
    public static boolean initializeParsed(Inventory.Job job,long now){
        if(job==null)throw new IllegalArgumentException("작업이 없습니다.");
        if(job.initialQuantity>=0)return false;
        validTime(now);long total=Inventory.grandTotal(job.rows);validQuantity(total,false);
        job.initialQuantity=total;job.stockUpdatedAt=now;job.stockSessionId=UUID.randomUUID().toString();job.stockChangeCount=0;return true;
    }

    /** Records a real four-size quantity change, including same-total size transfers. */
    public static boolean noteRowChange(Inventory.Job before,Inventory.Job after,Inventory.Row oldRow,Inventory.Row newRow,long now){
        if(before==null||after==null)throw new IllegalArgumentException("작업이 없습니다.");
        boolean changed=false;
        for(int i=0;i<Inventory.SIZES.length;i++)if((oldRow==null?0:oldRow.quantities[i])!=(newRow==null?0:newRow.quantities[i]))changed=true;
        if(!changed)return false;
        validTime(now);
        if(!before.hasCurrentTable())after.initialQuantity=0;
        after.stockUpdatedAt=now;return true;
    }

    /** Invoke inside the caller's atomic switch, after cancellation decisions are settled. */
    public static boolean finish(StateCodec.State state,Inventory.Job job,long now){
        if(state==null||job==null)throw new IllegalArgumentException("작업이 없습니다.");
        if(!job.hasCurrentTable())return false;
        validTime(now);long remaining=Inventory.grandTotal(job.rows);validQuantity(job.initialQuantity,true);validQuantity(remaining,false);
        if(job.title==null||job.title.length()>80||job.sourceRecordedAt<0||job.stockUpdatedAt<0)throw new IllegalArgumentException("재고 기록의 내용이 올바르지 않습니다.");
        if(job.stockSessionId!=null&&!job.stockSessionId.isEmpty())for(int i=0;i<state.stockHistory.size();i++){
            Entry previous=state.stockHistory.get(i);
            if(job.stockSessionId.equals(previous.stockSessionId)){
                long count=Math.max(previous.changeCount,job.stockChangeCount);
                Entry updated=new Entry(previous.id,job.title,previous.initialQuantity,remaining,previous.sourceRecordedAt,job.stockUpdatedAt,now,job.stockSessionId,count);
                state.stockHistory.remove(i);state.stockHistory.add(0,updated);return true;
            }
        }
        Entry entry=new Entry(UUID.randomUUID().toString(),job.title,job.initialQuantity,remaining,job.sourceRecordedAt,job.stockUpdatedAt,now,job.stockSessionId,job.stockSessionId==null||job.stockSessionId.isEmpty()?-1:job.stockChangeCount);
        state.stockHistory.add(0,entry);while(state.stockHistory.size()>MAX_ENTRIES)state.stockHistory.remove(state.stockHistory.size()-1);return true;
    }

    private static void validTime(long timestamp){if(timestamp<=0)throw new IllegalArgumentException("기록 시각이 올바르지 않습니다.");}
    private static void validQuantity(long quantity,boolean unknown){if(quantity<(unknown?-1:0)||quantity>MAX_TOTAL)throw new IllegalArgumentException("재고 수량이 허용 범위를 벗어났습니다.");}
}
