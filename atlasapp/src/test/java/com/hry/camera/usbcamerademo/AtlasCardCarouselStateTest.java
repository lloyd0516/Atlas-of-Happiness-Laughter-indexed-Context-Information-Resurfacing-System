package com.hry.camera.usbcamerademo;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class AtlasCardCarouselStateTest {
    @Test
    public void nextAndPreviousWrapAcrossFourEvents() {
        AtlasCardCarouselState state = new AtlasCardCarouselState(4);

        assertEquals(0, state.currentIndex());
        assertEquals(3, state.previousIndex());
        assertEquals(1, state.nextIndex());

        state.movePrevious();
        assertEquals(3, state.currentIndex());
        state.moveNext();
        assertEquals(0, state.currentIndex());
    }

    @Test
    public void currentCardIsAlwaysLastInDrawOrder() {
        AtlasCardCarouselState state = new AtlasCardCarouselState(4);
        List<AtlasCardCarouselState.CardSlot> slots = state.drawOrder();

        assertEquals(3, slots.size());
        assertEquals(AtlasCardCarouselState.Role.PREVIOUS, slots.get(0).role);
        assertEquals(AtlasCardCarouselState.Role.NEXT, slots.get(1).role);
        assertEquals(AtlasCardCarouselState.Role.CURRENT, slots.get(2).role);
        assertEquals(state.currentIndex(), slots.get(2).dataIndex);
    }

    @Test
    public void oneEventHasNoSideTargets() {
        AtlasCardCarouselState state = new AtlasCardCarouselState(1);

        assertEquals(-1, state.previousIndex());
        assertEquals(-1, state.nextIndex());
        assertEquals(1, state.drawOrder().size());
        assertEquals(0, state.drawOrder().get(0).dataIndex);
    }

    @Test
    public void twoEventsUseOneSidePreviewWithoutDuplication() {
        AtlasCardCarouselState state = new AtlasCardCarouselState(2);
        List<AtlasCardCarouselState.CardSlot> slots = state.drawOrder();

        assertEquals(2, slots.size());
        assertEquals(AtlasCardCarouselState.Role.NEXT, slots.get(0).role);
        assertEquals(1, slots.get(0).dataIndex);
        assertEquals(AtlasCardCarouselState.Role.CURRENT, slots.get(1).role);
        assertEquals(0, slots.get(1).dataIndex);
    }
}
