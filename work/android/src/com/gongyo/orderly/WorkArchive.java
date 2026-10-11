package com.gongyo.orderly;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** One saved entry follows a work ID until the user explicitly starts another work. */
public final class WorkArchive {
    private static final int MAX_WORKS = 100;
    private WorkArchive() {}

    public static void createNew(StateCodec.State state, Inventory.Job next, long now) {
        if (next == null || next.id == null || !next.id.isEmpty()) {
            throw new IllegalArgumentException("새 작업의 식별자가 올바르지 않습니다.");
        }
        requireSpace(state);
        if (now <= 0) throw new IllegalArgumentException("작업 생성 시각이 올바르지 않습니다.");
        next.id = UUID.randomUUID().toString();
        assignDefaultTitle(state, next, now);
        state.jobs.add(0, next.copy());
        state.draft = next;
    }

    /** Adopt a meaningful pre-existing draft once; a deleted saved work stays deleted. */
    public static boolean retainCurrent(StateCodec.State state, long now) {
        Inventory.Job draft = state.draft;
        if (draft.id != null && !draft.id.isEmpty()) return refreshTrackedDraft(state);
        if (!draft.hasCurrentTable() && draft.sourceText.trim().isEmpty()) return false;
        requireSpace(state);
        if (now <= 0) throw new IllegalArgumentException("작업 저장 시각이 올바르지 않습니다.");
        draft.id = UUID.randomUUID().toString();
        assignDefaultTitle(state, draft, now);
        state.jobs.add(0, draft.copy());
        return true;
    }

    /** Refresh only an existing entry, without creating, reordering or deleting work. */
    public static boolean refreshTrackedDraft(StateCodec.State state) {
        Inventory.Job draft = state.draft;
        if (draft == null || draft.id == null || draft.id.isEmpty()) return false;
        for (int i = 0; i < state.jobs.size(); i++) {
            if (draft.id.equals(state.jobs.get(i).id)) {
                state.jobs.set(i, draft.copy());
                return true;
            }
        }
        return false;
    }

    /** Completion state is committed together with the matching archive entry. */
    public static void setCompleted(StateCodec.State state, boolean completed) {
        if (state == null || state.draft == null) {
            throw new IllegalArgumentException("완료할 작업이 없습니다.");
        }
        if (completed && !state.draft.completed) {
            // Freeze exactly what the active screen/export currently displays before locking.
            // displayBinding preserves hidden and ambiguous references without changing stock.
            for (Inventory.Row row : state.draft.rows) {
                row.binding = PhotoBindings.displayBinding(state, row);
            }
        }
        state.draft.completed = completed;
        refreshTrackedDraft(state);
    }

    private static void requireSpace(StateCodec.State state) {
        if (state.jobs.size() >= MAX_WORKS) {
            throw new IllegalArgumentException("보관함에 작업이 100개 있습니다. 필요 없는 작업을 삭제한 뒤 새 작업을 만들어 주세요.");
        }
    }

    private static void assignDefaultTitle(StateCodec.State state, Inventory.Job job, long now) {
        String title = job.title == null ? "" : job.title.trim();
        if (!title.isEmpty() && !"새 주문".equals(title) && !"새 작업".equals(title)) return;
        String prefix = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date(now)) + " 작업 ";
        int number = 1;
        while (hasTitle(state, prefix + number)) number++;
        job.title = prefix + number;
    }

    private static boolean hasTitle(StateCodec.State state, String title) {
        for (Inventory.Job saved : state.jobs) if (title.equals(saved.title)) return true;
        return false;
    }
}
