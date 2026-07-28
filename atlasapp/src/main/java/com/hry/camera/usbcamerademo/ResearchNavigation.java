package com.hry.camera.usbcamerademo;

import android.content.Intent;

/** Shared navigation metadata used for research entry-source attribution. */
final class ResearchNavigation {
    static final String EXTRA_ENTRY_SOURCE = "research_entry_source";

    private ResearchNavigation() {
    }

    static Intent withSource(Intent intent, String source) {
        if (intent != null) {
            intent.putExtra(EXTRA_ENTRY_SOURCE, source);
        }
        return intent;
    }

    static String source(Intent intent, String fallback) {
        if (intent == null) {
            return fallback;
        }
        String value = intent.getStringExtra(EXTRA_ENTRY_SOURCE);
        return value == null || value.length() == 0 ? fallback : value;
    }
}
