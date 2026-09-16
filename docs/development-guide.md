# CodexQuota Foundation 开发与交付指南

本文适用于 fork 的 Foundation 01。先读根目录 `AGENTS.md`、`CONTEXT.md` 和
`docs/current-status.md`。本阶段不提交、不 push、不创建 PR/Release，必须保留 owner 可审阅的未提交 diff。

## 开始前

```powershell
git status --short
git branch --show-current
git rev-parse HEAD
git remote -v
```

保留已有修改，不使用 `git reset --hard`、`git clean`、未经检查的 stash 或 `git add -A`。

## CleanRoom SDK

源码位于 `third_party/xms_wearable_sdk_cleanroom/`，固定 commit：

```text
6483f939785e9c1dd011465d573931f669a6adab
```

`android-app/settings.gradle.kts` 把 `xms-wearable-lib` 作为本地 Android library module 引入。
禁止恢复 `android-app/app/libs/xms-wearable-lib_1.4_release.aar`，也不要改成未经确认长期可用的第三方
Maven artifact。更新 upstream 必须单独审阅 API、Binder、LICENSE 和 pinned commit。

`XiaomiWearableBridge` 必须通过 `XiaomiWearableBackend.initializer` 先设置
`WearableBackend.XIAOMI`，再调用任何 `Wearable.get*Api()`。

## Windows 构建与测试

```powershell
Set-Location windows-native
cargo test --workspace
cargo build --release --bin codex_quota_windows
```

重点测试：

- relay AES-GCM round-trip、wrong key、篡改 tag、96-bit 随机 nonce；
- plaintext 白名单和 envelope 外层字段；
- sequence 持久化；
- 可替换/mock HTTP endpoint、失败隔离和有限重试；
- 正式入口使用 `RelayHost`，不启动 `WindowsHost`、LAN listener 或 UDP discovery。

正在运行已安装托盘程序时使用独立 `CARGO_TARGET_DIR`，避免旧 EXE 文件锁干扰。Foundation 不构建
安装包或创建 Release，除非 owner 另行要求。

## Android 构建与测试

```powershell
Set-Location android-app
$env:JAVA_HOME = '<JDK 17 path>'
$env:ANDROID_HOME = '<Android SDK path>'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
..\spikes\android-background-probe\gradlew.bat -p . :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

验收必须证明删除 proprietary AAR 后仍能完成 debug APK。重点测试：

- Android AES-GCM round-trip、wrong key/tag；
- strict envelope / payload parser；
- ntfy `message/open/keepalive` 分类；
- persistent sequence replay/rollback；
- 首次 `since=latest` 与 cursor replay；
- `PairingCredentialStore` 的 relay codec 和 Android Keystore instrumented round-trip；
- CleanRoom Node/Auth/Message/Notify/Data/Listener/Permission API 编译；
- Xiaomi backend 在 API factory 前完成配置。

真实网络测试只允许随机 topic/key 和虚构 quota/task。不得发送本机真实任务标题、token 或日志。

## Relay 开发约束

- 默认 base URL 是 `https://ntfy.sh`，配置必须是 HTTPS origin。
- ntfy message body 只能是 `version/nonce/ciphertext` envelope。
- 不使用 title、tags、priority、filename 或 actions 传业务信息。
- Android 首次无 cursor 使用 `since=latest`，重连用最后 message ID。
- Android 刷新只重连/取缓存/重算 freshness，不新增 Android → Windows command channel。
- relay 失败不得阻塞本地 quota collector 或 Hook spool 处理。
- 不创建 Windows 网络 listener、防火墙入站规则或局域网发现广播。

## Package identity

- Android runtime application ID：`io.github.rogerlang.codexquota`
- Vela package identity：`io.github.rogerlang.codexquota`
- Kotlin namespace/source package 暂保留 `com.codex.quota.android`

不要为包名迁移顺手全量移动 Kotlin 源码。

## 真机边界

本轮不修改/实现 Band 9 Pro Lua watchface、AOD、Vela → Lua IPC 或 336×480 UI。Band 9 Pro 未完成
真实安装、XMS 权限、消息、通知、后台/锁屏和小米运动健康共存验收前，只能写“目标设备 / 适配中”。

上游 Band 10 验收记录可以作为历史参考，不能替代本 fork 的 Band 9 Pro 验收。

## 交付检查

结束前生成 `docs/foundation_01_review.md`，至少包含：修改摘要、架构图、修改文件、CleanRoom 来源、
relay protocol v1、ntfy metadata boundary、测试命令/结果、未真机验证、下一阶段建议、
`git status --short`、`git diff --stat` 和明确的 no commit / no push。
