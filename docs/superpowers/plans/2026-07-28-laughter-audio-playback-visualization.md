# Laughter Audio Playback Visualization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a compact play/pause control, a real PCM-derived waveform, and synchronized playback progress to laughter audio rows in the event review detail page.

**Architecture:** A pure-Java WAV extractor reads the existing 16-bit PCM files and returns normalized amplitudes. A focused custom Android `View` renders those amplitudes and playback progress, while a pure-Java playback state reducer makes play/pause/switch/completion behavior deterministic. `EventDetailActivity` remains the single owner of `MediaPlayer`, asynchronous waveform loading, and lifecycle cleanup.

**Tech Stack:** Android SDK 28, Java, Android `MediaPlayer`, custom `View`/`Canvas`, `ExecutorService`, JUnit 4.

## Global Constraints

- Keep all existing short-term and long-term review content, photo viewing, video playback, editing, and deletion behavior unchanged.
- Use the existing PCM WAV files directly; do not migrate or rewrite event JSON.
- This plan does not change recording gain, normalize audio, amplify playback, or re-encode files.
- At most one audio clip may play at a time.
- Do not add third-party dependencies.
- Preserve all pre-existing uncommitted device-status changes.
- Never stage `.superpowers/`, `artifacts/`, `.gradle/`, `atlasapp/build/`, or `local.properties`.
- Do not push; pushing remains a separate user-authorized action.

## File Structure

- Create `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasWavWaveformExtractor.java`: parse PCM WAV files and produce normalized amplitudes.
- Create `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasWavWaveformExtractorTest.java`: validate waveform extraction and malformed-file behavior.
- Create `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasAudioPlaybackState.java`: pure state reducer for play, pause, switch, completion, failure, and stop.
- Create `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasAudioPlaybackStateTest.java`: verify playback state transitions.
- Create `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasWaveformView.java`: draw compact real waveform and progress.
- Modify `atlasapp/src/main/res/layout/item_laughter_clip_card.xml`: replace static waveform images with `AtlasWaveformView` instances while retaining existing row IDs.
- Create `atlasapp/src/main/res/drawable/ic_atlas_pause_circle.xml`: pause-state icon matching the existing play icon.
- Modify `atlasapp/src/main/res/values/strings.xml`: English playback and error copy.
- Modify `atlasapp/src/main/res/values-zh/strings.xml`: Chinese playback and error copy.
- Modify `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java`: bind audio controls, load waveforms asynchronously, coordinate `MediaPlayer`, and update progress.

---

### Task 1: Real PCM WAV Waveform Extraction

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasWavWaveformExtractor.java`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasWavWaveformExtractorTest.java`

**Interfaces:**
- Consumes: a local `File` containing RIFF/WAVE PCM and a positive target bar count.
- Produces: `static float[] extract(File file, int barCount) throws IOException`, with exactly `barCount` values clamped to `0.0f..1.0f`.
- Produces: `static String cacheKey(File file)` using absolute path, length, and last-modified time.

- [ ] **Step 1: Write failing tests for valid, silent, and malformed WAV files**

Use `TemporaryFolder` and write small deterministic PCM WAV fixtures. The test must prove that the output comes from real samples:

```java
@Rule
public TemporaryFolder temporaryFolder = new TemporaryFolder();

@Test
public void extractsRequestedBarsFromRealPcmAmplitude() throws Exception {
    File wav = writePcm16MonoWav(new short[] {
            0, 0, 0, 0,
            12000, -12000, 10000, -10000
    }, 8);

    float[] bars = AtlasWavWaveformExtractor.extract(wav, 2);

    assertEquals(2, bars.length);
    assertTrue(bars[0] < 0.01f);
    assertTrue(bars[1] > 0.30f);
}

@Test
public void silentWavProducesStableZeroBars() throws Exception {
    File wav = writePcm16MonoWav(new short[] {0, 0, 0, 0}, 4);
    assertArrayEquals(new float[] {0f, 0f, 0f, 0f},
            AtlasWavWaveformExtractor.extract(wav, 4), 0.0001f);
}

@Test(expected = IOException.class)
public void rejectsNonWaveInput() throws Exception {
    File invalid = temporaryFolder.newFile("invalid.wav");
    AtlasWavWaveformExtractor.extract(invalid, 16);
}
```

