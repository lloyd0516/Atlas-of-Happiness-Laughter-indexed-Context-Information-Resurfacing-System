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

/**
 * Requirement 2 pre-screen: after the user manually stops a session, several rule-based
 * laughter events may have been aggregated. List them (named by timestamp) so the user can
 * pick which ones to add subjective notes to; the list can be left at any time without
 * supplementing every event.
 */
public class SupplementPickerActivity extends AppCompatActivity {
    public static final String EXTRA_SESSION_ID = "session_id";

    private AtlasReviewRepository repository;
    private LinearLayout eventContainer;
    private TextView emptyView;
    private String sessionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_supplement_picker);

        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        repository = new AtlasReviewRepository(this);
        eventContainer = findViewById(R.id.pickerEventContainer);
        emptyView = findViewById(R.id.emptyPickerView);

        findViewById(R.id.btnPickerDone).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        render();
    }

    private void render() {
        List<AtlasReviewRepository.EventSummary> events = repository.loadEventSummariesForSession(sessionId);
        eventContainer.removeAllViews();
        emptyView.setVisibility(events.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (final AtlasReviewRepository.EventSummary event : events) {
            View card = inflater.inflate(R.layout.item_event_card, eventContainer, false);
            ((TextView) card.findViewById(R.id.txtEventTime)).setText(event.timeRangeText);
            ((TextView) card.findViewById(R.id.txtEventBody)).setText(event.timeRangeText);
            String metaText = getString(R.string.label_period) + ": " + event.periodCount;
            ((TextView) card.findViewById(R.id.txtEventMeta)).setText(metaText);
            ((ImageView) card.findViewById(R.id.imgEventIcon)).setImageResource(R.drawable.ic_atlas_laughter);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(SupplementPickerActivity.this, EventSupplementActivity.class);
                    intent.putExtra("event_id", event.eventId);
                    intent.putExtra("session_id", event.sessionId);
                    startActivity(intent);
                }
            });
            eventContainer.addView(card);
        }
    }
}
