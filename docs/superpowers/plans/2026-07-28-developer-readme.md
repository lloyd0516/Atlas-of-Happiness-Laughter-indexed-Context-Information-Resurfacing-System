# Atlas 2.0 中文开发者 README Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the one-line README with a factual Chinese developer guide that lets a new collaborator understand, build, test, and extend Atlas 2.0.

**Architecture:** Keep the documentation self-contained in the repository root `README.md`. Present the product model before implementation details, use one Mermaid flowchart for the end-to-end pipeline, and derive all technical claims from the current `user-study-prototype` source rather than historical 1.1 behavior.

**Tech Stack:** GitHub-flavored Markdown, Mermaid, Android/Java source references, Gradle verification.

## Global Constraints

- Write Chinese prose while preserving exact class names, JSON fields, commands, and necessary English terms.
- State that `user-study-prototype` is Atlas 2.0 and the `master` branch contains the older 1.1 app.
- Treat “push/resurfacing” as local Android notifications, not a remote push service.
- Do not include real API keys, personal SDK/JDK paths, nonexistent screenshots, CI badges, releases, or license claims.
- Describe only behavior implemented in the current source.
- Keep the generated `artifacts/` APK outside the README commit.
- Do not push until the user explicitly authorizes it.

---

### Task 1: Write and verify the developer README

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: current `atlasapp` source, `AndroidManifest.xml`, `AppConfig`, Gradle configuration, and launcher icon.
- Produces: the repository’s canonical onboarding document for collaborating developers.

- [ ] **Step 1: Confirm the current README is incomplete**

Run:

```sh
rg -n '^## (项目定位|核心用户流程|系统架构|构建与运行)' README.md
```

Expected: no matches because the current README contains only the sentence identifying 2.0.

- [ ] **Step 2: Replace README with the approved information architecture**

Write these sections in this order:

```text
title + launcher icon
project status callout
项目定位
核心用户流程
主要页面与功能
系统架构与数据流
输入、处理与输出
Moment 数据结构
Resurfacing 机制
代码导航
环境要求
API Key 配置
构建与运行
获取与安装 APK
权限与硬件
测试
常见问题与已知限制
数据与隐私
协作约定
```

Use this exact project framing:

```text
Atlas of Happiness 2.0 是一个 Android 研究原型：被动捕捉包含 laughter 的 positive
moments，以笑声作为索引组织情境信息，并在用户授权后通过短期、长期和同地点提醒帮助
moment resurfacing。
```

Add this branch warning near the top:

```markdown
> [!IMPORTANT]
> 当前开发版本位于 `user-study-prototype`。`master` 分支下的 1.1 不是最新版。
```

Show the icon with a repository-relative path:

```markdown
<img src="atlasapp/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
     alt="Atlas of Happiness" width="112">
```

- [ ] **Step 3: Document the product flow and pages**

The core flow must cover:

```text
start recording
USB camera + 16 kHz mono PCM capture
Speechmatics realtime laughter detection
event aggregation and automatic context/media capture
stop recording
user supplement questions
delete / save_push / save_no_push decision
map / calendar / timeline review
short / long / same-place resurfacing
event editing and permanent deletion
```

The page table must include:

```text
MainActivity — Record, preview, status, today statistics, recent moments
EventSupplementActivity — three optional context questions and save decision
ReviewShellActivity — Map, Calendar, Timeline
EventDetailActivity — short/long reconstruction, media/context edits, delete
MeActivity — language and independent daily/location reminder switches
SettingsActivity — detection, Speechmatics, capture, context, and camera tuning
LogViewerActivity — developer diagnostics
```

- [ ] **Step 4: Add the architecture diagram and input/output reference**

Use one Mermaid `flowchart TD` with these boundaries:

```text
USB camera / microphone
MainActivity + JoyfulMomentController
JoyfulMomentRealtimeEngine
JoyfulMomentSpeechmaticsClient
JoyfulMomentClusterer / JoyfulMomentEventStore
AtlasContextResolver
event JSON + captured_media + logs
AtlasReviewRepository
Review UI
daily/location reminder policy and Android notifications
```

Document inputs and outputs in a table. Include:

```text
inputs: USB video, 16 kHz mono PCM, Speechmatics events, GPS, AMap reverse geocoding,
OpenWeather context, user notes/audio/photos/social context/save decision
outputs: event JSON, audio windows, copied event media, session logs, local notifications
```

- [ ] **Step 5: Add a minimal truthful Moment JSON example**

Use a compact example containing these exact top-level fields:

