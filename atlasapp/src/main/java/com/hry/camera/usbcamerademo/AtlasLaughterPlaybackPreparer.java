package com.hry.camera.usbcamerademo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Creates a playback-only enhanced copy of a quiet PCM16 WAV.
 *
 * <p>The source file is only read. Unsupported input and cache failures always return the original
 * file so enhancement can never make an otherwise playable clip unavailable.</p>
 */
final class AtlasLaughterPlaybackPreparer {
    private static final int PCM_FORMAT = 1;
    private static final int PCM_BITS_PER_SAMPLE = 16;

    static final class Result {
        final File playbackFile;
        final double gainDb;
        final int algorithmVersion;
        final boolean enhanced;
        final String fallbackReason;

        Result(File playbackFile, double gainDb, boolean enhanced, String fallbackReason) {
            this.playbackFile = playbackFile;
            this.gainDb = gainDb;
            this.algorithmVersion = AppConfig.LAUGHTER_PLAYBACK_ALGORITHM_VERSION;
            this.enhanced = enhanced;
            this.fallbackReason = fallbackReason;
        }
    }

    private AtlasLaughterPlaybackPreparer() {
    }

    static Result prepare(File source, File cacheDir) {
        if (source == null || !source.isFile()) {
            return new Result(source, 0.0, false, "missing_source");
        }

        final WavInfo info;
        final double gainDb;
        try {
            info = readWavInfo(source);
            gainDb = AtlasLaughterGainPolicy.computeGainDb(
                    measureEffectiveLoudnessDbfs(source, info));
        } catch (IOException error) {
            return new Result(source, 0.0, false, "unsupported_or_invalid_wav");
        }

        if (gainDb <= 0.0) {
            return new Result(source, 0.0, false, null);
        }
        if (cacheDir == null || (!cacheDir.isDirectory() && !cacheDir.mkdirs())) {
            return new Result(source, 0.0, false, "cache_write_failed");
        }

        final File cached;
        try {
            cached = new File(cacheDir, buildCacheFileName(source));
        } catch (IOException error) {
            return new Result(source, 0.0, false, "cache_write_failed");
        }
        if (cached.isFile() && cached.length() == source.length()) {
            return new Result(cached, gainDb, true, null);
        }

        File temporary = new File(
                cacheDir,
                cached.getName() + "." + System.nanoTime() + ".tmp");
        try {
            copyFile(source, temporary);
            applyGainToDataChunk(temporary, info, gainDb);
            if (cached.isFile() && !cached.delete()) {
                throw new IOException("Unable to replace stale playback cache");
            }
            if (!temporary.renameTo(cached)) {
                throw new IOException("Unable to publish playback cache");
            }
            return new Result(cached, gainDb, true, null);
        } catch (IOException error) {
            if (temporary.exists()) {
                temporary.delete();
            }
            return new Result(source, 0.0, false, "cache_write_failed");
        }
    }

    private static WavInfo readWavInfo(File source) throws IOException {
        RandomAccessFile input = new RandomAccessFile(source, "r");
        try {
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
                    info.sampleRate = readLittleEndianUnsignedInt(input);
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

            int sampleBytesPerFrame = info.channels * 2;
            if (!info.hasFormat
                    || !info.hasData
                    || info.audioFormat != PCM_FORMAT
                    || info.bitsPerSample != PCM_BITS_PER_SAMPLE
                    || info.channels <= 0
                    || info.sampleRate <= 0L
                    || info.blockAlign < sampleBytesPerFrame
                    || info.dataSize <= 0L
                    || info.dataSize % info.blockAlign != 0L
                    || info.dataSize > Integer.MAX_VALUE) {
                throw new IOException("Unsupported or invalid PCM WAV");
            }
            return info;
        } finally {
            input.close();
        }
    }