The fixture helper must write a valid 44-byte PCM header and little-endian samples so the production parser—not a mock—is exercised.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasWavWaveformExtractorTest \
  --console=plain
```

Expected: FAIL because `AtlasWavWaveformExtractor` does not exist.

- [ ] **Step 3: Implement the WAV parser**

Implement RIFF chunk traversal instead of assuming that audio data always starts at byte 44:

```java
final class AtlasWavWaveformExtractor {
    static float[] extract(File file, int barCount) throws IOException {
        if (file == null || barCount <= 0) {
            throw new IOException("Invalid waveform input");
        }
        RandomAccessFile input = new RandomAccessFile(file, "r");
        try {
            WavInfo info = readWavInfo(input);
            if (info.audioFormat != 1 || info.bitsPerSample != 16) {
                throw new IOException("Only 16-bit PCM WAV is supported");
            }
            long frameCount = info.dataSize / Math.max(1, info.blockAlign);
            float[] peaks = new float[barCount];
            input.seek(info.dataOffset);
            for (long frame = 0; frame < frameCount; frame++) {
                int bucket = (int) Math.min(barCount - 1,
                        frame * barCount / Math.max(1, frameCount));
                int sample = readLittleEndianSignedShort(input);
                for (int channel = 1; channel < info.channels; channel++) {
                    int candidate = readLittleEndianSignedShort(input);
                    if (Math.abs(candidate) > Math.abs(sample)) {
                        sample = candidate;
                    }
                }
                peaks[bucket] = Math.max(peaks[bucket],
                        Math.abs(sample) / 32768.0f);
            }
            return peaks;
        } finally {
            input.close();
        }
    }

    static String cacheKey(File file) {
        return file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
    }
}
```

`readWavInfo` must verify `RIFF`, `WAVE`, locate `fmt ` and `data`, parse channel count/block alignment/bits per sample, and reject truncated chunks with `IOException`.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: all `AtlasWavWaveformExtractorTest` tests PASS.

- [ ] **Step 5: Commit only the extractor task**

```bash
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasWavWaveformExtractor.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasWavWaveformExtractorTest.java
git commit -m "feat: extract real waveforms from laughter audio"
```

---

### Task 2: Deterministic Playback State

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasAudioPlaybackState.java`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasAudioPlaybackStateTest.java`

**Interfaces:**
- Consumes: `State current`, `Event event`, and optional `String path`.
- Produces: immutable `State` with `String path` and `Status` (`IDLE`, `PLAYING`, `PAUSED`, `ERROR`).
- Produces: `static State transition(State current, Event event, String path)`.

- [ ] **Step 1: Write failing transition tests**

```java
@Test
public void sameClipTogglesBetweenPlayingAndPaused() {
    State playing = transition(idle(), PLAY_REQUESTED, "a.wav");
    assertEquals(PLAYING, playing.status);
    State paused = transition(playing, TOGGLE_REQUESTED, "a.wav");
    assertEquals(PAUSED, paused.status);
    State resumed = transition(paused, TOGGLE_REQUESTED, "a.wav");
    assertEquals(PLAYING, resumed.status);
}

@Test
public void differentClipReplacesCurrentPlayback() {
    State first = transition(idle(), PLAY_REQUESTED, "a.wav");
    State second = transition(first, PLAY_REQUESTED, "b.wav");
    assertEquals(PLAYING, second.status);
    assertEquals("b.wav", second.path);
}

