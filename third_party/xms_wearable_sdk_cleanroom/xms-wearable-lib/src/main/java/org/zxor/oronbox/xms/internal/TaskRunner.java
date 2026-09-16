package org.zxor.oronbox.xms.internal;

import com.xiaomi.xms.wearable.tasks.Task;

public final class TaskRunner {
    private TaskRunner() {}

    public static <T> Task<T> run(Operation<T> operation) {
        TaskImpl<T> task = new TaskImpl<>();
        TaskExecutors.BACKGROUND.execute(() -> {
            try {
                operation.run(task);
            } catch (Exception error) {
                task.trySetException(error);
            }
        });
        return task;
    }

    public interface Operation<T> {
        void run(TaskImpl<T> task) throws Exception;
    }
}
