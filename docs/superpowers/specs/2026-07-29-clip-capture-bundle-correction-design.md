# Laughter clip 采集 Bundle 与媒体关联修正规范

日期：2026-07-29
状态：待用户复核

## 1. 背景与问题

现有 App 在一次通过限频策略的 laughter 检测后，会启动一次自动媒体采集：

- 录制 1 段视频；
- 在触发后约 1.5 秒拍摄第 1 张照片；
- 在触发后约 3.5 秒拍摄第 2 张照片。

因此，产品语义中的一次采集 bundle 固定为：

> 2 张照片 + 1 段视频

此前实现 clip 媒体关联时，把“最近 1 次 photo + video 采集”错误解释成了
“每种媒体最多选择 1 个”，导致每个 clip 最多只显示 1 张照片和 1 段视频，
第二张照片被展示层主动丢弃。

此外，当前媒体记录只保存 `event_id` 和各媒体自己的 `capture_time_ms`，
没有保存同一次采集的共同标识。仅把照片上限从 1 改成 2，可能在一个 event
包含多次自动采集时，把不同采集轮次的媒体拼成一个不存在的 bundle。

## 2. 目标

本次修正需要满足：

1. 每次通过限频策略并真正触发的自动采集固定产生一个
   `2 photos + 1 video` bundle。
2. 新采集数据显式保存 bundle 归属，不能依赖全局“最后触发事件”推断。
3. 每个 laughter clip 在自身时间点的对称 `±90s` 窗口内寻找最近的完整采集
   bundle，并展示该 bundle 中实际成功保存的全部媒体。
4. 同一个 bundle 可以被多个相邻 clip 共用。
5. bundle 中某项采集失败时，只展示同一 bundle 中成功保存的媒体，不从其他
   bundle 补齐。
6. 旧数据没有 bundle 标识时，使用确定性的时间规则推断，继续可读。
7. 删除设置中会造成误解的“每次触发照片数量”调节入口，同时兼容旧配置文件。
8. 修正 README、既有设计说明和自动化测试中的错误表述。

## 3. 非目标

本次不改变：

- laughter 检测模型和阈值；
- event 聚合规则；
- 自动采集的 120 秒限频逻辑；
- 视频时长；
- clip 的 `±90s` 媒体匹配窗口；
- 照片、视频的查看与播放交互；
- 用户生成媒体的展示规则。

本次也不会尝试为历史文件永久回写 bundle 标识；旧数据只在读取时兼容推断。

## 4. 固定 Bundle 规格

新增统一的采集 bundle 参数，替代分散或有歧义的数量定义：

- `AUTO_CAPTURE_PHOTOS_PER_BUNDLE = 2`
- `AUTO_CAPTURE_VIDEOS_PER_BUNDLE = 1`
- `AUTO_CAPTURE_PHOTO_DELAY_1_MS = 1500`
- `AUTO_CAPTURE_PHOTO_DELAY_2_MS = 3500`
- `CLIP_MEDIA_MATCH_WINDOW_MS = 90000`
- `LEGACY_CAPTURE_BUNDLE_GROUP_WINDOW_MS = 15000`

其中，15 秒只用于恢复没有 `bundle_id` 的旧数据。它明显大于当前两张照片之间
约 2 秒的间隔，又明显小于自动采集的 120 秒限频间隔，能够避免把正常情况下
相邻两轮采集合并。

设置中的 frequent、medium、sparse 预设仍可改变检测和采集频率、clip 时长及视频
时长，但不再改变单个 bundle 的媒体构成。

旧配置中的 `trigger_photo_count`：

- 读取时允许存在，避免旧配置解析失败；
- 运行时统一规范为 2；
- 保存新配置时写入 2，或在后续配置格式升级时移除；
- 设置页面不再显示该滑杆。

## 5. Bundle 身份与请求传递

每次自动采集真正触发时，控制器创建一个不可变的采集请求，至少包含：

- `bundle_id`：本次采集的唯一标识；
- `event_id`：触发时已经确定的所属 event；
- `trigger_time_ms`：本次采集触发的系统时间；
- `automation_bucket_id`：原有限频 bucket，便于诊断；
- 视频时长；
- 两个照片请求各自的 `bundle_media_index`，取值 0、1。

`bundle_id` 使用本地唯一值生成，不依赖稍后可能发生变化的
`lastTriggeredEventId`。

控制器向 `MainActivity` 请求视频和延迟照片时，显式传递上述 bundle 上下文。
`MainActivity` 的视频 pending 状态和照片队列都保存该上下文。媒体开始、完成或
失败后，再把同一上下文传回控制器。

这可以消除当前延迟照片回调在执行时再次读取“最后触发事件”所带来的潜在串绑
风险。

## 6. 持久化格式

新采集的每个媒体记录继续保存已有字段：

- `path`
- `content_uri`（视频适用）
- `capture_time_ms`

并新增：

- `bundle_id`
- `bundle_trigger_time_ms`
- `bundle_media_index`

照片的 `bundle_media_index` 分别为 0、1；视频固定为 0。字段采用可选读取方式，
因此旧 event JSON 无需迁移即可继续加载。

示例：

```json
{
  "path": ".../event_photo_....jpg",
  "capture_time_ms": 1785295801500,
  "bundle_id": "capture_bundle_...",
  "bundle_trigger_time_ms": 1785295800000,
  "bundle_media_index": 0
}
```

自动采集日志中的 triggered、started、saved、skipped 和 save_failed 记录也携带
`bundle_id`；照片记录额外携带 `bundle_media_index`。这既支持排障，也能验证
一轮采集是否实际获得 2 张照片和 1 段视频。

## 7. Repository 归一化

