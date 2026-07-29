package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JoyfulMomentConfigTest {
    @Test
    public void everyPresetUsesFixedTwoPhotoBundle() {
        assertEquals(
                2,
                JoyfulMomentConfig.preset(
                        JoyfulMomentConfig.LEVEL_FREQUENT)
                        .triggerPhotoCount);
        assertEquals(
                2,
                JoyfulMomentConfig.preset(
                        JoyfulMomentConfig.LEVEL_MEDIUM)
                        .triggerPhotoCount);
        assertEquals(
                2,
                JoyfulMomentConfig.preset(
                        JoyfulMomentConfig.LEVEL_SPARSE)
                        .triggerPhotoCount);
    }

    @Test
    public void legacyPhotoCountIsAcceptedButNormalized() throws Exception {
        JoyfulMomentConfig config = JoyfulMomentConfig.fromJson(
                new JSONObject().put("trigger_photo_count", 6));

        assertEquals(
                AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE,
                config.triggerPhotoCount);
        assertEquals(
                AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE,
                config.toJson().getInt("trigger_photo_count"));
    }

    @Test
    public void fixedPhotoDelaysAreAddressedByMediaIndex() {
        assertEquals(1500L, AppConfig.autoCapturePhotoDelayMs(0));
        assertEquals(3500L, AppConfig.autoCapturePhotoDelayMs(1));
    }
}
