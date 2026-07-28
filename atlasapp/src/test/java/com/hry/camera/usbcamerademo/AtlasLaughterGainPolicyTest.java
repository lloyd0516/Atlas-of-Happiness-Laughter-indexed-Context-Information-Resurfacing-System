package com.hry.camera.usbcamerademo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AtlasLaughterGainPolicyTest {
    @Test
    public void normalClipIsNeverReduced() {
        assertEquals(0.0, AtlasLaughterGainPolicy.computeGainDb(-18.0), 0.0001);
        assertEquals(0.0, AtlasLaughterGainPolicy.computeGainDb(-24.0), 0.0001);
    }

    @Test
    public void quietClipReceivesPartialPositiveCompensation() {
        assertEquals(6.0, AtlasLaughterGainPolicy.computeGainDb(-32.0), 0.0001);
        assertEquals(15.0, AtlasLaughterGainPolicy.computeGainDb(-44.0), 0.0001);
    }

    @Test
    public void boostIsCappedAndInvalidMeasurementsStaySafe() {
        assertEquals(18.0, AtlasLaughterGainPolicy.computeGainDb(-80.0), 0.0001);
        assertEquals(0.0, AtlasLaughterGainPolicy.computeGainDb(Double.NaN), 0.0001);
        assertEquals(0.0, AtlasLaughterGainPolicy.computeGainDb(Double.NEGATIVE_INFINITY), 0.0001);
    }

    @Test
    public void outputLevelRemainsMonotonic() {
        double quietOutput = -44.0 + AtlasLaughterGainPolicy.computeGainDb(-44.0);
        double mediumOutput = -32.0 + AtlasLaughterGainPolicy.computeGainDb(-32.0);
        double loudOutput = -18.0 + AtlasLaughterGainPolicy.computeGainDb(-18.0);
        assertTrue(quietOutput < mediumOutput);
        assertTrue(mediumOutput < loudOutput);
    }
}
