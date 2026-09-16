package com.xiaomi.xms.wearable.message;

public interface OnMessageReceivedListener {
    void onMessageReceived(String nodeId, byte[] message);
}
