package com.xiaomi.xms.wearable.auth;

import android.content.Context;

import com.xiaomi.xms.wearable.Status;
import com.xiaomi.xms.wearable.tasks.Task;

import org.zxor.oronbox.xms.internal.ApiSupport;
import org.zxor.oronbox.xms.internal.TaskImpl;

public class AuthApi extends ApiSupport {
    public AuthApi(Context context) { super(context); }

    public Task<Boolean> checkPermission(String nodeId, Permission permission) {
        TaskImpl<Boolean> result = task();
        client.execute(true, operation(service -> service.checkPermission(nodeId, permission,
                new IPermissionCheckCallback.Stub() {
                    @Override public void onPermissionGranted(boolean granted) {
                        result.trySetResult(granted);
                    }
                    @Override public void onFailure(Status status) {
                        result.trySetException(statusError(status, "check permission failed"));
                    }
                }), result));
        return result;
    }

    public Task<boolean[]> checkPermissions(String nodeId, Permission[] permissions) {
        TaskImpl<boolean[]> result = task();
        client.execute(true, operation(service -> service.checkPermissions(nodeId, permissions,
                new IPermissionsCheckCallback.Stub() {
                    @Override public void onPermissionGranted(boolean[] granted) {
                        result.trySetResult(granted);
                    }
                    @Override public void onFailure(Status status) {
                        result.trySetException(statusError(status, "check permissions failed"));
                    }
                }), result));
        return result;
    }

    public Task<Permission[]> requestPermission(String nodeId, Permission... permissions) {
        TaskImpl<Permission[]> result = task();
        client.execute(false, operation(service -> service.requestPermission(nodeId, permissions,
                new IPermissionCallback.Stub() {
                    @Override public void onPermissionGranted(Permission[] granted) {
                        if (granted == null || granted.length == 0) {
                            result.trySetException(new Exception("permission denied"));
                        } else {
                            result.trySetResult(granted);
                        }
                    }
                    @Override public void onFailure(Status status) {
                        result.trySetException(statusError(status, "request permission failed"));
                    }
                }), result));
        return result;
    }
}
