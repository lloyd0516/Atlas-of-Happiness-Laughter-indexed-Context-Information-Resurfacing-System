package com.hry.camera.usbcamerademo;

import org.junit.Test;

import java.util.Arrays;

import static com.hry.camera.usbcamerademo.AtlasResurfacingWindowPresentation.Section.CONTEXT_AUDIO;
import static com.hry.camera.usbcamerademo.AtlasResurfacingWindowPresentation.Section.LAUGHTER;
import static com.hry.camera.usbcamerademo.AtlasResurfacingWindowPresentation.Section.LOCATION_DATE;
import static com.hry.camera.usbcamerademo.AtlasResurfacingWindowPresentation.Section.MEDIA;
import static com.hry.camera.usbcamerademo.AtlasResurfacingWindowPresentation.Section.SOCIAL_AND_SUMMARY;
import static org.junit.Assert.assertEquals;

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
}
