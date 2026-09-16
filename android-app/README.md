# Codex额度 Android

Foundation 01 的正式链路为：

`ntfy WebSocket/replay → AES-256-GCM decrypt → RuntimeStateRepository → 小米运动健康 → Band 9 Pro（适配中）`

Android 不再依赖 proprietary `xms-wearable-lib_1.4_release.aar`。Gradle 直接构建仓库中的
`third_party/xms_wearable_sdk_cleanroom/xms-wearable-lib`，固定 upstream commit
`6483f939785e9c1dd011465d573931f669a6adab`。`XiaomiWearableBridge` 在创建 Node/Auth/Message/
Notify API 前强制 `WearableBackend.XIAOMI`，继续绑定小米运动健康官方 XMS service。

正式 Windows → Android transport 是 relay protocol v1：

- 二维码保存 HTTPS base URL、随机 256-bit topic、256-bit AES key 和 device ID；
- `PairingCredentialStore` 使用 Android Keystore AES-GCM 加密保存 relay secret；
- 首次无 cursor 订阅 `since=latest`，重连使用最后接受的 ntfy message ID；
- `open` / `keepalive` 不进入 domain；
- 解密后复用 quota v3 / task v1，持久化拒绝重复或倒退 sequence；
- “刷新”只重连 relay、获取缓存 state 并重新计算 freshness，不命令 Windows 请求 OpenAI。

旧 WSS、TLS pinning、UDP discovery 和 6 位配对类暂留为 legacy 对照，`CodexQuotaApplication`
不再启动它们。不要把 6 位配对扩展为公网协议。

运行时 application ID 是 `io.github.rogerlang.codexquota`，Kotlin namespace 暂保留
`com.codex.quota.android`。Vela package identity 必须与 application ID 一致。

本地验证：

```powershell
$env:JAVA_HOME = '<JDK 17 path>'
$env:ANDROID_HOME = '<Android SDK path>'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
..\spikes\android-background-probe\gradlew.bat -p . :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

Band 9 Pro 仍是目标设备 / 适配中，Foundation 不实现 Lua watchface、AOD、Vela → Lua IPC 或
336×480 UI，也不宣称真机支持。