```json
{
  "event_id": "participant_event_...",
  "start_time_ms": 0,
  "end_time_ms": 0,
  "period_ids": [],
  "auto_captured": {
    "audio_clips": [],
    "photos": [],
    "videos": []
  },
  "derived_context": {
    "gps": {},
    "weather": {}
  },
  "user_generated": {
    "notes": [],
    "audio_notes": [],
    "photos": [],
    "social_context": {
      "with_whom": "",
      "doing_what": "",
      "mood": ""
    }
  },
  "save_decision": {
    "action": "save_push"
  }
}
```

Explain that `save_push` is notification-eligible, `save_no_push` remains reviewable without
notifications, and deletion removes the event plus app-owned event media.

- [ ] **Step 6: Document current resurfacing behavior**

Include the exact defaults from `AppConfig`:

```text
Short: daily around 19:30, yesterday, notification ID 2101, opens forced short detail
Long: daily around 19:30, seven calendar days ago, notification ID 2102, opens forced long detail
Location: 50 m radius, backing event at least 6 h old, once per location per local day,
          global 2 h cooldown, opens Map centered on the place
```

Explain the Basic selection order:

```text
save_push → target calendar day → user supplement presence → media count →
distance from 19:30 → eventId
```

State that both switches default on and can be disabled independently in Me.

- [ ] **Step 7: Add build, configuration, permissions, and test guidance**

State the current build facts:

```text
single module: atlasapp
applicationId: com.hry.camera.atlasofhappiness
versionName: 2.0-main
minSdk: 22
target/compile SDK: 28
Android Gradle Plugin: 3.2.1
Gradle wrapper: 4.10.1
language: Java
UI: Android Support Library 28
network: OkHttp 3.12.13
```

Recommend environment variables first:

```sh
export SPEECHMATICS_API_KEY="..."
export SPEECHMATICS_RT_URL="wss://eu2.rt.speechmatics.com/v2"
export AMAP_API_KEY="..."
export OPENWEATHER_API_KEY="..."
```

Also show property names without values:

```properties
speechmatics.api.key=
speechmatics.api.url=
amap.api.key=
openweather.api.key=
```

Build and test commands:

```sh
./gradlew :atlasapp:assembleDebug
./gradlew :atlasapp:testDebugUnitTest
```

Add an APK section that distinguishes the repository artifact from source builds:

```text
repository path: artifacts/Atlas-of-Happiness-2.0-user-study-prototype-debug.apk
package: com.hry.camera.atlasofhappiness
build type: Debug research/test build
```

Explain two download/install paths:

```text
GitHub: open artifacts/ → select the APK → Download raw file → allow the browser/file manager
        to install unknown apps when Android prompts → open and grant required runtime permissions
ADB: adb install -r artifacts/Atlas-of-Happiness-2.0-user-study-prototype-debug.apk
```

State that collaborators can rebuild the same artifact with `assembleDebug`, whose Gradle output
is `atlasapp/build/outputs/apk/debug/atlasapp-debug.apk`. Do not claim that the debug APK is a
signed production release.

Explain that the legacy Gradle/AGP combination is most reliable with JDK 8, USB hardware is
needed for the full capture path, and real-device background notification/location behavior
must be smoke-tested.

- [ ] **Step 8: Run documentation safety and consistency checks**

Run:

```sh
git diff --check -- README.md
rg -n 'TBD|TODO|待补充|/Users/|E:\\\\' README.md
rg -n '^## (项目定位|核心用户流程|主要页面与功能|系统架构与数据流|输入、处理与输出|Moment 数据结构|Resurfacing 机制|代码导航|构建与运行|测试)' README.md
test -f atlasapp/src/main/res/mipmap-xxxhdpi/ic_launcher.png
test -f artifacts/Atlas-of-Happiness-2.0-user-study-prototype-debug.apk
```

Expected:

```text
git diff --check: exit 0
secret/path/placeholder scan: no matches
required heading scan: all headings present
icon target: exit 0
```

- [ ] **Step 9: Run project verification**

Temporarily point `local.properties` at an available Android SDK without changing any API-key
lines, configure JDK 8 through the local environment, then run:

```sh
sh gradlew :atlasapp:testDebugUnitTest :atlasapp:assembleDebug --console=plain --quiet
```

Expected: exit code 0. Restore `.gradle`, `atlasapp/build`, and `local.properties` afterward.

- [ ] **Step 10: Commit only the README**

Confirm `artifacts/` remains untracked and stage only:

```sh
git add README.md
git commit -m "docs: document Atlas 2.0 for collaborators"
```

Do not push.
