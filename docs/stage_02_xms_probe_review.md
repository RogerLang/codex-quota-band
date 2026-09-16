# Stage 02 XMS 通信探针审阅记录

> 本文按时间保留构建与真机诊断过程；中间章节的“未通过”“未提交”是当时状态。
> 最终真机结论见文末“更新 Probe 复测结论”：**Stage 02D communication probe 为
> `PASS_WITH_NOTIFY_API_WARNING`**，通知实际到达并振动，`NotifyApi` 回执超时保留为观察项。
> 正式产品链路仍未验收，当前摘要以 [`current-status.md`](current-status.md) 为准。

日期：2026-09-16。基线：`cafbb9679f4a7545312e16c9d8d3392ffe03900b`，开始时工作区干净且与 `origin/main` 一致。

## 目的与范围

只验证 Android validation APK → CleanRoom XMS SDK（强制 `WearableBackend.XIAOMI`）→ 小米运动健康 → Xiaomi Smart Band 9 Pro。独立 `band-probe/` 工程以 336×480 画布提供通信状态；不含 quota 页面、Lua、后台接收或正式 runtime 协议修改。

APK 引导检查 XMS service、目标 node、RPK 安装、`DEVICE_MANAGER` 和 `NOTIFY` 授权。用户在手环打开 Probe 后，四种 validation-only 消息完成带随机 nonce 的双向握手和最终回执，8 秒内未收到预期消息则失败。NotifyApi 只发送固定 synthetic 文案；API 成功后仍需用户确认通知及振动，文字到达但无振动记录 `NOTIFY_RECEIVED_NO_VIBRATION`。脱敏报告仅含固定项目状态和错误代码。

## 本地产物

| 文件 | 绝对路径 | 大小 | SHA-256 |
| --- | --- | ---: | --- |
| Android APK | `D:\github_repo\codex-quota-band\out\stage02\CodexQuota-Stage02-validation.apk` | 46,365,815 bytes | `6A9493ECFB10F8AD88DB5B848CFD96AC2FECEF58DC0A5289394809EDF3F451FA` |
| Probe RPK | `D:\github_repo\codex-quota-band\out\stage02\CodexQuota-Stage02-probe.rpk` | 37,897 bytes | `14ED9C4F21F0AF158FC38114B26D4C59B7D93B302506C0F18BBD9852B397764E` |

APK application ID 和 RPK package 均为 `io.github.rogerlang.codexquota.validation`。APK 版本为 `0.6.5-validation` / 607；RPK 为 `0.6.5` / 607。validation 包与正式 Android 包并存，不覆盖正式安装。

## 本地签名

`band-probe/scripts/prepare-validation-signing.ps1` 在本机生成一次性独立的 Stage 02 validation identity：Android 使用忽略的 PKCS12 文件及 `android-app/local.properties` 配置，RPK 使用同一 identity 的 PEM 私钥与证书。两份私钥材料及构建产物均被 Git 忽略，不提交或上传。

`apksigner verify --print-certs` 验证 APK；built RPK 测试验证签名块内嵌对应证书。证书 SHA-256 为 `fd6239d22597887c5e64c669ecbcb93fe1127d9a8718fe0e7de179be4d2760e9`。**APK/RPK signing identity: MATCH**。此处只记录公开证书指纹，不记录私钥或口令。

## 自动测试

- Android `:app:testValidationUnitTest :app:lintValidation :app:assembleValidation`：134/134 JVM tests 通过、Lint 通过、APK 构建通过。
- Android `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`：129/129 JVM tests 通过、Lint 通过、正式 source set 的 debug APK 构建通过。
- `band-probe`：3/3 消息协议测试、RPK release 构建、1/1 built artifact 测试通过。
- 根目录 `npm test`：47/47 通过。检查 package identity、validation 代码不在 production source set、签名文件被 Git 忽略。
- 检查正式 debug APK 的 20 个 DEX 文件：`Stage02Runner`、`Stage02Message`、`ValidationActivity`、`stage02_ping` 标记均不存在。
- `git diff --check` 通过。

构建时本机原有 JDK/Android SDK 缺失，临时下载到仓库忽略的 `.temp_stage02_tools/`，未改正式构建配置。Kotlin daemon 因沙箱不能写入用户目录而回退至进程内编译；最终构建成功。

## 真机状态与安装

**Band 9 Pro 尚未验证通过。** 自动测试和签名校验不能证明目标固件可安装 RPK、XMS 可授权、双向消息可达或提醒会振动。本次没有使用 USB、ADB 或 Android Studio 真机调试，也未替用户安装 APK/RPK。

