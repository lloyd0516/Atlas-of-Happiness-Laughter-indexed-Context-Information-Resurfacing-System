package com.hry.camera.usbcamerademo;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public class LogViewerActivity extends AppCompatActivity {
    private AtlasReviewRepository repository;
    private LinearLayout logContainer;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        repository = new AtlasReviewRepository(this);
        logContainer = findViewById(R.id.logContainer);
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
                renderLogs();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderLogs();
    }

    private void renderLogs() {
        List<AtlasReviewRepository.LogItem> items = repository.loadMergedLogs();
        logContainer.removeAllViews();
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (AtlasReviewRepository.LogItem item : items) {
            View row = inflater.inflate(R.layout.item_log, logContainer, false);
            TextView title = row.findViewById(R.id.txtLogTitle);
            TextView body = row.findViewById(R.id.txtLogBody);
            title.setText(item.title);
            body.setText(item.body);
            logContainer.addView(row);
        }
    }
}
