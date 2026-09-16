# Stage 02 Band 9 Pro 真机失败讨论材料

> 历史诊断记录：本文只描述早期失败。随后 Stage 02D 真机探针已达到
> `PASS_WITH_NOTIFY_API_WARNING`，基础通信、通知到达及振动均通过；
> 最新结论见 [`current-status.md`](current-status.md) 和
> [`stage_02_xms_probe_review.md`](stage_02_xms_probe_review.md)。

日期：2026-09-16。目标设备：Xiaomi Smart Band 9 Pro。仓库基线 HEAD：`cafbb9679f4a7545312e16c9d8d3392ffe03900b`。

## 最新结果：无状态改动后直接重试

用户确认两次测试之间没有更改手机或手环的连接、应用安装、权限状态，只是直接重试。第二份相同报告在同一条用户消息中贴了两遍；按一次新结果记录，不推断发生了两次独立测试。

```text
CodexQuota Stage 02
Service: PASS
Node: NODE_FOUND
Probe: FAIL
DeviceManager: WAITING
NotifyPermission: WAITING
PhoneToBand: WAITING
BandToPhone: WAITING
Notification: WAITING
Error code: WEAR_APP_CHECK_FAILED
Overall: FAIL
```

**当前结论：**第二次 `getConnectedNodes()` 已成功返回名称匹配 Band 9 Pro 的 node，因此第一次的 `XMS_SERVICE_UNAVAILABLE` 不能再当作 service 恒久不可用或设备不兼容的证据。失败现在位于 `isWearAppInstalled(node.id)`：它抛出了异常，而非正常返回 `false`，所以 `WEAR_APP_CHECK_FAILED` 不等于“RPK 未安装”。Probe 已能在手环打开。权限、双向消息与通知仍未执行，对它们没有结论。

源码中的 [Stage02Runner.kt](../android-app/app/src/validation/java/com/codex/quota/android/validation/Stage02Runner.kt) 把该调用的任意异常合并成 `WEAR_APP_CHECK_FAILED`，还包括本地 8 秒超时。[NodeApi.java](../third_party/xms_wearable_sdk_cleanroom/xms-wearable-lib/src/main/java/com/xiaomi/xms/wearable/node/NodeApi.java) 明确区分 `onWearAppInstalled(boolean)` 正常结果与 `onFailure(Status)`；后者经 [ExceptionUtil.java](../third_party/xms_wearable_sdk_cleanroom/xms-wearable-lib/src/main/java/com/xiaomi/xms/wearable/exception/ExceptionUtil.java) 可转为断连、权限拒绝、包未安装或签名校验失败异常，其他状态可能成为一般异常。当前报告无法再细分。

| 待区分的假设 | 目前证据 | 下一步最小证据 |
| --- | --- | --- |
| 连接或 XMS 调用短暂失效 | 未改变状态，直接重试从 Service FAIL 进到 Node FOUND | validation APK 分别标记超时、断连、其他 API 错误；仅做一次有界重试 |
| 安装查询被权限挡住 | 安装查询排在 `DEVICE_MANAGER` / `NOTIFY` 检查与授权之前 | 区分 `PermissionDeniedException`；若命中，再调整 validation 流程授权顺序并重试 |
| 包或签名身份未被设备侧接受 | APK/RPK 本地产物证书一致，但设备侧尚未验证 | 区分 `AppNotInstalledException`、`SignatureVerifyFailedException`；交叉检查手环 `interconnect.diagnosis()` 固定状态 |
| 安装查询的其他状态或兼容问题 | 当前一律归成同一个错误码 | 区分已知 SDK 异常、8 秒超时和其他异常，不输出异常文本、node ID 或完整日志 |

