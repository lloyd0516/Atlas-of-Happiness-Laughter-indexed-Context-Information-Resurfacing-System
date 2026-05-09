# Atlas of Happiness: Laughter-indexed Context Information Resurfacing System

本仓库包含两个主要部分：

1. `android_apps/1.1_main/`  
   当前稳定的 Android App 版本。它通过 Speechmatics realtime API 检测 laughter，并围绕 laughter event 自动采集照片、视频和上下文音频。

2. `speechmatics_demo/`  
   浏览器/本地 websocket 版本的 Speechmatics realtime laughter detection demo，用于快速验证 API、音频流和 laughter event 返回。

---

## 当前主版本：Android App 1.1 Main

主工程目录：

```text
android_apps/1.1_main
```

Android module：

```text
atlasapp
```

包名：

```text
com.hry.camera.atlasofhappiness
```

详细 App pipeline、聚合逻辑、自动化记录逻辑、用户弹窗逻辑、输出文件结构和测试流程见：

```text
android_apps/1.1_main/README_APP_PIPELINE.md
```

聚合逻辑图：

```text
android_apps/1.1_main/docs/atlas_1_1_pipeline.svg
```

---

## 1.1 Main 核心设计

当前 App 使用两层语义聚合：

```text
detection-level -> event-level
```

- Speechmatics `AudioEventEnded(type=laughter)` 返回完整 `start_time / end_time / confidence` 后，App 根据阈值生成 laughter detection。
- 相邻 laughter detection 的 `start_sec` 间隔若 `<= 600s`，聚合到同一个 laughter event。
- 若 gap `> 600s`，当前 event 结束，新 detection 开启新 event。
- 自动拍照/录像由 accepted detection 触发，但按 2 个 30s clip 节流，即默认 60s 内最多触发一次。
- 用户输入弹窗在 event 结束后触发，而不是 detection 到来时触发。
- 30s `.wav` clip 仅用于 laughter/context 音频保存，不再作为用户层面的 period 聚合层。

---

## Android 构建

当前工程使用较旧但已验证可用的 Android 构建栈：

```text
Gradle wrapper: 4.10.1
Android Gradle Plugin: 3.2.1
compileSdkVersion: 28
targetSdkVersion: 28
```

建议使用 JDK 8 构建：

```powershell
cd android_apps/1.1_main
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-8.0.482.8-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :atlasapp:assembleDebug
```

APK 输出：

```text
android_apps/1.1_main/atlasapp/build/outputs/apk/debug/atlasapp-debug.apk
```

---

## API Key 配置

出于安全原因，仓库中不提交真实 Speechmatics API key。

可以通过以下方式配置：

1. 在 Android App 的 Settings 页面填写 Speechmatics API key；或
2. 构建时设置环境变量：

```powershell
$env:SPEECHMATICS_API_KEY='your_key_here'
$env:SPEECHMATICS_RT_URL='wss://eu2.rt.speechmatics.com/v2'
```

---

## Speechmatics Demo

`speechmatics_demo/` 包含本地浏览器 demo、websocket 服务和测试脚本。详见：

```text
speechmatics_demo/README.md
```

---

## 未纳入/不建议纳入 GitHub 的本地内容

本地 workspace 中可能存在大规模数据、实验输出、手机日志和第三方研究代码目录。这些内容不属于当前发布版本，通常不应上传：

```text
datasets_unified/
phone_runs/
run_logs/
output/
server_sync/
```
