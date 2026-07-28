package com.hry.camera.usbcamerademo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ImageView;

/**
 * Requirement 3.II: tap a photo thumbnail in a laughter clip card to see it full-screen,
 * since the clip card's thumbnail strip is too small to make out details.
 */
public class FullscreenPhotoActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen_photo);

        String path = getIntent().getStringExtra("photo_path");
        if (!getIntent().getBooleanExtra(
                "research_media_open_logged", false)) {
            String mediaItemId = getIntent().getStringExtra(
                    "research_media_item_id");
            if (mediaItemId == null || mediaItemId.length() == 0) {
                mediaItemId = ResearchIdentifiers.anonymousId(
                        "media", path);
            }
            ResearchInteractionLogger.log(
                    this,
                    ResearchEventNames.MEDIA_OPENED,
                    getIntent().getStringExtra(
                            "research_session_id"),
                    getIntent().getStringExtra(
                            "research_moment_id"),
                    null,
                    ResearchInteractionLogger.properties(
                            "media_item_id", mediaItemId,
                            "media_type", "photo",
                            "open_target", "fullscreen"));
        }
        ImageView imageView = findViewById(R.id.imgFullscreenPhoto);
        if (path != null) {
            Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap);
            }
        }

        findViewById(R.id.btnFullscreenClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        imageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }
}
