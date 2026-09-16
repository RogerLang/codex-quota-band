package com.xiaomi.xms.wearable.exception;

import com.xiaomi.xms.wearable.Status;

public final class ExceptionUtil {
    private ExceptionUtil() {}

    public static Exception convertStatusToException(Status status) {
        if (status == null) {
            return null;
        }
        int code = status.getCode();
        if (code == Status.RESULT_DISCONNECTED.getCode()) {
            return new DeviceDisconnectedException("device disconnected");
        }
        if (code == Status.RESULT_PERMISSION_DENIED.getCode()) {
            return new PermissionDeniedException("permission denied");
        }
        if (code == Status.RESULT_PACKAGE_NOT_INSTALLED.getCode()) {
            return new AppNotInstalledException("app not installed");
        }
        if (code == Status.RESULT_SIGNATURE_VERIFY_FAILED.getCode()) {
            return new SignatureVerifyFailedException("fingerprint verify failed");
        }
        return null;
    }
}
