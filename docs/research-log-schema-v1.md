# 研究交互日志 Schema v1

本文档面向 Atlas of Happiness 2.0 用户研究的数据导出与分析。日志文件为
`joyful_moment/research_interaction_log.jsonl`，每行一个独立 JSON 对象，当前
`schema_version` 为 `1`。

## 通用 envelope

| 字段 | 类型 | 可为 null | 说明 |
| --- | --- | --- | --- |
| `schema_version` | integer | 否 | 当前固定为 `1` |
| `event_name` | string | 否 | 本文下方列出的稳定事件名 |
| `event_id` | string | 否 | 每一日志行的 UUID，不是 moment ID |
| `timestamp_ms` | integer | 否 | Unix wall-clock 毫秒 |
| `timestamp_local` | string | 否 | 带时区偏移的本地 ISO 时间 |
| `timezone_id` | string | 否 | 记录时的系统时区 ID |
| `elapsed_realtime_ms` | integer | 否 | Android 单调时钟，用于进程内计时 |
| `participant_id` | string | 是 | 当前参与者编号；未设置时为 null |
| `session_id` | string | 是 | 相关采集 session |
| `moment_id` | string | 是 | 相关 moment；地点通知等事件可为 null |
| `notification_instance_id` | string | 是 | 单次通知实例 ID |
| `app_version_name` | string | 否 | App 版本名 |
| `app_version_code` | integer | 否 | App 版本号 |
| `device_model` | string | 否 | Android 设备型号 |
| `properties` | object | 否 | 事件专属、无内容型字段 |

所有持续时间均使用毫秒。ID 用于关联行为，不应解释为用户填写内容。

## 事件及 properties

表中“envelope”表示 ID 位于通用 envelope，不会在 `properties` 重复保存。

### 日志、session 与页面

| `event_name` | `properties` |
| --- | --- |
| `research_log_started` | `schema_version` |
| `capture_session_started` | `start_timestamp_ms`, `duration_estimated:false` |
| `capture_session_stopped` | `duration_ms`, `stop_reason`, `detection_count`, `moment_count`, `duration_estimated:false` |
| `capture_session_interrupted` | `start_timestamp_ms`, `recovered_timestamp_ms`, `duration_ms`, `stop_reason`, `duration_estimated:true` |
| `screen_opened` | `screen_name`, `entry_source`, `visit_id` |
| `screen_closed` | `screen_name`, `visit_id`, `visible_duration_ms` |

正常结束的 session 使用单调时钟计算 `capture_session_stopped.duration_ms`。进程异常退出
后，下次启动会以 wall clock 恢复 `capture_session_interrupted`，此时
`duration_estimated:true`，分析时应与正常 duration 分开或明确标记。

### Moment 决策、补充与详情

| `event_name` | `properties` |
| --- | --- |
| `moment_save_decision` | `action`, `push_allowed`, `is_update` |
| `supplement_flow_opened` | `entry_source` |
| `supplement_step_skipped` | `step_name` |
| `supplement_flow_completed` | `completion_reason`; moment 问答完成时还含 `answered_step_count`, `skipped_step_count`, `total_step_count`, `persistence_succeeded` |
| `moment_detail_opened` | `visit_id`, `entry_source`, `reconstruction_mode` |
| `moment_detail_closed` | `visit_id`, `visible_duration_ms`, `reconstruction_mode` |
| `moment_edit_started` | `entry_source` |
| `moment_edit_completed` | `field_category`, `operation`;可选 `item_id` 或 `media_item_id` |
| `moment_deleted` | `delete_source` |
| `detail_section_expanded` | `section_name`, `clip_id` |
| `detail_section_collapsed` | `section_name`, `clip_id`, `expanded_duration_ms` |

`answered_step_count` 只表示非空步骤数，日志层不接收或保存答案字符串。

`moment_save_decision` 采用追加式历史：首次 A/B/C 为 `is_update=false`；已有决策成功
变为另一项时为 `is_update=true`。重复选择同一 action、取消或持久化失败不写新行。

### 媒体

| `event_name` | `properties` |
| --- | --- |
| `media_opened` | `media_item_id`, `media_type`, `open_target` |
| `media_play_started` | `media_item_id`, `media_type`, `playback_instance_id`, `position_ms`, `duration_ms`, `resumed` |
| `media_play_paused` | `media_item_id`, `media_type`, `playback_instance_id`, `position_ms`, `played_duration_ms`, `reason` |
| `media_play_completed` | `media_item_id`, `media_type`, `playback_instance_id`, `position_ms`, `duration_ms`, `played_duration_ms`, `duration_played`, `total_duration` |
| `media_play_failed` | `media_item_id`, `media_type`, `playback_instance_id`, `position_ms`, `played_duration_ms`, `failure_type` |

