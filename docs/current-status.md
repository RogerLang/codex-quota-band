# CodexQuota 当前状态

更新时间：2026-09-16。

## Fork Foundation 01

Foundation 01 **COMPLETE / accepted**；fork 后第一轮基础架构迁移已由用户验收。正式提交为
`7c505a882f3a20ed4973cc1b6f2d51c205a22466`，已推送至 `origin/main`。产品版本暂保持
`0.6.5`；没有创建 PR 或 GitHub Release，仍不是正式发布版本。

目标设备改为 **Xiaomi Smart Band 9 Pro**，状态只能写“目标设备 / 适配中”。Foundation 不实现
Lua 真表盘、AOD、Vela → Lua 文件 IPC 或 Band 9 Pro 336×480 UI，也没有完成 Band 9 Pro 真机验收。

## Stage 02D communication probe

**PASS_WITH_NOTIFY_API_WARNING（2026-09-16，用户真机确认）**。独立 validation APK 与 Probe RPK
在 Band 9 Pro 上验证了 CleanRoom SDK → Xiaomi backend → 小米运动健康 → 手环的基础通信可行性。
XMS service、node discovery、授权前后安装查询、`DEVICE_MANAGER`、`NOTIFY` 权限、Vela 互联诊断、
Android → Band 与 Band → Android 消息均通过；更新 Probe 后手环侧双向消息页面也显示 PASS。
用户确认测试通知实际到达手环并振动，故 `Notification: PASS`。

`NotificationApi: TIMEOUT` 仅表示 validation APK 的本地 8 秒 Task 等待没有得到完成结果，
不表示通知失败；回调的后续状态未知。现阶段将其保留为技术观察项，不单独追查、不自动重发，
也不改正式 `XiaomiWearableBridge` 或 vendored SDK。详细证据见
[`stage_02_xms_probe_review.md`](stage_02_xms_probe_review.md)。

Stage 02D **不是正式产品包验收**。正式 `io.github.rogerlang.codexquota` APK/RPK identity、
额度/任务数据、Windows → ntfy → Android → Band 端到端链路、后台/锁屏/网络切换、真表盘、
Vela → Lua IPC 和 AOD 尚未验证或实现。下一主线是正式 Band 9 Pro 适配；用户已决定最终主 UI
是真表盘，首版以 weekly quota 为核心，具体约束见
[`band_9_pro_watchface_design.md`](band_9_pro_watchface_design.md)。Band 9 Pro 仍是“目标设备 / 适配中”。

上游 `0.6.4` / `0.6.5` 曾对小米手环 10 完成或积累三端验证，这是 fork 起点的历史事实；它不能
作为当前 fork 对 Band 9 Pro 的支持结论。

## 已迁移基础

| 范围 | 当前 Foundation 状态 |
| --- | --- |
| Android XMS SDK | proprietary AAR 构建依赖已移除；源码集成 CleanRoom commit `6483f939785e9c1dd011465d573931f669a6adab` |
| XMS backend | 在任何 `Wearable.get*Api()` 前强制 `WearableBackend.XIAOMI`，继续使用小米运动健康官方 service |
| Windows transport | 正式入口使用 relay host，不启动 LAN WSS listener 或 UDP discovery |
| Relay | 默认 `https://ntfy.sh`，Windows HTTPS POST，Android OkHttp WebSocket + replay cursor |
| 加密 | relay protocol v1，AES-256-GCM、每消息随机 96-bit nonce、128-bit tag、magic + topic AAD |
| 配对 | 二维码携带 HTTPS base URL、随机 topic、AES key、device ID 和 protocol version |
| 凭据 | Windows DPAPI；Android Keystore + AES-GCM；不得进入日志和诊断 |
| 防重放 | Windows sequence 持久递增；Android 持久化最后接受 sequence，拒绝重复和倒退 |
| package identity | Android application ID 与 Vela identity 改为 `io.github.rogerlang.codexquota`；Kotlin namespace 暂不重命名 |

旧 `host.rs`、`network.rs`、UDP discovery、WSS 协议与对应测试暂留作 legacy 回滚和对照，不属于正式
runtime。旧 6 位配对没有被映射成公网协议；二维码是 Foundation 正式配对路径。

## 保持不变的产品边界

- 只同步额度、重置、连接/新鲜度和裁剪后的任务状态/短标题。
- 不同步提示词、回复、工具参数、命令、文件路径、完整日志、Cookie、密码或 Codex token。
- `Stop` 仍显示“等待查看”，不表示“成功完成”。
- `PermissionRequest` 与“等待查看”继续按通知设置产生手机/手环提醒；处理中静默。
- 小米运动健康继续是手环主连接和健康同步应用；不使用 OronBox backend、Notify for Xiaomi、
  Gadgetbridge、root 或 LSPosed。
- Android 不新增常驻前台服务，也不承诺系统杀进程后的提醒必达。

## 已完成验证

- Windows `cargo test --workspace` 全部通过，包含 relay protocol/runtime 与既有 legacy 回归测试。
- Android `:app:testDebugUnitTest :app:lintDebug :app:lintValidation :app:assembleDebug
  :app:assembleValidation` 全部通过；129 个 JVM 单测通过，Debug 和独立 Validation APK 均已组装。
- 原版 Foundation 01V 独立 Android 验证 APK 曾由用户确认 7/7 PASS。Foundation 01R 将无 cursor 的
  latest 恢复与持久 cursor 恢复拆成两项；用户已在 Android 真机确认最终版 **8/8 PASS**。测试仅使用
  synthetic data，由验证 App 发往随机 ntfy topic，再由 Android 正式 subscriber 接收，不等于正式
  Windows → Android 联动验收。
- 根目录 Node 契约/历史回归测试 44/44 通过；保留的 Band 10 legacy RPK 工程构建成功，36/36
  built tests 通过。该构建只验证 package identity 与既有工程未损坏，不代表 Band 9 Pro 已适配。

standalone APK 的 8 项按 `ValidationItem` 定义记录：

| ValidationItem | 页面项目 | Android 真机结果 |
| --- | --- | --- |
| `Keystore` | 安全密钥存储 | PASS |
| `Relay` | ntfy 实时接收 | PASS |
| `Encryption` | 加密解密 | PASS |
| `LatestRecovery` | 无 cursor 最新缓存恢复 | PASS |
| `CursorRecovery` | 持久 cursor 断线恢复 | PASS |
| `Tamper` | 篡改拒绝 | PASS |
| `Replay` | 重复消息拒绝 | PASS |
| `Rollback` | 旧消息拒绝 | PASS |

## 仍需真机或外部服务验证

- Windows 公共 ntfy 随机虚构数据 smoke test 已通过；429/5xx/断网退避已由实现与 mock failure
  路径覆盖，但未对公共服务故障注入。
- 正式 Windows → Android 联动，以及正式 App 在网络切换、后台/锁屏情况下的重连和持久 cursor
  行为；standalone APK 的 8/8 PASS 不覆盖这些正式双端场景。
- 正式 Android runtime → 小米运动健康 → Band 9 Pro 的真实额度/任务同步、提醒、后台与重连仍需
  真机验收。正式 Band 9 Pro RPK、Vela/Lua 真表盘、AOD 和产品 UI 尚未实现。

在以上真机工作完成前，不得写“Band 9 Pro 已支持”。
