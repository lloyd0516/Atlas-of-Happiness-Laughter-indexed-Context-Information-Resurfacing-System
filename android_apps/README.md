# Android Apps

本目录用于整理当前项目的两版 Android App 独立工程，方便分别维护、构建、安装和说明。

## 目录说明

- `0.0_legacy_usb_camera_demo/`
  - 旧版基础验证工程
  - 主要用于 USB camera / mic 接入、preview、拍照、录像、Joyful trigger 等核心链路验证
  - applicationId：`com.hry.camera.usbcamerademo`

- `1.0_atlas_of_happiness/`
  - 新版 Atlas of Happiness 工程
  - 在旧版核心功能基础上，增加了新的 UI、设置页、事件回看、日志查看和地图回看预留结构
  - applicationId：`com.hry.camera.atlasofhappiness`

> 原始开发工程仍保留在：`android_build/USBCameraDemo_ascii`  
> `android_apps/` 下是整理后的独立版本工程快照。

---

## 整体 pipeline

```text
USB 外接设备
├─ camera video
└─ mic audio
        ↓
Android phone（USB Host）
        ↓
Android app
        ↓
Speechmatics realtime laughter detection
        ↓
Detection -> Period -> Event
        ↓
自动采集与存储
├─ video
├─ photo
├─ laughter audio
└─ related speech context audio
        ↓
本地 event json / log / media
        ↓
前端回看
├─ time-level review
└─ map-level review（1.0 预留）
```

---

## API / 服务状态

### 已接入

- Speechmatics Realtime API
  - 用于 laughter detection
  - 0.0 和 1.0 均使用

### 已预留但未完成

- Google Maps API
  - 1.0 中已有地图回看模块入口
  - 目前仍缺正式 key 和最终接入配置

- Google Weather API
  - 1.0 中 context weather 已留接口
  - 目前仍缺正式 key

### 当前缺失信息

- Google Maps key
- Google Weather key
- release signing / keystore 信息

---

## Joyful config 参数说明

Android 1.0 的 Joyful settings 页面，参数设计对齐以下配置文件：

- `joyful_moment_project_snapshot_20260424/python_pipeline/tools/joyful_moment/config.json`

主要参数如下：

- `chunk_ms`
  - 音频分块时长
  - 影响流式发送频率

- `clip_duration_s`
  - 单个 period clip 的时长
  - 影响 period 切分与后续回看

- `context_neighbor_clips`
  - 可被视为上下文的相邻 clip 数量
  - 影响 speech context 关联范围

- `event_window_s`
  - event 合并窗口
  - 多个 laughter period 会在该时间范围内合并为同一 event

- `trigger_video_duration_s`
  - laughter 触发后自动录像时长

- `trigger_photo_count`
  - laughter 触发后自动拍照数量

- `detection_level`
  - 预设等级
  - 支持：`frequent / medium / sparse / custom`

- `speechmatics_language`
  - Speechmatics 使用语言

- `speechmatics_operating_point`
  - Speechmatics 运行模式

- `speechmatics_max_delay_s`
  - 最大延迟设置

- `output_root`
  - 输出根目录语义字段

- `camera_brightness`
  - UVC 相机亮度
  - 不是手机屏幕亮度

### detection level 含义

- `frequent`
  - 更敏感
  - clip 更短
  - 更容易触发 event

- `medium`
  - 当前默认平衡配置

- `sparse`
  - 更稀疏
  - clip 更长
  - 触发更少

- `custom`
  - 前端手动调整全部参数

---

## 工程使用方法

### 环境准备

建议准备：

- Android Studio
- JDK 8
- Android SDK

每个独立工程目录下都提供了：

- `local.properties.example`

可复制为 `local.properties` 后使用。

### 建议环境变量

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-8.0.482.8-hotspot"
$env:SPEECHMATICS_API_KEY="<YOUR_KEY>"
$env:SPEECHMATICS_RT_URL="wss://eu2.rt.speechmatics.com/v2"
$env:GOOGLE_MAPS_API_KEY="<OPTIONAL>"
$env:GOOGLE_WEATHER_API_KEY="<OPTIONAL>"
```

### 0.0 构建

```powershell
cd android_apps/0.0_legacy_usb_camera_demo
.\gradlew.bat :app:assembleDebug
```

### 1.0 构建

```powershell
cd android_apps/1.0_atlas_of_happiness
.\gradlew.bat :atlasapp:assembleDebug
```

### 安装示例

0.0：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

1.0：

```powershell
adb install -r .\atlasapp\build\outputs\apk\debug\atlasapp-debug.apk
```

---

## 两个版本的区别

### 0.0：Legacy USB Camera Demo

特点：

- 单页旧版 UI
- 结构直接
- 更适合底层验证

适合场景：

- USB camera 是否能打开
- preview 是否正常
- photo / record 是否能保存
- Joyful trigger 是否跑通

### 1.0：Atlas of Happiness

特点：

- 多页面新版 UI
- 有 Home、Preview、Settings、Logs、Event Review、Map Review
- 更适合展示和整理后的前端流程

适合场景：

- demo 展示
- 事件回看
- Joyful 参数调节
- 用户补充文本、语音、照片
- 手机端查看日志

---

## 当前建议

如果后续继续推进，建议分工如下：

- `0.0`：继续承担底层 USB / preview / record 稳定性验证
- `1.0`：继续承担 UI、review、context、地图与展示相关开发