1. 手机上通过文件安装 APK。
2. 只用 AstroBox 将 probe RPK 临时安装到 Band 9 Pro；日常连接继续由小米运动健康保持。
3. 打开 APK，点击“开始测试”，按提示在手环打开 Probe，继续消息测试，最后按实际通知和振动选择结果。
4. 提供最终页面截图或“复制脱敏报告”内容。

如当前 Band 9 Pro / 固件无法通过 AstroBox 安装，点击 APK 的“无法安装 RPK”记录 `RPK_INSTALL_BLOCKED`，停止该分支验证。

## Git 与发布

所有改动和产物仅在本地；未提交、未推送、未创建 PR 或 Release。`docs/current-status.md` 未改为 Stage 02 PASS，待用户真实设备结果返回后再更新。

## Stage 02D diagnostic build（2026-09-16）

### 工作区起点

用户明确授权以未提交的 Stage 02 成果继续；未 reset、clean、stash 或覆盖现有文件。起点 `HEAD` 与本地 `origin/main` 均为 `cafbb9679f4a7545312e16c9d8d3392ffe03900b`。只读检查未发现与 Stage 02 无关的用户修改。起点记录：

```text
 M android-app/app/build.gradle.kts
 M android-app/app/src/validation/AndroidManifest.xml
 M android-app/app/src/validation/java/com/codex/quota/android/validation/ValidationActivity.kt
?? android-app/app/src/testValidation/
?? android-app/app/src/validation/java/com/codex/quota/android/validation/Stage02Message.kt
?? android-app/app/src/validation/java/com/codex/quota/android/validation/Stage02Runner.kt
?? band-probe/
?? docs/stage_02_xms_failure_discussion.md
?? docs/stage_02_xms_probe_review.md
?? test/stage02-isolation.test.js
```

起点 `git diff --stat`（Git 不统计未跟踪文件）：

```text
 android-app/app/build.gradle.kts                   |  30 ++-
 android-app/app/src/validation/AndroidManifest.xml |   2 +-
 .../quota/android/validation/ValidationActivity.kt | 255 +++++++--------------
 3 files changed, 111 insertions(+), 176 deletions(-)
```

### 真机证据与诊断设计

小米运动健康显示 Band 9 Pro 已连接，Probe RPK 可安装和打开。第一次 `XMS_SERVICE_UNAVAILABLE` 后，用户没有改变状态，直接重试得到 `Service: PASS`、`Node: NODE_FOUND`、`WEAR_APP_CHECK_FAILED`。后者是 `isWearAppInstalled()` 异常，不是 `false`。故 Stage 02D 将安装查询变为**授权前、授权后两次诊断**，无论结果如何继续权限和消息验证；只有三次有界 node 查询均不能找到目标时才停止。重试等待约 1 秒、2 秒，报告只记成功/停止的第几次，不记录 node ID。

validation-only APK 使用 CleanRoom 公开异常类给出固定代码：安装查询为 `TRUE/FALSE/TIMEOUT/DISCONNECTED/PERMISSION_DENIED/PACKAGE_NOT_INSTALLED/SIGNATURE_FAILED/NOT_BONDED/OTHER_FAILURE`；service 为 `XMS_SERVICE_OK/XMS_SERVICE_TIMEOUT/XMS_NOT_BONDED/XMS_SERVICE_OTHER_FAILURE`；node 为 `NODE_FOUND/NO_CONNECTED_NODE/NODE_QUERY_TIMEOUT/NODE_QUERY_DISCONNECTED/NODE_QUERY_OTHER_FAILURE`（另可标记目标型号未找到）。`PACKAGE_NOT_INSTALLED` 只表示 XMS 返回的异常类别，不反推用户未安装 RPK。

`DEVICE_MANAGER` 与 `NOTIFY` 各最多请求授权一次，然后复查。两项独立记录 `PASS/DENIED/ERROR`。消息测试对随机 nonce 和类型严格匹配；手机发起和手环按钮发起互不依赖，单项失败仍继续其余可执行步骤。`NotifyApi` 仅在 NOTIFY 授权通过时发送固定虚构文案，最终结果需用户按实际收到与振动情况确认。所有报告只含固定枚举和 node 尝试序号。

