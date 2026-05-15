package com.hry.camera.usbcamerademo;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class JoyfulMomentRealtimeEngine {
    public interface Listener {
        void onSpeechmaticsMessage(JSONObject payload);
        void onAudioChunkSent(double offsetSec, int byteLen);
        void onAudioPcmChunk(byte[] pcm16le, int byteLen, int sampleRate, int channelCount);
        void onClipClosed(int clipId, double startSec, double endSec, File tmpPath);
        void onEngineInfo(JSONObject info);
        void onEngineError(String errorText);
        void onEngineStopped();
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private final Context context;
    private final JoyfulMomentConfig config;
    private final File sessionDir;
    private final String apiKey;
    private final String rtUrl;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread workerThread;
    private JoyfulMomentSpeechmaticsClient speechmaticsClient;

    public JoyfulMomentRealtimeEngine(
            Context context,
            JoyfulMomentConfig config,
            File sessionDir,
            String apiKey,
            String rtUrl,
            Listener listener
    ) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.sessionDir = sessionDir;
        this.apiKey = apiKey;
        this.rtUrl = rtUrl;
        this.listener = listener;
    }

    public void start() {
        if (running.get()) {
            return;
        }
        running.set(true);
        workerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop();
            }
        }, "JoyfulMomentRealtimeEngine");
        workerThread.start();
    }

    public void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }
    }

    private void runLoop() {
        AudioRecord audioRecord = null;
        JoyfulMomentWavClipWriter clipWriter = null;
        try {
            if (apiKey == null || apiKey.trim().isEmpty()) {
                listener.onEngineError("Missing Speechmatics API key in BuildConfig / env.");
                return;
            }

            int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
            int chunkBytes = SAMPLE_RATE * 2 * Math.max(1, config.chunkMs) / 1000;
            int bufferSize = Math.max(minBuffer, chunkBytes * 4);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SAMPLE_RATE, CHANNEL_CONFIG, ENCODING, bufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                listener.onEngineError("AudioRecord init failed.");
                return;
            }

            emitAvailableInputDevices();
            AudioDeviceInfo preferredDevice = chooseUsbInputDevice();
            if (preferredDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioRecord.setPreferredDevice(preferredDevice);
                JSONObject info = new JSONObject();
                info.put("type", "engine.audio.preferred_device");
                info.put("product_name", String.valueOf(preferredDevice.getProductName()));
                info.put("id", preferredDevice.getId());
                info.put("device_type", preferredDevice.getType());
                listener.onEngineInfo(info);
            } else {
                JSONObject info = new JSONObject();
                info.put("type", "engine.audio.preferred_device_missing");
                info.put("message", "No USB audio input device discovered; capture may fall back to phone mic.");
                listener.onEngineInfo(info);
            }

            clipWriter = new JoyfulMomentWavClipWriter(sessionDir, SAMPLE_RATE, 1, 2, config.clipDurationSec);
            speechmaticsClient = new JoyfulMomentSpeechmaticsClient(rtUrl, apiKey, new JoyfulMomentSpeechmaticsClient.Listener() {
                @Override
                public void onJsonMessage(JSONObject payload) {
                    listener.onSpeechmaticsMessage(payload);
                }

                @Override
                public void onError(String errorText) {
                    listener.onEngineError(errorText);
                }
            });
            speechmaticsClient.connect(config.speechmaticsLanguage, "enhanced", config.speechmaticsEventTypes);
            if (!speechmaticsClient.awaitReady(10000L)) {
                listener.onEngineError("Speechmatics websocket open timeout.");
                return;
            }

            audioRecord.startRecording();
            emitRoutedInputDevice(audioRecord);
            byte[] buffer = new byte[chunkBytes];
            long totalBytesSent = 0;
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                byte[] chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
                listener.onAudioPcmChunk(chunk, read, SAMPLE_RATE, 1);
                boolean sent = speechmaticsClient.sendAudioChunk(chunk);
                if (!sent) {
                    listener.onEngineError("Failed to send audio chunk to Speechmatics.");
                    break;
                }
                double offsetSec = totalBytesSent / (double) (SAMPLE_RATE * 2);
                listener.onAudioChunkSent(offsetSec, read);
                totalBytesSent += read;

                List<JoyfulMomentWavClipWriter.ClosedClip> closedClips = clipWriter.write(chunk, chunk.length);
                for (JoyfulMomentWavClipWriter.ClosedClip clip : closedClips) {
                    if (clip != null) {
                        listener.onClipClosed(clip.clipId, clip.startSec, clip.endSec, clip.path);
                    }
                }
            }

            if (speechmaticsClient != null) {
                speechmaticsClient.endStream();
            }
            if (clipWriter != null) {
                JoyfulMomentWavClipWriter.ClosedClip partial = clipWriter.finalizePartial();
                if (partial != null) {
                    listener.onClipClosed(partial.clipId, partial.startSec, partial.endSec, partial.path);
                }
            }
        } catch (IOException | JSONException e) {
            listener.onEngineError("Joyful realtime engine error: " + e.getMessage());
        } finally {
            running.set(false);
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                } catch (Exception ignored) {
                }
                audioRecord.release();
            }
            if (speechmaticsClient != null) {
                speechmaticsClient.close();
                speechmaticsClient = null;
            }
            listener.onEngineStopped();
        }
    }

    private AudioDeviceInfo chooseUsbInputDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return null;
        }
        AudioDeviceInfo[] infos = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (AudioDeviceInfo info : infos) {
            if (info.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || info.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return info;
            }
        }
        return null;
    }

    private void emitAvailableInputDevices() throws JSONException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        JSONArray devices = new JSONArray();
        AudioDeviceInfo[] infos = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (AudioDeviceInfo info : infos) {
            JSONObject item = new JSONObject();
            item.put("id", info.getId());
            item.put("type", info.getType());
            item.put("product_name", String.valueOf(info.getProductName()));
            devices.put(item);
        }
        JSONObject json = new JSONObject();
        json.put("type", "engine.audio.input_devices");
        json.put("devices", devices);
        listener.onEngineInfo(json);
    }

    private void emitRoutedInputDevice(AudioRecord audioRecord) throws JSONException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        AudioDeviceInfo routed = audioRecord.getRoutedDevice();
        JSONObject json = new JSONObject();
        json.put("type", "engine.audio.routed_device");
        if (routed == null) {
            json.put("message", "AudioRecord routed device unavailable.");
        } else {
            json.put("id", routed.getId());
            json.put("device_type", routed.getType());
            json.put("product_name", String.valueOf(routed.getProductName()));
        }
        listener.onEngineInfo(json);
    }
}
