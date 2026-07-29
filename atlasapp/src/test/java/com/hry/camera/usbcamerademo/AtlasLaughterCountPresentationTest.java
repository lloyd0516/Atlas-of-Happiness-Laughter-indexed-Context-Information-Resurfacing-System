package com.hry.camera.usbcamerademo;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AtlasLaughterCountPresentationTest {
    @Test
    public void totalSumsActualLaughterClipsAcrossEvents() {
        AtlasReviewRepository.EventSummary first = event(2);
        AtlasReviewRepository.EventSummary second = event(3);

        assertEquals(
                5,
                AtlasLaughterCountPresentation.total(
                        Arrays.asList(first, second)));
    }

    @Test
    public void chineseLabelDoesNotRenderZeroAsAClipCount() {
        assertEquals(
                "暂无笑声片段",
                AtlasLaughterCountPresentation.chineseLabel(0));
        assertEquals(
                "2段笑声",
                AtlasLaughterCountPresentation.chineseLabel(2));
    }

    @Test
    public void mapGroupsActualLaughterCountAtTheSameLocation() {
        AtlasReviewRepository.EventSummary first = event(2);
        first.lat = 39.9042;
        first.lng = 116.4074;
        first.locationName = "王府井";
        AtlasReviewRepository.EventSummary second = event(1);
        second.lat = 39.9042;
        second.lng = 116.4074;
        second.locationName = "王府井";

        String html = AtlasMapHtmlBuilder.build(
                Arrays.asList(first, second));

        assertTrue(html.contains("3段笑声"));
        assertFalse(html.contains("2段笑声"));
    }

    @Test
    public void mapShowsEmptyLabelInsteadOfZeroLaughterClips() {
        AtlasReviewRepository.EventSummary empty = event(0);
        empty.lat = 39.9042;
        empty.lng = 116.4074;
        empty.locationName = "王府井";

        String html = AtlasMapHtmlBuilder.build(
                Collections.singletonList(empty));

        assertTrue(html.contains("暂无笑声片段"));
        assertFalse(html.contains("0段笑声"));
    }

    private AtlasReviewRepository.EventSummary event(int count) {
        AtlasReviewRepository.EventSummary event =
                new AtlasReviewRepository.EventSummary();
        event.laughterClipCount = count;
        return event;
    }
}
