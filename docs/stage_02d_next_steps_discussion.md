# CodexQuota Stage 02D 结果与下一阶段讨论材料

> 本文保留 Stage 02D 结束时的讨论快照。用户随后已决定下一主线是正式 Band 9 Pro 适配，
> `NotifyApi` 8 秒回执超时暂列观察项；真表盘方向已写入
> [`band_9_pro_watchface_design.md`](band_9_pro_watchface_design.md)。当前状态以
> [`current-status.md`](current-status.md) 为准。

日期：2026-09-16。仓库：`D:\github_repo\codex-quota-band`。讨论时 `HEAD` 与本地 `origin/main` 均为 `cafbb9679f4a7545312e16c9d8d3392ffe03900b`；当时 Stage 02/02D 的工作仍在未提交的 working tree 中。本文件供与 ChatGPT 讨论下一步范围，**不是发布或正式验收声明**。

## 一句话现状

Xiaomi Smart Band 9 Pro 上，独立的 Stage 02D validation APK + Probe RPK 已真机验证 XMS node、两项权限、授权前后安装查询、手环互联诊断、双向消息、通知到达及振动。唯一的诊断警告是 Android validation APK 等待 `NotifyApi` Task 8 秒未完成；手环实际收到了通知并振动。正式产品包的身份、额度/任务链路和后台场景还没有验收。

## 已确认的真机事实

- 小米运动健康显示 Band 9 Pro 已连接；Probe RPK 经 AstroBox 安装并可打开。AstroBox 仅用于安装，不参与日常消息链路。
- 手环 Probe 的 `Interconnect` 显示 `OK`，更新 Probe 后“测试手环→手机”也显示 `PASS`。
- 手机脱敏报告：

```text
CodexQuota Stage 02D
Service: PASS
ServiceCode: XMS_SERVICE_OK
Node: NODE_FOUND
NodeAttempt: 1
InstallBefore: TRUE
DeviceManager: PASS
NotifyPermission: PASS
InstallAfter: TRUE
BandDiagnosis: OK
PhoneToBand: PASS
BandToPhone: PASS
NotificationApi: TIMEOUT
Notification: PASS
Overall: PASS_WITH_NOTIFY_API_WARNING
```

- `Notification: PASS` 是用户按手环实际表现确认：通知到达且发生振动。没有 USB、ADB、logcat 或系统内部日志；报告不含设备标识、账号、密钥或真实 Codex 内容。
- 早期 `XMS_SERVICE_UNAVAILABLE` 和 `WEAR_APP_CHECK_FAILED` 曾出现过；随后无状态改动的重试找到 node，本轮授权前后安装查询均为 `TRUE`。不能把早期宽泛错误继续解释成 service 永久不可用或 RPK 未安装。

## `NotificationApi: TIMEOUT` 的准确含义

[Stage02DRunner.kt](../android-app/app/src/validation/java/com/codex/quota/android/validation/Stage02DRunner.kt) 的 Task 等待使用本地 `withTimeout(8000)`。[NotifyApi.java](../third_party/xms_wearable_sdk_cleanroom/xms-wearable-lib/src/main/java/com/xiaomi/xms/wearable/notify/NotifyApi.java) 只有在 Binder 回调报告成功时才完成 Task；非成功状态经异常路径返回。此次 `TIMEOUT` 是 validation 层等满 8 秒，没有拿到 Task 完成结果。**它不表示通知未送达，也不能证明回调最终会或不会到达。**

[正式 XiaomiWearableBridge.kt](../android-app/app/src/main/java/com/codex/quota/android/runtime/XiaomiWearableBridge.kt) 当前调用 `sendNotify()` 后即返回“已请求”，不等待 Task，因此不会产生同一个 validation 8 秒等待结果。这也意味着现有 `sendTaskAlert()` 返回值只表示请求已发起，**不能单独当作手环实际收到的证据**。正式任务提醒的真实效果还未测。

目前没有证据证明延长 8 秒阈值可得到成功回执，也不应为了消除警告自动重发通知，避免重复提醒。若后续要定位回执时序，可只在 validation 层对**同一次** synthetic 通知被动观察 Task 在 8 秒后是否完成，记录固定脱敏状态，不改 CleanRoom 或正式 runtime。

## 已验证与未验证的边界

| 项目 | 当前证据 |
| --- | --- |
| XMS Xiaomi backend → 小米运动健康 → Band 9 Pro 的基础通信可行性 | Stage 02D validation 真机通过 |
| validation APK / Probe RPK 的包名与签名 | 两端均为 `io.github.rogerlang.codexquota.validation`，证书匹配，并获真机连接结果 |
| 正式 Android APK / 正式 Band 9 Pro RPK 的包名、签名和互联 | 未验证；正式 identity 为 `io.github.rogerlang.codexquota`，不能从 validation identity 推断 |
| 手环通知与振动 | synthetic Stage 02D 通知已实际到达并振动；API Task 回执超过本地 8 秒等待 |
| 正式 Windows Hook → 加密 ntfy → Android → 手环额度/任务与提醒 | 尚未完成三端端到端真机验收 |
| 后台、锁屏、断线重连、网络切换及缓存语义 | Stage 02D Probe 未覆盖，正式候选仍需验收 |
| Band 9 Pro 正式额度/任务 UI、watchface、AOD、Vela→Lua IPC | 未实现；Probe 页面不能作为产品 UI 验收 |

