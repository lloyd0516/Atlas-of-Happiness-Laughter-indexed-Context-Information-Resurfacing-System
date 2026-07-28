package com.hry.camera.usbcamerademo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AtlasWavWaveformExtractorTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void extractsRequestedBarsFromRealPcmAmplitude() throws Exception {
        File wav = writePcm16MonoWav(new short[] {
                0, 0, 0, 0,
                12000, -12000, 10000, -10000
        }, 8);

        float[] bars = AtlasWavWaveformExtractor.extract(wav, 2);

        assertEquals(2, bars.length);
        assertTrue(bars[0] < 0.01f);
        assertTrue(bars[1] > 0.30f);
    }

    @Test
    public void silentWavProducesStableZeroBars() throws Exception {
        File wav = writePcm16MonoWav(new short[] {0, 0, 0, 0}, 4);

        assertArrayEquals(
                new float[] {0f, 0f, 0f, 0f},
                AtlasWavWaveformExtractor.extract(wav, 4),
                0.0001f);
    }

    @Test
    public void quietClipIsNormalizedAgainstItsOwnPeakForVisibleShape() throws Exception {
        File wav = writePcm16MonoWav(
                new short[] {1000, -1000, 2000, -2000},
                4);

        float[] bars = AtlasWavWaveformExtractor.extract(wav, 2);

        assertEquals(0.5f, bars[0], 0.001f);
        assertEquals(1f, bars[1], 0.001f);
    }

    @Test(expected = IOException.class)
    public void rejectsNonWaveInput() throws Exception {
        File invalid = temporaryFolder.newFile("invalid.wav");

        AtlasWavWaveformExtractor.extract(invalid, 16);
    }

    @Test
    public void displayHeightKeepsQuietSamplesVisibleAndClampsPeak() {
        assertEquals(2f, AtlasWaveformView.computeBarHeight(0f, 24f, 2f), 0.001f);
        assertEquals(12f, AtlasWaveformView.computeBarHeight(0.5f, 24f, 2f), 0.001f);
        assertEquals(24f, AtlasWaveformView.computeBarHeight(2f, 24f, 2f), 0.001f);
    }

    @Test
    public void progressIsClampedToUnitInterval() {
        assertEquals(0f, AtlasWaveformView.clampProgress(-1f), 0.001f);
        assertEquals(0.5f, AtlasWaveformView.clampProgress(0.5f), 0.001f);
        assertEquals(1f, AtlasWaveformView.clampProgress(2f), 0.001f);
    }

    private File writePcm16MonoWav(short[] samples, int sampleRate) throws IOException {
        File file = temporaryFolder.newFile("fixture-" + System.nanoTime() + ".wav");
        FileOutputStream output = new FileOutputStream(file);
        try {
            int dataBytes = samples.length * 2;
            output.write(new byte[] {'R', 'I', 'F', 'F'});
            writeLittleEndianInt(output, 36 + dataBytes);
            output.write(new byte[] {'W', 'A', 'V', 'E'});
            output.write(new byte[] {'f', 'm', 't', ' '});
            writeLittleEndianInt(output, 16);
            writeLittleEndianShort(output, 1);
            writeLittleEndianShort(output, 1);
            writeLittleEndianInt(output, sampleRate);
            writeLittleEndianInt(output, sampleRate * 2);
            writeLittleEndianShort(output, 2);
            writeLittleEndianShort(output, 16);
            output.write(new byte[] {'d', 'a', 't', 'a'});
            writeLittleEndianInt(output, dataBytes);
            for (short sample : samples) {
                writeLittleEndianShort(output, sample);
            }
        } finally {
            output.close();
        }
        return file;
    }

    private static void writeLittleEndianInt(FileOutputStream output, int value)
            throws IOException {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
        output.write((value >> 16) & 0xFF);
        output.write((value >> 24) & 0xFF);
    }

    private static void writeLittleEndianShort(FileOutputStream output, int value)
            throws IOException {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
    }
}