建议先制作 **validation-only** 诊断 APK，增加固定脱敏错误码：`WEAR_APP_TIMEOUT`、`WEAR_APP_DISCONNECTED`、`WEAR_APP_PERMISSION_DENIED`、`WEAR_APP_PACKAGE_NOT_INSTALLED`、`WEAR_APP_SIGNATURE_FAILED`、`WEAR_APP_OTHER_FAILURE`。service/node 调用也应区分超时与其他错误。拿到具体类别后再决定是否调整授权顺序、复核设备侧签名，或调查时序；目前不应把重新安装 RPK、更换签名或修改 CleanRoom 当成既定修复。正式 runtime、协议与日常连接方式暂不动。

向 ChatGPT 讨论时可问：`isWearAppInstalled()` 的失败路径分别代表什么？查询安装状态是否需要先授予 `DEVICE_MANAGER`？Band 9 Pro Probe 能打开与 `interconnect.diagnosis()` 的状态分别能证明什么？首次失败后无状态改动直接重试成功找到 node，应如何验证 XMS 绑定或连接时序？

## 第一次结果与当时分析（历史记录）

以下记录保留第一次测试的现场。其“没有交付可用 node”结论只适用于**第一次**，已被上面的第二次结果推进；不要将其当成当前总体结论。

## 讨论目标

确定手机 validation APK 在调用 CleanRoom XMS `getConnectedNodes()` 时失败的真实层级，并决定下一版无 USB、无 ADB 的最小诊断方案。当前证据**不足以认定小米运动健康的 XMS service 不存在，也不足以认定 Band 9 Pro 不支持通信**。

## 用户真机事实

- 小米运动健康界面显示 Band 9 Pro **已连接**。
- Stage 02 Probe RPK 已通过 AstroBox 安装到手环，且能在手环上打开。
- 手机 validation APK 的脱敏报告如下；测试在第一项停止，权限、消息和通知均未执行：

```text
CodexQuota Stage 02
Service: FAIL
Node: WAITING
Probe: WAITING
DeviceManager: WAITING
NotifyPermission: WAITING
PhoneToBand: WAITING
BandToPhone: WAITING
Notification: WAITING
Error code: XMS_SERVICE_UNAVAILABLE
Overall: FAIL
```

- 当前 APK/RPK 构建时 package identity 均为 `io.github.rogerlang.codexquota.validation`，公开证书 SHA-256 相同，构建侧结果为 `APK/RPK signing identity: MATCH`。这只证明**本地产物**匹配，尚未证明手环和小米运动健康在真机上接受了互联身份。
- 本轮未使用 USB、ADB 或 Android Studio 真机调试；没有系统日志、XMS Binder 返回码或 node 列表。报告未包含 node ID、MAC、Android ID、令牌或用户数据。

## 源码证据与当前误判

1. [Stage02Runner.kt](../android-app/app/src/validation/java/com/codex/quota/android/validation/Stage02Runner.kt) 在 `await(nodeApi.connectedNodes)` 抛出**任意**异常时直接报告 `XMS_SERVICE_UNAVAILABLE`；没有记录异常类别，也没有先检查服务是否可见。因此当前错误码语义过宽。
2. [WearableClient.java](../third_party/xms_wearable_sdk_cleanroom/xms-wearable-lib/src/main/java/org/zxor/oronbox/xms/internal/WearableClient.java) 的 Xiaomi backend 仅尝试 `com.mi.health`、`com.xiaomi.wearable`，使用 action `com.xiaomi.wearable.XMS_WEARABLE_SERVICE`。它在 Binder 绑定后先调用 `getConnectedNodes()` 做 readiness probe；**空 node 列表会被当作候选服务失败**，然后尝试下一个候选。
3. 两个候选都未通过 readiness probe 时，排队的 API 调用可能收到 [ApiSupport.java](../third_party/xms_wearable_sdk_cleanroom/xms-wearable-lib/src/main/java/org/zxor/oronbox/xms/internal/ApiSupport.java) 的 `IllegalStateException("not bond")`。它既可能来自未找到/未绑定服务，也可能来自服务返回空 node；当前 APK 将两者都改写成同一错误码。
4. 小米运动健康显示“已连接”证明其用户界面中的手环连接状态，但不能单独证明第三方 XMS Binder 服务对该 APK 可见、可绑定或返回相同 node。Probe RPK 能打开也不等于 XMS 消息链路已建立。