@Test
public void completionStopAndFailureClearActivePath() {
    State playing = transition(idle(), PLAY_REQUESTED, "a.wav");
    State completed = transition(playing, COMPLETED, null);
    State stopped = transition(playing, STOPPED, null);
    State failed = transition(playing, FAILED, null);
    assertEquals(IDLE, completed.status);
    assertEquals(IDLE, stopped.status);
    assertEquals(ERROR, failed.status);
    assertNull(completed.path);
    assertNull(stopped.path);
    assertNull(failed.path);
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasAudioPlaybackStateTest \
  --console=plain
```

Expected: FAIL because `AtlasAudioPlaybackState` does not exist.

- [ ] **Step 3: Implement the minimal immutable reducer**

```java
final class AtlasAudioPlaybackState {
    enum Status { IDLE, PLAYING, PAUSED, ERROR }
    enum Event { PLAY_REQUESTED, TOGGLE_REQUESTED, COMPLETED, STOPPED, FAILED }

    static final class State {
        final String path;
        final Status status;
        State(String path, Status status) {
            this.path = path;
            this.status = status;
        }
    }

    static State transition(State current, Event event, String path) {
        State safe = current == null ? new State(null, Status.IDLE) : current;
        if (event == null) {
            return safe;
        }
        switch (event) {
            case PLAY_REQUESTED:
                return path == null || path.length() == 0
                        ? new State(null, Status.ERROR)
                        : new State(path, Status.PLAYING);
            case TOGGLE_REQUESTED:
                if (path != null && path.equals(safe.path)) {
                    if (safe.status == Status.PLAYING) {
                        return new State(path, Status.PAUSED);
                    }
                    if (safe.status == Status.PAUSED) {
                        return new State(path, Status.PLAYING);
                    }
                }
                return path == null || path.length() == 0
                        ? new State(null, Status.ERROR)
                        : new State(path, Status.PLAYING);
            case FAILED:
                return new State(null, Status.ERROR);
            case COMPLETED:
            case STOPPED:
            default:
                return new State(null, Status.IDLE);
        }
    }
}
```

Do not put Android types, `MediaPlayer`, or view references in this class.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: all `AtlasAudioPlaybackStateTest` tests PASS.

- [ ] **Step 5: Commit only the state task**

```bash
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasAudioPlaybackState.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasAudioPlaybackStateTest.java
git commit -m "feat: model laughter audio playback state"
```

---

### Task 3: Compact Real-Waveform View and Layout

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasWaveformView.java`
- Create: `atlasapp/src/main/res/drawable/ic_atlas_pause_circle.xml`
- Modify: `atlasapp/src/main/res/layout/item_laughter_clip_card.xml:91-157`
- Modify: `atlasapp/src/main/res/layout/item_laughter_clip_card.xml:222-266`

**Interfaces:**
- Consumes: `setAmplitudes(float[])`, `setProgress(float)`, and `setPlaybackActive(boolean)`.
- Produces: a compact waveform view that invalidates and redraws whenever amplitudes or progress change.
- Layout IDs produced: `waveClipLaughter`, `waveClipContextShort`, `waveClipContextLong`; existing play image and duration text IDs remain unchanged.

- [ ] **Step 1: Add a focused geometry test to the extractor test suite**

Add assertions proving progress is clamped and amplitude-to-height conversion preserves a minimum visible bar:

```java
@Test
public void displayHeightKeepsQuietSamplesVisibleAndClampsPeak() {
    assertEquals(2f, AtlasWaveformView.computeBarHeight(0f, 24f, 2f), 0.001f);
    assertEquals(12f, AtlasWaveformView.computeBarHeight(0.5f, 24f, 2f), 0.001f);
    assertEquals(24f, AtlasWaveformView.computeBarHeight(2f, 24f, 2f), 0.001f);
}

@Test
public void progressIsClampedToUnitInterval() {
    assertEquals(0f, AtlasWaveformView.clampProgress(-1f), 0.001f);
    assertEquals(0.5f, AtlasWaveformView.clampProgress(0.5f), 0.001f);
    assertEquals(1f, AtlasWaveformView.clampProgress(2f), 0.001f);
}
```

Keep these two static helpers package-visible and free of Android runtime calls so JVM tests execute them directly.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasWavWaveformExtractorTest \
  --console=plain
```

Expected: FAIL because `AtlasWaveformView` does not exist.

- [ ] **Step 3: Implement the waveform view**

`AtlasWaveformView` must:

```java
public final class AtlasWaveformView extends View {
    private float[] amplitudes = new float[0];
    private float progress;
    private boolean playbackActive;

    public void setAmplitudes(float[] values) {
        amplitudes = values == null ? new float[0] : values.clone();
        invalidate();
    }

    public void setProgress(float value) {
        progress = clampProgress(value);
        invalidate();
    }

    public void setPlaybackActive(boolean active) {
        playbackActive = active;
        invalidate();
    }
}
```

In `onDraw`, center vertical rounded bars with a `2dp` minimum height. Draw bars before `progress * amplitudes.length` in `mock_orange_dark`/the existing coral tone and remaining bars in `mock_border`. Do not animate heights; “dynamic” means real playback progress advancing through the real waveform.

- [ ] **Step 4: Replace static waveform images in the XML**

For all three audio rows, retain the current row, play icon, and duration text IDs. Replace the unnamed static waveform `ImageView` with:

```xml
<com.hry.camera.usbcamerademo.AtlasWaveformView
    android:id="@+id/waveClipLaughter"
    android:layout_width="0dp"
    android:layout_height="24dp"
    android:layout_marginStart="10dp"
    android:layout_weight="1" />
```

Use `waveClipContextShort` and `waveClipContextLong` for the two context rows.

Create `ic_atlas_pause_circle.xml` using the same circle color and dimensions as `ic_atlas_play_circle.xml`, with two centered vertical white bars.

- [ ] **Step 5: Run the focused test and compile resources**

Run the focused test from Step 2, then:

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:assembleDebug --console=plain
```

Expected: tests PASS and APK assembly exits `0`.

- [ ] **Step 6: Commit only the view and layout task**

```bash
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasWaveformView.java \
  atlasapp/src/main/res/drawable/ic_atlas_pause_circle.xml \
  atlasapp/src/main/res/layout/item_laughter_clip_card.xml
git commit -m "feat: render compact laughter waveforms"
```

---

### Task 4: MediaPlayer Integration, Progress, and Errors

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasAudioPlaybackState.java`
- Modify: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasAudioPlaybackStateTest.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java:75-161`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java:327-458`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java:758-835`
- Modify: `atlasapp/src/main/res/values/strings.xml`
- Modify: `atlasapp/src/main/res/values-zh/strings.xml`

**Interfaces:**
- Consumes: `AtlasWavWaveformExtractor.extract(File, int)`, `AtlasWavWaveformExtractor.cacheKey(File)`, `AtlasAudioPlaybackState.transition(State, Event, String)`, and the waveform view setters.
- Produces: one `AudioRowBinding` per visible audio row, with `path`, play icon, waveform view, and time text.
- Produces: actual play/pause/resume/switch/completion/error behavior.

- [ ] **Step 1: Write failing progress and elapsed-time tests**

Add these tests to `AtlasAudioPlaybackStateTest`:

```java
@Test
public void playbackProgressClampsToUnitInterval() {
    assertEquals(0f, AtlasAudioPlaybackState.progress(10L, 0L), 0.001f);
    assertEquals(0.5f, AtlasAudioPlaybackState.progress(500L, 1000L), 0.001f);
    assertEquals(1f, AtlasAudioPlaybackState.progress(2000L, 1000L), 0.001f);
}

@Test
public void elapsedTimeUsesMinuteSecondFormat() {
    assertEquals("00:00", AtlasAudioPlaybackState.formatTime(0L));
    assertEquals("01:05", AtlasAudioPlaybackState.formatTime(65000L));
}
```

Run the focused state test command from Task 2.

Expected: FAIL because `progress(long, long)` and `formatTime(long)` do not exist.

- [ ] **Step 2: Implement progress calculation and time formatting**

Add to `AtlasAudioPlaybackState`:

```java
static float progress(long positionMs, long durationMs) {
    if (durationMs <= 0L) {
        return 0f;
    }
    return Math.max(0f, Math.min(1f, positionMs / (float) durationMs));
}

static String formatTime(long positionMs) {
    long totalSeconds = Math.max(0L, positionMs) / 1000L;
    return String.format(Locale.US, "%02d:%02d",
            totalSeconds / 60L, totalSeconds % 60L);
}
```

Run the focused state test again.

Expected: all `AtlasAudioPlaybackStateTest` tests PASS.

- [ ] **Step 3: Add playback strings**

Add matching English and Chinese strings:

```xml
<string name="audio_playback_play">Play audio</string>
<string name="audio_playback_pause">Pause audio</string>
<string name="audio_playback_unavailable">Audio file is unavailable.</string>
<string name="audio_playback_failed">Audio could not be played.</string>
```

Chinese:

```xml
<string name="audio_playback_play">播放音频</string>
<string name="audio_playback_pause">暂停音频</string>
<string name="audio_playback_unavailable">音频文件不可用</string>
<string name="audio_playback_failed">音频播放失败</string>
```

- [ ] **Step 4: Add the binding and waveform loader**

Inside `EventDetailActivity`, add:

```java
private static final int WAVEFORM_BAR_COUNT = 28;
private static final long AUDIO_PROGRESS_INTERVAL_MS = 80L;
private final ExecutorService waveformExecutor = Executors.newSingleThreadExecutor();
private final Map<String, float[]> waveformCache = new HashMap<>();
private final Handler audioUiHandler = new Handler();
private AudioRowBinding activeAudioBinding;
private String activeAudioPath;
private boolean audioPaused;
```

`AudioRowBinding` holds `View row`, `ImageView playIcon`, `AtlasWaveformView waveform`, `TextView time`, and `String path`.

`loadWaveformAsync(binding)` must:

1. Validate `new File(path).isFile()`.
2. Read cache by `AtlasWavWaveformExtractor.cacheKey(file)`.
3. Extract on `waveformExecutor`.
4. Post the result to the main thread only if the Activity is not finishing and the binding still has the same path.
5. On error, log through `devWarn`/`devError`, disable the row, and show the unavailable state without crashing.

- [ ] **Step 5: Replace `wireAudioClick` with binding-aware control**

Change calls in `bindClipCard` to provide the correct icon, waveform, and time views:

```java
wireAudioControl(
        laughterAudioRow,
        card.findViewById(R.id.imgClipLaughterPlay),
        card.findViewById(R.id.waveClipLaughter),
        laughterDuration,
        clip.optString("path", null));
```

Repeat for short and long context audio rows.

`wireAudioControl` must set content descriptions, load the waveform, and route clicks:

- no active path → start selected file;
- same path + playing → pause;
- same path + paused → resume;
- different path → reset old binding, release old player, and start selected file.

- [ ] **Step 6: Make playback asynchronous and observable**

Replace synchronous `prepare()` with `prepareAsync()`:

```java
mediaPlayer = new MediaPlayer();
mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
mediaPlayer.setDataSource(path);
mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
    @Override
    public void onPrepared(MediaPlayer player) {
        player.start();
        renderPlaying(binding);
        scheduleAudioProgress();
    }
});
mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
    @Override
    public void onCompletion(MediaPlayer player) {
        finishAudioPlayback(AtlasAudioPlaybackState.Event.COMPLETED, null);
    }
});
mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
    @Override
    public boolean onError(MediaPlayer player, int what, int extra) {
        RuntimeException error = new RuntimeException(
                "MediaPlayer error what=" + what + " extra=" + extra);
        finishAudioPlayback(AtlasAudioPlaybackState.Event.FAILED, error);
        Toast.makeText(EventDetailActivity.this,
                R.string.audio_playback_failed, Toast.LENGTH_SHORT).show();
        return true;
    }
});
mediaPlayer.prepareAsync();
```

Do not swallow exceptions. `catch` must log the path and exception and show `audio_playback_failed`.

Use one terminal helper for completion, failure, and explicit stop:

```java
private void finishAudioPlayback(
        AtlasAudioPlaybackState.Event event, Throwable error) {
    audioUiHandler.removeCallbacks(audioProgressRunnable);
    if (error != null) {
        devError("audio playback failed: " + activeAudioPath, error);
    }
    resetAudioBinding(activeAudioBinding);
    releaseMediaPlayer();
    playbackState = AtlasAudioPlaybackState.transition(playbackState, event, null);
    activeAudioBinding = null;
    activeAudioPath = null;
    audioPaused = false;
}
```

The progress runnable reads `getCurrentPosition()` and `getDuration()`, updates the waveform with `AtlasAudioPlaybackState.progress(position, duration)`, and updates elapsed text with `AtlasAudioPlaybackState.formatTime(position)`. It reposts itself only while the same player is active and playing.

- [ ] **Step 7: Complete lifecycle cleanup**

`stopAudioPlayback()` must:

- remove the progress runnable;
- reset the active row icon, progress, and time;
- stop only when the player is in a stoppable state;
- release the player;
- clear active path/binding/paused fields through the state reducer.

`onStop()` continues calling this method. Add `waveformExecutor.shutdownNow()` in `onDestroy()` rather than `onStop()` so returning from a temporary pause does not permanently disable waveform loading.

- [ ] **Step 8: Run focused and full tests**

Run:

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasAudioPlaybackStateTest \
  --tests com.hry.camera.usbcamerademo.AtlasWavWaveformExtractorTest \
  --console=plain
```

Then run:

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest :atlasapp:assembleDebug --console=plain
```

Expected: all unit tests PASS and debug APK assembly exits `0`.

- [ ] **Step 9: Commit only integration files**

```bash
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasAudioPlaybackState.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasAudioPlaybackStateTest.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java \
  atlasapp/src/main/res/values/strings.xml \
  atlasapp/src/main/res/values-zh/strings.xml
git commit -m "feat: add playback progress to laughter audio"
```

---

### Task 5: Fresh Verification and OPPO Device Acceptance

**Files:**
- Verify: all Task 1–4 files
- Do not modify unrelated files.

**Interfaces:**
- Consumes: assembled debug APK and connected OPPO device `64baced7`.
- Produces: evidence that waveform rendering and playback controls work on real saved moments.

- [ ] **Step 1: Run clean source checks**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; status contains only known pre-existing device-status work and ignored/untracked local artifacts.

- [ ] **Step 2: Run the complete unit suite and build**

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest :atlasapp:assembleDebug --console=plain
```

Expected: exit `0`; XML reports contain zero failures and zero errors.

- [ ] **Step 3: Install the debug APK without clearing app data**

```bash
/Users/fangjun/Library/Android/sdk/platform-tools/adb install -r \
  atlasapp/build/outputs/apk/debug/atlasapp-debug.apk
```

Expected: `Success`. The `-r` flag preserves the existing `session_20260728_132012` moment and its four laughter WAV files.

- [ ] **Step 4: Verify the four real laughter clips**

On the phone, open `00_event_1` and verify:

1. Each of the four laughter rows displays a waveform derived from its WAV file.
2. The waveforms are not all identical.
3. Tapping a row changes play to pause immediately after preparation.
4. The coral played region and `mm:ss` value advance.
5. Tapping pause freezes both progress and time.
6. Tapping again resumes from the same position.
7. Tapping another row resets the previous row and starts the new row.
8. Natural completion resets the active row.
9. Returning to the review page stops audio.
10. Photos and videos remain usable.

- [ ] **Step 5: Capture diagnostic evidence**

```bash
/Users/fangjun/Library/Android/sdk/platform-tools/adb logcat -c
/Users/fangjun/Library/Android/sdk/platform-tools/adb logcat \
  Atlas.EventDetail:V MediaPlayer:V '*:S'
```

Expected: playback preparation/completion logs, with no `MediaPlayer` error or uncaught exception during the acceptance flow.

- [ ] **Step 6: Restore generated tracked files**

After verification:

```bash
git restore -- .gradle atlasapp/build local.properties
git status --short
```

Expected: no Gradle cache, build-output, or machine-local SDK changes remain. Do not restore or overwrite the pre-existing device-status source changes.

- [ ] **Step 7: Report completion without pushing**

Report:

- unit-test count and zero failures/errors;
- debug build result;
- device model and the ten acceptance checks;
- local commit IDs created by Tasks 1–4;
- explicit statement that the low-volume issue remains separate;
- explicit statement that nothing was pushed.
