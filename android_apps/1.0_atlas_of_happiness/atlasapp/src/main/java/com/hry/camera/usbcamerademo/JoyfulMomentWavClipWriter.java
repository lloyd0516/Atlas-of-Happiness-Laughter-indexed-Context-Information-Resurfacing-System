package com.hry.camera.usbcamerademo;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class JoyfulMomentWavClipWriter {
    public static class ClosedClip {
        public int clipId;
        public double startSec;
        public double endSec;
        public File path;
    }

    private final File tmpDir;
    private final int sampleRate;
    private final int channels;
    private final int sampleWidthBytes;
    private final int clipDurationSec;
    private final int framesPerClip;
    private final int bytesPerFrame;

    private int nextClipId = 0;
    private int framesInClip = 0;
    private RandomAccessFile currentFile;
    private File currentPath;
    private long currentDataBytes = 0;

    public JoyfulMomentWavClipWriter(File sessionDir, int sampleRate, int channels, int sampleWidthBytes, int clipDurationSec) {
        this.tmpDir = new File(new File(sessionDir, "clips"), "_tmp");
        if (!tmpDir.exists()) {
            tmpDir.mkdirs();
        }
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.sampleWidthBytes = sampleWidthBytes;
        this.clipDurationSec = clipDurationSec;
        this.framesPerClip = sampleRate * clipDurationSec;
        this.bytesPerFrame = channels * sampleWidthBytes;
    }

    public synchronized List<ClosedClip> write(byte[] data, int length) throws IOException {
        if (currentFile == null) {
            openNewClip();
        }
        List<ClosedClip> closedClips = new ArrayList<>();
        int offset = 0;
        while (offset < length) {
            int remainingFrames = framesPerClip - framesInClip;
            int remainingBytes = remainingFrames * bytesPerFrame;
            int takeBytes = Math.min(remainingBytes, length - offset);
            takeBytes -= (takeBytes % bytesPerFrame);
            if (takeBytes <= 0) {
                break;
            }
            currentFile.write(data, offset, takeBytes);
            currentDataBytes += takeBytes;
            framesInClip += (takeBytes / bytesPerFrame);
            offset += takeBytes;

            if (framesInClip >= framesPerClip) {
                closedClips.add(closeCurrentClip(clipDurationSec));
                openNewClip();
            }
        }
        return closedClips;
    }

    public synchronized ClosedClip finalizePartial() throws IOException {
        if (currentFile == null || framesInClip <= 0) {
            return null;
        }
        double durationSec = framesInClip / (double) sampleRate;
        ClosedClip clip = closeCurrentClip(durationSec);
        currentFile = null;
        currentPath = null;
        currentDataBytes = 0;
        framesInClip = 0;
        return clip;
    }

    private void openNewClip() throws IOException {
        if (currentFile != null) {
            currentFile.close();
        }
        currentPath = new File(tmpDir, String.format(Locale.US, "clip_%06d.wav", nextClipId));
        currentFile = new RandomAccessFile(currentPath, "rw");
        currentFile.setLength(0);
        writeHeader(currentFile, 0);
        currentDataBytes = 0;
        framesInClip = 0;
    }

    private ClosedClip closeCurrentClip(double durationSec) throws IOException {
        if (currentFile == null || currentPath == null) {
            return null;
        }
        currentFile.seek(0);
        writeHeader(currentFile, currentDataBytes);
        currentFile.close();

        ClosedClip clip = new ClosedClip();
        clip.clipId = nextClipId;
        clip.startSec = nextClipId * (double) clipDurationSec;
        clip.endSec = clip.startSec + durationSec;
        clip.path = currentPath;

        nextClipId += 1;
        currentFile = null;
        currentPath = null;
        currentDataBytes = 0;
        framesInClip = 0;
        return clip;
    }

    private void writeHeader(RandomAccessFile file, long dataBytes) throws IOException {
        long byteRate = sampleRate * channels * sampleWidthBytes;
        short blockAlign = (short) (channels * sampleWidthBytes);
        short bitsPerSample = (short) (sampleWidthBytes * 8);

        file.writeBytes("RIFF");
        writeLittleEndianInt(file, (int) (36 + dataBytes));
        file.writeBytes("WAVE");
        file.writeBytes("fmt ");
        writeLittleEndianInt(file, 16);
        writeLittleEndianShort(file, (short) 1);
        writeLittleEndianShort(file, (short) channels);
        writeLittleEndianInt(file, sampleRate);
        writeLittleEndianInt(file, (int) byteRate);
        writeLittleEndianShort(file, blockAlign);
        writeLittleEndianShort(file, bitsPerSample);
        file.writeBytes("data");
        writeLittleEndianInt(file, (int) dataBytes);
    }

    private void writeLittleEndianInt(RandomAccessFile file, int value) throws IOException {
        file.write(value & 0xFF);
        file.write((value >> 8) & 0xFF);
        file.write((value >> 16) & 0xFF);
        file.write((value >> 24) & 0xFF);
    }

    private void writeLittleEndianShort(RandomAccessFile file, short value) throws IOException {
        file.write(value & 0xFF);
        file.write((value >> 8) & 0xFF);
    }
}
