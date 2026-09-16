package com.xiaomi.xms.wearable.node;

public interface OnDataChangedListener {
    void onDataChanged(String nodeId, DataItem dataItem, DataSubscribeResult data);
}
