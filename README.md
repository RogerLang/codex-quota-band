<p align="right"><a href="README_EN.md">English (upstream historical document)</a></p>

# Xiaomi Smart Band 9 Pro Codex额度

CodexQuota 是一个正在适配 **Xiaomi Smart Band 9 Pro** 的非官方开源项目，用于在 Windows、
Android 和手环侧查看 Codex 5 小时额度、周额度、重置时间和只读任务状态。

> Band 9 Pro 当前是目标设备 / 适配中。`main` 保留 Stage 02D 已验证安全基线；
> Stage 03A–03G 为实验历史。Stage 03G 真机出现严重 UI freeze，现为
> **QUARANTINED / DO_NOT_REDEPLOY**。真实设备表盘测试暂停，等待设备恢复状态确认。
> [事故记录](docs/incidents/2026-09-17-stage03g-watchface-freeze.md) ·
> [表盘开发安全规则](docs/safety/watchface-development-safety.md)
> 上游曾完成小米手环 10 的 RPK 与三端联动验证；这是 fork 起点的历史事实，不代表本 fork
> 已完成 Band 9 Pro 验收。

未发布的 `0.6.5` Foundation + Stage 02D 通信验证保留在 `main` 作为安全基线。Stage 02D 独立 validation APK
与 Probe RPK 验证了基础 XMS 通信、双向消息以及通知到达和振动；`NotifyApi` Task 的 8 秒回执超时
保留为观察项。Lua 真表盘实验已推进至 Stage 03G，但尚无获验收的正式产品表盘；AOD、
正式 Vela → Lua IPC 和产品 336×480 UI 也尚未实现。恢复开发时先按 simulator-first 流程
和恢复路径审查推进。

## 架构

```text
ChatGPT Windows Hook / 官方额度接口
  → Windows CodexQuota
  → relay protocol v1（AES-256-GCM）
  → HTTPS POST https://ntfy.sh/<随机 topic>
  → Android CodexQuota（ntfy WebSocket / replay API）
  → RuntimeStateRepository
  → 小米运动健康 + CleanRoom XMS SDK（强制 Xiaomi backend）
  → Xiaomi Smart Band 9 Pro（适配中）
```

- Windows 与 Android 不要求处于同一局域网，不需要固定 IP、端口转发、DDNS、VPN 或 Tailscale。
- Windows 不监听 LAN 端口，也不为同步创建 Windows 防火墙入站规则。
- 默认 relay 是 `https://ntfy.sh`；base URL 是配对凭据中的配置字段，未来可指向自建 ntfy。
- ntfy message body 只包含版本化的加密 envelope；title、tags、priority、filename 等明文字段不承载业务信息。
- ntfy 仍可观察随机 topic、源 IP、消息时间和密文长度，详见 [安全说明](docs/security.md)。

## 数据和状态边界

只允许同步：额度白名单、重置摘要、同步时间、连接状态、经过裁剪的任务状态和短标题。

不会同步提示词、回复、工具参数、命令、终端输出、文件路径、Cookie、密码、完整日志或 Codex
访问令牌。Windows 只在本机进程内使用 Codex 访问令牌向官方额度接口做低频确认。

任务状态保持原语义：

| Hook | 显示 | 提醒 |
| --- | --- | --- |
| `UserPromptSubmit` / `PreToolUse` | 处理中 | 静默 |
| `PermissionRequest` | 需要授权 | 按用户设置提醒 |
| `Stop` | 等待查看 | 按用户设置提醒 |

“等待查看”只表示本轮 Hook 已停止，不表示成功完成。

## 配对和刷新

Windows 首次启动或用户重新配对时生成 256-bit 随机 topic、256-bit AES key 和随机 device ID，
通过二维码交给 Android。Windows 使用当前用户 DPAPI 保存 relay secret；Android 使用 Android
Keystore + AES-GCM 保存。二维码不得进入日志或诊断。

旧 6 位局域网 discovery 配对代码暂留作 legacy 对照，但不属于正式 runtime，也没有被改造成不安全的
公网 6 位配对协议。Foundation 的主要配对方式是二维码。

Android 的“刷新”在 relay v1 中只会重连 relay、恢复最新缓存 state 并重新计算 freshness；它不会承诺
强制 Windows 立即请求 OpenAI。Windows 按自己的节拍独立确认官方额度。

## Xiaomi Wearable SDK

Android 不再依赖开发者私有的 `xms-wearable-lib_1.4_release.aar`。仓库 vendored：

- upstream: `OrPudding/XMS_Wearable_SDK_CleanRoom`
- pinned commit: `6483f939785e9c1dd011465d573931f669a6adab`
- license: MIT

来源记录见 [UPSTREAM.md](third_party/xms_wearable_sdk_cleanroom/UPSTREAM.md)。运行时在第一次
`Wearable.get*Api()` 前强制选择 `WearableBackend.XIAOMI`，继续绑定小米运动健康的官方 XMS service，
不使用 OronBox backend，也不要求 Notify for Xiaomi、Gadgetbridge、root 或 LSPosed。

## 从源码构建

Windows：

```powershell
Set-Location windows-native
cargo test --workspace
cargo build --release --bin codex_quota_windows
```

Android：

```powershell
Set-Location android-app
$env:JAVA_HOME = '<JDK 17 path>'
$env:ANDROID_HOME = '<Android SDK path>'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
..\spikes\android-background-probe\gradlew.bat -p . :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

Android runtime application ID 与 Vela package identity 均为 `io.github.rogerlang.codexquota`；Kotlin
源码 namespace 暂保留 `com.codex.quota.android`，避免 Foundation 夹带大规模包重命名。

## 当前限制

- Band 9 Pro 的 Stage 02D 独立通信探针已通过；正式 APK/RPK identity、额度/任务、三端联动、后台场景及产品表盘仍未验收。Stage 03G 已隔离，真实设备表盘测试暂停。
- Android 不使用常驻前台服务；进程被系统杀死后不承诺提醒必达。
- 公共 ntfy 是第三方 relay，虽然看不到业务明文，仍有上述 metadata 可见性和公共服务可用性限制。
- Foundation 01 已获用户验收；尚未创建 PR 或 Release，构建产物不得被描述为正式发布版本。

## 开发文档

- [文档总入口](docs/README.md)
- [当前状态](docs/current-status.md)
- [Band 9 Pro 表盘设计决策](docs/band_9_pro_watchface_design.md)
- [架构说明](docs/architecture.md)
- [开发指南](docs/development-guide.md)
- [安全说明](docs/security.md)
- [Stage 03G 事故记录](docs/incidents/2026-09-17-stage03g-watchface-freeze.md)
- [Band 9 Pro 表盘开发安全规则](docs/safety/watchface-development-safety.md)
- [贡献指南](CONTRIBUTING.md)

源码使用 [MIT License](LICENSE)。本项目不是 OpenAI、小米、ntfy、AstroBox 或 OrPudding 的官方产品。
