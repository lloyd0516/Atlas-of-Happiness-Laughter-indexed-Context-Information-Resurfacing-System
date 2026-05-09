const state = {
  socket: null,
  audioContext: null,
  mediaStream: null,
  sourceNode: null,
  processorNode: null,
  currentSegment: null,
  events: [],
  sessionId: null,
  sessionStartedAt: null,
  language: "en",
  saveInFlight: false,
};

const elements = {
  startBtn: document.getElementById("startBtn"),
  stopBtn: document.getElementById("stopBtn"),
  clearBtn: document.getElementById("clearBtn"),
  languageInput: document.getElementById("languageInput"),
  chunkMsInput: document.getElementById("chunkMsInput"),
  connectionStatus: document.getElementById("connectionStatus"),
  detectorStatus: document.getElementById("detectorStatus"),
  activeSegment: document.getElementById("activeSegment"),
  latestOutput: document.getElementById("latestOutput"),
  eventList: document.getElementById("eventList"),
  eventCount: document.getElementById("eventCount"),
  logList: document.getElementById("logList"),
  timeline: document.getElementById("timeline"),
  saveStatus: document.getElementById("saveStatus"),
};

function wsUrl() {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${window.location.host}/api/v1/laughter/stream`;
}

function setConnectionStatus(text) {
  elements.connectionStatus.textContent = text;
}

function setDetectorStatus(text, active = false) {
  elements.detectorStatus.textContent = text;
  elements.detectorStatus.parentElement.classList.toggle("is-live", active);
}

function renderLatest(payload) {
  elements.latestOutput.textContent = JSON.stringify(payload, null, 2);
}

function setSaveStatus(text) {
  elements.saveStatus.textContent = text;
}

function addLog(text) {
  const row = document.createElement("div");
  row.className = "log-row";
  row.textContent = `${new Date().toLocaleTimeString()}  ${text}`;
  elements.logList.prepend(row);
}

function updateActiveSegment() {
  if (!state.currentSegment) {
    elements.activeSegment.textContent = "none";
    return;
  }
  const start = state.currentSegment.start_time?.toFixed?.(2) ?? state.currentSegment.start_time;
  elements.activeSegment.textContent = `[${start}, ...]`;
}

function renderEvents() {
  elements.eventCount.textContent = `${state.events.length} event${state.events.length === 1 ? "" : "s"}`;
  elements.eventList.innerHTML = "";

  if (state.events.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.textContent = "No laughter events yet.";
    elements.eventList.appendChild(empty);
    return;
  }

  [...state.events].reverse().forEach((event) => {
    const row = document.createElement("div");
    row.className = "event-row";

    const title = document.createElement("div");
    title.className = "event-title";
    title.textContent = event.message;

    const meta = document.createElement("pre");
    meta.className = "event-json";
    meta.textContent = JSON.stringify(event, null, 2);

    row.appendChild(title);
    row.appendChild(meta);
    elements.eventList.appendChild(row);
  });
}

function renderTimeline() {
  elements.timeline.innerHTML = "";
  if (state.events.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.textContent = "Timeline will appear after the first completed laughter segment.";
    elements.timeline.appendChild(empty);
    return;
  }

  const maxEndTime = Math.max(...state.events.map((event) => Number(event.end_time || event.start_time || 0)), 1);
  state.events.forEach((event, index) => {
    const row = document.createElement("div");
    row.className = "timeline-row";

    const label = document.createElement("div");
    label.className = "timeline-label";
    label.textContent = `#${index + 1} [${Number(event.start_time).toFixed(2)}, ${Number(event.end_time).toFixed(2)}]`;

    const track = document.createElement("div");
    track.className = "timeline-track";

    const bar = document.createElement("div");
    bar.className = "timeline-bar";
    const leftPct = (Number(event.start_time) / maxEndTime) * 100;
    const widthPct = ((Number(event.end_time) - Number(event.start_time)) / maxEndTime) * 100;
    bar.style.left = `${leftPct}%`;
    bar.style.width = `${Math.max(widthPct, 1.5)}%`;

    track.appendChild(bar);
    row.appendChild(label);
    row.appendChild(track);
    elements.timeline.appendChild(row);
  });
}

