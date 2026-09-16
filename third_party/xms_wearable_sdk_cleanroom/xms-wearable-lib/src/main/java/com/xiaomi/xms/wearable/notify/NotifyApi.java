package com.xiaomi.xms.wearable.notify;

import android.content.Context;

import com.xiaomi.xms.wearable.Status;
import com.xiaomi.xms.wearable.tasks.Task;

import org.zxor.oronbox.xms.internal.ApiSupport;
import org.zxor.oronbox.xms.internal.TaskImpl;

public class NotifyApi extends ApiSupport {
    public NotifyApi(Context context) { super(context); }

    public Task<Status> sendNotify(String nodeId, String title, String message) {
        TaskImpl<Status> result = task();
        NotificationData data = new NotificationData();
        data.setTitle(title);
        data.setMessage(message);
        client.execute(false, operation(service -> service.sendNotify(nodeId, data,
                new INotifyCallback.Stub() {
                    @Override public void onResult(Status status) {
                        if (status != null && status.isSuccess()) result.trySetResult(status);
                        else result.trySetException(statusError(status, "send notify failed"));
                    }
                }), result));
        return result;
    }
}
