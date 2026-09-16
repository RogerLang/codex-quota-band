package com.codex.quota.android

import android.app.Application
import android.net.Uri
import com.codex.quota.android.notifications.NotificationChannels
import com.codex.quota.android.notifications.TaskAlertCoordinator
import com.codex.quota.android.notifications.TaskNotificationDispatcher
import com.codex.quota.android.protocol.PairingOffer
import com.codex.quota.android.protocol.RelayPairingDeepLinkContract
import com.codex.quota.android.runtime.BandConnectionCheckResult
import com.codex.quota.android.runtime.RuntimeStateRepository
import com.codex.quota.android.runtime.SharedPreferencesTaskVisibilityStore
import com.codex.quota.android.runtime.RelayWebSocketClient
import com.codex.quota.android.runtime.XiaomiWearableBridge
import com.codex.quota.android.security.PairingCredentialStore
import com.codex.quota.android.ui.NotificationSettings
import com.codex.quota.android.ui.NotificationSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CodexQuotaApplication : Application() {
  val runtimeRepository by lazy {
    RuntimeStateRepository(taskVisibility = SharedPreferencesTaskVisibilityStore(this), relayMode = true)
  }
  private lateinit var wearableBridge: XiaomiWearableBridge

  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private lateinit var credentialStore: PairingCredentialStore
  private lateinit var syncClient: RelayWebSocketClient
  private lateinit var taskAlerts: TaskAlertCoordinator

  override fun onCreate() {
    super.onCreate()
    NotificationChannels.create(this)
    credentialStore = PairingCredentialStore(this)
    wearableBridge = XiaomiWearableBridge(this, runtimeRepository)
    val phoneDispatcher = TaskNotificationDispatcher(this)
    taskAlerts =
      TaskAlertCoordinator(
        phoneDispatcher = { phoneDispatcher.notify(it) },
        bandDispatcher = { wearableBridge.sendTaskAlert(it) },
      )
    taskAlerts.updateSettings(NotificationSettingsStore(this).load())
    syncClient = RelayWebSocketClient(applicationScope, runtimeRepository, credentialStore, taskAlerts)
    wearableBridge.start()
    startSavedConnection()
  }

  fun checkBandConnection(onResult: (BandConnectionCheckResult) -> Unit = {}) {
    wearableBridge.checkConnection(onResult)
  }

  fun updateNotificationSettings(settings: NotificationSettings) {
    taskAlerts.updateSettings(settings)
  }

  fun handlePairingLink(uri: Uri) {
    runCatching { RelayPairingDeepLinkContract.decode(uri) }
      .onSuccess { credentials ->
        credentialStore.saveRelay(credentials)
        syncClient.start(credentials)
      }
      .onFailure { runtimeRepository.markTransportDisconnected() }
  }

  /** The 6-digit LAN flow is retained only as legacy code and is not a public relay protocol. */
  fun handlePairingOffer(offer: PairingOffer) {
    @Suppress("UNUSED_VARIABLE") val legacyOffer = offer
    runtimeRepository.markTransportDisconnected()
  }

  fun refreshSync(): Boolean = syncClient.refresh()

  private fun startSavedConnection() {
    val credentials = credentialStore.loadRelay() ?: return
    syncClient.start(credentials)
  }
}