**目前最准确的结论：CleanRoom 的 Xiaomi backend 没有向 validation APK 交付可用的 connected node；根因仍待定位。**

## 值得区分的原因

| 可能层级 | 现有证据 | 下一步需要的非敏感证据 |
| --- | --- | --- |
| 小米运动健康包或 XMS service 不可见、未导出、绑定被拒绝 | 尚无直接证据 | APK 本机只读检查目标 package/service 的可见性与绑定结果，不输出包外数据 |
| service 可绑定，但 XMS `getConnectedNodes()` 返回空列表或失败状态 | CleanRoom readiness 逻辑允许此情况被归为当前错误 | validation-only 诊断分别记录“绑定成功”“node 为空”“node 查询失败”的固定代码 |
| CleanRoom 兼容性或时序问题 | 尚未验证；不能仅凭本次失败认定 | 在保持 Xiaomi backend 的前提下做一次受限重试和明确超时，比较两次固定状态码 |
| package/signature 在设备侧未被接受 | 本地产物证书匹配，但设备侧未验证 | 手环 `system.interconnect.diagnosis()` 的固定状态码；不记录设备标识 |

小米官方 [interconnect 文档](https://iot.mi.com/vela/quickapp/en/features/network/interconnect.html) 明确要求手机应用与快应用的 package 和签名一致，并提供 `diagnosis()` 状态用于检查手环端互联。此文档支持把“手环端互联状态”作为后续独立诊断项；它**不能**解释本次 Android 侧 `getConnectedNodes()` 的具体异常。

## 建议讨论的最小下一步

1. **先修正 validation APK 的诊断，不修改正式 runtime 或 vendored CleanRoom。** 在手机端增加固定状态码：目标小米 package 可见性、XMS service 可解析性、绑定/探测结果、node 查询结果。报告仍只输出枚举状态，不输出 node ID、包内日志或异常堆栈。
2. 对 `getConnectedNodes()` 的异常至少保留受控类别：超时、`not bond`、权限/签名异常、其他 API 异常。`not bond` 应标记为“XMS 绑定或 node readiness 未通过”，不再直接断言 service 不存在。
3. 若 APK 只读检查仍无法区分“服务绑定成功但 node 为空”，再考虑 validation-only 的直接 Binder 诊断。它仅用于定位原因；正式通信仍走 CleanRoom 与 Xiaomi backend。
4. 可在 probe RPK 增加手环侧 `interconnect.diagnosis()` 固定状态显示，与 Android 侧结果交叉验证。此项需要重建和重新安装 RPK，应在讨论诊断方案后进行。
5. **不要仅为绕过本次失败就更改 CleanRoom 的空 node readiness 规则。** 先确认服务和 node 的真实返回，再决定是否需要单独审阅 SDK 兼容修复。

## 讨论时可直接提的问题

- 在小米运动健康显示已连接、RPK 可打开的条件下，如何用不依赖 ADB 的最小 APK/RPK 诊断，严格区分“服务不可见/绑定失败”“服务可用但 node 为空”“node API 返回错误”？
- CleanRoom 将空 node 视作候选服务失败的设计，在强制 Xiaomi backend 的验证场景中是否会遮蔽 `NO_CONNECTED_NODE`？若要调整，应如何限定在 validation-only，避免影响正式 runtime？
- 手环侧 `interconnect.diagnosis()` 的结果与 Android XMS `getConnectedNodes()` 各自能证明什么，哪些结论仍需真实消息往返？

## 状态与边界

Stage 02 **未通过真机验收**，也未证实 Band 9 Pro 不兼容。现有 APK/RPK 和源码改动仍保留在本地；本报告仅记录调查，不修改协议、不更换后端、不提交、不推送、不创建 PR 或 Release。
