package com.xiaomi.xms.wearable.message;

import android.content.Context;

import com.xiaomi.xms.wearable.Status;
import com.xiaomi.xms.wearable.tasks.Task;

import org.zxor.oronbox.xms.internal.ApiSupport;
import org.zxor.oronbox.xms.internal.TaskImpl;

public class MessageApi extends ApiSupport {
    private static final Object LISTENER_LOCK = new Object();
    private static OnMessageReceivedListener registeredListener;

    public MessageApi(Context context) { super(context); }

    public Task<Void> sendMessage(String nodeId, byte[] message) {
        TaskImpl<Void> result = task();
        client.execute(false, operation(service -> service.sendMessage(nodeId, message,
                callback(result, null)), result));
        return result;
    }

    public Task<Void> addListener(String nodeId, OnMessageReceivedListener listener) {
        TaskImpl<Void> result = task();
        synchronized (LISTENER_LOCK) {
            if (registeredListener != null) {
                result.trySetException(new IllegalStateException("you have registered"));
                return result;
            }
        }
        IMessageListener remote = new IMessageListener.Stub() {
            @Override public void onMessageReceived(String id, byte[] bytes) {
                listener.onMessageReceived(id, bytes);
            }
        };
        client.execute(false, operation(service -> service.addMessageListener(nodeId, remote,
                callback(result, () -> {
                    synchronized (LISTENER_LOCK) { registeredListener = listener; }
                })), result));
        return result;
    }

    public Task<Void> removeListener(String nodeId) {
        TaskImpl<Void> result = task();
        synchronized (LISTENER_LOCK) {
            if (registeredListener == null) {
                result.trySetException(new IllegalStateException("you have not registered"));
                return result;
            }
        }
        client.execute(false, operation(service -> service.removeMessageListener(nodeId,
                callback(result, () -> {
                    synchronized (LISTENER_LOCK) { registeredListener = null; }
                })), result));
        return result;
    }

    private static IMessageCallback callback(TaskImpl<Void> result, Runnable onSuccess) {
        return new IMessageCallback.Stub() {
            @Override public void onMessageSent(Status status) {
                if (status != null && status.isSuccess()) {
                    if (onSuccess != null) onSuccess.run();
                    result.trySetResult(null);
                } else {
                    result.trySetException(statusError(status, "message operation failed"));
                }
            }
        };
    }
}