    private static double measureEffectiveLoudnessDbfs(File source, WavInfo info)
            throws IOException {
        RandomAccessFile input = new RandomAccessFile(source, "r");
        try {
            input.seek(info.dataOffset);
            long totalPcmFrames = info.dataSize / info.blockAlign;
            long analysisFramesSamples = Math.max(
                    1L,
                    info.sampleRate * AppConfig.LAUGHTER_PLAYBACK_FRAME_MS / 1000L);
            List<Double> rmsFrames = new ArrayList<>();
            long pcmFrame = 0L;
            while (pcmFrame < totalPcmFrames) {
                long framesInWindow = Math.min(
                        analysisFramesSamples,
                        totalPcmFrames - pcmFrame);
                double squareSum = 0.0;
                long sampleCount = 0L;
                for (long frame = 0L; frame < framesInWindow; frame++) {
                    for (int channel = 0; channel < info.channels; channel++) {
                        int sample = readLittleEndianSignedShort(input);
                        squareSum += (double) sample * (double) sample;
                        sampleCount += 1L;
                    }
                    int padding = info.blockAlign - info.channels * 2;
                    if (padding > 0) {
                        input.skipBytes(padding);
                    }
                }
                rmsFrames.add(Math.sqrt(squareSum / Math.max(1L, sampleCount)));
                pcmFrame += framesInWindow;
            }

            if (rmsFrames.isEmpty()) {
                throw new IOException("WAV contains no complete PCM frame");
            }
            Collections.sort(rmsFrames);
            int topFrameCount = Math.max(
                    1,
                    (int) Math.ceil(
                            rmsFrames.size()
                                    * AppConfig.LAUGHTER_PLAYBACK_TOP_FRAME_RATIO));
            double topRmsSum = 0.0;
            for (int i = rmsFrames.size() - topFrameCount;
                    i < rmsFrames.size();
                    i++) {
                topRmsSum += rmsFrames.get(i);
            }
            double effectiveRms = topRmsSum / topFrameCount;
            double normalized = Math.max(1.0 / 32768.0, effectiveRms / 32768.0);
            return 20.0 * Math.log10(normalized);
        } finally {
            input.close();
        }
    }

    private static void applyGainToDataChunk(
            File file,
            WavInfo info,
            double gainDb) throws IOException {
        RandomAccessFile output = new RandomAccessFile(file, "rw");
        try {
            byte[] data = new byte[(int) info.dataSize];
            output.seek(info.dataOffset);
            output.readFully(data);

            double linearGain = Math.pow(10.0, gainDb / 20.0);
            double guard = Math.pow(
                    10.0,
                    AppConfig.LAUGHTER_PLAYBACK_PEAK_GUARD_DBFS / 20.0);
            int sampleBytesPerFrame = info.channels * 2;
            for (int frameOffset = 0;
                    frameOffset + info.blockAlign <= data.length;
                    frameOffset += info.blockAlign) {
                for (int sampleOffset = frameOffset;
                        sampleOffset < frameOffset + sampleBytesPerFrame;
                        sampleOffset += 2) {
                    int raw = (data[sampleOffset] & 0xFF)
                            | ((data[sampleOffset + 1] & 0xFF) << 8);
                    int signed = raw > 32767 ? raw - 65536 : raw;
                    short enhanced = enhanceSample(signed, linearGain, guard);
                    data[sampleOffset] = (byte) (enhanced & 0xFF);
                    data[sampleOffset + 1] = (byte) ((enhanced >> 8) & 0xFF);
                }
            }
            output.seek(info.dataOffset);
            output.write(data);
        } finally {
            output.close();
        }
    }

    private static short enhanceSample(int sample, double linearGain, double guard) {
        double amplified = sample / 32768.0 * linearGain;
        double magnitude = Math.abs(amplified);
        double protectedMagnitude = magnitude <= guard
                ? magnitude
                : guard + (1.0 - guard)
                        * Math.tanh((magnitude - guard) / (1.0 - guard));
        double limited = Math.max(
                -1.0,
                Math.min(
                        32767.0 / 32768.0,
                        Math.copySign(protectedMagnitude, amplified)));
        return (short) Math.round(limited * 32768.0);
    }

    private static String buildCacheFileName(File source) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 unavailable", error);
        }
        String identity = source.getCanonicalPath()
                + "|" + source.length()
                + "|" + source.lastModified()
                + "|" + AppConfig.LAUGHTER_PLAYBACK_ALGORITHM_VERSION;
        byte[] hash;
        try {
            hash = digest.digest(identity.getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException error) {
            throw new IOException("UTF-8 unavailable", error);
        }
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            hex.append(String.format("%02x", value & 0xFF));
        }
        return "laughter_v"
                + AppConfig.LAUGHTER_PLAYBACK_ALGORITHM_VERSION
                + "_" + hex + ".wav";
    }

    private static void copyFile(File source, File destination) throws IOException {
        FileInputStream input = new FileInputStream(source);
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(destination, false);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        } finally {
            try {
                input.close();
            } finally {
                if (output != null) {
                    output.close();
                }
            }
        }
    }

    private static String readFourCc(RandomAccessFile input) throws IOException {
        byte[] value = new byte[4];
        input.readFully(value);
        try {
            return new String(value, "US-ASCII");
        } catch (java.io.UnsupportedEncodingException error) {
            throw new IOException("US-ASCII unavailable", error);
        }
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
        long sampleRate;
        int blockAlign;
        int bitsPerSample;
        long dataOffset;
        long dataSize;
        boolean hasFormat;
        boolean hasData;
    }
}
