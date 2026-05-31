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
        mapWebView.loadDataWithBaseURL("https://webapi.amap.com/", buildMapHtml(events), "text/html", "UTF-8", null);
    }

    private String buildMapHtml(List<AtlasReviewRepository.EventSummary> events) {
        StringBuilder items = new StringBuilder();
        int count = 0;
        for (AtlasReviewRepository.EventSummary item : events) {
            if (count >= 50) {
                break;
            }
            if (items.length() > 0) {
                items.append(',');
            }
            items.append('{')
                    .append("id:'").append(jsEscape(item.eventId)).append("',")
                    .append("name:'").append(jsEscape(!TextUtils.isEmpty(item.locationName) ? item.locationName : item.eventId)).append("',")
                    .append("time:'").append(jsEscape(item.timeRangeText)).append("',")
                    .append("weather:'").append(jsEscape(item.weather == null ? "" : item.weather)).append("',")
                    .append("lng:").append(String.format(Locale.US, "%.6f", mapLng(item))).append(',')
                    .append("lat:").append(String.format(Locale.US, "%.6f", mapLat(item))).append(',')
                    .append("coord:'").append(item.amapLat != null && item.amapLng != null ? "amap" : "gps").append("'")
                    .append('}');
            count += 1;
        }
        return "<!doctype html><html><head>"
                + "<meta charset='utf-8'>"
                + "<meta name='viewport' content='initial-scale=1,maximum-scale=1,user-scalable=no,width=device-width'>"
                + "<style>"
                + "html,body,#map{width:100%;height:100%;margin:0;padding:0;background:#F4EFE9;}"
                + ".info{font-family:sans-serif;color:#333;padding:2px 0;line-height:1.45;max-width:230px;}"
                + ".title{font-weight:700;font-size:14px;margin-bottom:3px;}"
                + ".meta{font-size:12px;color:#666;}"
                + "</style>"
                + "<script src='https://webapi.amap.com/maps?v=1.4.15&key=" + htmlEscape(BuildConfig.AMAP_API_KEY) + "'></script>"
                + "</head><body><div id='map'></div><script>"
                + "var raw=[" + items.toString() + "];"
                + "var map=new AMap.Map('map',{resizeEnable:true,zoom:13,zooms:[3,19],viewMode:'2D'});"
                + "var bounds=[];"
                + "var info=new AMap.InfoWindow({offset:new AMap.Pixel(0,-28)});"
                + "function esc(s){return String(s||'').replace(/[&<>\"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c];});}"
                + "function content(it){var w=it.weather?'<div class=\"meta\">'+esc(it.weather)+'</div>':'';return '<div class=\"info\"><div class=\"title\">'+esc(it.name)+'</div><div class=\"meta\">'+esc(it.time)+'</div>'+w+'</div>';}"
                + "function add(it,pos){var m=new AMap.Marker({map:map,position:pos,title:it.name});m.on('click',function(){info.setContent(content(it));info.open(map,pos);});bounds.push(pos);}"
                + "function fit(){if(bounds.length===1){map.setCenter(bounds[0]);map.setZoom(15);}else if(bounds.length>1){map.setFitView();}}"
                + "var pending=raw.length;"
                + "function done(){pending--;if(pending<=0)fit();}"
                + "if(!raw.length){map.setZoom(4);}else{raw.forEach(function(it){var p=[it.lng,it.lat];if(it.coord==='gps'&&AMap.convertFrom){AMap.convertFrom(p,'gps',function(status,result){if(status==='complete'&&result.locations&&result.locations.length){p=result.locations[0];}add(it,p);done();});}else{add(it,p);done();}});}"
                + "</script></body></html>";
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

    private String jsEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private String htmlEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
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
