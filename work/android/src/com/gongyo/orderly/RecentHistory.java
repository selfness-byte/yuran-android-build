package com.gongyo.orderly;

import java.util.Iterator;
import java.util.UUID;

/** Automatic source snapshots are independent of the user's named saved jobs. */
public final class RecentHistory {
    public static final int MAX_JOBS = StateCodec.MAX_RECENT_JOBS;

    private RecentHistory() {}

    /** Keep the latest exact source once, without changing the original job. */
    public static void remember(StateCodec.State state, Inventory.Job job) {
        if (job == null || job.sourceText == null || job.sourceText.trim().isEmpty()) return;

        Inventory.Job snapshot = new Inventory.Job();
        snapshot.id = UUID.randomUUID().toString();
        snapshot.title = job.title;
        snapshot.sourceText = job.sourceText;
        snapshot.sourceRecordedAt = job.sourceRecordedAt;
        snapshot.initialQuantity = job.initialQuantity;
        snapshot.stockUpdatedAt = job.stockUpdatedAt;
        snapshot.inputCleared = job.inputCleared;
        snapshot.updatedAt = System.currentTimeMillis();
        for (Inventory.Row row : job.rows) snapshot.rows.add(row.copy());

        for (Iterator<Inventory.Job> it = state.recentJobs.iterator(); it.hasNext();) {
            if (snapshot.sourceText.equals(it.next().sourceText)) it.remove();
        }
        state.recentJobs.add(0, snapshot);
        while (state.recentJobs.size() > MAX_JOBS) {
            state.recentJobs.remove(state.recentJobs.size() - 1);
        }
    }
}
