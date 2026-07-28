package com.hry.camera.usbcamerademo;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EventDetailActivity extends AppCompatActivity {
    static final String EXTRA_RESURFACING_MODE = "resurfacing_mode";
    private static final String TAG = "Atlas.EventDetail";
    private static final int WAVEFORM_BAR_COUNT = 28;
    private static final long AUDIO_PROGRESS_INTERVAL_MS = 80L;
    private static final int REQ_LOCATION = 201;
    private static final int REQ_AUDIO = 202;
    private static final int REQ_PHOTO = 203;
    private static final int REQ_CAMERA = 204;

    private AtlasReviewRepository repository;
    private JSONObject eventJson;
    private String eventId;
    private String sessionId;
    private String selectedPeriodId;
    private boolean longTermMode;
    private TextView headerTime;
    private TextView headerRecency;
    private LinearLayout clipCardsContainer;
    private LinearLayout timelineContainer;
    private LinearLayout autoMediaContainer;
    private LinearLayout notesContainer;
    private LinearLayout userMediaContainer;
    private TextView gpsView;
    private TextView weatherView;
    private ImageView weatherIconView;
    private ImageView imagePreview;
    private VideoView videoPreview;
    private TextView audioStatus;
    private EditText noteInput;
    private Button audioNoteButton;
    private MediaPlayer mediaPlayer;
    private MediaRecorder mediaRecorder;
    private final ExecutorService waveformExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, float[]> waveformCache = new ConcurrentHashMap<>();
    private final Handler audioUiHandler = new Handler(Looper.getMainLooper());
    private AtlasAudioPlaybackState.State playbackState =
            new AtlasAudioPlaybackState.State(null, AtlasAudioPlaybackState.Status.IDLE);
    private AudioRowBinding activeAudioBinding;
    private String activePlaybackPath;
    private boolean audioPaused;
    private boolean audioPreparing;
    private String activeAudioNotePath;
    private String pendingPhotoPath;
    private final SimpleDateFormat fileFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private final Runnable audioProgressRunnable = new Runnable() {
        @Override
        public void run() {
            MediaPlayer player = mediaPlayer;
            AudioRowBinding binding = activeAudioBinding;
            if (player == null || audioPaused || audioPreparing) {
                return;
            }
            try {
                int positionMs = player.getCurrentPosition();
                int durationMs = player.getDuration();
                if (binding != null) {
                    binding.waveform.setProgress(
                            AtlasAudioPlaybackState.progress(positionMs, durationMs));
                    binding.time.setText(AtlasAudioPlaybackState.formatTime(positionMs));
                }
                if (player.isPlaying() && player == mediaPlayer) {
                    audioUiHandler.postDelayed(this, AUDIO_PROGRESS_INTERVAL_MS);
                }
            } catch (RuntimeException error) {
                finishAudioPlayback(AtlasAudioPlaybackState.Event.FAILED, error);
                Toast.makeText(EventDetailActivity.this,
                        R.string.audio_playback_failed, Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_event_detail);
        AtlasDevLogger.session(this, TAG, AtlasDevLogger.buildSessionBanner("EventDetailActivity.onCreate"));
        repository = new AtlasReviewRepository(this);
        eventId = getIntent().getStringExtra("event_id");
        sessionId = getIntent().getStringExtra("session_id");
        eventJson = repository.loadEventById(sessionId, eventId);
        if (eventJson == null) {
            Toast.makeText(this, R.string.toast_no_event, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        headerTime = findViewById(R.id.txtHeaderTime);
        headerRecency = findViewById(R.id.txtHeaderRecency);
        clipCardsContainer = findViewById(R.id.clipCardsContainer);
        timelineContainer = findViewById(R.id.timelineContainer);
        autoMediaContainer = findViewById(R.id.autoMediaContainer);
        notesContainer = findViewById(R.id.notesContainer);
        userMediaContainer = findViewById(R.id.userMediaContainer);
        gpsView = findViewById(R.id.txtGps);
        weatherView = findViewById(R.id.txtWeather);
        weatherIconView = findViewById(R.id.imgWeatherIcon);
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
        findViewById(R.id.btnDeleteEvent).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDeleteEvent();
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

    private void reloadEvent() {
        eventJson = repository.loadEventById(sessionId, eventId);
    }

    @Override
    protected void onStop() {
        AtlasDevLogger.i(this, TAG, "onStop");
        stopAudioPlayback();
        stopAudioRecording(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        waveformExecutor.shutdownNow();
        super.onDestroy();
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

    private void confirmDeleteEvent() {
        new android.support.v7.app.AlertDialog.Builder(this)
                .setMessage(R.string.event_delete_confirm)
                .setPositiveButton(
                        R.string.event_delete_button,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(
                                    android.content.DialogInterface dialog, int which) {
                                AtlasReviewRepository.EventSummary summary =
                                        findCurrentSummary();
                                boolean deleted = summary != null
                                        && repository.deleteEventPermanently(summary);
                                Toast.makeText(
                                        EventDetailActivity.this,
                                        deleted
                                                ? R.string.event_delete_success
                                                : R.string.event_delete_failed,
                                        Toast.LENGTH_SHORT).show();
                                if (deleted) {
                                    AtlasResurfacingManager.refreshLocationsAsync(
                                            EventDetailActivity.this);
                                    finish();
                                }
                            }
                        })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private AtlasReviewRepository.EventSummary findCurrentSummary() {
        for (AtlasReviewRepository.EventSummary summary
                : repository.loadEventSummariesForSession(sessionId)) {
            if (eventId.equals(summary.eventId)) {
                return summary;
            }
        }
        return null;
    }

    private void renderEvent() {
        long startMs = eventJson.optLong("start_time_ms");
        long nowMs = System.currentTimeMillis();
        String forcedMode = getIntent().getStringExtra(EXTRA_RESURFACING_MODE);
        if ("long".equals(forcedMode)) {
            longTermMode = true;
        } else if ("short".equals(forcedMode)) {
            longTermMode = false;
        } else {
            longTermMode = AtlasRelativeTimeFormatter.isLongTerm(startMs, nowMs);
        }
        headerTime.setText(repository.formatTimeRange(startMs, eventJson.optLong("end_time_ms")));
        headerRecency.setText(longTermMode
                ? AtlasRelativeTimeFormatter.formatLongTermHeader(this, startMs, nowMs)
                : AtlasRelativeTimeFormatter.format(this, startMs, nowMs));
        renderClipCards();
        renderTimeline();
        renderAutoMedia();
        renderContext();
        renderUserGenerated();
    }

    /**
     * Requirement 4: one card per laughter clip. Long-term mode shows a compact default (audio +
     * location/date, tag pill if social context exists) with photos/context-audio/social/summary
     * behind "view more details". Short-term mode shows photos + both audio rows by default, with
     * only social context + user summary behind the toggle. Both read the same event-level social
     * context / user summary (requirement decision: not per-clip).
     */
    private void renderClipCards() {
        clipCardsContainer.removeAllViews();
        JSONObject auto = eventJson.optJSONObject("auto_captured");
        JSONArray audioClips = auto != null ? auto.optJSONArray("audio_clips") : null;
        if (audioClips == null) {
            return;
        }
        JSONObject user = eventJson.optJSONObject("user_generated");
        JSONObject socialContext = repository.getSocialContext(eventJson);
        String socialTag = repository.buildSocialContextTag(eventJson);
        boolean hasSocialContext = repository.hasSocialContext(eventJson);
        String userSummary = firstNoteText(user);
        String locationDate = buildLocationDateText();

        ArrayList<JSONObject> laughterClips = new ArrayList<>();
        ArrayList<JSONObject> contextClips = new ArrayList<>();
        for (int i = 0; i < audioClips.length(); i++) {
            JSONObject clip = audioClips.optJSONObject(i);
            if (clip == null) {
                continue;
            }
            String type = clip.optString("type", "");
            if ("laughter".equals(type)) {
                laughterClips.add(clip);
            } else if ("possible_related_speech_context".equals(type)) {
                contextClips.add(clip);
            }
        }
        Collections.sort(laughterClips, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Long.compare(a.optLong("device_time_ms", 0L), b.optLong("device_time_ms", 0L));
            }
        });

        JSONArray photos = auto.optJSONArray("photos");
        JSONArray videos = auto.optJSONArray("videos");

        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < laughterClips.size(); i++) {
            JSONObject clip = laughterClips.get(i);
            View card = inflater.inflate(R.layout.item_laughter_clip_card, clipCardsContainer, false);
            bindClipCard(card, i + 1, clip, i == laughterClips.size() - 1, findNearestContextClip(clip, contextClips),
                    photos, videos, socialTag, hasSocialContext, socialContext, userSummary);
            clipCardsContainer.addView(card);
        }
    }

    private JSONObject findNearestContextClip(JSONObject laughterClip, List<JSONObject> contextClips) {
        long laughterTimeMs = laughterClip.optLong("device_time_ms", -1L);
        JSONObject best = null;
        long bestDelta = Long.MAX_VALUE;
        for (JSONObject candidate : contextClips) {
            JSONArray linkedPeriodIds = candidate.optJSONArray("linked_period_ids");
            if (linkedPeriodIds != null && linkedPeriodIds.length() > 0) {
                return candidate;
            }
            long candidateTimeMs = candidate.optLong("device_time_ms", -1L);
            if (laughterTimeMs < 0L || candidateTimeMs < 0L) {
                continue;
            }
            long delta = Math.abs(candidateTimeMs - laughterTimeMs);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = candidate;
            }
        }
        return best;
    }

    private void bindClipCard(
            View card,
            int clipNumber,
            JSONObject clip,
            boolean isLast,
            JSONObject contextClip,
            JSONArray photos,
            JSONArray videos,
            String socialTag,
            boolean hasSocialContext,
            JSONObject socialContext,
            String userSummary
    ) {
        TextView durationBadge = card.findViewById(R.id.txtClipDurationBadge);
        View rail = card.findViewById(R.id.clipRail);
        TextView title = card.findViewById(R.id.txtClipTitle);
        TextView timeRange = card.findViewById(R.id.txtClipTimeRange);
        View photoStripShort = card.findViewById(R.id.clipPhotoStripShort);
        LinearLayout photoStripShortContainer = card.findViewById(R.id.clipPhotoStripShortContainer);
        View laughterAudioRow = card.findViewById(R.id.clipLaughterAudioRow);
        ImageView laughterPlayIcon = card.findViewById(R.id.imgClipLaughterPlay);
        AtlasWaveformView laughterWaveform = card.findViewById(R.id.waveClipLaughter);
        TextView laughterDuration = card.findViewById(R.id.txtClipLaughterDuration);
        View contextAudioRowShort = card.findViewById(R.id.clipContextAudioRowShort);
        ImageView contextPlayIconShort = card.findViewById(R.id.imgClipContextPlayShort);
        AtlasWaveformView contextWaveformShort = card.findViewById(R.id.waveClipContextShort);
        TextView contextAudioDurationShort = card.findViewById(R.id.txtClipContextAudioDurationShort);
        TextView locationDateView = card.findViewById(R.id.txtClipLocationDate);
        TextView socialTagPill = card.findViewById(R.id.txtClipSocialTagPill);
        final View expandedDetails = card.findViewById(R.id.clipExpandedDetails);
        View labelPhotoVideoLong = card.findViewById(R.id.labelClipPhotoVideoLong);
        View photoStripLong = card.findViewById(R.id.clipPhotoStripLong);
        LinearLayout photoStripLongContainer = card.findViewById(R.id.clipPhotoStripLongContainer);
        View labelContextAudioLong = card.findViewById(R.id.labelClipContextAudioLong);
        View contextAudioRowLong = card.findViewById(R.id.clipContextAudioRowLong);
        ImageView contextPlayIconLong = card.findViewById(R.id.imgClipContextPlayLong);
        AtlasWaveformView contextWaveformLong = card.findViewById(R.id.waveClipContextLong);
        TextView contextAudioDurationLong = card.findViewById(R.id.txtClipContextAudioDurationLong);
        TextView socialContextText = card.findViewById(R.id.txtClipSocialContext);
        final LinearLayout notesLogContainer = card.findViewById(R.id.clipNotesLogContainer);
        final EditText userSummaryInput = card.findViewById(R.id.inputClipUserSummary);
        Button addNoteButton = card.findViewById(R.id.btnClipAddNote);
        Button toggleButton = card.findViewById(R.id.btnClipToggleDetails);

        double durationSec = clip.optDouble("duration_sec", 0.0);
        String durationLabel = formatDurationShort(durationSec);
        durationBadge.setText(durationLabel);
        rail.setVisibility(isLast ? View.INVISIBLE : View.VISIBLE);
        title.setText(getString(R.string.clip_label_prefix) + " " + clipNumber);
        long deviceTimeMs = clip.optLong("device_time_ms", 0L);
        timeRange.setText(deviceTimeMs > 0L ? formatClipTimeRange(deviceTimeMs, durationSec) : "");
        laughterDuration.setText(AtlasAudioPlaybackState.formatTime(0L));
        locationDateView.setText(locationDateTextOrFallback());

        if (hasSocialContext && !TextUtils.isEmpty(socialTag)) {
            socialTagPill.setVisibility(longTermMode ? View.VISIBLE : View.GONE);
            socialTagPill.setText(socialTag);
        } else {
            socialTagPill.setVisibility(View.GONE);
        }

        final String contextPath = contextClip != null ? contextClip.optString("path", null) : null;

        List<String> clipPhotoPaths = collectNearbyPhotoPaths(photos, deviceTimeMs);
        List<String> clipVideoPaths = collectNearbyVideoPaths(videos, deviceTimeMs);

        if (longTermMode) {
            photoStripShort.setVisibility(View.GONE);
            contextAudioRowShort.setVisibility(View.GONE);
            labelPhotoVideoLong.setVisibility(clipPhotoPaths.isEmpty() && clipVideoPaths.isEmpty() ? View.GONE : View.VISIBLE);
            photoStripLong.setVisibility(clipPhotoPaths.isEmpty() && clipVideoPaths.isEmpty() ? View.GONE : View.VISIBLE);
            populatePhotoStrip(photoStripLongContainer, clipPhotoPaths, clipVideoPaths);
            boolean hasContext = contextClip != null;
            labelContextAudioLong.setVisibility(hasContext ? View.VISIBLE : View.GONE);
            contextAudioRowLong.setVisibility(hasContext ? View.VISIBLE : View.GONE);
            contextAudioDurationLong.setText(AtlasAudioPlaybackState.formatTime(0L));
            wireAudioControl(
                    laughterAudioRow,
                    laughterPlayIcon,
                    laughterWaveform,
                    laughterDuration,
                    clip.optString("path", null));
            wireAudioControl(
                    contextAudioRowLong,
                    contextPlayIconLong,
                    contextWaveformLong,
                    contextAudioDurationLong,
                    contextPath);
        } else {
            photoStripShort.setVisibility(clipPhotoPaths.isEmpty() && clipVideoPaths.isEmpty() ? View.GONE : View.VISIBLE);
            populatePhotoStrip(photoStripShortContainer, clipPhotoPaths, clipVideoPaths);
            boolean hasContext = contextClip != null;
            contextAudioRowShort.setVisibility(hasContext ? View.VISIBLE : View.GONE);
            contextAudioDurationShort.setText(AtlasAudioPlaybackState.formatTime(0L));
            labelPhotoVideoLong.setVisibility(View.GONE);
            photoStripLong.setVisibility(View.GONE);
            labelContextAudioLong.setVisibility(View.GONE);
            contextAudioRowLong.setVisibility(View.GONE);
            wireAudioControl(
                    laughterAudioRow,
                    laughterPlayIcon,
                    laughterWaveform,
                    laughterDuration,
                    clip.optString("path", null));
            wireAudioControl(
                    contextAudioRowShort,
                    contextPlayIconShort,
                    contextWaveformShort,
                    contextAudioDurationShort,
                    contextPath);
        }

        socialContextText.setText(hasSocialContext ? buildSocialContextDetailText(socialContext) : getString(R.string.social_context_empty));
        renderNotesLog(notesLogContainer);
        addNoteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = userSummaryInput.getText().toString().trim();
                if (TextUtils.isEmpty(text)) {
                    return;
                }
                if (repository.addTextNote(eventJson, text, "clip_card_summary")) {
                    userSummaryInput.setText("");
                    reloadEvent();
                    renderNotesLog(notesLogContainer);
                }
            }
        });

        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean expanded = expandedDetails.getVisibility() == View.VISIBLE;
                expandedDetails.setVisibility(expanded ? View.GONE : View.VISIBLE);
                ((Button) v).setText(expanded ? (longTermMode ? R.string.btn_view_more_details : R.string.btn_view_more_details)
                        : R.string.btn_collapse_details);
            }
        });
    }

    private void wireAudioControl(
            View row,
            ImageView playIcon,
            AtlasWaveformView waveform,
            TextView time,
            String path
    ) {
        if (row == null || playIcon == null || waveform == null || time == null) {
            return;
        }
        final AudioRowBinding binding =
                new AudioRowBinding(row, playIcon, waveform, time, path);
        resetAudioBinding(binding);
        if (TextUtils.isEmpty(path)) {
            markAudioUnavailable(binding, null);
            return;
        }
        row.setEnabled(true);
        row.setAlpha(1f);
        row.setContentDescription(getString(R.string.audio_playback_play));
        loadWaveformAsync(binding);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleAudioControlClick(binding);
            }
        });
    }

    private void loadWaveformAsync(final AudioRowBinding binding) {
        final File file = new File(binding.path);
        if (!file.isFile()) {
            markAudioUnavailable(binding,
                    new IllegalArgumentException("Audio file does not exist: " + binding.path));
            return;
        }
        final String cacheKey = AtlasWavWaveformExtractor.cacheKey(file);
        float[] cached = waveformCache.get(cacheKey);
        if (cached != null) {
            binding.waveform.setAmplitudes(cached);
            return;
        }
        try {
            waveformExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        float[] amplitudes = waveformCache.get(cacheKey);
                        if (amplitudes == null) {
                            amplitudes = AtlasWavWaveformExtractor.extract(
                                    file, WAVEFORM_BAR_COUNT);
                            waveformCache.put(cacheKey, amplitudes);
                        }
                        final float[] result = amplitudes;
                        audioUiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing() && binding.row.getParent() != null) {
                                    binding.waveform.setAmplitudes(result);
                                }
                            }
                        });
                    } catch (final Exception error) {
                        audioUiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing()) {
                                    markAudioUnavailable(binding, error);
                                }
                            }
                        });
                    }
                }
            });
        } catch (RuntimeException error) {
            if (!isFinishing()) {
                markAudioUnavailable(binding, error);
            }
        }
    }

    private void markAudioUnavailable(AudioRowBinding binding, Throwable error) {
        binding.row.setEnabled(false);
        binding.row.setAlpha(0.45f);
        binding.row.setContentDescription(getString(R.string.audio_playback_unavailable));
        binding.playIcon.setContentDescription(getString(R.string.audio_playback_unavailable));
        binding.waveform.setPlaybackActive(false);
        binding.waveform.setProgress(0f);
        binding.time.setText(AtlasAudioPlaybackState.formatTime(0L));
        if (error != null) {
            devError("audio unavailable: " + binding.path, error);
            Toast.makeText(this, R.string.audio_playback_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void handleAudioControlClick(AudioRowBinding binding) {
        if (!new File(binding.path).isFile()) {
            markAudioUnavailable(binding,
                    new IllegalArgumentException("Audio file does not exist: " + binding.path));
            return;
        }
        if (binding == activeAudioBinding && mediaPlayer != null) {
            if (audioPreparing) {
                return;
            }
            if (audioPaused) {
                resumeAudioPlayback(binding);
            } else {
                pauseAudioPlayback(binding);
            }
            return;
        }
        startAudioPlayback(binding, binding.path);
    }

    private void pauseAudioPlayback(AudioRowBinding binding) {
        try {
            mediaPlayer.pause();
            audioPaused = true;
            playbackState = AtlasAudioPlaybackState.transition(
                    playbackState,
                    AtlasAudioPlaybackState.Event.TOGGLE_REQUESTED,
                    binding.path);
            audioUiHandler.removeCallbacks(audioProgressRunnable);
            binding.playIcon.setImageResource(R.drawable.ic_atlas_play_circle);
            binding.playIcon.setContentDescription(getString(R.string.audio_playback_play));
            binding.row.setContentDescription(getString(R.string.audio_playback_play));
            binding.waveform.setPlaybackActive(false);
            devInfo("audio playback paused: " + binding.path);
        } catch (RuntimeException error) {
            finishAudioPlayback(AtlasAudioPlaybackState.Event.FAILED, error);
            Toast.makeText(this, R.string.audio_playback_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void resumeAudioPlayback(AudioRowBinding binding) {
        try {
            mediaPlayer.start();
            audioPaused = false;
            playbackState = AtlasAudioPlaybackState.transition(
                    playbackState,
                    AtlasAudioPlaybackState.Event.TOGGLE_REQUESTED,
                    binding.path);
            renderPlaying(binding);
            scheduleAudioProgress();
            devInfo("audio playback resumed: " + binding.path);
        } catch (RuntimeException error) {
            finishAudioPlayback(AtlasAudioPlaybackState.Event.FAILED, error);
            Toast.makeText(this, R.string.audio_playback_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private List<String> collectNearbyPhotoPaths(JSONArray photos, long clipTimeMs) {
        ArrayList<String> result = new ArrayList<>();
        if (photos == null) {
            return result;
        }
        for (int i = 0; i < photos.length(); i++) {
            JSONObject photo = photos.optJSONObject(i);
            if (photo == null) {
                continue;
            }
            String path = photo.optString("photo_path", null);
            if (!TextUtils.isEmpty(path)) {
                result.add(path);
            }
        }
        return result;
    }

    private List<String> collectNearbyVideoPaths(JSONArray videos, long clipTimeMs) {
        ArrayList<String> result = new ArrayList<>();
        if (videos == null) {
            return result;
        }
        for (int i = 0; i < videos.length(); i++) {
            JSONObject video = videos.optJSONObject(i);
            if (video == null) {
                continue;
            }
            String path = video.optString("video_path", null);
            if (!TextUtils.isEmpty(path)) {
                result.add(path);
            }
        }
        return result;
    }

    private void populatePhotoStrip(LinearLayout container, List<String> photoPaths, List<String> videoPaths) {
        container.removeAllViews();
        int thumbSize = dp(64);
        for (final String path : videoPaths) {
            ImageView thumb = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(thumbSize, thumbSize);
            params.rightMargin = dp(6);
            thumb.setLayoutParams(params);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundResource(R.drawable.atlas_section_bg);
            thumb.setImageResource(R.drawable.ic_atlas_video);
            thumb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    previewMedia(new JSONObject(), path);
                }
            });
            container.addView(thumb);
        }
        for (final String path : photoPaths) {
            ImageView thumb = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(thumbSize, thumbSize);
            params.rightMargin = dp(6);
            thumb.setLayoutParams(params);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap != null) {
                thumb.setImageBitmap(bitmap);
            } else {
                thumb.setBackgroundResource(R.drawable.atlas_section_bg);
                thumb.setImageResource(R.drawable.ic_atlas_photo);
            }
            thumb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(EventDetailActivity.this, FullscreenPhotoActivity.class);
                    intent.putExtra("photo_path", path);
                    startActivity(intent);
                }
            });
            container.addView(thumb);
        }
    }

    private String formatDurationShort(double seconds) {
        long rounded = Math.round(Math.max(0.0, seconds));
        return rounded + "s";
    }

    private String formatClipTimeRange(long startMs, double durationSec) {
        SimpleDateFormat exact = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        long endMs = startMs + Math.round(durationSec * 1000.0);
        return exact.format(new Date(startMs)) + " — " + exact.format(new Date(endMs));
    }

    private String buildLocationDateText() {
        JSONObject derived = eventJson.optJSONObject("derived_context");
        JSONObject gps = derived != null ? derived.optJSONObject("gps") : null;
        String address = gps != null ? gps.optString("address", "") : "";
        SimpleDateFormat dateFormat = new SimpleDateFormat("MM.dd", Locale.getDefault());
        String dateText = dateFormat.format(new Date(eventJson.optLong("start_time_ms")));
        if (TextUtils.isEmpty(address)) {
            return dateText;
        }
        return address + " · " + dateText;
    }

    private String locationDateTextOrFallback() {
        String text = buildLocationDateText();
        return TextUtils.isEmpty(text) ? getString(R.string.event_detail_context_missing) : text;
    }

    /** Requirement 3.IV: each supplement is its own dated entry, newest last, chat-message style. */
    private void renderNotesLog(LinearLayout container) {
        container.removeAllViews();
        JSONObject user = eventJson.optJSONObject("user_generated");
        JSONArray notes = user != null ? user.optJSONArray("notes") : null;
        if (notes == null || notes.length() == 0) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        SimpleDateFormat noteTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note == null) {
                continue;
            }
            View entry = inflater.inflate(R.layout.item_clip_note_log_entry, container, false);
            TextView timestampView = entry.findViewById(R.id.txtNoteLogTimestamp);
            TextView textView = entry.findViewById(R.id.txtNoteLogText);
            long timestampMs = note.optLong("timestamp_ms", 0L);
            timestampView.setText(timestampMs > 0L ? noteTimeFormat.format(new Date(timestampMs)) : note.optString("timestamp", ""));
            textView.setText(note.optString("text", ""));
            container.addView(entry);
        }
    }

    private String buildSocialContextDetailText(JSONObject socialContext) {
        String withWhom = socialContext.optString("with_whom", "");
        String doingWhat = socialContext.optString("doing_what", "");
        String mood = socialContext.optString("mood", "");
        ArrayList<String> parts = new ArrayList<>();
        if (!TextUtils.isEmpty(withWhom)) {
            parts.add(getString(R.string.social_tag_with_prefix) + withWhom);
        }
        if (!TextUtils.isEmpty(doingWhat)) {
            parts.add(doingWhat);
        }
        if (!TextUtils.isEmpty(mood)) {
            parts.add(mood);
        }
        return parts.isEmpty() ? getString(R.string.social_context_empty) : joinWithSeparator(parts);
    }

    private String joinWithSeparator(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(getString(R.string.social_tag_separator));
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private String firstNoteText(JSONObject user) {
        if (user == null) {
            return null;
        }
        JSONArray notes = user.optJSONArray("notes");
        if (notes == null || notes.length() == 0) {
            return null;
        }
        JSONObject first = notes.optJSONObject(0);
        return first == null ? null : first.optString("text", null);
    }

    private void saveUserSummaryFromInput(String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        JSONObject user = eventJson.optJSONObject("user_generated");
        JSONArray notes = user != null ? user.optJSONArray("notes") : null;
        String existingId = null;
        if (notes != null && notes.length() > 0) {
            JSONObject first = notes.optJSONObject(0);
            existingId = first != null ? first.optString("item_id", null) : null;
        }
        boolean saved;
        if (!TextUtils.isEmpty(existingId)) {
            saved = repository.editTextNote(eventJson, existingId, text);
        } else {
            saved = repository.addTextNote(eventJson, text, "clip_card_summary");
        }
        if (saved) {
            reloadEvent();
            renderUserGenerated();
        }
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
        devInfo("video preview launch external player: " + videoUri + " (path=" + path + ")");
        openVideoExternal(videoUri, path);
        audioStatus.setText(videoUri.toString());
    }

    private void openVideoExternal(Uri uri, String path) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            Toast.makeText(this, R.string.toast_video_external_open, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            devWarn("external video open failed; fallback to in-app player: " + path);
            Intent intent = new Intent(this, VideoPlayerActivity.class);
            intent.putExtra("video_uri", uri.toString());
            intent.putExtra("video_path", path);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        }
    }

    private void hideVisualPreview() {
        imagePreview.setVisibility(View.GONE);
        videoPreview.setVisibility(View.GONE);
        videoPreview.stopPlayback();
    }

    private void playAudio(String path) {
        startAudioPlayback(null, path);
    }

    private void startAudioPlayback(final AudioRowBinding binding, final String path) {
        stopAudioPlayback();
        if (TextUtils.isEmpty(path) || !new File(path).isFile()) {
            if (binding != null) {
                markAudioUnavailable(binding,
                        new IllegalArgumentException("Audio file does not exist: " + path));
            } else {
                devWarn("audio unavailable: " + path);
                Toast.makeText(this, R.string.audio_playback_unavailable, Toast.LENGTH_SHORT).show();
            }
            playbackState = AtlasAudioPlaybackState.transition(
                    playbackState, AtlasAudioPlaybackState.Event.FAILED, null);
            return;
        }
        activeAudioBinding = binding;
        activePlaybackPath = path;
        audioPaused = false;
        audioPreparing = true;
        playbackState = AtlasAudioPlaybackState.transition(
                playbackState, AtlasAudioPlaybackState.Event.PLAY_REQUESTED, path);
        if (binding != null) {
            binding.row.setEnabled(false);
        }
        audioStatus.setText(path);
        try {
            final MediaPlayer player = new MediaPlayer();
            mediaPlayer = player;
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(path);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer preparedPlayer) {
                    if (mediaPlayer != preparedPlayer || !path.equals(activePlaybackPath)) {
                        return;
                    }
                    try {
                        audioPreparing = false;
                        if (binding != null) {
                            binding.row.setEnabled(true);
                        }
                        preparedPlayer.start();
                        renderPlaying(binding);
                        scheduleAudioProgress();
                        devInfo("audio playback started: " + path);
                    } catch (RuntimeException error) {
                        finishAudioPlayback(AtlasAudioPlaybackState.Event.FAILED, error);
                        Toast.makeText(EventDetailActivity.this,
                                R.string.audio_playback_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer completedPlayer) {
                    if (mediaPlayer == completedPlayer) {
                        devInfo("audio playback completed: " + path);
                        finishAudioPlayback(
                                AtlasAudioPlaybackState.Event.COMPLETED, null);
                    }
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer failedPlayer, int what, int extra) {
                    if (mediaPlayer != failedPlayer) {
                        return true;
                    }
                    RuntimeException error = new RuntimeException(
                            "MediaPlayer error what=" + what + " extra=" + extra);
                    finishAudioPlayback(AtlasAudioPlaybackState.Event.FAILED, error);
                    Toast.makeText(EventDetailActivity.this,
                            R.string.audio_playback_failed, Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
            player.prepareAsync();
        } catch (Exception error) {
            finishAudioPlayback(AtlasAudioPlaybackState.Event.FAILED, error);
            Toast.makeText(this, R.string.audio_playback_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void stopAudioPlayback() {
        finishAudioPlayback(AtlasAudioPlaybackState.Event.STOPPED, null);
    }

    private void renderPlaying(AudioRowBinding binding) {
        if (binding == null) {
            return;
        }
        binding.playIcon.setImageResource(R.drawable.ic_atlas_pause_circle);
        binding.playIcon.setContentDescription(getString(R.string.audio_playback_pause));
        binding.row.setContentDescription(getString(R.string.audio_playback_pause));
        binding.waveform.setPlaybackActive(true);
    }

    private void scheduleAudioProgress() {
        audioUiHandler.removeCallbacks(audioProgressRunnable);
        audioUiHandler.post(audioProgressRunnable);
    }

    private void finishAudioPlayback(
            AtlasAudioPlaybackState.Event event,
            Throwable error
    ) {
        String failedPath = activePlaybackPath;
        audioUiHandler.removeCallbacks(audioProgressRunnable);
        if (error != null) {
            devError("audio playback failed: " + failedPath, error);
        }
        resetAudioBinding(activeAudioBinding);
        releaseMediaPlayer();
        playbackState = AtlasAudioPlaybackState.transition(playbackState, event, null);
        activeAudioBinding = null;
        activePlaybackPath = null;
        audioPaused = false;
        audioPreparing = false;
    }

    private void resetAudioBinding(AudioRowBinding binding) {
        if (binding == null) {
            return;
        }
        binding.row.setEnabled(true);
        binding.row.setAlpha(1f);
        binding.row.setContentDescription(getString(R.string.audio_playback_play));
        binding.playIcon.setImageResource(R.drawable.ic_atlas_play_circle);
        binding.playIcon.setContentDescription(getString(R.string.audio_playback_play));
        binding.waveform.setPlaybackActive(false);
        binding.waveform.setProgress(0f);
        binding.time.setText(AtlasAudioPlaybackState.formatTime(0L));
    }

    private void releaseMediaPlayer() {
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        if (player == null) {
            return;
        }
        player.setOnPreparedListener(null);
        player.setOnCompletionListener(null);
        player.setOnErrorListener(null);
        player.release();
    }

    private Uri resolveVideoUri(JSONObject item, String path) {
        if (!TextUtils.isEmpty(path) && path.startsWith("content://")) {
            return Uri.parse(path);
        }
        if (!TextUtils.isEmpty(path)) {
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
            Uri mediaStoreUri = findMediaStoreVideoUriByName(new File(path).getName());
            if (mediaStoreUri != null) {
                return mediaStoreUri;
            }
        }
        String contentUri = item != null ? item.optString("content_uri", null) : null;
        if (!TextUtils.isEmpty(contentUri)) {
            return Uri.parse(contentUri);
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
            String address = gps.optString("address", "");
            String coords = getString(R.string.label_gps_coordinates) + ": " + gps.optDouble("lat") + ", " + gps.optDouble("lng");
            if (gps.has("accuracy_m")) {
                coords = coords + "\n" + getString(R.string.label_location_accuracy) + ": " + Math.round(gps.optDouble("accuracy_m")) + getString(R.string.unit_meter_short);
            }
            gpsView.setText(TextUtils.isEmpty(address) ? coords : address + "\n" + coords);
        } else {
            gpsView.setText(R.string.event_detail_context_missing);
        }
        if (weather != null && (weather.has("condition") || weather.has("temperature"))) {
            weatherView.setText(weather.optString("condition", "") + "  " + weather.optString("temperature", ""));
            weatherIconView.setImageResource(AtlasWeatherIconMapper.drawableForKey(weather.optString("icon_key", AtlasWeatherIconMapper.keyForCondition(weather.optString("condition", "")))));
        } else {
            weatherView.setText(R.string.event_detail_context_missing);
            weatherIconView.setImageResource(R.drawable.ic_atlas_weather);
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
                final JSONObject note = notes.optJSONObject(i);
                if (note == null) {
                    continue;
                }
                final String itemId = note.optString("item_id", null);
                TextView textView = new TextView(this);
                textView.setBackgroundResource(R.drawable.atlas_section_bg);
                textView.setPadding(dp(12), dp(12), dp(12), dp(12));
                textView.setText(note.optString("text") + "\n" + note.optString("timestamp", ""));
                textView.setTextColor(ContextCompat.getColor(this, R.color.atlas_text_primary));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                params.bottomMargin = dp(8);
                textView.setLayoutParams(params);
                if (!TextUtils.isEmpty(itemId)) {
                    textView.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showEditNoteDialog(itemId, note.optString("text", ""));
                        }
                    });
                    textView.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            confirmDeleteNote(itemId);
                            return true;
                        }
                    });
                }
                notesContainer.addView(textView);
            }
        }
        addUserMediaButtons(user.optJSONArray("audio_notes"), getString(R.string.btn_audio_note), "audio_notes");
        addUserMediaButtons(user.optJSONArray("photos"), getString(R.string.btn_photo_note), "photos");
    }

    private void showEditNoteDialog(final String itemId, String currentText) {
        final EditText input = new EditText(this);
        input.setText(currentText);
        input.setSelection(input.getText().length());
        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle(R.string.btn_text_note)
                .setView(input)
                .setPositiveButton(R.string.btn_save, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String newText = input.getText().toString().trim();
                        if (!TextUtils.isEmpty(newText) && repository.editTextNote(eventJson, itemId, newText)) {
                            reloadEvent();
                            renderUserGenerated();
                        }
                    }
                })
                .setNeutralButton(R.string.save_decision_delete, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        confirmDeleteNote(itemId);
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void confirmDeleteNote(final String itemId) {
        new android.support.v7.app.AlertDialog.Builder(this)
                .setMessage(R.string.save_decision_delete_confirm)
                .setPositiveButton(R.string.save_decision_delete, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (repository.deleteTextNote(eventJson, itemId)) {
                            reloadEvent();
                            renderUserGenerated();
                        }
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void addUserMediaButtons(JSONArray array, String prefix, final String arrayName) {
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
            final String itemId = item.optString("item_id", null);
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
            if (!TextUtils.isEmpty(itemId)) {
                button.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        confirmDeleteUserMedia(arrayName, itemId);
                        return true;
                    }
                });
            }
            userMediaContainer.addView(button);
        }
    }

    private void confirmDeleteUserMedia(final String arrayName, final String itemId) {
        new android.support.v7.app.AlertDialog.Builder(this)
                .setMessage(R.string.save_decision_delete_confirm)
                .setPositiveButton(R.string.save_decision_delete, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        boolean deleted = "audio_notes".equals(arrayName)
                                ? repository.deleteAudioNote(eventJson, itemId)
                                : repository.deletePhotoNote(eventJson, itemId);
                        if (deleted) {
                            reloadEvent();
                            renderUserGenerated();
                        }
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void saveTextNote() {
        String text = noteInput.getText().toString().trim();
        if (text.length() == 0) {
            return;
        }
        if (repository.addTextNote(eventJson, text, "post_edit")) {
            noteInput.setText("");
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
            reloadEvent();
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
            activeAudioNotePath = new File(
                    dir, "audio_note_" + fileFormat.format(new Date()) + ".m4a")
                    .getAbsolutePath();
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOutputFile(activeAudioNotePath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            audioNoteButton.setText(getString(R.string.btn_cancel));
            Toast.makeText(this, R.string.toast_audio_recording_started, Toast.LENGTH_SHORT).show();
            devInfo("audio note recording started: " + activeAudioNotePath);
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
        if (persist && activeAudioNotePath != null) {
            boolean saved = repository.addAudioNote(
                    eventJson, activeAudioNotePath, "post_edit");
            devInfo("audio note stopped: persist=" + persist
                    + ", saved=" + saved + ", path=" + activeAudioNotePath);
            reloadEvent();
            renderUserGenerated();
            Toast.makeText(this, R.string.toast_audio_recording_stopped, Toast.LENGTH_SHORT).show();
        } else {
            devInfo("audio note stopped without persist: path=" + activeAudioNotePath);
        }
        activeAudioNotePath = null;
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
            public void onResolved(final Double lat, final Double lng, final Double amapLat, final Double amapLng, final Float accuracyMeters, final Long timestampMs, final String locationName, final String adcode, final String weatherCondition, final Double temperature) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        repository.updateDerivedContext(eventJson, lat, lng, amapLat, amapLng, accuracyMeters, timestampMs, locationName, adcode, weatherCondition, temperature);
                        reloadEvent();
                        repository.backfillMissingContextFromNearby(eventJson, 6L * 60L * 60L * 1000L);
                        renderContext();
                        AtlasResurfacingManager.refreshLocationsAsync(
                                EventDetailActivity.this);
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
            reloadEvent();
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

    private static final class AudioRowBinding {
        final View row;
        final ImageView playIcon;
        final AtlasWaveformView waveform;
        final TextView time;
        final String path;

        AudioRowBinding(
                View row,
                ImageView playIcon,
                AtlasWaveformView waveform,
                TextView time,
                String path
        ) {
            this.row = row;
            this.playIcon = playIcon;
            this.waveform = waveform;
            this.time = time;
            this.path = path;
        }
    }
}
