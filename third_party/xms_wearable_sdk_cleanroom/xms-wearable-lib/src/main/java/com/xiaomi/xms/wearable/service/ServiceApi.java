package com.xiaomi.xms.wearable.service;

import android.content.Context;

import com.xiaomi.xms.wearable.tasks.Task;

import org.zxor.oronbox.xms.internal.ApiSupport;
import org.zxor.oronbox.xms.internal.TaskImpl;

public class ServiceApi extends ApiSupport {
    public ServiceApi(Context context) { super(context); }

    public Task<Integer> getServiceApiLevel() {
        TaskImpl<Integer> result = task();
        client.execute(true, operation(service -> result.trySetResult(service.getServiceApiLevel()), result));
        return result;
    }

    public void registerServiceConnectionListener(OnServiceConnectionListener listener) {
        client.addConnectionListener(listener);
    }

    public void unregisterServiceConnectionListener(OnServiceConnectionListener listener) {
        client.removeConnectionListener(listener);
    }
}
