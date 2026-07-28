package com.hry.camera.usbcamerademo;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

final class AtlasWavWaveformExtractor {
    private static final int PCM_FORMAT = 1;
    private static final int SUPPORTED_BITS_PER_SAMPLE = 16;

    private AtlasWavWaveformExtractor() {
    }

    static float[] extract(File file, int barCount) throws IOException {
        if (file == null || !file.isFile() || barCount <= 0) {
            throw new IOException("Invalid waveform input");
        }
        RandomAccessFile input = new RandomAccessFile(file, "r");
        try {
            WavInfo info = readWavInfo(input);
            if (info.audioFormat != PCM_FORMAT
                    || info.bitsPerSample != SUPPORTED_BITS_PER_SAMPLE) {
                throw new IOException("Only 16-bit PCM WAV is supported");
            }
            int sampleBytesPerFrame = info.channels * 2;
            if (info.channels <= 0 || info.blockAlign < sampleBytesPerFrame) {
                throw new IOException("Invalid WAV channel layout");
            }

            long frameCount = info.dataSize / info.blockAlign;
            float[] peaks = new float[barCount];
            input.seek(info.dataOffset);
            for (long frame = 0; frame < frameCount; frame++) {
                int bucket = (int) Math.min(
                        barCount - 1,
                        frame * barCount / Math.max(1L, frameCount));
                int peakSample = 0;
                for (int channel = 0; channel < info.channels; channel++) {
                    int sample = readLittleEndianSignedShort(input);
                    if (Math.abs(sample) > Math.abs(peakSample)) {
                        peakSample = sample;
                    }
                }
                int paddingBytes = info.blockAlign - sampleBytesPerFrame;
                if (paddingBytes > 0) {
                    input.skipBytes(paddingBytes);
                }
                peaks[bucket] = Math.max(
                        peaks[bucket],
                        Math.abs(peakSample) / 32768.0f);
            }
            normalizeAgainstClipPeak(peaks);
            return peaks;
        } finally {
            input.close();
        }
    }

    static String cacheKey(File file) {
        if (file == null) {
            return "";
        }
        return file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
    }

    private static void normalizeAgainstClipPeak(float[] peaks) {
        float clipPeak = 0f;
        for (float peak : peaks) {
            clipPeak = Math.max(clipPeak, peak);
        }
        if (clipPeak <= 0f) {
            return;
        }
        for (int i = 0; i < peaks.length; i++) {
            peaks[i] = Math.min(1f, peaks[i] / clipPeak);
        }
    }

    private static WavInfo readWavInfo(RandomAccessFile input) throws IOException {
        if (input.length() < 12L
                || !"RIFF".equals(readFourCc(input))
                || readLittleEndianUnsignedInt(input) < 4L
                || !"WAVE".equals(readFourCc(input))) {
            throw new IOException("Invalid RIFF/WAVE header");
        }

        WavInfo info = new WavInfo();
        while (input.getFilePointer() + 8L <= input.length()) {
            String chunkId = readFourCc(input);
            long chunkSize = readLittleEndianUnsignedInt(input);
            long chunkDataOffset = input.getFilePointer();
            long chunkEnd = chunkDataOffset + chunkSize;
            if (chunkEnd < chunkDataOffset || chunkEnd > input.length()) {
                throw new IOException("Truncated WAV chunk: " + chunkId);
            }

            if ("fmt ".equals(chunkId)) {
                if (chunkSize < 16L) {
                    throw new IOException("Invalid WAV fmt chunk");
                }
                info.audioFormat = readLittleEndianUnsignedShort(input);
                info.channels = readLittleEndianUnsignedShort(input);
                readLittleEndianUnsignedInt(input);
                readLittleEndianUnsignedInt(input);
                info.blockAlign = readLittleEndianUnsignedShort(input);
                info.bitsPerSample = readLittleEndianUnsignedShort(input);
                info.hasFormat = true;
            } else if ("data".equals(chunkId)) {
                info.dataOffset = chunkDataOffset;
                info.dataSize = chunkSize;
                info.hasData = true;
            }

            long nextChunk = chunkEnd + (chunkSize & 1L);
            if (nextChunk > input.length()) {
                throw new IOException("Invalid WAV chunk padding");
            }
            input.seek(nextChunk);
        }

        if (!info.hasFormat || !info.hasData) {
            throw new IOException("WAV fmt or data chunk missing");
        }
        return info;
    }

    private static String readFourCc(RandomAccessFile input) throws IOException {
        byte[] value = new byte[4];
        input.readFully(value);
        return new String(value, "US-ASCII");
    }

    private static int readLittleEndianSignedShort(RandomAccessFile input)
            throws IOException {
        int value = readLittleEndianUnsignedShort(input);
        return value > 32767 ? value - 65536 : value;
    }

    private static int readLittleEndianUnsignedShort(RandomAccessFile input)
            throws IOException {
        int b0 = input.read();
        int b1 = input.read();
        if (b0 < 0 || b1 < 0) {
            throw new IOException("Unexpected end of WAV");
        }
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8);
    }

    private static long readLittleEndianUnsignedInt(RandomAccessFile input)
            throws IOException {
        int b0 = input.read();
        int b1 = input.read();
        int b2 = input.read();
        int b3 = input.read();
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
            throw new IOException("Unexpected end of WAV");
        }
        return (b0 & 0xFFL)
                | ((b1 & 0xFFL) << 8)
                | ((b2 & 0xFFL) << 16)
                | ((b3 & 0xFFL) << 24);
    }

    private static final class WavInfo {
        int audioFormat;
        int channels;
        int blockAlign;
        int bitsPerSample;
        long dataOffset;
        long dataSize;
        boolean hasFormat;
        boolean hasData;
    }
}
