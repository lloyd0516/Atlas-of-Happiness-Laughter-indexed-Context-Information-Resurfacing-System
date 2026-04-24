package com.hry.camera.usbcamerademo;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public class HomeActivity extends AppCompatActivity {
    private AtlasReviewRepository repository;
    private LinearLayout eventContainer;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        repository = new AtlasReviewRepository(this);

        eventContainer = findViewById(R.id.eventContainer);
        emptyView = findViewById(R.id.emptyView);

        findViewById(R.id.cardOpenPreview).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, MainActivity.class));
            }
        });
        findViewById(R.id.cardOpenMap).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, MapReviewActivity.class));
            }
        });
        findViewById(R.id.cardOpenSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.cardOpenLogs).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, LogViewerActivity.class));
            }
        });
        findViewById(R.id.fabOpenPreview).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, MainActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        AtlasLocaleManager.apply(this);
        renderEvents();
    }

    private void renderEvents() {
        List<AtlasReviewRepository.EventSummary> events = repository.loadEventSummaries();
        eventContainer.removeAllViews();
        emptyView.setVisibility(events.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final AtlasReviewRepository.EventSummary event : events) {
            View card = inflater.inflate(R.layout.item_event_card, eventContainer, false);
            TextView time = card.findViewById(R.id.txtEventTime);
            TextView body = card.findViewById(R.id.txtEventBody);
            TextView meta = card.findViewById(R.id.txtEventMeta);
            ImageView icon = card.findViewById(R.id.imgEventIcon);

            time.setText(event.timeRangeText);
            body.setText(event.eventId);
            StringBuilder metaText = new StringBuilder();
            metaText.append(getString(R.string.label_period)).append(": ").append(event.periodCount)
                    .append("  •  media: ").append(event.mediaCount);
            if (event.weather != null && event.weather.length() > 0) {
                metaText.append("  •  ").append(event.weather);
            }
            meta.setText(metaText.toString());
            icon.setImageResource(R.drawable.ic_atlas_event);

            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(HomeActivity.this, EventDetailActivity.class);
                    intent.putExtra("event_id", event.eventId);
                    startActivity(intent);
                }
            });
            eventContainer.addView(card);
        }
    }
}
