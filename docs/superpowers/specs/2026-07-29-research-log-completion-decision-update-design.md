# 媒体播放完成与保存决策更新日志设计

## 背景

Atlas 2.0 将研究交互事件追加写入手机本地
`research_interaction_log.jsonl`。当前有两个分析缺口：

1. `media_play_completed` 已包含 `played_duration_ms` 和 `duration_ms`，但缺少研究分析约定
   使用的 `duration_played` 与 `total_duration` 字段；
2. `moment_save_decision` 主要在 session 结束后的首次 A/B/C 决策中记录，后续决策变化
   没有统一的 `is_update` 标记。

A/B/C 分别表示：

- A：删除；
- B：保留并允许推送回顾建议，即 `save_push`；
- C：保留但不推送，即 `save_no_push`。

本次只补充日志，不新增详情页 B/C 切换入口，不改变保存、删除或 resurfacing policy。

## 目标

- 音频和视频的 `media_play_completed` 均能直接计算实际播放比例；
- 保留现有播放字段，兼容已经存在的日志分析脚本；
- 首次保存决策与后续决策变化使用一致字段；
- 研究日志保持 append-only，修改决策时追加新行，不覆盖历史行；
- 只记录持久化成功的状态变化；
- 不记录用户回答内容、媒体内容或新的敏感信息。

## 非目标

- 不新增或重做详情页 A/B/C 设置 UI；
- 不改变 `ResearchPlaybackTracker` 的暂停排除与累计算法；
- 不改变 event JSON 的 `save_decision` 数据结构；
- 不修改 Daily 或 Location notification 的资格规则；
- 不迁移或重写已有 JSONL 日志；
- 不移除现有 `played_duration_ms`、`duration_ms` 字段。

## `media_play_completed` 字段

音频与视频播放自然完成时，properties 同时包含：

| 字段 | 单位 | 语义 |
| --- | --- | --- |
| `duration_played` | ms | 同一 `playback_instance_id` 实际累计播放时间，不含暂停或页面隐藏间隔 |
| `total_duration` | ms | 播放器报告的媒体总时长 |
| `played_duration_ms` | ms | 保留的兼容字段，值与 `duration_played` 相同 |
| `duration_ms` | ms | 保留的兼容字段，值与 `total_duration` 相同 |
| `position_ms` | ms | 完成时播放位置；沿用现有字段 |

分析时可计算：

```text
completion_ratio = duration_played / total_duration
```

`total_duration <= 0` 时分析端不得计算比例。App 不额外写入比例，避免浮点精度和除零语义
在不同分析工具中不一致。

`duration_played` 继续由 `ResearchPlaybackTracker` 使用单调时钟计算。暂停、重复回调和
页面隐藏区间不重复累计。字段只加入 `media_play_completed`；本次不改 paused、failed
事件 schema。

## `moment_save_decision` 字段与状态变化

所有新写入的 `moment_save_decision` properties 包含：

| 字段 | 语义 |
| --- | --- |
| `action` | `delete`、`save_push` 或 `save_no_push` |
| `push_allowed` | 仅 `save_push` 为 `true` |
| `is_update` | 首次决策为 `false`；已有决策成功变为另一项时为 `true` |

日志是追加式事件历史。例如：

```json
{"event_name":"moment_save_decision","properties":{"action":"save_push","push_allowed":true,"is_update":false}}
{"event_name":"moment_save_decision","properties":{"action":"save_no_push","push_allowed":false,"is_update":true}}
{"event_name":"moment_save_decision","properties":{"action":"save_push","push_allowed":true,"is_update":true}}
```

判定规则：

1. event 没有已有 `save_decision.action`，成功选择 A/B/C：`is_update=false`；
2. event 已有 action，成功改为另一 action：`is_update=true`；
3. 选择与当前 action 相同：不是状态变化，不追加 `moment_save_decision`；
4. 用户取消、JSON 保存失败或物理删除失败：不追加；
5. 详情页永久删除成功：追加 `action=delete`、`push_allowed=false`、
   `is_update=true`，并继续追加原有 `moment_deleted`；
6. session 结束流程首次直接删除成功：记录 `is_update=false`；
7. 如果同一决策流程未来被用于已有 event 的 B↔C 切换，将按上述规则自动记录 update。

本次不新增详情页 B/C 切换入口。因此当前详情页能够新增覆盖的后续决策是 A（永久删除）；
B↔C 只有在现有决策流程被再次调用时才产生新日志。

## 实现边界

新增一个无 Android UI 依赖的日志 properties 构造/判定组件，集中负责：

- 构造完成播放的兼容字段与新字段；
- 判断首次决策、实际更新和 no-op；
- 构造统一的 `action`、`push_allowed`、`is_update`。

`EventDetailActivity` 和 `VideoPlayerActivity` 使用同一个播放完成 properties 构造方法。
`EventSupplementActivity` 在保存或删除前读取旧 action，在操作成功后按判定结果记录。
`EventDetailActivity` 的事件级永久删除成功后记录 update 决策，再保留原有
`moment_deleted` 日志。

日志仍通过 `ResearchInteractionLogger` 写入同一个 JSONL 文件。组件不持有
`Context`、不直接写文件，也不把研究日志职责放进 `AtlasReviewRepository`。

## 失败与兼容处理

- 新字段只增不删，不提升 `ResearchEventNames.SCHEMA_VERSION`；
- 已有日志和只认识旧字段的脚本继续工作；
- 新分析优先使用 `duration_played`、`total_duration`，也可回退到旧字段；
- 播放器时长异常时仍记录原始非正值，由分析端排除比例；
- 日志写入失败不回滚用户已经成功完成的保存或删除操作；
- 删除只有在物理删除成功后才记录新的保存决策和 `moment_deleted`。

## 测试

先写失败测试，再实现：

- 播放完成 properties 同时包含新旧四个时长字段，且别名值完全一致；
- `duration_played` 使用 tracker 的实际累计值，`total_duration` 使用播放器总时长；
- 首次 `save_push` / `save_no_push` / `delete` 得到 `is_update=false`；
- 已有 B 后改 C、已有 C 后改 B、已有 B/C 后删除得到 `is_update=true`；
- `push_allowed` 只随 `save_push` 为真；
- 重复选择相同 action 被识别为 no-op，不生成决策 properties；
- null、空 action 或不支持 action 安全拒绝；
- 现有 `ResearchPlaybackTrackerTest` 继续验证暂停区间和重复回调不重复累计；
- 完整 `testDebugUnitTest` 和 `assembleDebug` 通过。

同步更新：

- `docs/research-log-schema-v1.md`；
- `README.md` 的研究交互日志说明。

## 完成标准

- 音频和视频完成事件均写入 `duration_played` 与 `total_duration`；
- 现有时长字段继续存在；
- 首次和后续保存决策能通过 `is_update` 区分；
- 当前详情页永久删除产生 update 决策日志；
- 失败和 no-op 不产生虚假决策变化；
- 不新增决策 UI，不改变 App 现有保存与通知功能；
- 新增测试、完整单元测试和 APK 构建通过；
- 未经用户再次明确授权，不 push。
