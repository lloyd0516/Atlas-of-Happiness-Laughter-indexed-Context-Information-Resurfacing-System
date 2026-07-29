package com.hry.camera.usbcamerademo;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;

import static com.hry.camera.usbcamerademo.AtlasResurfacingWindowPresentation.Section.CONTEXT_AUDIO;
import static com.hry.camera.usbcamerademo.AtlasResurfacingWindowPresentation.Section.LAUGHTER;
import static com.hry.camera.usbcamerademo.AtlasResurfacingWindowPresentation.Section.LOCATION_DATE;
import static com.hry.camera.usbcamerademo.AtlasResurfacingWindowPresentation.Section.MEDIA;
import static com.hry.camera.usbcamerademo.AtlasResurfacingWindowPresentation.Section.SOCIAL_AND_SUMMARY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AtlasResurfacingWindowPresentationTest {
    @Test
    public void shortTermDefaultKeepsRequiredOrder() {
        assertEquals(
                Arrays.asList(
                        LAUGHTER,
                        MEDIA,
                        LOCATION_DATE,
                        CONTEXT_AUDIO),
                AtlasResurfacingWindowPresentation.visibleSections(
                        false,
                        false,
                        true,
                        true));
    }

    @Test
    public void shortTermExpandedAddsOneSocialSummarySection() {
        assertEquals(
                Arrays.asList(
                        LAUGHTER,
                        MEDIA,
                        LOCATION_DATE,
                        CONTEXT_AUDIO,
                        SOCIAL_AND_SUMMARY),
                AtlasResurfacingWindowPresentation.visibleSections(
                        false,
                        true,
                        true,
                        true));
    }

    @Test
    public void longTermHidesOptionalContentUntilExpanded() {
        assertEquals(
                Arrays.asList(LAUGHTER, LOCATION_DATE),
                AtlasResurfacingWindowPresentation.visibleSections(
                        true,
                        false,
                        true,
                        true));
        assertEquals(
                Arrays.asList(
                        LAUGHTER,
                        LOCATION_DATE,
                        MEDIA,
                        CONTEXT_AUDIO,
                        SOCIAL_AND_SUMMARY),
                AtlasResurfacingWindowPresentation.visibleSections(
                        true,
                        true,
                        true,
                        true));
    }

    @Test
    public void absentOptionalDataDoesNotCreateEmptySections() {
        assertEquals(
                Arrays.asList(LAUGHTER, LOCATION_DATE),
                AtlasResurfacingWindowPresentation.visibleSections(
                        false,
                        false,
                        false,
                        false));
        assertEquals(
                Arrays.asList(
                        LAUGHTER,
                        LOCATION_DATE,
                        SOCIAL_AND_SUMMARY),
                AtlasResurfacingWindowPresentation.visibleSections(
                        true,
                        true,
                        false,
                        false));
    }

    @Test
    public void cardLayoutUsesDynamicAudioContainers() throws Exception {
        File layout = new File(
                "atlasapp/src/main/res/layout/item_laughter_clip_card.xml");
        if (!layout.isFile()) {
            layout = new File(
                    "src/main/res/layout/item_laughter_clip_card.xml");
        }
        String xml = new String(
                Files.readAllBytes(layout.toPath()),
                Charset.forName("UTF-8"));

        assertTrue(xml.contains("windowLaughterAudioContainer"));
        assertTrue(xml.contains("clipPhotoStripShort"));
        assertTrue(xml.contains("txtClipLocationDate"));
        assertTrue(xml.contains("windowContextAudioContainerShort"));
        assertFalse(xml.contains("clipLaughterAudioRow"));
        assertFalse(xml.contains("clipContextAudioRowShort"));
        assertTrue(
                xml.indexOf("windowLaughterAudioContainer")
                        < xml.indexOf("clipPhotoStripShort"));
        assertTrue(
                xml.indexOf("clipPhotoStripShort")
                        < xml.indexOf("txtClipLocationDate"));
        assertTrue(
                xml.indexOf("txtClipLocationDate")
                        < xml.indexOf(
                        "windowContextAudioContainerShort"));
        assertTrue(
                xml.indexOf("clipPhotoStripLong")
                        < xml.indexOf(
                        "windowContextAudioContainerLong"));
    }
}
