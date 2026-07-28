package com.hry.camera.usbcamerademo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class AtlasLaughterPlaybackPreparerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void loudestFivePercentFramesIgnoreLongPaddingSilence() throws Exception {
        File source = writeWavWithSilenceAndTone(
                16000, 4000, 200, amplitudeForDbfs(-32.0));

        AtlasLaughterPlaybackPreparer.Result result =
                AtlasLaughterPlaybackPreparer.prepare(
                        source, temporaryFolder.newFolder("cache-padding"));

        assertTrue(result.enhanced);
        assertEquals(6.0, result.gainDb, 0.6);
    }

    @Test
    public void normalClipUsesOriginalFileWithoutReducingIt() throws Exception {
        File source = writeConstantToneWav(
                16000, 300, amplitudeForDbfs(-18.0));
        byte[] before = readAll(source);

        AtlasLaughterPlaybackPreparer.Result result =
                AtlasLaughterPlaybackPreparer.prepare(
                        source, temporaryFolder.newFolder("cache-normal"));

        assertFalse(result.enhanced);
        assertEquals(source.getCanonicalFile(), result.playbackFile.getCanonicalFile());
        assertArrayEquals(before, readAll(source));
    }

    @Test
    public void quietClipCreatesReusableEnhancedCacheAndKeepsOriginalBytes()
            throws Exception {
        File source = writeConstantToneWav(
                16000, 300, amplitudeForDbfs(-44.0));
        byte[] before = readAll(source);
        File cache = temporaryFolder.newFolder("cache-quiet");

        AtlasLaughterPlaybackPreparer.Result first =
                AtlasLaughterPlaybackPreparer.prepare(source, cache);
        AtlasLaughterPlaybackPreparer.Result second =
                AtlasLaughterPlaybackPreparer.prepare(source, cache);

        assertTrue(first.enhanced);
        assertEquals(15.0, first.gainDb, 0.6);
        assertEquals(first.playbackFile.getCanonicalFile(),
                second.playbackFile.getCanonicalFile());
        assertArrayEquals(before, readAll(source));
        assertNotEquals(source.getCanonicalFile(), first.playbackFile.getCanonicalFile());
    }

    @Test
    public void sourceMetadataChangeInvalidatesCachedPlaybackCopy() throws Exception {
        File source = writeConstantToneWav(
                16000, 300, amplitudeForDbfs(-44.0));
        File cache = temporaryFolder.newFolder("cache-key");
        AtlasLaughterPlaybackPreparer.Result first =
                AtlasLaughterPlaybackPreparer.prepare(source, cache);
        long changedTime = source.lastModified() + 2000L;
        assertTrue(source.setLastModified(changedTime));

        AtlasLaughterPlaybackPreparer.Result second =
                AtlasLaughterPlaybackPreparer.prepare(source, cache);

        assertNotEquals(first.playbackFile.getName(), second.playbackFile.getName());
    }

    @Test
    public void peakProtectionAvoidsHardPositiveClipping() throws Exception {
        short quiet = amplitudeForDbfs(-44.0);
        short[] samples = new short[16000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = quiet;
        }
        samples[100] = 10000;
        File source = writePcm16MonoWav(samples, 16000);

        AtlasLaughterPlaybackPreparer.Result result =
                AtlasLaughterPlaybackPreparer.prepare(
                        source, temporaryFolder.newFolder("cache-peak"));
        short[] output = readPcm16Samples(result.playbackFile);

        assertTrue(result.enhanced);
        assertTrue(output[100] > 10000);
        assertTrue(output[100] < Short.MAX_VALUE);
        assertTrue(Math.abs(output[100] - Short.MAX_VALUE) > 8);
    }

    @Test
    public void brokenWaveFallsBackToOriginalWithoutThrowing() throws Exception {
        File source = temporaryFolder.newFile("broken.wav");
        writeBytes(source, new byte[] {1, 2, 3});

        AtlasLaughterPlaybackPreparer.Result result =
                AtlasLaughterPlaybackPreparer.prepare(
                        source, temporaryFolder.newFolder("cache-broken"));

        assertFalse(result.enhanced);
        assertEquals(source.getCanonicalFile(), result.playbackFile.getCanonicalFile());
        assertEquals("unsupported_or_invalid_wav", result.fallbackReason);
    }

    private File writeWavWithSilenceAndTone(
            int sampleRate,
            int totalDurationMs,
            int toneDurationMs,
            short amplitude) throws IOException {
        int totalSamples = sampleRate * totalDurationMs / 1000;
        int toneSamples = sampleRate * toneDurationMs / 1000;
        short[] samples = new short[totalSamples];
        for (int i = totalSamples - toneSamples; i < totalSamples; i++) {
            samples[i] = amplitude;
        }
        return writePcm16MonoWav(samples, sampleRate);
    }

    private File writeConstantToneWav(
            int sampleRate,
            int durationMs,
            short amplitude) throws IOException {
        int sampleCount = sampleRate * durationMs / 1000;
        short[] samples = new short[sampleCount];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = amplitude;
        }
        return writePcm16MonoWav(samples, sampleRate);
    }

    private short amplitudeForDbfs(double dbfs) {
        return (short) Math.round(32768.0 * Math.pow(10.0, dbfs / 20.0));
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

    private short[] readPcm16Samples(File file) throws IOException {
        byte[] bytes = readAll(file);
        List<Short> samples = new ArrayList<>();
        for (int i = 44; i + 1 < bytes.length; i += 2) {
            int value = (bytes[i] & 0xFF) | ((bytes[i + 1] & 0xFF) << 8);
            samples.add((short) value);
        }
        short[] result = new short[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            result[i] = samples.get(i);
        }
        return result;
    }

    private byte[] readAll(File file) throws IOException {
        FileInputStream input = new FileInputStream(file);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private void writeBytes(File file, byte[] bytes) throws IOException {
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(bytes);
        } finally {
            output.close();
        }
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