async function persistSession() {
  if (!state.sessionId || state.events.length === 0 || state.saveInFlight) {
    return;
  }

  state.saveInFlight = true;
  setSaveStatus("saving...");
  try {
    const response = await fetch("/api/v1/laughter/save-session", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        session_id: state.sessionId,
        started_at: state.sessionStartedAt,
        source: "browser_microphone",
        language: state.language,
        events: state.events,
      }),
    });
    const payload = await response.json();
    if (!response.ok) {
      throw new Error(payload.error || "Failed to save session.");
    }
    setSaveStatus(`saved: ${payload.path}`);
    addLog(`Saved JSON to ${payload.path}`);
  } catch (error) {
    setSaveStatus("save failed");
    addLog(`Save failed: ${error.message}`);
  } finally {
    state.saveInFlight = false;
  }
}

function clearEvents() {
  state.events = [];
  state.currentSegment = null;
  setDetectorStatus("waiting for laughter", false);
  setSaveStatus("not saved");
  updateActiveSegment();
  renderLatest({ message: "cleared" });
  renderEvents();
  renderTimeline();
  addLog("Cleared local event history.");
}

function downsampleBuffer(input, inputSampleRate, outputSampleRate) {
  if (outputSampleRate === inputSampleRate) {
    return input;
  }
  if (outputSampleRate > inputSampleRate) {
    throw new Error("Output sample rate must be less than or equal to input sample rate.");
  }

  const sampleRateRatio = inputSampleRate / outputSampleRate;
  const newLength = Math.round(input.length / sampleRateRatio);
  const result = new Float32Array(newLength);
  let offsetResult = 0;
  let offsetBuffer = 0;

  while (offsetResult < result.length) {
    const nextOffsetBuffer = Math.round((offsetResult + 1) * sampleRateRatio);
    let accum = 0;
    let count = 0;
    for (let i = offsetBuffer; i < nextOffsetBuffer && i < input.length; i += 1) {
      accum += input[i];
      count += 1;
    }
    result[offsetResult] = count > 0 ? accum / count : 0;
    offsetResult += 1;
    offsetBuffer = nextOffsetBuffer;
  }

  return result;
}

function floatTo16BitPCM(float32Array) {
  const buffer = new ArrayBuffer(float32Array.length * 2);
  const view = new DataView(buffer);
  for (let i = 0; i < float32Array.length; i += 1) {
    const sample = Math.max(-1, Math.min(1, float32Array[i]));
    view.setInt16(i * 2, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true);
  }
  return buffer;
}

async function handleServerMessage(event) {
  const payload = JSON.parse(event.data);
  renderLatest(payload);

  if (payload.type === "session.started") {
    state.sessionId = payload.data.session_id;
    state.sessionStartedAt = new Date().toISOString();
    state.language = payload.data.language;
    setConnectionStatus("session started");
    setSaveStatus("not saved");
    addLog(`Session started with language=${payload.data.language}`);
    return;
  }

  if (payload.type === "recognition.started") {
    setConnectionStatus("recognition started");
    addLog("Speechmatics recognition started.");
    return;
  }

  if (payload.type === "laughter.detected") {
    state.currentSegment = payload.data;
    setDetectorStatus("laughter detected", true);
    updateActiveSegment();
    addLog(`Laughter started at ${payload.data.start_time}`);
    return;
  }

  if (payload.type === "laughter.segment") {
    const merged = {
      message: "laughter detected",
      start_time: state.currentSegment?.start_time ?? null,
      end_time: payload.data.end_time ?? null,
      confidence: state.currentSegment?.confidence ?? payload.data.confidence ?? null,
      channel: payload.data.channel ?? null,
      event_type: "laughter",
    };
    state.events.push(merged);
    state.currentSegment = null;
    setDetectorStatus("waiting for laughter", false);
    updateActiveSegment();
    renderEvents();
    renderTimeline();
    addLog(`Laughter ended at ${payload.data.end_time}`);
    return;
  }

  if (payload.type === "session.completed") {
    setConnectionStatus("completed");
    await persistSession();
    addLog("Session completed.");
    return;
  }

  if (payload.type === "error") {
    setConnectionStatus("error");
    setDetectorStatus("error", false);
    addLog(`Error: ${payload.data.message}`);
  }
}

