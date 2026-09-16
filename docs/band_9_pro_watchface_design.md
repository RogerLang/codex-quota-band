# Band 9 Pro 真表盘设计决策

用户确认日期：2026-09-16。目标设备：Xiaomi Smart Band 9 Pro，设计画布 **336 × 480**。
本文记录下一阶段的产品约束；本次 Stage 02D 固化不实现表盘、AOD 或 Vela → Lua IPC。
正式 UI 源码开始前，须按目标设备规格提供预览并由用户确认。

## 主界面与信息

- 最终主界面是**真正的 Band 9 Pro 表盘**。抬腕第一眼就能看到 quota，无须打开 RPK。
- 视觉方向采用用户选定的“信息稍多”B 方案，同时保持克制；单屏呈现，不做滑页。
- 第一版以 **weekly quota** 为核心。当前 Pro 用户场景暂不依赖 5h quota，第一版表盘不显示 5h；若未来恢复或重新有意义，再单独设计兼容。
- 主表盘包含：居中时间、日期、sync 状态、WEEKLY quota、大号 weekly remaining %、weekly progress bar、reset 倒计时或时间、Codex status、Running ×N 或更高优先级状态、Last sync。
- Codex 可同时运行多个任务。运行态必须支持计数，例如 `Running ×1`、`Running ×2`；不得把多个运行中任务折算成单一任务。
- 缺失或过期的额度不得推算成实时百分比；沿用项目既有的缓存与新鲜度语义。

## 状态与颜色

状态区域使用小图标感，并始终保留可读的文字或计数，不仅靠颜色表达：

| 状态 | 局部标记 | 显示优先级 |
| --- | --- | ---: |
| Approval / 需要授权 | 红色提示图标 | 1 |
| Review / 等待查看 | 橙色提示图标 | 2 |
| Running / 处理中 | 青绿色圆点和 `Running ×N` | 3 |
| Idle | 克制的中性状态 | 4 |
| Offline | 灰色状态 | 5 |

优先级固定为 **Approval > Review > Running > Idle > Offline**。`Review` 只表示等待查看，
不表示任务已成功完成。具体图标形状、字号与间距须在后续预览中确认。

AMOLED 配色：纯黑背景；主 quota 白色；正常、Running 和 sync 青绿色；Review 橙色；
Approval 红色；Offline 与缓存灰色。颜色只用于对应语义，数据新鲜度仍须明确标示。

## RPK 与尚未决定的内容

RPK 后续承担数据桥与备用详情页，不作为额度主界面。RPK 第二页和详细功能尚未决定，
不得自行扩展。不要将上游 Band 10 的 212×520 两页布局拉伸到 Band 9 Pro；Stage 02D 的
336×480 Probe 只是通信测试界面，不是正式表盘预览或产品 UI 验收。

正式 identity、额度/任务链路、后台/锁屏与网络切换、真表盘和 AOD 仍未真机验收。
Band 9 Pro 对外仍标注“目标设备 / 适配中”。