`played_duration_ms` 是同一 `playback_instance_id` 的累计实际播放时间，暂停或页面隐藏的
间隔不会计入。多次 pause 后继续播放时，不要直接相加每一行累计值；每个播放实例取
`played_duration_ms` 的最大值，再跨实例求和。

`duration_played` 与 `total_duration` 均为毫秒，分别等于兼容字段
`played_duration_ms` 与 `duration_ms`。当 `total_duration > 0` 时，可计算
`duration_played / total_duration`；非正总时长应排除。

### 回顾、地图与设置

| `event_name` | `properties` |
| --- | --- |
| `review_tab_selected` | `tab`, `selection_source` |
| `review_calendar_month_changed` | `direction` |
| `review_calendar_day_selected` | `days_from_today`, `event_count` |
| `map_opened` | `entry_source`, `legacy`, `focused_from_notification` |
| `map_card_changed` | `from_index`, `to_index`, `total`, `navigation_method` |
| `map_moment_opened` | `card_index`, `total`, `map_variant`; moment/session 位于 envelope |
| `map_recenter_requested` | `method`, `legacy`;长按卡片时可含 `card_index` |
| `setting_changed` | `setting_name`, `enabled`, `change_source` |

`days_from_today` 是相对天数，不是精确日期。地图事件不保存标题、地点名或坐标。

### 通知

| `event_name` | `properties` |
| --- | --- |
| `notification_posted` | `notification_type`, `android_notification_id`, `posted_timestamp_ms`, `destination`;地点类型另含 `anonymous_cluster_id` |
| `notification_post_failed` | posted 字段，加 `failure_reason` |
| `notification_opened` | posted 字段，加 `response_delay_ms` |
| `notification_dismissed` | posted 字段，加 `response_delay_ms` |
| `notification_skipped` | `notification_type`, `reason` |

`notification_type` 为 `short`、`long` 或 `location`。Short/Long 的 moment/session 位于
envelope；location 不指向具体 moment，只携带不可逆的 `anonymous_cluster_id`。
`notification_instance_id` 将 posted、opened 或 dismissed 关联到同一次通知，
`response_delay_ms = max(0, response_time - posted_time)`。opened 和 dismissed 分别
幂等，Activity 重建不会重复记录同一种响应。

标准 skip 原因包括：

- Daily：`setting_disabled`, `already_sent_today`, `no_eligible_moment`
- Location：`setting_disabled`, `invalid_cluster_payload`,
  `current_fix_outside_radius`, `no_old_eligible_moment`,
  `already_sent_place_today`, `cooldown_active`

## 建议分析口径

- 交互次数：按 `event_name` 计数；例如展开次数统计
  `detail_section_expanded`，通知点击次数统计 `notification_opened`。
- 每日佩戴/使用时间：按本地日期和 participant 聚合
  `capture_session_stopped.duration_ms`；`capture_session_interrupted` 单独报告为估算值。
- 页面停留：按 `visit_id` 使用对应 `screen_closed.visible_duration_ms`；moment 详情可用
  `moment_detail_closed.visible_duration_ms`。
- 媒体收听/观看：按 `playback_instance_id` 取最大的 `played_duration_ms`，再按
  `media_type`、moment 或日期求和。
- 通知响应：以 `notification_instance_id` 连接 `notification_posted` 与
  `notification_opened` / `notification_dismissed`，使用 `response_delay_ms`。

示例（只使用事件名和数值字段）：

```python
from collections import defaultdict

session_ms = defaultdict(int)
media_instance_ms = defaultdict(int)

for row in rows:
    if row["event_name"] == "capture_session_stopped":
        session_ms[row["participant_id"]] += row["properties"]["duration_ms"]
    if row["event_name"] in {
        "media_play_paused", "media_play_completed", "media_play_failed"
    }:
        p = row["properties"]
        media_instance_ms[p["playback_instance_id"]] = max(
            media_instance_ms[p["playback_instance_id"]],
            p.get("played_duration_ms", 0),
        )
```

## 隐私边界与删除语义

研究交互日志不包含：

- “和谁 / 在做什么 / 心情”或其他文字回答；
- Speechmatics payload、转写文本或 API Key；
- GPS 经纬度、地点名、地址、天气内容；
- 音频、照片、视频内容及其本地文件路径。

媒体与地点使用匿名派生 ID，仅用于同一对象的行为关联。删除 moment 会物理删除 App
管理的事件 JSON 与媒体，但此前已经追加的日志行不会回写或删除；历史行中的
`moment_id` 作为不透明 ID 保留，以维持完整的研究行为时间线。
