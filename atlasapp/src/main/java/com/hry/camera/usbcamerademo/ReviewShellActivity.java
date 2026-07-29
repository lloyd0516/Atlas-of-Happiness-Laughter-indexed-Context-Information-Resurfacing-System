package com.hry.camera.usbcamerademo;

import android.content.Intent;
import android.os.Bundle;
import android.support.design.widget.TabLayout;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Requirement 3: three organization views (map / calendar / timeline) over the same event set.
 * Requirement 4: "view details" from any view opens EventDetailActivity, which itself decides
 * long-term vs short-term rendering based on how old the event is.
 */
public class ReviewShellActivity extends AppCompatActivity {
    private AtlasReviewRepository repository;
    private List<AtlasReviewRepository.EventSummary> allEvents = new ArrayList<>();
    private TabLayout reviewTabLayout;
    private Double focusedMapLat;
    private Double focusedMapLng;
    private int lastReviewTab = -1;
    private int lastMapCardPosition = -1;
    private String pendingMapNavigationMethod;
    private String pendingTabSelectionSource;
    private boolean bindingMapCarousel;
    private boolean activityStarted;
    private boolean mapOpenedThisVisit;

    private View tabMapContent;
    private View tabCalendarContent;
    private View tabTimelineContent;

    private WebView mapWebView;
    private TextView mapEmptyView;
    private View mapStatsPill;
    private TextView txtMapStatsHeadline;
    private TextView txtMapStatsSubline;
    private View mapTrailSummary;
    private TextView txtMapTrailSummary;
    private StackedCardView mapEventStack;
    private View mapStackControls;
    private View btnMapStackPrevious;
    private View btnMapStackNext;
    private TextView txtMapStackPosition;
    private List<AtlasReviewRepository.EventSummary> mapLocatedEvents = new ArrayList<>();

    private TextView calendarMonthLabel;
    private GridLayout calendarGrid;
    private LinearLayout calendarDayEventsContainer;
    private TextView calendarDayHeading;
    private Calendar calendarCursor = Calendar.getInstance();
    private Long selectedDayStartMs = null;

    private LinearLayout timelineContainer;
    private TextView timelineEmptyView;

