package com.hry.camera.usbcamerademo;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.UUID;

public class VideoPlayerActivity extends AppCompatActivity {
    private VideoView videoView;
    private TextView statusView;
    private ImageView fallbackFrameView;
    private Uri playbackUri;
    private String playbackPath;
    private String researchSessionId;
    private String researchMomentId;
    private String researchMediaItemId;
    private String playbackInstanceId;
    private ResearchPlaybackTracker playbackTracker;
    private boolean playbackStarted;
    private boolean playbackCompleted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);
        videoView = findViewById(R.id.videoView);
        statusView = findViewById(R.id.txtVideoStatus);
        fallbackFrameView = findViewById(R.id.imgVideoFallbackFrame);
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnOpenExternalVideo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (playbackUri != null) {
                    tryOpenExternal(playbackUri, playbackPath);
                }
            }
        });

        final String uriString = getIntent().getStringExtra("video_uri");
        final String path = getIntent().getStringExtra("video_path");
        playbackPath = path;
        researchSessionId = getIntent().getStringExtra(
                "research_session_id");
        researchMomentId = getIntent().getStringExtra(
                "research_moment_id");
        researchMediaItemId = getIntent().getStringExtra(
                "research_media_item_id");
        if (TextUtils.isEmpty(researchMediaItemId)) {
            researchMediaItemId = ResearchIdentifiers.anonymousId(
                    "media", path);
        }
        if (!getIntent().getBooleanExtra(
                "research_media_open_logged", false)) {
            logMediaOpened("in_app");
        }
        Uri uri = null;
        final boolean hasLocalFile = !TextUtils.isEmpty(path) && new File(path).exists();
        if (!TextUtils.isEmpty(uriString)) {
            uri = Uri.parse(uriString);
        } else if (hasLocalFile) {
            File file = new File(path);
            try {
                uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            } catch (Exception ignored) {
                uri = Uri.fromFile(file);
            }
        }
        if (uri == null) {
            statusView.setText(R.string.toast_video_open_failed);
            logPlaybackFailure("missing_source");
            return;
        }
        final Uri finalUri = uri;
        playbackUri = finalUri;
        showFallbackFrame(path);
        statusView.setText(finalUri.toString());
        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                AtlasDevLogger.i(VideoPlayerActivity.this, "Atlas.VideoPlayer", "prepared: " + finalUri
                        + ", video=" + mp.getVideoWidth() + "x" + mp.getVideoHeight()
                        + ", path=" + path);
                String meta = readVideoMeta(path);
                statusView.setText("video=" + mp.getVideoWidth() + "x" + mp.getVideoHeight()
                        + (TextUtils.isEmpty(meta) ? "" : "\n" + meta)
                        + "\n" + finalUri);
                videoView.start();
                playbackInstanceId =
                        UUID.randomUUID().toString();
                playbackTracker = new ResearchPlaybackTracker();
                playbackTracker.start(
                        SystemClock.elapsedRealtime());
                playbackStarted = true;
                playbackCompleted = false;
                logPlayback(
                        ResearchEventNames.MEDIA_PLAY_STARTED,
                        ResearchInteractionLogger.properties(
                                "position_ms",
                                videoView.getCurrentPosition(),
                                "duration_ms",
                                videoView.getDuration(),
                                "resumed", false));
            }
        });
        videoView.setOnCompletionListener(
                new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(
                            MediaPlayer completedPlayer) {
                        if (!playbackStarted
                                || playbackCompleted) {
                            return;
                        }
                        long playedDurationMs =
                                playbackTracker == null
                                        ? 0L
                                        : playbackTracker.finish(
                                                SystemClock.elapsedRealtime());
                        playbackCompleted = true;
                        logPlayback(
                                ResearchEventNames.MEDIA_PLAY_COMPLETED,
                                ResearchLogProperties.mediaPlayCompleted(
                                        videoView.getDuration(),
                                        videoView.getDuration(),
                                        playedDurationMs));
                    }
                });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                AtlasDevLogger.w(VideoPlayerActivity.this, "Atlas.VideoPlayer", "error what=" + what + ", extra=" + extra + ", uri=" + finalUri);
                statusView.setText(getString(R.string.toast_video_open_failed) + "\n" + finalUri);
                logPlaybackFailure("player_error");
                if (!TextUtils.isEmpty(path)) {
                    tryOpenExternal(finalUri, path);
                }
                return true;
            }
        });
        if (hasLocalFile) {
            AtlasDevLogger.i(this, "Atlas.VideoPlayer", "setVideoPath: " + path + " (uri=" + finalUri + ")");
            videoView.setVideoPath(path);
        } else {
            videoView.setVideoURI(finalUri);
        }
    }

    @Override
    protected void onStop() {
        if (playbackStarted && !playbackCompleted) {
            long playedDurationMs = playbackTracker == null
                    ? 0L
                    : playbackTracker.pause(
                            SystemClock.elapsedRealtime());
            logPlayback(
                    ResearchEventNames.MEDIA_PLAY_PAUSED,
                    ResearchInteractionLogger.properties(
                            "position_ms",
                            Math.max(
                                    0,
                                    videoView.getCurrentPosition()),
                            "played_duration_ms",
                            playedDurationMs,
                            "reason", "screen_hidden"));
            videoView.pause();
            playbackStarted = false;
        }
        super.onStop();
    }

    private void logPlaybackFailure(String failureType) {
        long playedDurationMs = playbackTracker == null
                ? 0L
                : playbackTracker.pause(
                        SystemClock.elapsedRealtime());
        logPlayback(
                ResearchEventNames.MEDIA_PLAY_FAILED,
                ResearchInteractionLogger.properties(
                        "position_ms",
                        videoView == null
                                ? 0
                                : Math.max(
                                        0,
                                        videoView.getCurrentPosition()),
                        "played_duration_ms",
                        playedDurationMs,
                        "failure_type", failureType));
        playbackStarted = false;
    }

    private void logPlayback(
            String eventName,
            org.json.JSONObject properties
    ) {
        try {
            properties.put(
                    "media_item_id", researchMediaItemId);
            properties.put("media_type", "video");
            properties.put(
                    "playback_instance_id",
                    TextUtils.isEmpty(playbackInstanceId)
                            ? UUID.randomUUID().toString()
                            : playbackInstanceId);
        } catch (org.json.JSONException ignored) {
        }
        ResearchInteractionLogger.log(
                this,
                eventName,
                researchSessionId,
                researchMomentId,
                null,
                properties);
    }

    private void logMediaOpened(String openTarget) {
        ResearchInteractionLogger.log(
                this,
                ResearchEventNames.MEDIA_OPENED,
                researchSessionId,
                researchMomentId,
                null,
                ResearchInteractionLogger.properties(
                        "media_item_id",
                        researchMediaItemId,
                        "media_type", "video",
                        "open_target", openTarget));
    }

    private void showFallbackFrame(String path) {
        if (TextUtils.isEmpty(path) || fallbackFrameView == null) {
            return;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            Bitmap bitmap = retriever.getFrameAtTime(0);
            if (bitmap != null) {
                fallbackFrameView.setImageBitmap(bitmap);
                fallbackFrameView.setVisibility(View.VISIBLE);
                AtlasDevLogger.i(this, "Atlas.VideoPlayer", "fallback frame loaded: "
                        + bitmap.getWidth() + "x" + bitmap.getHeight() + ", " + path);
            }
        } catch (Exception e) {
            AtlasDevLogger.w(this, "Atlas.VideoPlayer", "fallback frame failed: " + path + ", " + e.getMessage());
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private String readVideoMeta(String path) {
        if (TextUtils.isEmpty(path)) {
            return "";
        }
        File file = new File(path);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            return "meta=" + width + "x" + height + ", duration_ms=" + duration + ", bytes=" + file.length();
        } catch (Exception e) {
            return "meta_failed=" + e.getMessage() + ", bytes=" + (file.exists() ? file.length() : -1);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void tryOpenExternal(Uri uri, String path) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            logMediaOpened("external");
            Toast.makeText(this, R.string.toast_video_external_open, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            Toast.makeText(this, getString(R.string.toast_video_open_failed) + "\n" + path, Toast.LENGTH_LONG).show();
        }
    }
}
