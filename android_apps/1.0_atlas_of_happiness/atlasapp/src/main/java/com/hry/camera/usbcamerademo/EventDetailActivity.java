package com.hry.camera.usbcamerademo;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class EventDetailActivity extends AppCompatActivity {
    private static final String TAG = "Atlas.EventDetail";
    private static final int REQ_LOCATION = 201;
    private static final int REQ_AUDIO = 202;
    private static final int REQ_PHOTO = 203;
    private static final int REQ_CAMERA = 204;

    private AtlasReviewRepository repository;
    private JSONObject eventJson;
    private String eventId;
    private String selectedPeriodId;
    private TextView headerTime;
    private LinearLayout timelineContainer;
    private LinearLayout autoMediaContainer;
    private LinearLayout notesContainer;
    private LinearLayout userMediaContainer;
    private TextView gpsView;
    private TextView weatherView;
    private ImageView imagePreview;
    private VideoView videoPreview;
    private TextView audioStatus;
    private EditText noteInput;
    private Button audioNoteButton;
    private MediaPlayer mediaPlayer;
    private MediaRecorder mediaRecorder;
    private String activeAudioPath;
    private String pendingPhotoPath;
    private final SimpleDateFormat fileFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_event_detail);
        AtlasDevLogger.session(this, TAG, AtlasDevLogger.buildSessionBanner("EventDetailActivity.onCreate"));
        repository = new AtlasReviewRepository(this);
        eventId = getIntent().getStringExtra("event_id");
        eventJson = repository.loadEventById(eventId);
        if (eventJson == null) {
            Toast.makeText(this, R.string.toast_no_event, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        headerTime = findViewById(R.id.txtHeaderTime);
        timelineContainer = findViewById(R.id.timelineContainer);
        autoMediaContainer = findViewById(R.id.autoMediaContainer);
        notesContainer = findViewById(R.id.notesContainer);
        userMediaContainer = findViewById(R.id.userMediaContainer);
        gpsView = findViewById(R.id.txtGps);
        weatherView = findViewById(R.id.txtWeather);
        imagePreview = findViewById(R.id.imagePreview);
        videoPreview = findViewById(R.id.videoPreview);
        audioStatus = findViewById(R.id.txtAudioStatus);
        noteInput = findViewById(R.id.inputNote);
        audioNoteButton = findViewById(R.id.btnAddAudio);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnRefreshContext).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshContext();
            }
        });
        findViewById(R.id.btnAddText).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveTextNote();
            }
        });
        audioNoteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAudioNote();
            }
        });
        findViewById(R.id.btnAddPhoto).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addPhotoNote();
            }
        });

        renderEvent();
    }

    @Override
    protected void onStop() {
        AtlasDevLogger.i(this, TAG, "onStop");
        stopAudioPlayback();
        stopAudioRecording(false);
        super.onStop();
    }

    private void devInfo(String message) {
        Log.i(TAG, message);
        AtlasDevLogger.i(this, TAG, message);
    }

    private void devWarn(String message) {
        Log.w(TAG, message);
        AtlasDevLogger.w(this, TAG, message);
    }

    private void devError(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
        AtlasDevLogger.e(this, TAG, message, throwable);
    }

    private void renderEvent() {
        headerTime.setText(repository.formatTimeRange(eventJson.optLong("start_time_ms"), eventJson.optLong("end_time_ms")));
        renderTimeline();
        renderAutoMedia();
        renderContext();
        renderUserGenerated();
    }

    private void renderTimeline() {
        timelineContainer.removeAllViews();
        addTimelineButton(getString(R.string.event_detail_period_all), null);
        JSONArray periodIds = eventJson.optJSONArray("period_ids");
        if (periodIds == null) {
            return;
        }
        for (int i = 0; i < periodIds.length(); i++) {
            addTimelineButton(periodIds.optString(i), periodIds.optString(i));
        }
    }

    private void addTimelineButton(String label, final String periodId) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setBackgroundResource(periodId == null ? R.drawable.atlas_chip_selected : R.drawable.atlas_chip);
        button.setTextColor(ContextCompat.getColor(this, R.color.atlas_text_primary));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        button.setLayoutParams(params);
        if (periodId != null && periodId.equals(selectedPeriodId)) {
            button.setBackgroundResource(R.drawable.atlas_chip_selected);
        }
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedPeriodId = periodId;
                renderTimeline();
                renderAutoMedia();
            }
        });
        timelineContainer.addView(button);
    }

    private void renderAutoMedia() {
        autoMediaContainer.removeAllViews();
        hideVisualPreview();
        audioStatus.setText(R.string.label_no_media);
        JSONObject auto = eventJson.optJSONObject("auto_captured");
        if (auto == null) {
            return;
        }
        addMediaButtons(auto.optJSONArray("videos"), "video_path", getString(R.string.label_video));
        addMediaButtons(auto.optJSONArray("photos"), "photo_path", getString(R.string.label_photo));
        addMediaButtons(auto.optJSONArray("audio_clips"), "path", getString(R.string.label_audio));
        if (autoMediaContainer.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.event_detail_media_empty);
            empty.setTextColor(ContextCompat.getColor(this, R.color.atlas_text_secondary));
            autoMediaContainer.addView(empty);
        }
    }

    private void addMediaButtons(JSONArray items, String pathKey, String prefix) {
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            final JSONObject item = items.optJSONObject(i);
            if (item == null || !matchesSelectedPeriod(item)) {
                continue;
            }
            final String path = item.optString(pathKey, null);
            if (TextUtils.isEmpty(path)) {
                continue;
            }
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setBackgroundResource(R.drawable.atlas_button_soft);
            button.setText(prefix + " · " + new File(path).getName());
            button.setTextColor(ContextCompat.getColor(this, R.color.atlas_text_primary));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
            button.setLayoutParams(params);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    previewMedia(item, path);
                }
            });
            autoMediaContainer.addView(button);
        }
    }

    private boolean matchesSelectedPeriod(JSONObject item) {
        if (selectedPeriodId == null) {
            return true;
        }
        if (selectedPeriodId.equals(item.optString("linked_period_id", null))) {
            return true;
        }
        if (selectedPeriodId.equals(item.optString("period_id", null))) {
            return true;
        }
        JSONArray linked = item.optJSONArray("linked_period_ids");
        if (linked != null) {
            for (int i = 0; i < linked.length(); i++) {
                if (selectedPeriodId.equals(linked.optString(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void previewMedia(JSONObject item, String path) {
        stopAudioPlayback();
        String type = item.optString("type", "");
        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || item.has("photo_path")) {
            Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap != null) {
                imagePreview.setVisibility(View.VISIBLE);
                videoPreview.setVisibility(View.GONE);
                imagePreview.setImageBitmap(bitmap);
            } else {
                devWarn("image preview failed to decode: " + path);
            }
            audioStatus.setText(path);
            return;
        }
        if ("laughter".equals(type) || "possible_related_speech_context".equals(type) || path.endsWith(".wav") || path.endsWith(".m4a")) {
            audioStatus.setText(path);
            playAudio(path);
            return;
        }
        videoPreview.setVisibility(View.VISIBLE);
        imagePreview.setVisibility(View.GONE);
        final Uri videoUri = resolveVideoUri(item, path);
        if (videoUri == null) {
            devWarn("video preview could not resolve uri: " + path);
            audioStatus.setText(getString(R.string.toast_video_open_failed) + "\n" + path);
            return;
        }
        devInfo("video preview launch player: " + videoUri + " (path=" + path + ")");
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        intent.putExtra("video_uri", videoUri.toString());
        intent.putExtra("video_path", path);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
        audioStatus.setText(videoUri.toString());
    }

    private void hideVisualPreview() {
        imagePreview.setVisibility(View.GONE);
        videoPreview.setVisibility(View.GONE);
        videoPreview.stopPlayback();
    }

    private void playAudio(String path) {
        try {
            stopAudioPlayback();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(path);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception ignored) {
        }
    }

    private void stopAudioPlayback() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private Uri resolveVideoUri(JSONObject item, String path) {
        String contentUri = item != null ? item.optString("content_uri", null) : null;
        if (!TextUtils.isEmpty(contentUri)) {
            return Uri.parse(contentUri);
        }
        if (!TextUtils.isEmpty(path) && path.startsWith("content://")) {
            return Uri.parse(path);
        }
        if (!TextUtils.isEmpty(path)) {
            Uri mediaStoreUri = findMediaStoreVideoUriByName(new File(path).getName());
            if (mediaStoreUri != null) {
                return mediaStoreUri;
            }
            File file = new File(path);
            if (file.exists()) {
                try {
                    Uri providerUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                    devInfo("resolved FileProvider video uri: " + providerUri + " from " + path);
                    return providerUri;
                } catch (Exception e) {
                    devWarn("FileProvider video uri failed, fallback to file uri: " + path);
                    return Uri.fromFile(file);
                }
            }
        }
        return null;
    }

    private Uri findMediaStoreVideoUriByName(String displayName) {
        if (TextUtils.isEmpty(displayName)) {
            return null;
        }
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME},
                    MediaStore.Video.Media.DISPLAY_NAME + "=?",
                    new String[]{displayName},
                    MediaStore.Video.Media.DATE_ADDED + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
                Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                devInfo("resolved MediaStore video uri by name: " + displayName + " -> " + uri);
                return uri;
            }
        } catch (Exception e) {
            devError("findMediaStoreVideoUriByName failed: " + displayName, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private void renderContext() {
        JSONObject derived = eventJson.optJSONObject("derived_context");
        JSONObject gps = derived != null ? derived.optJSONObject("gps") : null;
        JSONObject weather = derived != null ? derived.optJSONObject("weather") : null;
        if (gps != null && gps.has("lat") && gps.has("lng")) {
            gpsView.setText(gps.optDouble("lat") + ", " + gps.optDouble("lng"));
        } else {
            gpsView.setText(R.string.event_detail_context_missing);
        }
        if (weather != null && (weather.has("condition") || weather.has("temperature"))) {
            weatherView.setText(weather.optString("condition", "") + "  " + weather.optString("temperature", ""));
        } else {
            weatherView.setText(R.string.event_detail_context_missing);
        }
    }

    private void renderUserGenerated() {
        notesContainer.removeAllViews();
        userMediaContainer.removeAllViews();
        JSONObject user = eventJson.optJSONObject("user_generated");
        if (user == null) {
            return;
        }
        JSONArray notes = user.optJSONArray("notes");
        if (notes != null) {
            for (int i = 0; i < notes.length(); i++) {
                JSONObject note = notes.optJSONObject(i);
                if (note == null) {
                    continue;
                }
                TextView textView = new TextView(this);
                textView.setBackgroundResource(R.drawable.atlas_section_bg);
                textView.setPadding(dp(12), dp(12), dp(12), dp(12));
                textView.setText(note.optString("text") + "\n" + note.optString("timestamp", ""));
                textView.setTextColor(ContextCompat.getColor(this, R.color.atlas_text_primary));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                params.bottomMargin = dp(8);
                textView.setLayoutParams(params);
                notesContainer.addView(textView);
            }
        }
        addUserMediaButtons(user.optJSONArray("audio_notes"), getString(R.string.btn_audio_note));
        addUserMediaButtons(user.optJSONArray("photos"), getString(R.string.btn_photo_note));
    }

    private void addUserMediaButtons(JSONArray array, String prefix) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            final JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            final String path = item.optString("path", null);
            if (TextUtils.isEmpty(path)) {
                continue;
            }
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText(prefix + " · " + new File(path).getName());
            button.setBackgroundResource(R.drawable.atlas_chip);
            button.setTextColor(ContextCompat.getColor(this, R.color.atlas_text_primary));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
            button.setLayoutParams(params);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    previewMedia(item, path);
                }
            });
            userMediaContainer.addView(button);
        }
    }

    private void saveTextNote() {
        String text = noteInput.getText().toString().trim();
        if (text.length() == 0) {
            return;
        }
        if (repository.addTextNote(eventJson, text, "post_edit")) {
            noteInput.setText("");
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
            eventJson = repository.loadEventById(eventId);
            renderUserGenerated();
        }
    }

    private void toggleAudioNote() {
        if (mediaRecorder != null) {
            stopAudioRecording(true);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        startAudioRecording();
    }

    private void startAudioRecording() {
        try {
            File dir = ensureEventMediaDir();
            activeAudioPath = new File(dir, "audio_note_" + fileFormat.format(new Date()) + ".m4a").getAbsolutePath();
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOutputFile(activeAudioPath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            audioNoteButton.setText(getString(R.string.btn_cancel));
            Toast.makeText(this, R.string.toast_audio_recording_started, Toast.LENGTH_SHORT).show();
            devInfo("audio note recording started: " + activeAudioPath);
        } catch (Exception e) {
            devError("startAudioRecording failed", e);
            Toast.makeText(this, R.string.toast_audio_recording_failed, Toast.LENGTH_LONG).show();
            stopAudioRecording(false);
        }
    }

    private void stopAudioRecording(boolean persist) {
        if (mediaRecorder == null) {
            return;
        }
        try {
            mediaRecorder.stop();
        } catch (Exception ignored) {
        }
        mediaRecorder.release();
        mediaRecorder = null;
        audioNoteButton.setText(getString(R.string.btn_audio_note));
        if (persist && activeAudioPath != null) {
            boolean saved = repository.addAudioNote(eventJson, activeAudioPath, "post_edit");
            devInfo("audio note stopped: persist=" + persist + ", saved=" + saved + ", path=" + activeAudioPath);
            eventJson = repository.loadEventById(eventId);
            renderUserGenerated();
            Toast.makeText(this, R.string.toast_audio_recording_stopped, Toast.LENGTH_SHORT).show();
        } else {
            devInfo("audio note stopped without persist: path=" + activeAudioPath);
        }
        activeAudioPath = null;
    }

    private void addPhotoNote() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        try {
            File dir = ensureEventMediaDir();
            File file = new File(dir, "photo_note_" + fileFormat.format(new Date()) + ".jpg");
            pendingPhotoPath = file.getAbsolutePath();
            Uri outputUri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider", file);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, outputUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (intent.resolveActivity(getPackageManager()) == null) {
                Toast.makeText(this, R.string.toast_photo_capture_failed, Toast.LENGTH_LONG).show();
                devWarn("No camera activity available for photo note");
                return;
            }
            devInfo("launching photo note capture: " + pendingPhotoPath);
            startActivityForResult(intent, REQ_PHOTO);
        } catch (Exception e) {
            devError("addPhotoNote failed", e);
            Toast.makeText(this, R.string.toast_photo_capture_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void refreshContext() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        AtlasContextResolver.refreshContext(this, repository, new AtlasContextResolver.Callback() {
            @Override
            public void onResolved(final Double lat, final Double lng, final Long timestampMs, final String weatherCondition, final Double temperature) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        repository.updateDerivedContext(eventJson, lat, lng, timestampMs, weatherCondition, temperature);
                        eventJson = repository.loadEventById(eventId);
                        renderContext();
                    }
                });
            }

            @Override
            public void onFailed(String reason) {
            }
        });
    }

    private File ensureEventMediaDir() {
        File eventFile = repository.resolveEventFile(eventJson);
        File parent = eventFile != null ? eventFile.getParentFile() : repository.getRootDir();
        File dir = new File(parent, "user_generated/" + eventId);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            return;
        }
        if (requestCode == REQ_PHOTO && data != null && data.getExtras() != null) {
            Object obj = data.getExtras().get("data");
            if (obj instanceof Bitmap) {
                saveCapturedBitmap((Bitmap) obj);
            }
            return;
        }
        if (requestCode == REQ_PHOTO && !TextUtils.isEmpty(pendingPhotoPath)) {
            File file = new File(pendingPhotoPath);
            if (file.exists() && file.length() > 0L) {
                saveCapturedPhotoFile(file);
            } else {
                devWarn("photo capture returned OK but file missing/empty: " + pendingPhotoPath);
                Toast.makeText(this, R.string.toast_photo_capture_failed, Toast.LENGTH_LONG).show();
            }
            pendingPhotoPath = null;
        }
    }

    private void saveCapturedBitmap(Bitmap bitmap) {
        try {
            File dir = ensureEventMediaDir();
            File file = new File(dir, "photo_note_" + fileFormat.format(new Date()) + ".jpg");
            FileOutputStream stream = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream);
            stream.flush();
            stream.close();
            saveCapturedPhotoFile(file);
        } catch (Exception e) {
            devError("saveCapturedBitmap failed", e);
            Toast.makeText(this, R.string.toast_photo_capture_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void saveCapturedPhotoFile(File file) {
        try {
            boolean saved = repository.addPhotoNote(eventJson, file.getAbsolutePath(), "post_edit");
            devInfo("photo note saved=" + saved + ", path=" + file.getAbsolutePath() + ", size=" + file.length());
            eventJson = repository.loadEventById(eventId);
            renderUserGenerated();
            previewMedia(new JSONObject().put("photo_path", file.getAbsolutePath()), file.getAbsolutePath());
            Toast.makeText(this, R.string.toast_photo_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            devError("saveCapturedPhotoFile failed", e);
            Toast.makeText(this, R.string.toast_photo_capture_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }
        if (!granted) {
            return;
        }
        if (requestCode == REQ_LOCATION) {
            refreshContext();
        } else if (requestCode == REQ_AUDIO) {
            startAudioRecording();
        } else if (requestCode == REQ_CAMERA) {
            addPhotoNote();
        }
    }
}
