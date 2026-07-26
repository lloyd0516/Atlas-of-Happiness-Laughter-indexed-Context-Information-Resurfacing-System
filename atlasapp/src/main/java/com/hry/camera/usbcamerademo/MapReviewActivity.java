package com.hry.camera.usbcamerademo;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MapReviewActivity extends AppCompatActivity {
    private AtlasReviewRepository repository;
    private WebView mapWebView;
    private LinearLayout listContainer;
    private TextView emptyView;
    private List<AtlasReviewRepository.EventSummary> currentLocatedEvents = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map_review);
        repository = new AtlasReviewRepository(this);
        mapWebView = findViewById(R.id.mapWebView);
        WebSettings settings = mapWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        mapWebView.setWebViewClient(new WebViewClient());
        mapWebView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            }
        });
        listContainer = findViewById(R.id.listContainer);
        emptyView = findViewById(R.id.emptyView);
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                render();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        List<AtlasReviewRepository.EventSummary> all = repository.loadEventSummaries();
        ArrayList<AtlasReviewRepository.EventSummary> located = new ArrayList<>();
        for (AtlasReviewRepository.EventSummary item : all) {
            if (item.lat != null && item.lng != null) {
                located.add(item);
            }
        }
        currentLocatedEvents = located;
        renderList(located);

        if (located.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText(R.string.event_map_empty);
            mapWebView.loadData("", "text/html", "UTF-8");
            return;
        }

        emptyView.setVisibility(View.GONE);
        loadDynamicMap(located);
    }

    private void renderList(List<AtlasReviewRepository.EventSummary> events) {
        listContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final AtlasReviewRepository.EventSummary event : events) {
            View card = inflater.inflate(R.layout.item_event_card, listContainer, false);
            ((TextView) card.findViewById(R.id.txtEventTime)).setText(event.timeRangeText);
            ((TextView) card.findViewById(R.id.txtEventBody)).setText(!TextUtils.isEmpty(event.locationName) ? event.locationName : event.eventId);
            String weatherText = TextUtils.isEmpty(event.weather) ? "" : "  •  " + event.weather;
            ((TextView) card.findViewById(R.id.txtEventMeta)).setText(formatMapCoordinates(event) + weatherText);
            ((ImageView) card.findViewById(R.id.imgEventIcon)).setImageResource(!TextUtils.isEmpty(event.weather)
                    ? AtlasWeatherIconMapper.drawableForKey(event.weatherIconKey)
                    : R.drawable.ic_atlas_location);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openEvent(event.eventId);
                }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    loadDynamicMap(java.util.Collections.singletonList(event));
                    return true;
                }
            });
            listContainer.addView(card);
        }
    }

    private void loadDynamicMap(List<AtlasReviewRepository.EventSummary> events) {
        mapWebView.loadDataWithBaseURL("https://webapi.amap.com/", AtlasMapHtmlBuilder.build(events), "text/html", "UTF-8", null);
    }

    private double mapLat(AtlasReviewRepository.EventSummary event) {
        return event.amapLat != null ? event.amapLat : event.lat;
    }

    private double mapLng(AtlasReviewRepository.EventSummary event) {
        return event.amapLng != null ? event.amapLng : event.lng;
    }

    private String formatMapCoordinates(AtlasReviewRepository.EventSummary event) {
        String text = String.format(Locale.US, "%.6f, %.6f", event.lat, event.lng);
        if (event.accuracyMeters != null) {
            text += "  •  " + getString(R.string.label_location_accuracy) + ": " + Math.round(event.accuracyMeters) + getString(R.string.unit_meter_short);
        }
        return text;
    }

    private void openEvent(String eventId) {
        Intent intent = new Intent(this, EventDetailActivity.class);
        intent.putExtra("event_id", eventId);
        for (AtlasReviewRepository.EventSummary event : currentLocatedEvents) {
            if (eventId.equals(event.eventId)) {
                intent.putExtra("session_id", event.sessionId);
                break;
            }
        }
        startActivity(intent);
    }
}
