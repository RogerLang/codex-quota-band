package com.xiaomi.xms.wearable;

import android.content.Context;

import com.xiaomi.xms.wearable.auth.AuthApi;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.node.NodeApi;
import com.xiaomi.xms.wearable.notify.NotifyApi;
import com.xiaomi.xms.wearable.service.ServiceApi;

public class Wearable {
    public Wearable() {}

    public static ServiceApi getServiceApi(Context context) { return new ServiceApi(context); }
    public static AuthApi getAuthApi(Context context) { return new AuthApi(context); }
    public static NodeApi getNodeApi(Context context) { return new NodeApi(context); }
    public static MessageApi getMessageApi(Context context) { return new MessageApi(context); }
    public static NotifyApi getNotifyApi(Context context) { return new NotifyApi(context); }
}
