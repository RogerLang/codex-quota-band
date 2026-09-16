package com.xiaomi.xms.wearable.tasks;

import java.util.concurrent.Executor;

public abstract class Task<TResult> {
    public abstract boolean isComplete();

    public abstract boolean isSuccessful();

    public abstract boolean isCanceled();

    public abstract TResult getResult();

    public abstract <X extends Throwable> TResult getResult(Class<X> exceptionType) throws X;

    public abstract Exception getException();

    public abstract Task<TResult> addOnSuccessListener(OnSuccessListener<? super TResult> listener);

    public abstract Task<TResult> addOnSuccessListener(
            Executor executor,
            OnSuccessListener<? super TResult> listener
    );

    public abstract Task<TResult> addOnFailureListener(OnFailureListener listener);

    public abstract Task<TResult> addOnFailureListener(
            Executor executor,
            OnFailureListener listener
    );
}