当前可把 **Stage 02D 通信探针**记录为 `PASS_WITH_NOTIFY_API_WARNING`；仍须等待用户对正式阶段明确回复“验收通过”，才能提交、推送或发布。Band 9 Pro 在产品文案中继续标为“目标设备 / 适配中”。

## 本地产物与自动验证

| 产物 | 绝对路径 | 大小 | SHA-256 |
| --- | --- | ---: | --- |
| 最终 validation APK | `D:\github_repo\codex-quota-band\out\stage02\CodexQuota-Stage02D-notify-feedback-validation.apk` | 46,405,156 bytes | `92846A648322756AA03DDA5B90E462F5D252249E1DECF808B88A40210A72E984` |
| 最终 Probe RPK | `D:\github_repo\codex-quota-band\out\stage02\CodexQuota-Stage02D-watch-feedback-probe.rpk` | 38,600 bytes | `7696C5A61DEB9C3E90766E476F70C6849C7DEA3872476646CB79331D742E818D` |

APK/RPK validation 签名证书 SHA-256 相同：`fd6239d22597887c5e64c669ecbcb93fe1127d9a8718fe0e7de179be4d2760e9`。讨论时的验证：Android validation JVM tests **142/142**、Lint、APK 构建；正式 debug JVM tests **129/129**、Lint、构建；Probe 测试 **5/5**、RPK 成品测试 **1/1**；根目录测试 **47/47**。`git diff --check` 通过。讨论时源码、文档和产物均保留本地，未提交、推送、创建 PR 或 Release。

## 下一步可讨论的两条路径

### A. 先追通知回执时序

做一个范围极小的 validation-only 诊断：仅发送一次 synthetic 通知，8 秒后继续被动观察同一个 Task 是否在较短的额外窗口完成，并只报告 `SUCCESS_AFTER_8S`、`NO_CALLBACK_BY_LIMIT` 或固定异常类别。好处是定位 callback 延迟还是缺失；代价是增加一次安装和真机试测，即使得到答案，也不能直接证明正式产品链路。不要改正式 `XiaomiWearableBridge`、vendored SDK 或自动重发策略。

### B. 进入正式 Band 9 Pro 适配与端到端验证（建议主线）

1. 先定义最小正式验收范围与协议：额度摘要、裁剪的任务短标题/状态、连接与同步时间、提醒；保持现有隐私边界。
2. 按项目 `AGENTS.md`，**先给 Band 9 Pro 真实规格的 UI 预览并由用户确认**，随后才实现正式 RPK 页面。Stage 02D 的 336×480 Probe 仅是通信测试页面，不替代产品 UI。
3. 核对正式 APK/RPK 的 `io.github.rogerlang.codexquota` identity、固定签名、版本与协议兼容；继续使用 Xiaomi backend 和小米运动健康。AstroBox 只用于安装/升级 RPK。
4. 先用 synthetic 数据做正式 Android → Band 的单次额度/任务与提醒 smoke test，再验证 Windows Hook → 加密 ntfy → Android → Band 的真实三端链路；不要将真实提示词、命令、路径或完整日志送入 relay 或手环。
5. 用短时、可重复场景检查失焦通知、锁屏/后台、断线重连、网络切换、离线缓存，以及手机/手环开关和降级。逐项记录实际结果；用户明确验收通过后才进入 Git/发布流程。

建议把 A 作为**回执诊断待办**，在正式提醒出现用户可见失败、重复通知或状态误报时优先处理；B 是当前最有价值的主线。这个建议是根据现有证据作出的工程判断，尚待用户决定下一阶段范围。

## 请 ChatGPT 重点回答

1. Stage 02D `PASS_WITH_NOTIFY_API_WARNING` 是否足以作为“基础 XMS 通信可行”的阶段门槛？哪些结论不能由它推出？
2. `NotificationApi: TIMEOUT` 在已确认通知到达并振动、正式 runtime 不等待 Task 的条件下，应现在单独调查，还是作为正式任务提醒验收的观察项？如调查，最小无重发方案是什么？
3. 正式 Band 9 Pro RPK 与 Android 桥接的**最小阶段范围和验收顺序**是什么？请先指出需要用户确认的 UI 预览、正式 identity/签名和协议决策。
4. 如何安排 synthetic smoke test 与后续三端真机验收，既能定位故障层级，又避免扩大敏感数据收集和长时间设备测试？
5. 在未收到用户“验收通过”前，应保留哪些未提交工作和本地候选产物，哪些旧 Stage 02 诊断代码可延后清理？

相关事实文件：[当前状态](current-status.md)、[Stage 02 审阅记录](stage_02_xms_probe_review.md)、[产品决策](../CONTEXT.md)、[项目执行规则](../AGENTS.md)。
