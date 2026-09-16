package org.zxor.oronbox.xms.internal;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class TaskExecutors {
    private static final int PROCESSORS = Runtime.getRuntime().availableProcessors();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public static final Executor MAIN = MAIN_HANDLER::post;
    public static final ExecutorService BACKGROUND = createBackgroundExecutor();
    public static final ScheduledExecutorService SCHEDULED =
            new ScheduledThreadPoolExecutor(1);

    private TaskExecutors() {}

    private static ExecutorService createBackgroundExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                PROCESSORS + 1,
                PROCESSORS * 2 + 1,
                1,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
