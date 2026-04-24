package com.hry.camera.usbcamerademo;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;

public class VideoPlayerActivity extends AppCompatActivity {
    private VideoView videoView;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);
        videoView = findViewById(R.id.videoView);
        statusView = findViewById(R.id.txtVideoStatus);
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        final String uriString = getIntent().getStringExtra("video_uri");
        final String path = getIntent().getStringExtra("video_path");
        Uri uri = null;
        if (!TextUtils.isEmpty(uriString)) {
            uri = Uri.parse(uriString);
        } else if (!TextUtils.isEmpty(path)) {
            File file = new File(path);
            if (file.exists()) {
                try {
                    uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                } catch (Exception ignored) {
                    uri = Uri.fromFile(file);
                }
            }
        }
        if (uri == null) {
            statusView.setText(R.string.toast_video_open_failed);
            return;
        }
        final Uri finalUri = uri;
        statusView.setText(finalUri.toString());
        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                AtlasDevLogger.i(VideoPlayerActivity.this, "Atlas.VideoPlayer", "prepared: " + finalUri);
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
        videoView.setVideoURI(finalUri);
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