`AtlasReviewRepository` 在输出 `auto_captured.photos` 和
`auto_captured.videos` 时：

1. 保留并透传新记录中的 bundle 字段；
2. 继续使用现有逻辑恢复 `capture_time_ms`；
3. 不为旧数据伪造并写回持久化 `bundle_id`；
4. 允许新旧媒体记录出现在同一个 event 中。

展示匹配器只接收归一化后的数据，不直接解析 event 文件的多个历史格式。

## 8. Clip 到 Bundle 的匹配规则

给定 laughter clip 时间 `T`：

### 8.1 新数据

1. 按非空 `bundle_id` 对照片和视频分组。
2. 每组使用 `bundle_trigger_time_ms` 作为 bundle 时间；若字段缺失，则回退到该组
   最早的有效 `capture_time_ms`。
3. 仅保留 bundle 时间位于 `[T - 90s, T + 90s]` 的候选。
4. 选择与 `T` 绝对时间差最小的 bundle。
5. 平局时依次选择：
   - 时间更早的 bundle；
   - `bundle_id` 字典序更小的 bundle。
6. 照片按 `bundle_media_index`、`capture_time_ms`、路径排序，最多显示 2 张。
7. 视频按 `bundle_media_index`、`capture_time_ms`、路径排序，最多显示 1 段。

匹配的是整个 bundle，不是先选照片再独立选视频。

### 8.2 旧数据

没有 `bundle_id` 的媒体使用只读时间推断：

1. 按 `capture_time_ms` 排序并忽略不存在或不可访问的文件。
2. 以每段视频作为优先 anchor，在该视频前后 15 秒内选择尚未归组、距离最近的
   最多 2 张照片。
3. 对没有视频 anchor 的剩余照片，按时间邻近关系组成最多 2 张的照片-only
   bundle；相邻媒体时间差不得超过 15 秒。
4. inferred bundle 的时间优先使用视频时间，没有视频时使用第一张照片时间。
5. 再使用与新数据相同的 `±90s`、最近优先和平局规则选择一个 inferred bundle。

旧数据推断只使用可访问且具有可信时间的媒体。无法确定时间的媒体不参与 clip
关联，也不会被远距离媒体替代。

### 8.3 部分失败

新数据存在 `bundle_id` 时，严格禁止跨 bundle 补齐：

- 只有 2 张照片：显示 2 张照片，不显示视频；
- 只有 1 张照片和 1 段视频：显示现有两项；
- 只有 1 段视频：只显示视频；
- 没有成功媒体：隐藏该 clip 的照片/视频折叠内容。

## 9. 详情页行为

`EventDetailActivity` 不再分别调用“最近照片”和“最近视频”匹配器，而是一次取得
一个 `MatchedCaptureBundle`，其中包含：

- `photoPaths`：0–2 项；
- `videoPaths`：0–1 项；
- bundle 标识和时间，仅供日志或调试使用。

现有缩略图、照片查看器和视频播放器继续复用，不改变视觉风格。照片按照第一张、
第二张、视频的稳定顺序呈现；若某项缺失则自然收缩，不显示占位内容。

## 10. 设置与兼容性

设置页移除 `trigger_photo_count` 滑杆，避免用户误以为 bundle 数量可变。

配置模型仍临时保留字段解析，以兼容已安装版本的 SharedPreferences 和导出的
配置 JSON。无论旧值为 0–6 中的任何值，运行时采集数量都固定为 2。

这是数据格式向后兼容、行为向统一产品定义收敛的变更。

## 11. 测试策略

### 11.1 纯单元测试

- 一个显式 bundle 返回 2 张照片和 1 段视频；
- 相邻两个 bundle 不发生照片/视频混拼；
- 选择 `±90s` 内距离最近的整个 bundle；
- 恰好 90 秒被包含，超过 90 秒被排除；
- 同一个 bundle 可被两个相邻 clip 选择；
- 显式 bundle 缺失媒体时不跨 bundle 补齐；
- 文件缺失时跳过对应媒体；
- 旧数据按 15 秒规则恢复 2+1；
- 旧数据视频缺失时可恢复 photo-only bundle；
- 平局选择稳定；
- 旧 `trigger_photo_count` 被规范为 2；
- bundle 字段正确序列化并经 repository 透传。

### 11.2 回归测试

- 现有 laughter 音频播放、增益、波形和进度测试；
- event 读取、详情编辑、删除和 resurfacing 测试；
- 通知与研究交互日志测试；
- 全量 `testDebugUnitTest`；
- `assembleDebug`。

## 12. 文档修正

以下内容统一改为“每个 clip 最多关联一个最近的采集 bundle；该 bundle 固定包含
最多 2 张照片和 1 段视频”：

- README 的采集、媒体关联和测试说明；
- 原 2026-07-29 laughter playback/media association 设计文档；
- 原实施计划中把 `CLIP_MEDIA_MAX_PER_TYPE = 1` 作为目标的描述。

历史实施计划保留其时间背景，同时增加显著的勘误说明和本规范链接，避免开发者把
旧错误继续当作当前需求。

## 13. 验收标准

1. 新检测成功采集时，event 数据中的 2 张照片和 1 段视频具有相同
   `bundle_id`。
2. 详情页中的 laughter clip 能同时显示同一 bundle 的 2 张照片和 1 段视频。
3. 多轮采集存在时不混合不同 bundle 的媒体。
4. 旧 event 在没有 `bundle_id` 时仍能按时间显示正确的 2+1 组合。
5. 窗口内没有 bundle 时不显示照片/视频内容，不使用远距离媒体填充。
6. 设置页不再允许修改单个 bundle 的照片数量。
7. 全量单元测试和 debug APK 构建通过。
