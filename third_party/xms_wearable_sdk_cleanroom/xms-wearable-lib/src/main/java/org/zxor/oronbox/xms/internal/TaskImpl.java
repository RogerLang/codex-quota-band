package org.zxor.oronbox.xms.internal;

import com.xiaomi.xms.wearable.tasks.OnFailureListener;
import com.xiaomi.xms.wearable.tasks.OnSuccessListener;
import com.xiaomi.xms.wearable.tasks.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public final class TaskImpl<TResult> extends Task<TResult> {
    private final Object lock = new Object();
    private final List<CompletionListener<TResult>> listeners = new ArrayList<>();

    private boolean complete;
    private TResult result;
    private Exception exception;

    @Override
    public boolean isComplete() {
        synchronized (lock) {
            return complete;
        }
    }

    @Override
    public boolean isSuccessful() {
        synchronized (lock) {
            return complete && exception == null;
        }
    }

    @Override
    public boolean isCanceled() {
        return false;
    }

    @Override
    public TResult getResult() {
        synchronized (lock) {
            if (exception != null) {
                throw new RuntimeException(exception);
            }
            return result;
        }
    }

    @Override
    public <X extends Throwable> TResult getResult(Class<X> exceptionType) throws X {
        synchronized (lock) {
            if (exceptionType.isInstance(exception)) {
                throw exceptionType.cast(exception);
            }
            if (exception != null) {
                throw new RuntimeException(exception);
            }
            return result;
        }
    }

    @Override
    public Exception getException() {
        synchronized (lock) {
            return exception;
        }
    }

    @Override
    public Task<TResult> addOnSuccessListener(OnSuccessListener<? super TResult> listener) {
        return addOnSuccessListener(TaskExecutors.MAIN, listener);
    }

    @Override
    public Task<TResult> addOnSuccessListener(
            Executor executor,
            OnSuccessListener<? super TResult> listener
    ) {
        return addListener(task -> {
            if (task.isSuccessful()) {
                executor.execute(() -> listener.onSuccess(task.getResult()));
            }
        });
    }

    @Override
    public Task<TResult> addOnFailureListener(OnFailureListener listener) {
        return addOnFailureListener(TaskExecutors.MAIN, listener);
    }

    @Override
    public Task<TResult> addOnFailureListener(Executor executor, OnFailureListener listener) {
        return addListener(task -> {
            if (!task.isSuccessful()) {
                executor.execute(() -> listener.onFailure(task.getException()));
            }
        });
    }

    public boolean trySetResult(TResult value) {
        return complete(value, null);
    }

    public boolean trySetException(Exception error) {
        return complete(null, error);
    }

    private Task<TResult> addListener(CompletionListener<TResult> listener) {
        boolean notifyNow;
        synchronized (lock) {
            notifyNow = complete;
            if (!notifyNow) {
                listeners.add(listener);
            }
        }
        if (notifyNow) {
            listener.onComplete(this);
        }
        return this;
    }

    private boolean complete(TResult value, Exception error) {
        List<CompletionListener<TResult>> pending;
        synchronized (lock) {
            if (complete) {
                return false;
            }
            complete = true;
            result = value;
            exception = error;
            pending = new ArrayList<>(listeners);
            listeners.clear();
        }
        for (CompletionListener<TResult> listener : pending) {
            listener.onComplete(this);
        }
        return true;
    }

    private interface CompletionListener<T> {
        void onComplete(Task<T> task);
    }
}