    private final SimpleDateFormat dayKeyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat monthLabelFormat = new SimpleDateFormat("yyyy年M月", Locale.getDefault());
    private final SimpleDateFormat dayHeaderFormat = new SimpleDateFormat("M月d日 EEEE", Locale.getDefault());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review_shell);
        repository = new AtlasReviewRepository(this);

        tabMapContent = findViewById(R.id.panelMap);
        tabCalendarContent = findViewById(R.id.panelCalendar);
        tabTimelineContent = findViewById(R.id.panelTimeline);

        mapWebView = findViewById(R.id.mapWebView);
        mapEmptyView = findViewById(R.id.emptyMapView);
        mapStatsPill = findViewById(R.id.mapStatsPill);
        txtMapStatsHeadline = findViewById(R.id.txtMapStatsHeadline);
        txtMapStatsSubline = findViewById(R.id.txtMapStatsSubline);
        mapTrailSummary = findViewById(R.id.mapTrailSummary);
        txtMapTrailSummary = findViewById(R.id.txtMapTrailSummary);
        mapEventStack = findViewById(R.id.mapEventStack);
        mapStackControls = findViewById(R.id.mapStackControls);
        btnMapStackPrevious = findViewById(R.id.btnMapStackPrevious);
        btnMapStackNext = findViewById(R.id.btnMapStackNext);
        txtMapStackPosition = findViewById(R.id.txtMapStackPosition);
        btnMapStackPrevious.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingMapNavigationMethod =
                        "previous_button";
                mapEventStack.showPrevious();
            }
        });
        btnMapStackNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingMapNavigationMethod = "next_button";
                mapEventStack.showNext();
            }
        });
        mapEventStack.setOnPositionChangedListener(new StackedCardView.OnPositionChangedListener() {
            @Override
            public void onPositionChanged(int zeroBasedPosition, int total) {
                updateMapCarouselControls(zeroBasedPosition, total);
                if (bindingMapCarousel) {
                    lastMapCardPosition = zeroBasedPosition;
                    pendingMapNavigationMethod = null;
                    return;
                }
                if (lastMapCardPosition < 0) {
                    lastMapCardPosition = zeroBasedPosition;
                    pendingMapNavigationMethod = null;
                    return;
                }
                if (zeroBasedPosition != lastMapCardPosition) {
                    ResearchInteractionLogger.log(
                            ReviewShellActivity.this,
                            ResearchEventNames.MAP_CARD_CHANGED,
                            null,
                            null,
                            null,
                            ResearchInteractionLogger.properties(
                                    "from_index",
                                    lastMapCardPosition,
                                    "to_index",
                                    zeroBasedPosition,
                                    "total", total,
                                    "navigation_method",
                                    TextUtils.isEmpty(
                                            pendingMapNavigationMethod)
                                            ? "swipe"
                                            : pendingMapNavigationMethod));
                    lastMapCardPosition = zeroBasedPosition;
                }
                pendingMapNavigationMethod = null;
            }
        });
        setUpMapWebView();

        calendarMonthLabel = findViewById(R.id.txtCalendarMonth);
        calendarGrid = findViewById(R.id.calendarGrid);
        calendarDayEventsContainer = findViewById(R.id.calendarDayEventsContainer);
        calendarDayHeading = findViewById(R.id.txtCalendarDayHeading);
        findViewById(R.id.btnCalendarPrevMonth).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logCalendarMonthChange("previous");
                calendarCursor.add(Calendar.MONTH, -1);
                selectedDayStartMs = null;
                renderCalendar();
            }
        });
        findViewById(R.id.btnCalendarNextMonth).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logCalendarMonthChange("next");
                calendarCursor.add(Calendar.MONTH, 1);
                selectedDayStartMs = null;
                renderCalendar();
            }
        });

        timelineContainer = findViewById(R.id.timelineContainer);
        timelineEmptyView = findViewById(R.id.emptyTimelineView);

        reviewTabLayout = findViewById(R.id.reviewTabLayout);
        reviewTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                String source = TextUtils.isEmpty(
                        pendingTabSelectionSource)
                        ? "user" : pendingTabSelectionSource;
                pendingTabSelectionSource = null;
                showTab(tab.getPosition(), source);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        AtlasBottomNav.setup(this, AtlasBottomNav.TAB_REVIEW);
        applyNavigationIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        mapOpenedThisVisit = false;
        if (lastReviewTab == 0) {
            logMapOpenedIfNeeded();
        }
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        allEvents = repository.loadEventSummaries();
        Collections.sort(allEvents, new Comparator<AtlasReviewRepository.EventSummary>() {
            @Override
            public int compare(AtlasReviewRepository.EventSummary a, AtlasReviewRepository.EventSummary b) {
                return Long.compare(b.startTimeMs, a.startTimeMs);
            }
        });
        renderMap();
        renderCalendar();
        renderTimeline();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyNavigationIntent(intent);
        if (repository != null) {
            renderMap();
        }
    }

    private void applyNavigationIntent(Intent intent) {
        ResearchNotificationTracker.logOpened(
                this, intent);
        if (intent != null && "map".equals(
                intent.getStringExtra("initial_review_tab"))) {
            double lat = intent.getDoubleExtra("focus_lat", Double.NaN);
            double lng = intent.getDoubleExtra("focus_lng", Double.NaN);
            focusedMapLat = Double.isNaN(lat) ? null : lat;
            focusedMapLng = Double.isNaN(lng) ? null : lng;
        }
        TabLayout.Tab mapTab = reviewTabLayout.getTabAt(0);
        if (mapTab != null) {
            pendingTabSelectionSource =
                    "navigation_intent";
            mapTab.select();
        }
        showTab(0, "navigation_intent");
        pendingTabSelectionSource = null;
    }

    private void showTab(int position, String selectionSource) {
        tabMapContent.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        tabCalendarContent.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        tabTimelineContent.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
        if (position != lastReviewTab) {
            ResearchInteractionLogger.log(
                    this,
                    ResearchEventNames.REVIEW_TAB_SELECTED,
                    null,
                    null,
                    null,
                    ResearchInteractionLogger.properties(
                            "tab", reviewTabName(position),
                            "selection_source",
                            selectionSource));
            lastReviewTab = position;
        }
        if (position == 0) {
            logMapOpenedIfNeeded();
        }
    }

    private String reviewTabName(int position) {
        if (position == 1) {
            return "calendar";
        }
        if (position == 2) {
            return "timeline";
        }
        return "map";
    }

    private void logMapOpenedIfNeeded() {
        if (!activityStarted || mapOpenedThisVisit) {
            return;
        }
        mapOpenedThisVisit = true;
        ResearchInteractionLogger.log(
                this,
                ResearchEventNames.MAP_OPENED,
                null,
                null,
                null,
                ResearchInteractionLogger.properties(
                        "entry_source",
                        ResearchNavigation.source(
                                getIntent(), "review_tab"),
                        "legacy", false,
                        "focused_from_notification",
                        focusedMapLat != null
                                && focusedMapLng != null));
    }

    private void logCalendarMonthChange(String direction) {
        ResearchInteractionLogger.log(
                this,
                ResearchEventNames.REVIEW_CALENDAR_MONTH_CHANGED,
                null,
                null,
                null,
                ResearchInteractionLogger.properties(
                        "direction", direction));
    }

    // ---------------------------------------------------------------------------------------
    // Map view (reuses the AMap WebView approach from MapReviewActivity, folded into a tab).
    // ---------------------------------------------------------------------------------------

    private void setUpMapWebView() {
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
    }

    /**
     * Requirement 3 (map redesign, per materials/地图组织视图.jpg): full-bleed map, a floating
     * "N个地点 · N次笑声记录" pill, a "笑声轨迹" one-line summary, and a swipeable stack of the
     * most recent located events (real drag gesture via StackedCardView, not a plain list).
     */
    private void renderMap() {
        final ArrayList<AtlasReviewRepository.EventSummary> located = new ArrayList<>();
        for (AtlasReviewRepository.EventSummary item : allEvents) {
            if (item.lat != null && item.lng != null) {
                located.add(item);
            }
        }
        if (located.isEmpty()) {
            lastMapCardPosition = -1;
            pendingMapNavigationMethod = null;
            mapEmptyView.setVisibility(View.VISIBLE);
            mapStatsPill.setVisibility(View.GONE);
            mapTrailSummary.setVisibility(View.GONE);
            mapEventStack.setVisibility(View.GONE);
            mapStackControls.setVisibility(View.GONE);
            mapWebView.loadData("", "text/html", "UTF-8");
            return;
        }
        mapEmptyView.setVisibility(View.GONE);
        mapStatsPill.setVisibility(View.VISIBLE);
        mapTrailSummary.setVisibility(View.VISIBLE);
        mapEventStack.setVisibility(View.VISIBLE);
        mapStackControls.setVisibility(View.VISIBLE);
        mapWebView.loadDataWithBaseURL(
                "https://webapi.amap.com/",
                AtlasMapHtmlBuilder.build(located, focusedMapLat, focusedMapLng),
                "text/html",
                "UTF-8",
                null);

        java.util.Set<String> distinctLocations = new java.util.HashSet<>();
        for (AtlasReviewRepository.EventSummary item : located) {
            distinctLocations.add(!TextUtils.isEmpty(item.locationName) ? item.locationName : (item.lat + "," + item.lng));
        }
        int mapLaughterCount =
                AtlasLaughterCountPresentation.total(located);
        txtMapStatsHeadline.setText(getString(R.string.map_stats_headline, distinctLocations.size()));
        txtMapStatsSubline.setText(
                mapLaughterCount > 0
                        ? getString(
                        R.string.map_stats_subline,
                        mapLaughterCount)
                        : getString(
                        R.string.event_laughter_count_empty));

        String topLocation = located.get(0).locationName;
        txtMapTrailSummary.setText(TextUtils.isEmpty(topLocation)
                ? (mapLaughterCount > 0
                ? getString(
                R.string.map_trail_summary_generic,
                mapLaughterCount)
                : getString(
                R.string.event_laughter_count_empty))
                : getString(
                R.string.map_trail_summary,
                distinctLocations.size()));

        bindingMapCarousel = true;
        mapEventStack.setAdapter(R.layout.item_map_stack_card, located.size(), new StackedCardView.Binder() {
            @Override
            public void bind(View card, int position) {
                AtlasReviewRepository.EventSummary event = located.get(position);
                ((TextView) card.findViewById(R.id.txtStackCardTitle)).setText(
                        !TextUtils.isEmpty(event.locationName) ? event.locationName : event.eventId);
                ((TextView) card.findViewById(R.id.txtStackCardMeta)).setText(event.timeRangeText
                        + "  ·  " + laughterCountText(
                        event.laughterClipCount));
            }
        });
        mapEventStack.setOnCardClickListener(new StackedCardView.OnCardClickListener() {
            @Override
            public void onCardClick(int position) {
                AtlasReviewRepository.EventSummary event =
                        located.get(position);
                ResearchInteractionLogger.log(
                        ReviewShellActivity.this,
                        ResearchEventNames.MAP_MOMENT_OPENED,
                        event.sessionId,
                        event.eventId,
                        null,
                        ResearchInteractionLogger.properties(
                                "card_index", position,
                                "total", located.size(),
                                "map_variant", "review_shell"));
                openEvent(event, "map_card");
            }
        });
        bindingMapCarousel = false;
    }

    private void updateMapCarouselControls(int zeroBasedPosition, int total) {
        if (total <= 0 || zeroBasedPosition < 0) {
            mapStackControls.setVisibility(View.GONE);
            return;
        }

        mapStackControls.setVisibility(View.VISIBLE);
        txtMapStackPosition.setText(
                getString(R.string.map_stack_position, zeroBasedPosition + 1, total));
        boolean canMove = total > 1;
        btnMapStackPrevious.setEnabled(canMove);
        btnMapStackNext.setEnabled(canMove);
        btnMapStackPrevious.setAlpha(canMove ? 1f : 0.4f);
        btnMapStackNext.setAlpha(canMove ? 1f : 0.4f);
    }

    private void openEvent(
            AtlasReviewRepository.EventSummary event,
            String entrySource
    ) {
        Intent intent = new Intent(this, EventDetailActivity.class);
        intent.putExtra("event_id", event.eventId);
        intent.putExtra("session_id", event.sessionId);
        ResearchNavigation.withSource(intent, entrySource);
        startActivity(intent);
    }

    // ---------------------------------------------------------------------------------------
    // Calendar view: full month grid, dot marks days with events, tap a day to filter below.
    // ---------------------------------------------------------------------------------------

    private void renderCalendar() {
        calendarMonthLabel.setText(monthLabelFormat.format(calendarCursor.getTime()));

        Map<String, List<AtlasReviewRepository.EventSummary>> byDay = new HashMap<>();
        for (AtlasReviewRepository.EventSummary item : allEvents) {
            String key = dayKeyFormat.format(new java.util.Date(item.startTimeMs));
            List<AtlasReviewRepository.EventSummary> bucket = byDay.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byDay.put(key, bucket);
            }
            bucket.add(item);
        }

        calendarGrid.removeAllViews();
        Calendar monthStart = (Calendar) calendarCursor.clone();
        monthStart.set(Calendar.DAY_OF_MONTH, 1);
        int leadingBlanks = (monthStart.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY + 7) % 7;
        int daysInMonth = monthStart.getActualMaximum(Calendar.DAY_OF_MONTH);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < leadingBlanks; i++) {
            View blank = new View(this);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(i % 7, 1f);
            calendarGrid.addView(blank, params);
        }

        Calendar today = Calendar.getInstance();
        boolean isCurrentMonth = today.get(Calendar.YEAR) == monthStart.get(Calendar.YEAR)
                && today.get(Calendar.MONTH) == monthStart.get(Calendar.MONTH);

        for (int day = 1; day <= daysInMonth; day++) {
            final Calendar cellCal = (Calendar) monthStart.clone();
            cellCal.set(Calendar.DAY_OF_MONTH, day);
            String key = dayKeyFormat.format(cellCal.getTime());
            final List<AtlasReviewRepository.EventSummary> dayEvents = byDay.get(key);
            final long dayStartMs = startOfDay(cellCal).getTimeInMillis();

            View cell = inflater.inflate(R.layout.item_calendar_day, calendarGrid, false);
            TextView dayNumber = cell.findViewById(R.id.txtDayNumber);
            TextView dayCaption = cell.findViewById(R.id.txtDayEventCount);
            dayNumber.setText(String.valueOf(day));
            int dayEventCount = dayEvents != null ? dayEvents.size() : 0;
            int dayLaughterCount =
                    AtlasLaughterCountPresentation.total(dayEvents);
            dayCaption.setText(
                    dayEventCount > 0
                            ? laughterCountText(dayLaughterCount)
                            : "");
            dayCaption.setVisibility(
                    dayEventCount > 0
                            ? View.VISIBLE
                            : View.GONE);
            boolean isToday = isCurrentMonth && today.get(Calendar.DAY_OF_MONTH) == day;
            boolean isSelected = selectedDayStartMs != null && selectedDayStartMs == dayStartMs;
            dayNumber.setBackgroundResource(isSelected || isToday ? R.drawable.atlas_calendar_day_bg : 0);

            int column = (leadingBlanks + day - 1) % 7;
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(column, 1f);
            cell.setLayoutParams(params);

            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedDayStartMs = dayStartMs;
                    Calendar todayStart =
                            startOfDay(Calendar.getInstance());
                    long daysFromToday = Math.round(
                            (dayStartMs
                                    - todayStart.getTimeInMillis())
                                    / (24d * 60d * 60d * 1000d));
                    ResearchInteractionLogger.log(
                            ReviewShellActivity.this,
                            ResearchEventNames.REVIEW_CALENDAR_DAY_SELECTED,
                            null,
                            null,
                            null,
                            ResearchInteractionLogger.properties(
                                    "days_from_today",
                                    daysFromToday,
                                    "event_count",
                                    dayEvents == null
                                            ? 0
                                            : dayEvents.size()));
                    renderCalendar();
                }
            });
            calendarGrid.addView(cell, params);
        }

        List<AtlasReviewRepository.EventSummary> selectedDayEvents = null;
        if (selectedDayStartMs != null) {
            String key = dayKeyFormat.format(new java.util.Date(selectedDayStartMs));
            selectedDayEvents = byDay.get(key);
        }
        calendarDayEventsContainer.removeAllViews();
        if (selectedDayStartMs == null) {
            calendarDayHeading.setText(R.string.review_calendar_pick_day);
        } else if (selectedDayEvents == null || selectedDayEvents.isEmpty()) {
            calendarDayHeading.setText(dayHeaderFormat.format(new java.util.Date(selectedDayStartMs)));
            TextView empty = new TextView(this);
            empty.setText(R.string.review_calendar_day_empty);
            empty.setTextColor(getResources().getColor(R.color.atlas_text_secondary));
            calendarDayEventsContainer.addView(empty);
        } else {
            calendarDayHeading.setText(dayHeaderFormat.format(new java.util.Date(selectedDayStartMs)));
            renderCalendarEventCards(selectedDayEvents);
        }
    }

    /** Requirement 2.II: calendar day-events cards, matching materials/日历组织视图.jpg exactly. */
    private void renderCalendarEventCards(List<AtlasReviewRepository.EventSummary> events) {
        calendarDayEventsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final AtlasReviewRepository.EventSummary event : events) {
            View card = inflater.inflate(R.layout.item_calendar_event_card, calendarDayEventsContainer, false);
            ((TextView) card.findViewById(R.id.txtCalEventTitle)).setText(
                    !TextUtils.isEmpty(event.locationName) ? event.locationName : event.eventId);
            ((TextView) card.findViewById(R.id.txtCalEventDuration)).setText(timeFormat.format(new java.util.Date(event.startTimeMs)));
            String weatherText = TextUtils.isEmpty(event.weather) ? "" : "  ·  " + event.weather;
            ((TextView) card.findViewById(R.id.txtCalEventMeta)).setText(
                    event.timeRangeText
                            + "  ·  "
                            + laughterCountText(
                            event.laughterClipCount)
                            + weatherText);
            final String eventId = event.eventId;
            final String sessionId = event.sessionId;
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openEvent(
                            eventId,
                            sessionId,
                            "calendar_card");
                }
            });
            calendarDayEventsContainer.addView(card);
        }
    }

    private Calendar startOfDay(Calendar source) {
        Calendar copy = (Calendar) source.clone();
        copy.set(Calendar.HOUR_OF_DAY, 0);
        copy.set(Calendar.MINUTE, 0);
        copy.set(Calendar.SECOND, 0);
        copy.set(Calendar.MILLISECOND, 0);
        return copy;
    }

    // ---------------------------------------------------------------------------------------
    // Timeline view: reverse-chronological list grouped by day, connecting line like the mockup.
    // ---------------------------------------------------------------------------------------

    private void renderTimeline() {
        timelineContainer.removeAllViews();
        if (allEvents.isEmpty()) {
            timelineEmptyView.setVisibility(View.VISIBLE);
            return;
        }
        timelineEmptyView.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        String currentDayKey = null;
        for (int i = 0; i < allEvents.size(); i++) {
            AtlasReviewRepository.EventSummary event = allEvents.get(i);
            String dayKey = dayKeyFormat.format(new java.util.Date(event.startTimeMs));
            if (!dayKey.equals(currentDayKey)) {
                currentDayKey = dayKey;
                TextView header = new TextView(this);
                header.setText(dayHeaderFormat.format(new java.util.Date(event.startTimeMs)));
                header.setTextColor(getResources().getColor(R.color.atlas_text_primary));
                header.setTextSize(14f);
                LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                headerParams.topMargin = dpToPx(18);
                headerParams.bottomMargin = dpToPx(6);
                header.setLayoutParams(headerParams);
                timelineContainer.addView(header);
            }

            View row = inflater.inflate(R.layout.item_timeline_entry, timelineContainer, false);
            TextView timeBubble = row.findViewById(R.id.txtTimelineTime);
            TextView title = row.findViewById(R.id.txtTimelineTitle);
            TextView meta = row.findViewById(R.id.txtTimelineMeta);
            View connectorBelow = row.findViewById(R.id.timelineRail);
            final ImageView coverImage = row.findViewById(R.id.imgTimelineCover);
            final View coverPlaceholder = row.findViewById(R.id.imgTimelineCoverPlaceholder);
            View coverEditButton = row.findViewById(R.id.txtTimelineCoverEdit);

            timeBubble.setText(timeFormat.format(new java.util.Date(event.startTimeMs)));
            title.setText(!TextUtils.isEmpty(event.locationName) ? event.locationName : event.eventId);
            StringBuilder metaText = new StringBuilder();
            metaText.append(
                    laughterCountText(
                            event.laughterClipCount));
            if (!TextUtils.isEmpty(event.weather)) {
                metaText.append("  •  ").append(event.weather);
            }
            meta.setText(metaText.toString());

            String coverPath = repository.getCoverPhoto(event.eventJson);
            if (!TextUtils.isEmpty(coverPath)) {
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(coverPath);
                if (bitmap != null) {
                    coverImage.setImageBitmap(bitmap);
                    coverPlaceholder.setVisibility(View.GONE);
                } else {
                    coverImage.setImageDrawable(null);
                    coverPlaceholder.setVisibility(View.VISIBLE);
                }
            } else {
                coverImage.setImageDrawable(null);
                coverPlaceholder.setVisibility(View.VISIBLE);
            }

            boolean isLastOfDay = (i == allEvents.size() - 1)
                    || !dayKeyFormat.format(new java.util.Date(allEvents.get(i + 1).startTimeMs)).equals(dayKey);
            connectorBelow.setVisibility(isLastOfDay ? View.INVISIBLE : View.VISIBLE);

            final String eventId = event.eventId;
            final String sessionId = event.sessionId;
            final AtlasReviewRepository.EventSummary eventRef = event;
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openEvent(
                            eventId,
                            sessionId,
                            "timeline_card");
                }
            });
            coverEditButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showCoverPickerDialog(eventRef);
                }
            });
            timelineContainer.addView(row);
        }
    }

    /** Requirement 2.III: tap the cover pic to pick a different photo from this event's captures. */
    private void showCoverPickerDialog(final AtlasReviewRepository.EventSummary event) {
        final java.util.List<String> photoPaths = repository.getAllPhotoPaths(event.eventJson);
        if (photoPaths.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.timeline_no_photos_for_cover, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[photoPaths.size()];
        for (int i = 0; i < photoPaths.size(); i++) {
            labels[i] = new java.io.File(photoPaths.get(i)).getName();
        }
        new android.support.v7.app.AlertDialog.Builder(this)
                .setTitle(R.string.timeline_change_cover)
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String selectedPath =
                                photoPaths.get(which);
                        if (repository.setCoverPhoto(
                                event.eventJson, selectedPath)) {
                            ResearchInteractionLogger.log(
                                    ReviewShellActivity.this,
                                    ResearchEventNames.MOMENT_EDIT_COMPLETED,
                                    event.sessionId,
                                    event.eventId,
                                    null,
                                    ResearchInteractionLogger.properties(
                                            "field_category",
                                            "cover_photo",
                                            "operation", "replace",
                                            "media_item_id",
                                            ResearchIdentifiers.anonymousId(
                                                    "media",
                                                    selectedPath)));
                            allEvents = repository.loadEventSummaries();
                            renderTimeline();
                        }
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------

    private String laughterCountText(int laughterClipCount) {
        return laughterClipCount > 0
                ? getString(
                R.string.event_laughter_count,
                laughterClipCount)
                : getString(
                R.string.event_laughter_count_empty);
    }

    private void renderCardList(LinearLayout container, List<AtlasReviewRepository.EventSummary> events, int fallbackIcon) {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final AtlasReviewRepository.EventSummary event : events) {
            View card = inflater.inflate(R.layout.item_event_card, container, false);
            ((TextView) card.findViewById(R.id.txtEventTime)).setText(event.timeRangeText);
            ((TextView) card.findViewById(R.id.txtEventBody)).setText(!TextUtils.isEmpty(event.locationName) ? event.locationName : event.eventId);
            String weatherText = TextUtils.isEmpty(event.weather) ? "" : "  •  " + event.weather;
            ((TextView) card.findViewById(R.id.txtEventMeta)).setText(
                    laughterCountText(
                            event.laughterClipCount)
                            + weatherText);
            ((ImageView) card.findViewById(R.id.imgEventIcon)).setImageResource(!TextUtils.isEmpty(event.weather)
                    ? AtlasWeatherIconMapper.drawableForKey(event.weatherIconKey)
                    : fallbackIcon);
            final String eventId = event.eventId;
            final String sessionId = event.sessionId;
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openEvent(
                            eventId,
                            sessionId,
                            "review_card");
                }
            });
            container.addView(card);
        }
    }

    private void openEvent(
            String eventId,
            String sessionId,
            String entrySource
    ) {
        Intent intent = new Intent(this, EventDetailActivity.class);
        intent.putExtra("event_id", eventId);
        intent.putExtra("session_id", sessionId);
        ResearchNavigation.withSource(intent, entrySource);
        startActivity(intent);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
