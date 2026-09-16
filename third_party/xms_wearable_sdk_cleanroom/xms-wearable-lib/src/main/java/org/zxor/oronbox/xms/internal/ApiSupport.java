package org.zxor.oronbox.xms.internal;

import android.content.Context;

import com.xiaomi.xms.wearable.Status;
import com.xiaomi.xms.wearable.exception.ExceptionUtil;
import com.xiaomi.xms.wearable.tasks.Task;

public abstract class ApiSupport {
    protected final WearableClient client;

    protected ApiSupport(Context context) {
        if (context == null) throw new NullPointerException("context");
        client = WearableClient.get(context.getApplicationContext());
    }

    protected static <T> TaskImpl<T> task() {
        return new TaskImpl<>();
    }

    protected static Exception statusError(Status status, String fallback) {
        if (status == null) return new Exception(fallback);
        Exception converted = ExceptionUtil.convertStatusToException(status);
        return converted != null ? converted : new Exception(fallback);
    }

    protected static WearableClient.RemoteOperation operation(
            RemoteCall call,
            TaskImpl<?> task
    ) {
        return new WearableClient.RemoteOperation() {
            @Override public void run(com.xiaomi.xms.wearable.IWearableInterface service)
                    throws android.os.RemoteException {
                call.run(service);
            }

            @Override public void onUnavailable() {
                task.trySetException(new IllegalStateException("not bond"));
            }

            @Override public void onRemoteError(Exception error) {
                task.trySetException(error);
            }
        };
    }

    protected interface RemoteCall {
        void run(com.xiaomi.xms.wearable.IWearableInterface service)
                throws android.os.RemoteException;
    }
}