手环 Probe 使用小米官方 [`connect.diagnosis()`](https://iot.mi.com/vela/quickapp/en/features/network/interconnect.html)，将状态 `0/204/1001/1000` 映射到 `OK/TIMEOUT/APP_UNINSTALLED/OTHER`。因为互联失败时无法依赖消息把诊断结果传回 Android，用户在手机 validation 页面选择手环显示的固定状态；未选时为 `NOT_REPORTED`。诊断异常不阻止消息尝试。`PASS` 由 node、两项权限、双向消息和用户确认通知振动决定；安装查询异常时使用 `PASS_WITH_INSTALL_CHECK_WARNING`，诊断未确认时使用 `PASS_WITH_DIAGNOSTIC_WARNING`。这只是 Stage 02D 测试结果，不是正式 runtime 验收。

### 新产物与校验

| 产物 | 本地绝对路径 | 大小 | SHA-256 |
| --- | --- | ---: | --- |
| Android validation APK | `D:\github_repo\codex-quota-band\out\stage02\CodexQuota-Stage02D-validation.apk` | 46,404,607 bytes | `3DBF60DE5F3272D3B041D524B84E6EC50C06BE0E87745C851E421A796B581AF6` |
| Band 9 Pro Probe RPK | `D:\github_repo\codex-quota-band\out\stage02\CodexQuota-Stage02D-probe.rpk` | 38,624 bytes | `7301F8E08A9DBFDB709D7427FCA42C63FA4857FBFD9AA6FFEE14F9FC10569A1F` |

APK `apksigner verify --print-certs` 与 RPK 内嵌证书的 SHA-256 均为 `fd6239d22597887c5e64c669ecbcb93fe1127d9a8718fe0e7de179be4d2760e9`：**APK/RPK signing identity MATCH**。沿用上一轮本地 identity；私钥和口令未写入报告或 Git。

### 自动验证与待验收

- Android validation：140/140 JVM tests、`lintValidation`、`assembleValidation` 通过。
- Android 正式 debug：129/129 JVM tests、`lintDebug`、`assembleDebug` 通过；未修改正式 runtime 源码。
- Probe：4/4 协议和诊断映射测试、release RPK 构建、1/1 built RPK 签名/内容测试通过。
- 根目录 `npm test`：47/47 通过；验证 validation-only source 不在正式 source set。另检查正式 debug APK 的 20 个 DEX，不含 `Stage02DRunner`；validation APK 的 21 个 DEX 包含该类。`git diff --check` 通过。
- 截至该次构建时尚未通过 Band 9 Pro 真机复测；安装查询的真实异常类别、设备侧 diagnosis、权限、双向消息及通知振动仍待用户回报。手环 Probe 的 336×480 页面已打包，但没有真机屏幕截图和触控验收。

新的 Probe RPK 内容已变更，需用 AstroBox 覆盖安装；APK 也需安装新版。两包均为 validation-only，不包含额度数据或正式协议改动。所有修改继续保持未提交、未推送、未创建 PR/Release。**Stage 02 未通过。**

## Stage 02D 真机反馈与通知回执修正（2026-09-16）

用户安装上述 APK/RPK 后回报：`Service: PASS`、`ServiceCode: XMS_SERVICE_OK`、`Node: NODE_FOUND`、`NodeAttempt: 1`、`InstallBefore: TRUE`、`DeviceManager: PASS`、`NotifyPermission: PASS`、`InstallAfter: TRUE`、`BandDiagnosis: OK`、`PhoneToBand: PASS`、`BandToPhone: PASS`。旧 APK 报 `Notification: SEND_FAILED`、`Overall: PARTIAL_PASS`；用户同时确认**手环实际收到测试通知并振动**，只是页面在 API 失败后直接结束，没有让用户选择实际结果。

这表明 Probe 真实设备上的 XMS node、授权、安装查询、互联诊断和双向消息已通过，测试通知也出现了可见送达及振动。`SEND_FAILED` 来自旧 validation 层的宽泛异常捕获；现有报告不能区分超时、SDK 返回失败或其他异常，也不能据此否定用户观察到的送达。它不证明正式 Android runtime 的提醒、后台和重连已验收。

validation-only 修正把 `NotificationApi` 与 `Notification` 分开：前者记录 `SUCCESS/TIMEOUT/DISCONNECTED/PERMISSION_DENIED/PACKAGE_NOT_INSTALLED/SIGNATURE_FAILED/NOT_BONDED/OTHER_FAILURE` 等固定状态；无论 API 回执如何，只要已经尝试发送，页面都会询问用户实际是否收到并振动。若 API 回执异常但用户确认两者均发生，结果为 `PASS_WITH_NOTIFY_API_WARNING`。不会自动重发，避免重复通知。RPK 与签名身份不变；只需更新 APK。

| 修正版产物 | 本地绝对路径 | 大小 | SHA-256 |
| --- | --- | ---: | --- |
| Android validation APK | `D:\github_repo\codex-quota-band\out\stage02\CodexQuota-Stage02D-notify-feedback-validation.apk` | 46,405,156 bytes | `92846A648322756AA03DDA5B90E462F5D252249E1DECF808B88A40210A72E984` |

修正版 APK 签名证书 SHA-256 仍为 `fd6239d22597887c5e64c669ecbcb93fe1127d9a8718fe0e7de179be4d2760e9`，与已安装 Probe RPK 相同。Android validation 142/142 JVM tests、Lint 和 APK 构建通过；新增测试覆盖 API 错误分类，以及失败回执后仍能记录用户观察。真机修正版尚待用户复测。Stage 02 仍未完成正式验收；所有修改和产物留在本地，未提交、推送或发布。

### 修正版真机复测

用户安装修正版 APK 后回报：

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

`Notification: PASS` 是用户在手环上确认通知到达且振动。`NotificationApi: TIMEOUT` 来自 validation 层的 `withTimeout(8000)`：等待 `NotifyApi.sendNotify()` 返回的 Task 8 秒内没有完成。当前 CleanRoom `NotifyApi` 将 Binder 回调的非成功 `Status` 经 `statusError` 转成异常；其中 SDK 的 `RESULT_TIMEOUT` 不会转换为 Kotlin 的 `TimeoutCancellationException`，因此这次固定 `TIMEOUT` 指向本地等待超时。测试没有保留 8 秒后的 Task 完成状态，因此不能判断回调是稍晚到达、一直未到达，还是服务端只完成了通知副作用。**通知的真实设备可见效果已通过，API 回执时序仍有未决问题。**

Stage 02D validation-only 探针的核心结果为 `PASS_WITH_NOTIFY_API_WARNING`；此结果不等于正式 Android runtime、额度/任务同步、后台或重连验收。当前不修改 vendored CleanRoom 或正式 runtime，不重复发送通知作默认修复。所有修改保持未提交、未推送、未发布。

### 手环 Probe 显示回归与修正

同次测试用户还报告：手环点击“测试手环→手机”后，一直显示“等待手机回复”，而手机脱敏报告为 `BandToPhone: PASS`。手机只有收到手环最后的 `stage02_pong` 才报告 PASS；Probe 只有收到匹配 nonce 的 `stage02_android_pong` 才发送该最后回执。因此手机 PASS 支持**手环已收到手机回复并已发送最终回执**的结论。旧 Probe 将页面 PASS 放在最终 `connection.send()` 的 `success` 回调中；该回调没有发生时，页面会停在等待文字。旧 Probe 还将 8 秒计时器放在首次发送的 `success` 回调中，回调不发生时等待也不会超时。

validation-only Probe 已改为：发起手环消息时立即开始 8 秒计时；收到类型与 nonce 均匹配的手机回复时立即停止计时并显示 PASS；仍发送最终 `stage02_pong` 供手机确认，但手环显示不再依赖该发送回调。新增测试模拟发送成功回调始终不来，确认计时器仍启动、匹配回复使手环显示 PASS、最终回执仍发出。

| 更新产物 | 本地绝对路径 | 大小 | SHA-256 |
| --- | --- | ---: | --- |
| Band 9 Pro Probe RPK | `D:\github_repo\codex-quota-band\out\stage02\CodexQuota-Stage02D-watch-feedback-probe.rpk` | 38,600 bytes | `7696C5A61DEB9C3E90766E476F70C6849C7DEA3872476646CB79331D742E818D` |

Probe 测试 5/5、RPK 构建及 built artifact 测试 1/1 通过；签名身份仍为 Stage 02 validation 证书，与修正版 Android APK 匹配。Android APK 未改，不需重装。更新 RPK 需要用 AstroBox 覆盖安装；手环页面修正尚待用户复测。正式 runtime 与 CleanRoom 未修改，Stage 02 正式验收仍未完成。

### 更新 Probe 复测结论

用户确认覆盖新版 RPK 后，手环“手环→手机”已显示 **PASS**。此时手机脱敏报告仍为 `PhoneToBand: PASS`、`BandToPhone: PASS`、`NotificationApi: TIMEOUT`、`Notification: PASS`、`Overall: PASS_WITH_NOTIFY_API_WARNING`；其余 service、node、两次安装查询、两项权限和手环 diagnosis 均通过。Stage 02D validation-only 的可见连接、双向消息和振动已完成真机验证，唯一未闭合的是 `NotifyApi` Task 在 validation 层 8 秒内未返回结果。

正式 `XiaomiWearableBridge.sendTaskAlert()` 当前调用 `notifyApi.sendNotify()` 后便返回“已请求”，**不等待该 Task 的结果**；所以 Stage 02D 的 8 秒等待超时不是正式 runtime 当前会遇到的相同超时路径。这不证明正式任务提醒可靠，仍需用正式 Android runtime、真实但脱敏的任务状态完成后续真机验收。没有证据说明延长等待一定能收到回调，也不能把通知已送达推断成回调成功。保持 `PASS_WITH_NOTIFY_API_WARNING`，不改 CleanRoom 或正式 runtime，不为消除警告而重复发送通知。
