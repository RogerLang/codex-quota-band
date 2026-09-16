# 安全说明

## 信任与网络边界

Foundation 01 的正式 Windows → Android 链路使用公共 ntfy relay，默认 `https://ntfy.sh`。
Windows 不对 LAN 或公网监听端口，不依赖 RFC1918 地址、同一局域网、固定 IP、端口转发、DDNS、
VPN 或 Tailscale。旧 LAN WSS/UDP 代码是 legacy，不属于正式 runtime policy。

ntfy 是不受信任的存储转发方。业务 payload 必须在 Windows 本地完成 AES-256-GCM 加密后才离开
电脑；Android 本地认证解密。ntfy 缓存中只允许出现加密 envelope。

## Relay protocol v1

- key：系统 CSPRNG 生成的 256-bit AES key；
- topic：独立的 256-bit 随机 identifier，不使用用户、机器或项目名称；
- nonce：每条消息新的 96-bit CSPRNG nonce，禁止重用；
- tag：128-bit GCM authentication tag；
- AAD：固定 protocol magic `CQ-RELAY-V1\0` + 原始 32-byte topic identifier；
- envelope 明文字段只有 `version`、`nonce`、`ciphertext`；
- decrypted payload 有独立 protocol version、持久 sequence、时间、quota、tasks 和 ChatGPT state。

Windows 在分配 sequence 后先持久化，再发布；进程重启不会简单重置为 0。Android 持久化最后接受的
sequence，拒绝重复和倒退消息。换 topic/key 的重新配对会清除 Android 旧 cursor/sequence。

AES-GCM authentication 失败、错误 topic、未知字段、非法长度、重复或倒退 sequence 都静默丢弃。
诊断最多记录裁剪后的错误类别，不记录 ciphertext、nonce、key、二维码或 plaintext。

## 配对凭据

Windows 首次启动或用户明确重新配对时生成 relay base URL、topic、key、protocol version 和 device ID。
二维码是正式配对方式。二维码中的材料等同长期读权限，不能截图公开或写入日志/analytics。

- Windows：relay secret 由当前用户 DPAPI 保护后落盘；
- Android：扩展 `PairingCredentialStore`，使用 Android Keystore 生成的 AES-256-GCM key 加密保存；
- 丢失手机或怀疑泄露：Windows 重新配对/撤销会轮换 topic 和 key，使旧凭据无法解密后续消息。

旧 6 位 LAN discovery 配对只作为 legacy 代码保留，没有被复用为公网 6 位密码协议。

## 数据最小化

加密不替代数据最小化。解密 payload 仍只允许：

- quota v3 白名单额度、重置摘要和 upstream freshness；
- task v1 的 conversation ID、最多 16 字短标题、状态、安全活动摘要和更新时间；
- ChatGPT/电脑连接状态、生成时间和 sequence。

禁止进入 relay、Android、手环、日志或诊断：原始提示词、回复、tool args、命令、终端输出、文件路径、
完整执行日志、Cookie、密码、账号资料、Codex access/refresh token 和官方接口原始响应。

Codex access token 只允许 Windows 进程内存向官方额度接口发送低频 HTTPS 请求，随后清零；不得进入
relay pairing、envelope、ntfy 或移动端。

## ntfy 可见 metadata

端到端加密不隐藏所有 metadata。公共 ntfy 服务仍可能看到：

- 随机 topic；
- 发布与订阅 IP；
- 消息时间、频率和密文长度；
- HTTP/WebSocket 连接 metadata；
- envelope 的 version、nonce 和 ciphertext（不能据此读取业务明文）。

topic 本身应视为不可公开的随机 channel identifier。公共服务还存在速率限制、缓存期限、可用性和
策略变化风险。实现对 429、5xx 和网络失败有限退避；relay 故障不能停止 Windows 本地额度采集。

## ntfy 发布约束

Windows 只向 `<baseUrl>/<topic>` POST envelope body，不在 title、tags、priority、filename、click、
actions 等字段写业务信息。Android 不依赖 ntfy Android App，直接使用 OkHttp WebSocket：首次无 cursor
时 `since=latest`，正常重连用最后接受的 message ID。`open` / `keepalive` 不进入 domain。

## Wearable 边界

Android 使用 MIT CleanRoom XMS SDK 源码，并在创建任何 Wearable API 前强制
`WearableBackend.XIAOMI`。这意味着日常手环主连接仍属于小米运动健康，不使用 OronBox backend、
Notify for Xiaomi、Gadgetbridge、root 或 LSPosed。

Android → 手环仍只发送已裁剪额度/任务摘要。Band 9 Pro 尚未真机验收；当前文档不能把适配目标写成
安全或兼容性已经验证。

## 已知限制

- Android 不使用常驻前台服务；系统杀死进程后不保证即时提醒必达。
- 公共 relay 的 metadata 与可用性不由本项目控制。
- package identity 已迁移到 `io.github.rogerlang.codexquota`，与上游 APK/RPK 是独立安装/签名链。
- Windows 安装包早期仍可能没有商业代码签名。
- Foundation 自动测试不能替代 Windows、Android、小米运动健康和 Band 9 Pro 真机验收。