async function startMicrophoneDemo() {
  if (state.socket) {
    return;
  }

  const language = elements.languageInput.value.trim() || "en";
  const chunkMs = Number(elements.chunkMsInput.value) || 200;
  const targetSampleRate = 16000;

  setConnectionStatus("connecting");
  addLog("Requesting microphone access.");

  state.mediaStream = await navigator.mediaDevices.getUserMedia({
    audio: {
      channelCount: 1,
      echoCancellation: false,
      noiseSuppression: false,
      autoGainControl: false,
    },
    video: false,
  });

  state.socket = new WebSocket(wsUrl());
  state.socket.binaryType = "arraybuffer";

  await new Promise((resolve, reject) => {
    state.socket.onopen = resolve;
    state.socket.onerror = () => reject(new Error("WebSocket connection failed."));
  });

  state.socket.onmessage = handleServerMessage;
  state.socket.onclose = () => {
    setConnectionStatus("closed");
    state.socket = null;
  };

  state.socket.send(
    JSON.stringify({
      sample_rate: targetSampleRate,
      encoding: "pcm_s16le",
      language,
      chunk_ms: chunkMs,
      channels: 1,
      event_types: ["laughter"],
    }),
  );

  state.audioContext = new AudioContext();
  state.sourceNode = state.audioContext.createMediaStreamSource(state.mediaStream);
  state.processorNode = state.audioContext.createScriptProcessor(4096, 1, 1);

  state.processorNode.onaudioprocess = (audioProcessingEvent) => {
    if (!state.socket || state.socket.readyState !== WebSocket.OPEN) {
      return;
    }
    const inputData = audioProcessingEvent.inputBuffer.getChannelData(0);
    const downsampled = downsampleBuffer(inputData, state.audioContext.sampleRate, targetSampleRate);
    const pcm16 = floatTo16BitPCM(downsampled);
    state.socket.send(pcm16);
  };

  state.sourceNode.connect(state.processorNode);
  state.processorNode.connect(state.audioContext.destination);

  setConnectionStatus("streaming");
  setDetectorStatus("waiting for laughter", false);
  setSaveStatus("not saved");
  elements.startBtn.disabled = true;
  elements.stopBtn.disabled = false;
  addLog("Microphone stream started.");
}

async function stopMicrophoneDemo() {
  if (state.socket && state.socket.readyState === WebSocket.OPEN) {
    state.socket.send(JSON.stringify({ message: "end" }));
    state.socket.close();
  }

  if (state.processorNode) {
    state.processorNode.disconnect();
    state.processorNode = null;
  }
  if (state.sourceNode) {
    state.sourceNode.disconnect();
    state.sourceNode = null;
  }
  if (state.audioContext) {
    await state.audioContext.close();
    state.audioContext = null;
  }
  if (state.mediaStream) {
    state.mediaStream.getTracks().forEach((track) => track.stop());
    state.mediaStream = null;
  }

  state.currentSegment = null;
  updateActiveSegment();
  await persistSession();
  setDetectorStatus("stopped", false);
  setConnectionStatus("stopped");
  elements.startBtn.disabled = false;
  elements.stopBtn.disabled = true;
  addLog("Microphone stream stopped.");
}

elements.startBtn.addEventListener("click", async () => {
  try {
    await startMicrophoneDemo();
  } catch (error) {
    setConnectionStatus("error");
    addLog(`Startup failed: ${error.message}`);
    renderLatest({ type: "error", data: { message: error.message } });
    await stopMicrophoneDemo();
  }
});

elements.stopBtn.addEventListener("click", async () => {
  await stopMicrophoneDemo();
});

elements.clearBtn.addEventListener("click", clearEvents);

renderEvents();
renderTimeline();
