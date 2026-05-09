# Speechmatics Realtime Laughter Demo

这个目录提供一个独立的实时笑声检测 demo 骨架，目标是把 Speechmatics `audio_events` 的实时能力接进当前仓库，并提供三种输入方式：

- 浏览器麦克风输入：打开本地 demo 页面，授权麦克风后做实时 laughter detection
- HTTP 文件输入：上传一个 `16k / mono / 16-bit PCM WAV` 文件，服务端按实时节奏推送给 Speechmatics
- WebSocket 音频流输入：客户端先发送一条配置消息，再持续发送 `pcm_s16le` 二进制音频块

## 目录结构

```text
speechmatics_demo/
  app/
    audio_sources.py
    config.py
    contracts.py
    main.py
    service.py
    speechmatics_client.py
  .env.example
  requirements.txt
  README.md
```

## 安装

```bash
pip install -r speechmatics_demo/requirements.txt
```

把环境变量写进你自己的 shell 或 `.env` 载入逻辑中：

```bash
SPEECHMATICS_API_KEY=...
SPEECHMATICS_RT_URL=wss://eu2.rt.speechmatics.com/v2
SPEECHMATICS_LANGUAGE=en
SPEECHMATICS_EVENT_TYPES=laughter
SPEECHMATICS_OUTPUT_DIR=speechmatics_demo/output
```

## 启动

```bash
uvicorn speechmatics_demo.app.main:app --host 127.0.0.1 --port 8010
```

启动后直接打开：

```text
http://127.0.0.1:8010/
```

## 浏览器麦克风 Demo

页面会做这些事：

- 请求浏览器麦克风权限
- 把麦克风音频降采样到 `16k`
- 转成 `pcm_s16le`
- 通过 `WS /api/v1/laughter/stream` 发给服务端
- 实时显示 `laughter detected`、当前 open segment，以及完整的 `[start_time, end_time]` 结构化事件

推荐测试方式：

1. 启动服务后打开浏览器页面
2. 点击 `Start Microphone`
3. 在电脑麦克风旁播放测试音频
4. 观察页面中的 `Realtime State`、`Latest Output` 和 `Laughter Events`

当检测到 laughter 时，页面会先进入实时状态：

```json
{
  "message": "laughter detected",
  "start_time": 12.34,
  "end_time": null,
  "confidence": 0.91,
  "event_type": "laughter"
}
```

当 laughter 结束后，会生成一条简单结构化结果：

```json
{
  "message": "laughter detected",
  "start_time": 12.34,
  "end_time": 13.71,
  "confidence": 0.91,
  "channel": null,
  "event_type": "laughter"
}
```

浏览器 session 结束后，页面会自动把当前事件列表保存为本地 JSON，默认输出到：

```text
speechmatics_demo/output/<session_id>.json
```

## 输入接口

### 1. HTTP: 上传 wav 文件做实时测试

`POST /api/v1/laughter/from-file`

表单字段：

- `file`: `16k / mono / 16-bit PCM WAV`
- `pace_realtime`: 是否按实时速度发送，默认 `true`
- `chunk_ms`: 每次发送多少毫秒音频，默认 `200`

PowerShell 示例：

```powershell
curl.exe -X POST "http://127.0.0.1:8010/api/v1/laughter/from-file?pace_realtime=true&chunk_ms=200" `
  -F "file=@raw/2000_01_02_09_22_00.wav"
```

返回示例：

```json
{
  "session_id": "5cb65d3f2c8b4d87b2446b2d949f4ee8",
  "source": "upload:2000_01_02_09_22_00.wav",
  "events": [
    {
      "phase": "started",
      "event_type": "laughter",
      "start_time": 12.4,
      "end_time": null,
      "confidence": 0.91,
      "channel": null,
      "raw_message": "AudioEventStarted"
    }
  ],
  "message_count": 42
}
```

### 2. WebSocket: 实时 PCM 输入

`WS /api/v1/laughter/stream`

第一帧必须是 JSON 文本配置：

```json
{
  "sample_rate": 16000,
  "encoding": "pcm_s16le",
  "language": "en",
  "chunk_ms": 200,
  "channels": 1,
  "event_types": ["laughter"]
}
```

之后持续发送二进制音频帧，格式必须与配置一致。

客户端可以在结束时发送：

```json
{"message":"end"}
```

服务端会回推这些消息类型：

- `session.started`
- `recognition.started`
- `audio_event.started`
- `audio_event.ended`
- `session.completed`
- `speechmatics.message`
- `error`

服务端推送示例：

```json
{
  "type": "audio_event.started",
  "data": {
    "phase": "started",
    "event_type": "laughter",
    "start_time": 5.22,
    "end_time": null,
    "confidence": 0.88,
    "channel": null,
    "raw_message": "AudioEventStarted"
  }
}
```

### 3. 本地 ws 测试脚本

如果你想先不写前端，直接验证本地服务输入协议，可以运行：

```bash
python speechmatics_demo/examples/stream_wav_to_local_ws.py --file raw/2000_01_02_09_22_00.wav
```

### 4. 用 4 小时长音频里的 30min-1h 做 realtime 模拟

仓库里的 [2000_01_01_21_54_53.wav](d:/Projects/laughter-detection/raw/2000_01_01_21_54_53.wav) 是 4 小时音频，下面这个脚本会截取 `30:00-60:00` 这一段，并按 realtime 节奏推给本地 ws 接口：

```bash
python speechmatics_demo/examples/stream_wav_segment_to_local_ws.py
```

等价参数写法：

```bash
python speechmatics_demo/examples/stream_wav_segment_to_local_ws.py ^
  --file raw/2000_01_01_21_54_53.wav ^
  --start-sec 1800 ^
  --end-sec 3600 ^
  --chunk-ms 200
```

如果你想测试别的窗口，比如 `1h-1h10m`：

```bash
python speechmatics_demo/examples/stream_wav_segment_to_local_ws.py --start-sec 3600 --end-sec 4200
```

## 当前实现假设

- 文件输入先只支持 `16k / mono / 16-bit PCM WAV`
- 流式输入先只支持 `pcm_s16le`
- 默认只请求 `laughter` 事件，避免把 `music` / `applause` 混进测试结果

## 对接建议

如果你下一步要接浏览器录音或一个前端 demo，可以直接对 `WS /api/v1/laughter/stream` 发 `Int16 PCM` 音频块。浏览器通常需要先把 `Float32` 麦克风数据转换成 `Int16 little-endian` 再发送。
