package com.codex.quota.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import org.zxor.oronbox.xms.WearableBackend
import com.xiaomi.xms.wearable.auth.AuthApi
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.DataItem
import com.xiaomi.xms.wearable.node.NodeApi
import com.xiaomi.xms.wearable.node.OnDataChangedListener
import com.xiaomi.xms.wearable.notify.NotifyApi

class WearableBackendInitializerTest {
  @Test
  fun xiaomiBackendIsConfiguredBeforeAnyApiFactoryRuns() {
    val events = mutableListOf<String>()
    val initializer = WearableBackendInitializer { events += "backend" }
    initializer.initialize { events += "api" }
    assertEquals(listOf("backend", "api"), events)
    assertEquals(WearableBackend.XIAOMI, XiaomiWearableBackend.requiredBackend)
  }

  @Test
  fun cleanRoomExposesEveryApiUsedByTheBridge() {
    val apiTypes =
      listOf(
        NodeApi::class.java,
        AuthApi::class.java,
        MessageApi::class.java,
        NotifyApi::class.java,
        DataItem::class.java,
        OnMessageReceivedListener::class.java,
        OnDataChangedListener::class.java,
        Permission::class.java,
      )
    assertEquals(8, apiTypes.size)
  }
}
