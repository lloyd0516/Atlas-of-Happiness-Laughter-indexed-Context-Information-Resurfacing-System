# 研究交互日志设计

日期：2026-07-28  
状态：待用户审阅  
目标分支：`fj_ver`

## 1. 目标

为 14 天用户研究增加一个长期保存在手机本地的、可分析的 App 使用与交互日志。日志用于回答以下类型的问题：

- 被试每天启动了多少次采集 session、每次运行多久、累计佩戴（运行）多久；
- 被试是否进入并完成补充流程，以及补充了哪些媒介类型和数量；
- 被试如何浏览 moment、地图和回顾页面；
- 展开按钮、媒体播放、编辑和删除等功能被使用了多少次；
- short-term、long-term 和 location 通知是否成功发出、是否被点击或划掉；
- 用户何时开启或关闭两类提醒。

“佩戴时间”在本研究中明确等于 laughter capture session 从成功开始到停止的运行时间，不以 USB 设备物理连接时长或 App 前后台时长代替。

## 2. 非目标与隐私边界

研究日志不保存以下内容：

- “和谁”“在做什么”“心情”和文字笔记等补充内容原文；
- 转录文本、Speechmatics 原始响应或检测音频内容；
- 照片、视频、音频二进制；
- GPS 经纬度、详细地址和天气内容；
- API key、access token 等凭据。

对于补充流程，日志只记录是否进入、跳过或完成，以及补充媒介类型和数量。对于 moment 和通知，日志使用不含语义内容的 ID 建立关联。

现有业务数据、开发日志和检测日志保持原样；研究日志是独立的数据层，不替代现有文件。

## 3. 方案选择

采用单个追加式 JSON Lines 文件：

`joyful_moment/research_interaction_log.jsonl`

每次交互写入一个完整 JSON 对象并换行。相较只保存累计次数，逐事件日志能够还原操作顺序、计算停留时长和通知响应时间。相较 SQLite，JSONL 更容易在研究结束后从手机复制并直接用 Python、R 或命令行工具分析。

14 天内不主动轮转或覆盖文件。App 重启和多个采集 session 共用同一个日志文件。

## 4. 通用记录结构

所有记录包含以下通用字段：

```json
{
  "schema_version": 1,
  "event_name": "capture_session_started",
  "event_id": "uuid",
  "timestamp_ms": 1785231000000,
  "timestamp_local": "2026-07-28T19:30:00.000+08:00",
  "timezone_id": "Asia/Shanghai",
  "elapsed_realtime_ms": 123456789,
  "participant_id": "P01",
  "session_id": "session-uuid",
  "moment_id": null,
  "notification_instance_id": null,
  "app_version_name": "2.0",
  "app_version_code": 20,
  "device_model": "device model",
  "properties": {}
}
```

字段约束：

- `event_id` 对每条日志唯一，用于去重；
- `timestamp_ms` 用于跨设备分析，`timestamp_local` 和 `timezone_id` 用于解释本地研究时间；
- `elapsed_realtime_ms` 用于抵抗用户修改系统时钟导致的同一运行周期内的时长误差；
- `participant_id` 使用 App 当前 participant number；尚未选择参与者时允许为空；
- `session_id`、`moment_id` 和 `notification_instance_id` 仅在适用时填写；
- 可选信息统一放入 `properties`，避免事件字段互相污染；
- 不使用可能随用户编辑而变化的标题、位置名称或补充内容作为关联键。

## 5. Session 与佩戴时间

### 5.1 开始

只有采集控制器确认 session 成功启动后才写入：

- `capture_session_started`

属性包括启动入口、音频设备状态和配置版本等非敏感运行信息。

### 5.2 停止

采集控制器完成停止流程后写入：

- `capture_session_stopped`

属性包括：

- `duration_ms`：以 monotonic clock 计算的实际运行时长；
- `stop_reason`：用户主动停止、服务停止、错误等；
- session 内生成的 laughter window 和 moment 数量。

### 5.3 异常中断恢复

活动 session 的 ID、开始墙钟时间和 monotonic 时间同步保存在内部偏好中。正常停止时清除活动标记。如果下次启动 App 时发现上次 session 未正常关闭，则追加：

- `capture_session_interrupted`

该事件带有可计算的墙钟区间和 `duration_estimated: true`。由于设备重启后 monotonic clock 会重置，异常时长不能伪装成精确值；分析时应与正常停止时长分开处理。

## 6. 交互事件

### 6.1 Moment 与补充流程

| 事件名 | 记录内容 |
| --- | --- |
| `moment_save_decision` | 保留/丢弃、是否允许回顾推送 |
| `supplement_flow_opened` | 入口、moment ID |
| `supplement_step_skipped` | 步骤类型，不记录内容 |
| `supplement_flow_completed` | 完成状态、照片/录音/文字等类型的项目数量 |
| `moment_detail_opened` | moment ID、进入来源 |
| `moment_detail_closed` | 停留时长 |
| `moment_edit_started` | 编辑入口 |
| `moment_edit_completed` | 修改的字段类别，不记录新旧值 |
| `moment_deleted` | moment ID、删除入口 |

删除 moment 不删除已经写入的研究交互历史；删除完成后追加 `moment_deleted`。

### 6.2 页面和控件

