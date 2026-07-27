package com.hry.camera.usbcamerademo;

import android.text.TextUtils;

import java.util.List;
import java.util.Locale;

/**
 * Shared AMap WebView HTML builder used by both the legacy standalone map screen
 * (MapReviewActivity) and the new map tab inside ReviewShellActivity (requirement 3),
 * so the two never drift apart.
 */
public final class AtlasMapHtmlBuilder {
    private AtlasMapHtmlBuilder() {
    }

    /**
     * Requirement 3.I (redesign pass): markers match materials/地图组织视图.jpg exactly — a small
     * white pin-card per location with an orange laughing-face icon, the bold location name, and
     * a gray "N次笑声" count, instead of AMap's default blue teardrop. Events sharing the same
     * rounded coordinate are grouped into one pin card.
     */
    public static String build(List<AtlasReviewRepository.EventSummary> events) {
        return build(events, null, null);
    }

    public static String build(
            List<AtlasReviewRepository.EventSummary> events,
            Double focusGpsLat,
            Double focusGpsLng) {
        java.util.LinkedHashMap<String, PinGroup> groups = new java.util.LinkedHashMap<>();
        for (AtlasReviewRepository.EventSummary item : events) {
            double lat = mapLat(item);
            double lng = mapLng(item);
            String key = String.format(Locale.US, "%.3f,%.3f", lat, lng);
            PinGroup group = groups.get(key);
            if (group == null) {
                group = new PinGroup();
                group.lat = lat;
                group.lng = lng;
                group.coordIsGps = item.amapLat == null || item.amapLng == null;
                group.name = !TextUtils.isEmpty(item.locationName) ? item.locationName : item.eventId;
                groups.put(key, group);
            }
            group.laughterCount += Math.max(1, item.periodCount);
        }

        StringBuilder items = new StringBuilder();
        int count = 0;
        for (PinGroup group : groups.values()) {
            if (count >= 50) {
                break;
            }
            if (items.length() > 0) {
                items.append(',');
            }
            items.append('{')
                    .append("name:'").append(jsEscape(group.name)).append("',")
                    .append("laughs:").append(group.laughterCount).append(',')
                    .append("lng:").append(String.format(Locale.US, "%.6f", group.lng)).append(',')
                    .append("lat:").append(String.format(Locale.US, "%.6f", group.lat)).append(',')
                    .append("coord:'").append(group.coordIsGps ? "gps" : "amap").append("'")
                    .append('}');
            count += 1;
        }
        String focus = focusGpsLat == null || focusGpsLng == null
                ? "null"
                : "{lat:" + String.format(Locale.US, "%.6f", focusGpsLat)
                + ",lng:" + String.format(Locale.US, "%.6f", focusGpsLng) + "}";
        return "<!doctype html><html><head>"
                + "<meta charset='utf-8'>"
                + "<meta name='viewport' content='initial-scale=1,maximum-scale=1,user-scalable=no,width=device-width'>"
                + "<style>"
                + "html,body,#map{width:100%;height:100%;margin:0;padding:0;background:#F7F4EF;}"
                + ".pin{display:flex;align-items:center;gap:6px;background:#FFFFFF;border-radius:20px;"
                + "padding:6px 12px 6px 6px;box-shadow:0 2px 8px rgba(0,0,0,0.15);white-space:nowrap;}"
                + ".pin .face{width:22px;height:22px;border-radius:50%;background:#FF982F;flex-shrink:0;}"
                + ".pin .label{font-family:sans-serif;font-weight:700;font-size:13px;color:#2B2B2B;}"
                + ".pin .count{font-family:sans-serif;font-size:11px;color:#8C877E;margin-left:2px;}"
                + "</style>"
                + "<script src='https://webapi.amap.com/maps?v=1.4.15&key=" + htmlEscape(BuildConfig.AMAP_API_KEY) + "'></script>"
                + "</head><body><div id='map'></div><script>"
                + "var raw=[" + items.toString() + "];"
                + "var focus=" + focus + ";"
                + "var map=new AMap.Map('map',{resizeEnable:true,zoom:13,zooms:[3,19],viewMode:'2D'});"
                + "map.setDefaultCursor('default');"
                + "var bounds=[];"
                + "function esc(s){return String(s||'').replace(/[&<>\"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c];});}"
                + "function content(it){return '<div class=\"pin\"><div class=\"face\"></div><span class=\"label\">'+esc(it.name)+'</span><span class=\"count\">'+it.laughs+'次笑声</span></div>';}"
                + "function add(it,pos){var m=new AMap.Marker({map:map,position:pos,content:content(it),offset:new AMap.Pixel(-10,-14),anchor:'bottom-left'});bounds.push(pos);}"
                + "function centerFocus(){if(!focus)return;var p=[focus.lng,focus.lat];"
                + "if(AMap.convertFrom){AMap.convertFrom(p,'gps',function(status,result){"
                + "if(status==='complete'&&result.locations&&result.locations.length){p=result.locations[0];}"
                + "map.setCenter(p);map.setZoom(17);});}else{map.setCenter(p);map.setZoom(17);}}"
                + "function fit(){if(focus){centerFocus();}else if(bounds.length===1){map.setCenter(bounds[0]);map.setZoom(15);}else if(bounds.length>1){map.setFitView();}}"
                + "var pending=raw.length;"
                + "function done(){pending--;if(pending<=0)fit();}"
                + "if(!raw.length){map.setZoom(4);}else{raw.forEach(function(it){var p=[it.lng,it.lat];if(it.coord==='gps'&&AMap.convertFrom){AMap.convertFrom(p,'gps',function(status,result){if(status==='complete'&&result.locations&&result.locations.length){p=result.locations[0];}add(it,p);done();});}else{add(it,p);done();}});}"
                + "</script></body></html>";
    }

    private static final class PinGroup {
        double lat;
        double lng;
        boolean coordIsGps;
        String name;
        int laughterCount;
    }

    private static double mapLat(AtlasReviewRepository.EventSummary event) {
        return event.amapLat != null ? event.amapLat : event.lat;
    }

    private static double mapLng(AtlasReviewRepository.EventSummary event) {
        return event.amapLng != null ? event.amapLng : event.lng;
    }

    private static String jsEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private static String htmlEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
