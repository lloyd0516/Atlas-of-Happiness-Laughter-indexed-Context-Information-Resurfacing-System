package com.hry.camera.usbcamerademo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class JoyfulMomentSpeechmaticsClient {
    public interface Listener {
        void onJsonMessage(JSONObject payload);
        void onError(String errorText);
    }

    private final OkHttpClient okHttpClient;
    private final String url;
    private final String apiKey;
    private final Listener listener;
    private final CountDownLatch openLatch = new CountDownLatch(1);
    private WebSocket webSocket;
    private int seqNo = 0;

    public JoyfulMomentSpeechmaticsClient(String url, String apiKey, Listener listener) {
        this.url = url;
        this.apiKey = apiKey;
        this.listener = listener;
        this.okHttpClient = new OkHttpClient.Builder().build();
    }

    public void connect(String language, String operatingPoint, String eventTypesCsv) {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .build();
        webSocket = okHttpClient.newWebSocket(request, new InternalListener(language, operatingPoint, eventTypesCsv));
    }

    public boolean awaitReady(long timeoutMs) {
        try {
            return openLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean sendAudioChunk(byte[] bytes) {
        boolean sent = webSocket != null && webSocket.send(okio.ByteString.of(bytes, 0, bytes.length));
        if (sent) {
            seqNo += 1;
        }
        return sent;
    }

    public void endStream() {
        if (webSocket == null) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("message", "EndOfStream");
            json.put("last_seq_no", seqNo);
            webSocket.send(json.toString());
        } catch (JSONException e) {
            listener.onError("Failed to send EndOfStream: " + e.getMessage());
        }
    }

    public void close() {
        if (webSocket != null) {
            webSocket.close(1000, "bye");
            webSocket = null;
        }
        okHttpClient.dispatcher().executorService().shutdown();
    }

    private class InternalListener extends WebSocketListener {
        private final String language;
        private final String operatingPoint;
        private final String eventTypesCsv;

        InternalListener(String language, String operatingPoint, String eventTypesCsv) {
            this.language = language;
            this.operatingPoint = operatingPoint;
            this.eventTypesCsv = eventTypesCsv;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            try {
                JSONObject start = new JSONObject();
                start.put("message", "StartRecognition");

                JSONObject audioFormat = new JSONObject();
                audioFormat.put("type", "raw");
                audioFormat.put("encoding", "pcm_s16le");
                audioFormat.put("sample_rate", 16000);
                start.put("audio_format", audioFormat);

                JSONObject transcriptionConfig = new JSONObject();
                transcriptionConfig.put("language", language);
                transcriptionConfig.put("operating_point", operatingPoint);
                transcriptionConfig.put("enable_partials", false);
                start.put("transcription_config", transcriptionConfig);

                JSONArray eventTypes = new JSONArray();
                String[] split = eventTypesCsv.split(",");
                for (String item : split) {
                    String trimmed = item.trim();
                    if (!trimmed.isEmpty()) {
                        eventTypes.put(trimmed);
                    }
                }
                JSONObject audioEventsConfig = new JSONObject();
                audioEventsConfig.put("types", eventTypes);
                start.put("audio_events_config", audioEventsConfig);

                webSocket.send(start.toString());
                openLatch.countDown();
            } catch (JSONException e) {
                listener.onError("Failed to build StartRecognition: " + e.getMessage());
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                listener.onJsonMessage(new JSONObject(text));
            } catch (JSONException e) {
                listener.onError("Bad Speechmatics JSON: " + e.getMessage());
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            listener.onError("Speechmatics websocket failure: " + t.getMessage());
            openLatch.countDown();
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            openLatch.countDown();
        }
    }
}
