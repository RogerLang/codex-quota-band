package com.xiaomi.xms.wearable.node;

import android.content.Context;
import android.os.Bundle;

import com.xiaomi.xms.wearable.Status;
import com.xiaomi.xms.wearable.tasks.Task;

import org.zxor.oronbox.xms.internal.ApiSupport;
import org.zxor.oronbox.xms.internal.TaskImpl;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NodeApi extends ApiSupport {
    private static final Map<Integer, OnDataChangedListener> LISTENERS = new ConcurrentHashMap<>();

    public NodeApi(Context context) { super(context); }

    public Task<List<Node>> getConnectedNodes() {
        TaskImpl<List<Node>> result = task();
        client.execute(false, operation(service -> service.getConnectedNodes(new INodeCallback.Stub() {
            @Override public void onNodesConnected(List<Node> nodes) { result.trySetResult(nodes); }
            @Override public void onFailure(Status status) {
                result.trySetException(statusError(status, "get connected nodes failed"));
            }
        }), result));
        return result;
    }

    public Task<Boolean> isWearAppInstalled(String nodeId) {
        TaskImpl<Boolean> result = task();
        client.execute(false, operation(service -> service.isWearAppInstalled(nodeId,
                new IWearAppInstalledCallback.Stub() {
                    @Override public void onWearAppInstalled(boolean installed) {
                        result.trySetResult(installed);
                    }
                    @Override public void onFailure(Status status) {
                        result.trySetException(statusError(status, "check wear app failed"));
                    }
                }), result));
        return result;
    }

    public Task<Void> launchWearApp(String nodeId, String uri) {
        TaskImpl<Void> result = task();
        client.execute(false, operation(service -> service.launchWearApp(nodeId, uri,
                new IWearAppLaunchedCallback.Stub() {
                    @Override public void onWearAppLaunched(Status status) {
                        completeStatus(result, status, "launch wear app failed");
                    }
                }), result));
        return result;
    }

    public Task<DataQueryResult> query(String nodeId, DataItem dataItem) {
        TaskImpl<DataQueryResult> result = task();
        client.execute(false, operation(service -> service.query(nodeId, dataItem,
                new IDataCallback.Stub() {
                    @Override public void onResult(DataItem item, Bundle values) {
                        result.trySetResult(toQueryResult(item, values));
                    }
                    @Override public void onFailure(Status status) {
                        if (isUnsupportedLiveStatus(dataItem, status)) {
                            result.trySetException(new UnsupportedOperationException(
                                    "sleep and wearing status are not supported by this backend"));
                        } else {
                            result.trySetException(statusError(status, "query failed"));
                        }
                    }
                }), result));
        return result;
    }

    public Task<Void> subscribe(String nodeId, DataItem dataItem, OnDataChangedListener listener) {
        TaskImpl<Void> result = task();
        IDataListener remote = new IDataListener.Stub() {
            @Override public void onDataChanged(String id, DataItem item, Bundle values) {
                OnDataChangedListener current = LISTENERS.get(item.getType());
                if (current != null) current.onDataChanged(id, item, toSubscribeResult(item, values));
            }
        };
        client.execute(false, operation(service -> {
            service.subscribe(nodeId, dataItem, remote);
            LISTENERS.put(dataItem.getType(), listener);
            result.trySetResult(null);
        }, result));
        return result;
    }

    public Task<Void> unsubscribe(String nodeId, DataItem dataItem) {
        TaskImpl<Void> result = task();
        LISTENERS.remove(dataItem.getType());
        client.execute(false, operation(service -> {
            service.unsubscribe(nodeId, dataItem);
            result.trySetResult(null);
        }, result));
        return result;
    }

    private static DataQueryResult toQueryResult(DataItem item, Bundle values) {
        DataQueryResult result = new DataQueryResult();
        if (item == null || values == null) return result;
        switch (item.getType()) {
            case 1: result.isConnected = values.getInt(DataItem.KEY_CONNECTION_STATUS) == 1; break;
            case 2: result.isCharging = values.getBoolean(DataItem.KEY_CHARGING_STATUS); break;
            case 3: result.isSleeping = values.getBoolean(DataItem.KEY_SLEEP_STATUS); break;
            case 4: result.isWearing = values.getBoolean(DataItem.KEY_WEARING_STATUS); break;
            case 5: result.battery = values.getInt(DataItem.KEY_BATTERY_STATUS); break;
            default: break;
        }
        return result;
    }

    private static DataSubscribeResult toSubscribeResult(DataItem item, Bundle values) {
        DataSubscribeResult result = new DataSubscribeResult();
        if (item == null || values == null) return result;
        switch (item.getType()) {
            case 1: result.setConnectedStatus(values.getInt(DataItem.KEY_CONNECTION_STATUS)); break;
            case 2: result.setChargingStatus(values.getInt(DataItem.KEY_CHARGING_STATUS)); break;
            case 3: result.setSleepStatus(values.getInt(DataItem.KEY_SLEEP_STATUS)); break;
            case 4: result.setWearingStatus(values.getInt(DataItem.KEY_WEARING_STATUS)); break;
            default: break;
        }
        return result;
    }

    private static void completeStatus(TaskImpl<Void> task, Status status, String message) {
        if (status != null && status.isSuccess()) task.trySetResult(null);
        else task.trySetException(statusError(status, message));
    }

    private static boolean isUnsupportedLiveStatus(DataItem item, Status status) {
        if (item == null || status == null ||
                status.getCode() != Status.RESULT_INTERRUPTED.getCode()) {
            return false;
        }
        return item.getType() == DataItem.ITEM_SLEEP.getType() ||
                item.getType() == DataItem.ITEM_WEARING.getType();
    }
}
