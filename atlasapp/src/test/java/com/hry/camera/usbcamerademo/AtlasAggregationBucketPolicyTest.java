package com.hry.camera.usbcamerademo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AtlasAggregationBucketPolicyTest {
    @Test
    public void threeClipsDefineOneBucketForEveryPreset() {
        assertEquals(
                60,
                AtlasAggregationBucketPolicy.bucketDurationSec(20));
        assertEquals(
                90,
                AtlasAggregationBucketPolicy.bucketDurationSec(30));
        assertEquals(
                135,
                AtlasAggregationBucketPolicy.bucketDurationSec(45));
    }

    @Test
    public void boundaryIsLeftClosedAndRightOpen() {
        assertEquals(
                0,
                AtlasAggregationBucketPolicy.bucketId(89.999, 30));
        assertEquals(
                1,
                AtlasAggregationBucketPolicy.bucketId(90.000, 30));
        assertEquals(
                90000L,
                AtlasAggregationBucketPolicy.bucketStartOffsetMs(1, 30));
        assertEquals(
                180000L,
                AtlasAggregationBucketPolicy.bucketEndOffsetMs(1, 30));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveClipDuration() {
        AtlasAggregationBucketPolicy.bucketDurationSec(0);
    }
}