| 事件名 | 记录内容 |
| --- | --- |
| `screen_opened` | 规范化页面名、进入来源 |
| `screen_closed` | 页面停留时长 |
| `detail_section_expanded` | section 名称、moment ID |
| `detail_section_collapsed` | section 名称、展开持续时间 |
| `setting_changed` | `daily_reminder` 或 `location_reminder`、新布尔值 |

Activity 重建或前后台切换不重复计为用户主动点击。页面停留时长以可见且处于 resumed 状态的区间累计。

### 6.3 媒体交互

| 事件名 | 记录内容 |
| --- | --- |
| `media_opened` | photo/video/audio、moment ID、匿名 media item ID |
| `media_play_started` | video/audio、匿名 media item ID、起始进度 |
| `media_play_paused` | 当前进度、本段实际播放时长 |
| `media_play_completed` | 总时长、本次实际播放时长 |
| `media_play_failed` | 失败类别，不记录绝对文件路径或异常中的敏感内容 |

通过播放状态累计“实际播放时长”，不把播放器页面停留时间当作收听时长。

### 6.4 地图交互

| 事件名 | 记录内容 |
| --- | --- |
| `map_opened` | 进入来源；若来自地点通知则包含通知实例 ID |
| `map_card_changed` | 前后卡片匿名 ID、操作方式 |
| `map_moment_opened` | moment ID、当前卡片索引 |
| `map_recenter_requested` | 用户触发重新定位 |

研究日志不记录地图中心或用户当前经纬度。

## 7. 通知事件与归因

每次实际通知分配唯一 `notification_instance_id`，同时保留 Android notification ID。short、long 和 location 分别记录，不能仅依赖固定 notification ID 统计。

| 事件名 | 写入时机 |
| --- | --- |
| `notification_posted` | `NotificationManager.notify` 成功返回后 |
| `notification_post_failed` | 构建或发送异常时 |
| `notification_opened` | 用户点击通知进入目标 Activity 时 |
| `notification_dismissed` | 用户从通知栏划掉通知时 |

公共属性包括：

- `notification_type`：`short`、`long` 或 `location`；
- `destination`：short/long detail 或 map；
- `android_notification_id`；
- `posted_timestamp_ms`；
- `response_delay_ms`（点击或划掉时计算）；
- short/long 可带 `moment_id`，location 不绑定具体 moment。

点击归因通过 PendingIntent extras 传递通知实例 ID，并在目标 Activity 的 `onCreate`/`onNewIntent` 中幂等写入。划掉归因通过 `deleteIntent` 接收。系统清理、设备关机或 App 被卸载无法可靠等同为用户主动划掉，因此不生成虚假的 dismissed 事件。

若通知因当天无符合条件的 moment 而未创建，不写 `notification_posted`；可写入不面向用户的 `notification_skipped`，属性仅包含 `no_eligible_moment` 等原因，便于区分“没有数据”和“调度未运行”。

## 8. 写入可靠性

实现一个进程内单例 `ResearchInteractionLogger`，所有业务模块只能通过它写入研究事件。

写入要求：

1. 在单线程队列中串行化 JSON，防止并发写入交错；
2. 使用追加模式写入一整行；
3. 每个事件写入后 flush，并对文件描述符执行同步落盘；
4. 写入失败时记录到现有开发日志，并保留有限次数的内存重试队列；
5. 下一次成功初始化时先重试尚未落盘的事件；
6. 日志初始化时检查最后一行；若 App 在写入中崩溃，只忽略不完整的末行，不改写前面的数据；
7. 对通知点击、删除等可能重复进入的回调，通过 `event_id` 或业务幂等键避免重复计数。

写日志失败不能阻塞或改变采集、保存、播放、通知和删除等现有业务行为。

## 9. 生命周期与文件保留

- 首次写入时追加 `research_log_started`，记录 schema 和 App 版本；
- App 版本升级后仍向同一文件追加，并由每行的版本字段区分；
- 不在 moment 删除、session 清理或 App 普通退出时删除研究日志；
- 本阶段不增加导出 UI，研究结束后由研究人员手动复制文件；
- App 被卸载、清除应用数据或手机恢复出厂设置时，文件可能被系统删除，因此必须在执行这些操作前先导出；
- 若 participant number 改变，继续使用同一物理文件，但每条记录使用当时有效的 participant ID，分析时按 participant ID 分组。

## 10. 兼容性与验证

新增日志不得改变当前 App 的页面导航、采集流程、媒体行为、moment 数据格式或通知选择逻辑。

实现时至少验证：

- session 正常开始/停止产生一对记录，时长合理；
-异常退出后能识别未关闭 session，且不伪造精确时长；
- 连续快速点击不会生成损坏或交错的 JSON；
- 展开/收起次数和持续时间可由日志还原；
- audio/video 播放、暂停、完成及实际播放时长正确；
- 三类通知可以区分 posted/opened/dismissed，点击能归因到同一实例；
- moment 删除后历史日志仍存在；
- 日志不包含补充原文、转录、经纬度或媒体文件内容；
- 每一行均可独立解析，App 重启后继续追加；
- 研究日志写入异常不影响现有核心功能。

## 11. 后续实施边界

设计获批后再编写具体实施计划。计划将按以下层次拆分：

1. 日志 schema、序列化和可靠追加写入器；
2. session 生命周期与异常恢复；
3. moment、补充、页面、媒体及地图埋点；
4. 通知发送、点击和划掉的实例级归因；
5. 单元测试、集成验证及 README 中的文件位置与手动提取说明。

