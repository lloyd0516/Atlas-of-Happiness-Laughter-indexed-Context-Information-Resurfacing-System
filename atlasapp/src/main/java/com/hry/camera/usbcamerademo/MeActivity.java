package com.hry.camera.usbcamerademo;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.Manifest;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SwitchCompat;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/**
 * Requirement 6: bottom nav "我的" tab. Only holds Language (bilingual switcher) and a Log
 * placeholder for now; the existing detailed detection/API settings screen (SettingsActivity)
 * stays reachable from here but is not itself one of the three main tabs.
 */
public class MeActivity extends AppCompatActivity {
    private static final int REQUEST_LOCATION_REMINDER = 701;
    private static final int REQUEST_BACKGROUND_LOCATION = 702;
    private static final int REQUEST_NOTIFICATIONS = 703;
    private RadioButton radioSystem;
    private RadioButton radioEn;
    private RadioButton radioZh;
    private SwitchCompat dailySwitch;
    private SwitchCompat locationSwitch;
    private TextView dailyStatus;
    private TextView locationStatus;
    private AtlasReminderPreferences reminderPreferences;
    private boolean bindingReminderSwitches;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_me);

        radioSystem = findViewById(R.id.radioLangSystem);
        radioEn = findViewById(R.id.radioLangEn);
        radioZh = findViewById(R.id.radioLangZh);
        reminderPreferences = new AtlasReminderPreferences(this);
        dailySwitch = findViewById(R.id.switchDailyReminder);
        locationSwitch = findViewById(R.id.switchLocationReminder);
        dailyStatus = findViewById(R.id.txtDailyReminderStatus);
        locationStatus = findViewById(R.id.txtLocationReminderStatus);
        applySavedLanguageSelection();
        bindReminderSettings();

        RadioGroup radioGroup = findViewById(R.id.radioGroupLanguage);
        radioGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                String language = checkedId == radioZh.getId() ? AtlasLocaleManager.LANGUAGE_ZH
                        : checkedId == radioEn.getId() ? AtlasLocaleManager.LANGUAGE_EN
                        : AtlasLocaleManager.LANGUAGE_SYSTEM;
                AtlasLocaleManager.saveLanguage(MeActivity.this, language);
                AtlasLocaleManager.apply(MeActivity.this, language);
                recreate();
            }
        });

        findViewById(R.id.btnOpenDeveloperSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MeActivity.this, SettingsActivity.class));
            }
        });

        AtlasBottomNav.setup(this, AtlasBottomNav.TAB_ME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AtlasLocaleManager.apply(this);
        applySavedLanguageSelection();
        refreshReminderState();
    }

    private void applySavedLanguageSelection() {
        String language = AtlasLocaleManager.getSavedLanguage(this);
        radioSystem.setChecked(AtlasLocaleManager.LANGUAGE_SYSTEM.equals(language));
        radioEn.setChecked(AtlasLocaleManager.LANGUAGE_EN.equals(language));
        radioZh.setChecked(AtlasLocaleManager.LANGUAGE_ZH.equals(language));
    }

    private void bindReminderSettings() {
        refreshReminderState();
        dailySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (bindingReminderSwitches) {
                    return;
                }
                reminderPreferences.setDailyEnabled(isChecked);
                if (isChecked) {
                    requestNotificationPermissionIfNeeded();
                    AtlasDailyReminderScheduler.reconcile(MeActivity.this);
                } else {
                    AtlasDailyReminderScheduler.cancel(MeActivity.this);
                }
                refreshReminderState();
            }
        });
        locationSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (bindingReminderSwitches) {
                    return;
                }
                reminderPreferences.setLocationEnabled(isChecked);
                if (isChecked) {
                    requestLocationPermissionIfNeeded();
                    requestNotificationPermissionIfNeeded();
                    AtlasResurfacingManager.refreshLocationsAsync(MeActivity.this);
                } else {
                    AtlasLocationReminderRegistrar.removeAll(MeActivity.this);
                }
                refreshReminderState();
            }
        });
    }

    private void refreshReminderState() {
        if (reminderPreferences == null) {
            return;
        }
        bindingReminderSwitches = true;
        dailySwitch.setChecked(reminderPreferences.isDailyEnabled());
        locationSwitch.setChecked(reminderPreferences.isLocationEnabled());
        bindingReminderSwitches = false;
        dailyStatus.setText(!reminderPreferences.isDailyEnabled()
                ? R.string.reminder_status_off
                : AtlasNotificationHelper.canPost(this)
                ? R.string.reminder_status_on
                : R.string.reminder_status_permission_needed);
        locationStatus.setText(!reminderPreferences.isLocationEnabled()
                ? R.string.reminder_status_off
                : hasAllLocationPermissions() && AtlasNotificationHelper.canPost(this)
                ? R.string.reminder_status_on
                : R.string.reminder_status_permission_needed);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && !AtlasNotificationHelper.canPost(this)) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQUEST_NOTIFICATIONS);
        }
    }

    private void requestLocationPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_LOCATION_REMINDER);
        } else if (Build.VERSION.SDK_INT >= 29
                && ContextCompat.checkSelfPermission(
                this, "android.permission.ACCESS_BACKGROUND_LOCATION")
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{"android.permission.ACCESS_BACKGROUND_LOCATION"},
                    REQUEST_BACKGROUND_LOCATION);
        }
    }

    private boolean hasAllLocationPermissions() {
        return AtlasLocationReminderRegistrar.hasLocationPermission(this)
                && (Build.VERSION.SDK_INT < 29
                || ContextCompat.checkSelfPermission(
                this, "android.permission.ACCESS_BACKGROUND_LOCATION")
                == PackageManager.PERMISSION_GRANTED);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_REMINDER) {
            requestLocationPermissionIfNeeded();
        }
        if (requestCode == REQUEST_LOCATION_REMINDER
                || requestCode == REQUEST_BACKGROUND_LOCATION) {
            AtlasResurfacingManager.refreshLocationsAsync(this);
        }
        refreshReminderState();
    }
}
