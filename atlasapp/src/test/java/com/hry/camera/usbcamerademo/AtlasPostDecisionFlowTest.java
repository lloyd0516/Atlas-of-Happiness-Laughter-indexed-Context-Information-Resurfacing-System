package com.hry.camera.usbcamerademo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AtlasPostDecisionFlowTest {
    @Test
    public void successfulSaveFinishesWithoutOfferingAnotherSupplementPrompt() {
        assertEquals(
                AtlasPostDecisionFlow.Action.FINISH,
                AtlasPostDecisionFlow.afterSave(true));
    }
}
