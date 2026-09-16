# XMS Wearable SDK Clean Room / XMS Wearable SDK 净室实现

这是 XMS Wearable SDK 1.4 Android 公共 API 与 Binder 协议的开源净室实现
This is an open-source clean-room implementation of the public Android API and Binder contract of XMS Wearable SDK 1.4

它保留现有应用使用的 `com.xiaomi.xms.wearable.*` 公共包名
It preserves the `com.xiaomi.xms.wearable.*` public packages used by existing applications

原创实现位于 `org.zxor.oronbox.xms.*`，可通过 OronBox 或小米官方配套服务通信
The original implementation lives under `org.zxor.oronbox.xms.*` and can communicate through OronBox or the official Xiaomi companion service

## 依赖 / Artifact

```kotlin
implementation("org.zxor.oronbox:xms-wearable-lib:1.4_oronbox.1_release")
```

现有应用代码可以继续使用标准入口
Existing application code can continue using the standard entry point

```java
MessageApi messages = Wearable.getMessageApi(context);
messages.sendMessage(nodeId, payload);
```

默认后端模式为 `AUTO`：安装并启用 OronBox XMS 服务时优先使用 OronBox，否则回退到小米运动健康或小米穿戴
The default backend mode is `AUTO`: it prefers OronBox when its XMS service is installed and available, then falls back to Xiaomi Health or Xiaomi Wear

```java
WearableBackendConfig.setBackend(context, WearableBackend.ORONBOX);
```

使用 `WearableBackend.XIAOMI` 可强制选择官方配套服务
Use `WearableBackend.XIAOMI` to force the official companion service

OronBox 后端不支持实时睡眠与佩戴状态查询或订阅，相关 Task 会以不支持失败
The OronBox backend does not support live sleep or wearing queries and subscriptions; the related Task fails as unsupported

请在首次调用 `Wearable.get*Api()` 前配置后端
Configure the backend before the first `Wearable.get*Api()` call

## 构建 / Build

请设置 `ANDROID_HOME`，或创建不纳入版本控制的 `local.properties`
Set `ANDROID_HOME`, or create an untracked `local.properties` file

```shell
./gradlew :xms-wearable-lib:assembleRelease
./gradlew :xms-wearable-lib:publishReleasePublicationToMavenLocal
```

AAR 输出到 `xms-wearable-lib/build/outputs/aar/`
The AAR is written to `xms-wearable-lib/build/outputs/aar/`

## 兼容范围 / Compatibility scope

本项目实现五组公共 API、Task 监听器系统、公共数据模型、Parcelable 字段布局、全部公共 AIDL 回调，以及 `IWearableInterface` 的 15 项操作协议
This project implements the five public API groups, Task listener system, public data models, Parcelable field layouts, all public AIDL callbacks, and the 15-operation `IWearableInterface` contract

本项目不包含小米源代码、签名材料或重新分发的小米二进制文件
This project contains no Xiaomi source code, signing material, or redistributed Xiaomi binaries

小米、Xiaomi Health、VelaOS 与 XMS 是其各自权利人的商标
Xiaomi, Xiaomi Health, VelaOS, and XMS are trademarks of their respective owners

本项目与小米无隶属关系，也未获得小米背书
This project is not affiliated with or endorsed by Xiaomi

## 许可证 / License

本 SDK 采用 MIT 许可证
This SDK is licensed under the MIT License

OronBox 的配套服务实现属于 OronBox 项目，采用 AGPL-3.0 许可证
The companion service implementation belongs to the OronBox project and is licensed under AGPL-3.0
