package com.hry.camera.usbcamerademo;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

public class MapReviewActivity extends AppCompatActivity {
    private AtlasReviewRepository repository;
    private ImageView mapImage;
    private LinearLayout listContainer;
    private TextView emptyView;
    private List<AtlasReviewRepository.EventSummary> currentLocatedEvents = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map_review);
        repository = new AtlasReviewRepository(this);
        mapImage = findViewById(R.id.mapImage);
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
            mapImage.setImageDrawable(null);
            return;
        }

        if (TextUtils.isEmpty(BuildConfig.GOOGLE_MAPS_API_KEY)) {
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText(R.string.maps_key_missing);
            mapImage.setImageResource(R.drawable.ic_launcher_background);
            return;
        }

        emptyView.setVisibility(View.GONE);
        loadStaticMap(located);
    }

    private void renderList(List<AtlasReviewRepository.EventSummary> events) {
        listContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final AtlasReviewRepository.EventSummary event : events) {
            View card = inflater.inflate(R.layout.item_event_card, listContainer, false);
            ((TextView) card.findViewById(R.id.txtEventTime)).setText(event.timeRangeText);
            ((TextView) card.findViewById(R.id.txtEventBody)).setText(event.eventId);
            ((TextView) card.findViewById(R.id.txtEventMeta)).setText(event.lat + ", " + event.lng + "  •  " + (event.weather == null ? "" : event.weather));
            ((ImageView) card.findViewById(R.id.imgEventIcon)).setImageResource(R.drawable.ic_atlas_location);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openEvent(event.eventId);
                }
            });
            listContainer.addView(card);
        }
    }

    private void loadStaticMap(final List<AtlasReviewRepository.EventSummary> events) {
        final String url = buildStaticMapUrl(events);
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpsURLConnection connection = null;
                InputStream stream = null;
                try {
                    connection = (HttpsURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(15000);
                    connection.connect();
                    stream = connection.getInputStream();
                    final Bitmap bitmap = BitmapFactory.decodeStream(stream);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (bitmap != null) {
                                mapImage.setImageBitmap(bitmap);
                            }
                        }
                    });
                } catch (Exception ignored) {
                } finally {
                    try {
                        if (stream != null) {
                            stream.close();
                        }
                    } catch (Exception ignored) {
                    }
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }, "AtlasStaticMapLoader").start();
    }

    private String buildStaticMapUrl(List<AtlasReviewRepository.EventSummary> events) {
        StringBuilder markers = new StringBuilder();
        for (AtlasReviewRepository.EventSummary item : events) {
            if (markers.length() > 0) {
                markers.append('&');
            }
            markers.append("markers=color:0xD98E73%7Clabel:")
                    .append(item.eventId.length() > 0 ? item.eventId.substring(item.eventId.length() - 1).toUpperCase(Locale.US) : "E")
                    .append("%7C")
                    .append(item.lat)
                    .append(',')
                    .append(item.lng);
        }
        return String.format(Locale.US,
                "https://maps.googleapis.com/maps/api/staticmap?size=900x520&scale=2&maptype=roadmap&%s&key=%s",
                markers.toString(),
                urlEncode(BuildConfig.GOOGLE_MAPS_API_KEY));
    }

    private String urlEncode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Exception ignored) {
            return text;
        }
    }

    private void openEvent(String eventId) {
        Intent intent = new Intent(this, EventDetailActivity.class);
        intent.putExtra("event_id", eventId);
        startActivity(intent);
    }
}
