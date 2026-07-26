package com.hry.camera.usbcamerademo;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;

/**
 * Requirement 6: bottom nav "我的" tab. Only holds Language (bilingual switcher) and a Log
 * placeholder for now; the existing detailed detection/API settings screen (SettingsActivity)
 * stays reachable from here but is not itself one of the three main tabs.
 */
public class MeActivity extends AppCompatActivity {
    private RadioButton radioSystem;
    private RadioButton radioEn;
    private RadioButton radioZh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_me);

        radioSystem = findViewById(R.id.radioLangSystem);
        radioEn = findViewById(R.id.radioLangEn);
        radioZh = findViewById(R.id.radioLangZh);
        applySavedLanguageSelection();

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
    }

    private void applySavedLanguageSelection() {
        String language = AtlasLocaleManager.getSavedLanguage(this);
        radioSystem.setChecked(AtlasLocaleManager.LANGUAGE_SYSTEM.equals(language));
        radioEn.setChecked(AtlasLocaleManager.LANGUAGE_EN.equals(language));
        radioZh.setChecked(AtlasLocaleManager.LANGUAGE_ZH.equals(language));
    }
}
