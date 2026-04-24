package com.hry.camera.usbcamerademo;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private static class SliderItem {
        String key;
        int min;
        int max;
        int step;
        SeekBar seekBar;
        TextView valueView;
    }

    private AtlasReviewRepository repository;
    private JoyfulMomentConfig config;
    private final List<SliderItem> sliderItems = new ArrayList<>();
    private LinearLayout sliderContainer;
    private TextView outputPathView;
    private TextView mapsKeyHintView;
    private EditText languageInput;
    private EditText operatingInput;
    private EditText outputRootInput;
    private EditText speechmaticsKeyInput;
    private EditText speechmaticsRtUrlInput;
    private EditText googleWeatherKeyInput;
    private RadioButton radioSystem;
    private RadioButton radioEn;
    private RadioButton radioZh;
    private View presetFrequent;
    private View presetMedium;
    private View presetSparse;
    private View presetCustom;
    private String selectedPreset;
    private boolean populating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        repository = new AtlasReviewRepository(this);
        config = JoyfulMomentConfig.load(this);
        selectedPreset = config.detectionLevel;

        sliderContainer = findViewById(R.id.sliderContainer);
        outputPathView = findViewById(R.id.txtOutputPath);
        mapsKeyHintView = findViewById(R.id.txtMapsKeyHint);
        languageInput = findViewById(R.id.inputSpeechLanguage);
        operatingInput = findViewById(R.id.inputOperatingPoint);
        outputRootInput = findViewById(R.id.inputOutputRoot);
        speechmaticsKeyInput = findViewById(R.id.inputSpeechmaticsKey);
        speechmaticsRtUrlInput = findViewById(R.id.inputSpeechmaticsRtUrl);
        googleWeatherKeyInput = findViewById(R.id.inputGoogleWeatherKey);
        radioSystem = findViewById(R.id.radioLangSystem);
        radioEn = findViewById(R.id.radioLangEn);
        radioZh = findViewById(R.id.radioLangZh);
        presetFrequent = findViewById(R.id.cardPresetFrequent);
        presetMedium = findViewById(R.id.cardPresetMedium);
        presetSparse = findViewById(R.id.cardPresetSparse);
        presetCustom = findViewById(R.id.cardPresetCustom);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAll();
            }
        });
        findViewById(R.id.btnOpenLogs).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(SettingsActivity.this, R.string.toast_open_logs, Toast.LENGTH_SHORT).show();
                startActivity(new Intent(SettingsActivity.this, LogViewerActivity.class));
            }
        });

        bindPresetCard(presetFrequent, JoyfulMomentConfig.LEVEL_FREQUENT);
        bindPresetCard(presetMedium, JoyfulMomentConfig.LEVEL_MEDIUM);
        bindPresetCard(presetSparse, JoyfulMomentConfig.LEVEL_SPARSE);
        bindPresetCard(presetCustom, JoyfulMomentConfig.LEVEL_CUSTOM);

        buildSliderRows();
        bindTextInputs();
        populateUi();
    }

    private void bindPresetCard(View view, final String level) {
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (JoyfulMomentConfig.LEVEL_CUSTOM.equals(level)) {
                    selectedPreset = JoyfulMomentConfig.LEVEL_CUSTOM;
                    refreshPresetSelection();
                    return;
                }
                config = JoyfulMomentConfig.preset(level);
                config.speechmaticsLanguage = languageInput.getText().toString().trim().length() == 0 ? config.speechmaticsLanguage : languageInput.getText().toString().trim();
                config.speechmaticsOperatingPoint = operatingInput.getText().toString().trim().length() == 0 ? config.speechmaticsOperatingPoint : operatingInput.getText().toString().trim();
                config.outputRoot = outputRootInput.getText().toString().trim().length() == 0 ? config.outputRoot : outputRootInput.getText().toString().trim();
                selectedPreset = level;
                populateUi();
            }
        });
    }

    private void buildSliderRows() {
        sliderContainer.removeAllViews();
        sliderItems.clear();
        addSlider("chunk_ms", R.string.config_chunk_ms, R.string.config_chunk_ms_desc, 50, 1000, 50);
        addSlider("clip_duration_s", R.string.config_clip_duration, R.string.config_clip_duration_desc, 5, 120, 5);
        addSlider("context_neighbor_clips", R.string.config_neighbor, R.string.config_neighbor_desc, 0, 6, 1);
        addSlider("event_window_s", R.string.config_event_window, R.string.config_event_window_desc, 60, 1800, 60);
        addSlider("trigger_video_duration_s", R.string.config_trigger_video, R.string.config_trigger_video_desc, 1, 30, 1);
        addSlider("trigger_photo_count", R.string.config_trigger_photo, R.string.config_trigger_photo_desc, 0, 6, 1);
        addSlider("speechmatics_max_delay_s", R.string.config_max_delay, R.string.config_max_delay_desc, 0, 10, 1);
        addSlider("camera_brightness", R.string.settings_camera, R.string.settings_camera_caption, 0, 100, 1);
    }

    private void addSlider(final String key, int labelRes, int descRes, final int min, final int max, final int step) {
        LayoutInflater inflater = LayoutInflater.from(this);
        View row = inflater.inflate(R.layout.item_setting_slider, sliderContainer, false);
        TextView label = row.findViewById(R.id.txtLabel);
        TextView desc = row.findViewById(R.id.txtDesc);
        final TextView value = row.findViewById(R.id.txtValue);
        SeekBar seekBar = row.findViewById(R.id.seekBar);
        label.setText(labelRes);
        desc.setText(descRes);
        seekBar.setMax((max - min) / step);
        final SliderItem item = new SliderItem();
        item.key = key;
        item.min = min;
        item.max = max;
        item.step = step;
        item.seekBar = seekBar;
        item.valueView = value;
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int actual = min + progress * step;
                value.setText(String.valueOf(actual));
                if (fromUser && !populating) {
                    selectedPreset = JoyfulMomentConfig.LEVEL_CUSTOM;
                    refreshPresetSelection();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sliderItems.add(item);
        sliderContainer.addView(row);
    }

    private void bindTextInputs() {
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!populating) {
                    selectedPreset = JoyfulMomentConfig.LEVEL_CUSTOM;
                    refreshPresetSelection();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        languageInput.addTextChangedListener(watcher);
        operatingInput.addTextChangedListener(watcher);
        outputRootInput.addTextChangedListener(watcher);
        speechmaticsKeyInput.addTextChangedListener(watcher);
        speechmaticsRtUrlInput.addTextChangedListener(watcher);
        googleWeatherKeyInput.addTextChangedListener(watcher);
    }

    private void populateUi() {
        populating = true;
        setSliderValue("chunk_ms", config.chunkMs);
        setSliderValue("clip_duration_s", config.clipDurationSec);
        setSliderValue("context_neighbor_clips", config.contextNeighborClips);
        setSliderValue("event_window_s", config.eventWindowSec);
        setSliderValue("trigger_video_duration_s", config.triggerVideoDurationSec);
        setSliderValue("trigger_photo_count", config.triggerPhotoCount);
        setSliderValue("speechmatics_max_delay_s", config.speechmaticsMaxDelaySec == null ? 0 : config.speechmaticsMaxDelaySec);
        setSliderValue("camera_brightness", repository.getCameraBrightnessPercent());

        languageInput.setText(config.speechmaticsLanguage);
        operatingInput.setText(config.speechmaticsOperatingPoint);
        outputRootInput.setText(config.outputRoot);
        speechmaticsKeyInput.setText(JoyfulMomentConfig.getSpeechmaticsApiKey(this));
        speechmaticsRtUrlInput.setText(JoyfulMomentConfig.getSpeechmaticsRtUrl(this));
        googleWeatherKeyInput.setText(repository.getGoogleWeatherApiKey());
        mapsKeyHintView.setText(BuildConfig.GOOGLE_MAPS_API_KEY == null || BuildConfig.GOOGLE_MAPS_API_KEY.trim().length() == 0
                ? getString(R.string.maps_key_missing)
                : "Google Maps SDK key injected at build time.");

        String language = AtlasLocaleManager.getSavedLanguage(this);
        radioSystem.setChecked(AtlasLocaleManager.LANGUAGE_SYSTEM.equals(language));
        radioEn.setChecked(AtlasLocaleManager.LANGUAGE_EN.equals(language));
        radioZh.setChecked(AtlasLocaleManager.LANGUAGE_ZH.equals(language));

        outputPathView.setText(repository.getConfigMirrorPath());
        refreshPresetSelection();
        populating = false;
    }

    private void refreshPresetSelection() {
        setPresetSelected(presetFrequent, JoyfulMomentConfig.LEVEL_FREQUENT.equals(selectedPreset));
        setPresetSelected(presetMedium, JoyfulMomentConfig.LEVEL_MEDIUM.equals(selectedPreset));
        setPresetSelected(presetSparse, JoyfulMomentConfig.LEVEL_SPARSE.equals(selectedPreset));
        setPresetSelected(presetCustom, JoyfulMomentConfig.LEVEL_CUSTOM.equals(selectedPreset));
    }

    private void setPresetSelected(View view, boolean selected) {
        view.setAlpha(selected ? 1.0f : 0.70f);
        view.setScaleX(selected ? 1.0f : 0.98f);
        view.setScaleY(selected ? 1.0f : 0.98f);
    }

    private void setSliderValue(String key, int value) {
        for (SliderItem item : sliderItems) {
            if (item.key.equals(key)) {
                int clamped = Math.max(item.min, Math.min(item.max, value));
                item.seekBar.setProgress((clamped - item.min) / item.step);
                item.valueView.setText(String.valueOf(clamped));
                return;
            }
        }
    }

    private int getSliderValue(String key) {
        for (SliderItem item : sliderItems) {
            if (item.key.equals(key)) {
                return item.min + item.seekBar.getProgress() * item.step;
            }
        }
        return 0;
    }

    private void saveAll() {
        config.detectionLevel = selectedPreset == null ? JoyfulMomentConfig.LEVEL_CUSTOM : selectedPreset;
        config.chunkMs = getSliderValue("chunk_ms");
        config.clipDurationSec = getSliderValue("clip_duration_s");
        config.contextNeighborClips = getSliderValue("context_neighbor_clips");
        config.eventWindowSec = getSliderValue("event_window_s");
        config.triggerVideoDurationSec = getSliderValue("trigger_video_duration_s");
        config.triggerPhotoCount = getSliderValue("trigger_photo_count");
        config.speechmaticsMaxDelaySec = getSliderValue("speechmatics_max_delay_s");
        if (config.speechmaticsMaxDelaySec != null && config.speechmaticsMaxDelaySec <= 0) {
            config.speechmaticsMaxDelaySec = null;
        }
        config.speechmaticsLanguage = safeInput(languageInput, config.speechmaticsLanguage);
        config.speechmaticsOperatingPoint = safeInput(operatingInput, config.speechmaticsOperatingPoint);
        config.outputRoot = safeInput(outputRootInput, config.outputRoot);
        JoyfulMomentConfig.save(this, config);
        repository.saveCameraBrightnessPercent(getSliderValue("camera_brightness"));
        JoyfulMomentConfig.saveSpeechmaticsApiKey(this, speechmaticsKeyInput.getText().toString().trim());
        JoyfulMomentConfig.saveSpeechmaticsRtUrl(this, speechmaticsRtUrlInput.getText().toString().trim());
        repository.saveGoogleWeatherApiKey(googleWeatherKeyInput.getText().toString().trim());

        String language = radioZh.isChecked() ? AtlasLocaleManager.LANGUAGE_ZH
                : radioEn.isChecked() ? AtlasLocaleManager.LANGUAGE_EN
                : AtlasLocaleManager.LANGUAGE_SYSTEM;
        AtlasLocaleManager.saveLanguage(this, language);
        AtlasLocaleManager.apply(this, language);

        File mirrorFile = JoyfulMomentConfig.mirrorConfigToExternalFile(this, config);
        outputPathView.setText(mirrorFile.getAbsolutePath());
        Toast.makeText(this, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private String safeInput(EditText input, String fallback) {
        String text = input.getText().toString().trim();
        return text.length() == 0 ? fallback : text;
    }
}
