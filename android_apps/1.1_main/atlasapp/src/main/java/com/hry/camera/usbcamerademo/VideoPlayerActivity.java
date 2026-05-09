package com.hry.camera.usbcamerademo;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
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

public class VideoPlayerActivity extends AppCompatActivity {
    private VideoView videoView;
    private TextView statusView;
    private ImageView fallbackFrameView;
    private Uri playbackUri;
    private String playbackPath;

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
            }
        });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                AtlasDevLogger.w(VideoPlayerActivity.this, "Atlas.VideoPlayer", "error what=" + what + ", extra=" + extra + ", uri=" + finalUri);
                statusView.setText(getString(R.string.toast_video_open_failed) + "\n" + finalUri);
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
            Toast.makeText(this, R.string.toast_video_external_open, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            Toast.makeText(this, getString(R.string.toast_video_open_failed) + "\n" + path, Toast.LENGTH_LONG).show();
        }
    }
}
