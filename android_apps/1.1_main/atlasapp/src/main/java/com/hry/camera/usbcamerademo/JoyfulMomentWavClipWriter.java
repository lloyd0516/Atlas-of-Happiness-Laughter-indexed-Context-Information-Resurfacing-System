package com.hry.camera.usbcamerademo;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class JoyfulMomentWavClipWriter {
    private static final int WAV_HEADER_BYTES = 44;

    public static class ClosedClip {
        public int clipId;
        public double startSec;
        public double endSec;
        public File path;
    }

    public static class SourceClip {
        public File path;
        public double startSec;
        public double endSec;

        public SourceClip(File path, double startSec, double endSec) {
            this.path = path;
            this.startSec = startSec;
            this.endSec = endSec;
        }
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
        writeWavHeader(file, sampleRate, channels, sampleWidthBytes, dataBytes);
    }

    public static boolean writeWindow(File outputPath, List<SourceClip> sourceClips, double windowStartSec, double windowEndSec) throws IOException {
        if (outputPath == null || sourceClips == null || sourceClips.isEmpty() || windowEndSec <= windowStartSec) {
            return false;
        }
        WavFormat format = null;
        for (SourceClip source : sourceClips) {
            if (source != null && source.path != null && source.path.exists()) {
                format = readFormat(source.path);
                break;
            }
        }
        if (format == null) {
            return false;
        }
        RandomAccessFile output = null;
        long dataBytes = 0;
        try {
            output = new RandomAccessFile(outputPath, "rw");
            output.setLength(0);
            writeWavHeader(output, format.sampleRate, format.channels, format.sampleWidthBytes, 0);
            byte[] buffer = new byte[8192];
            for (SourceClip source : sourceClips) {
                if (source == null || source.path == null || !source.path.exists()) {
                    continue;
                }
                double copyStartSec = Math.max(windowStartSec, source.startSec);
                double copyEndSec = Math.min(windowEndSec, source.endSec);
                if (copyEndSec <= copyStartSec) {
                    continue;
                }
                RandomAccessFile input = null;
                try {
                    input = new RandomAccessFile(source.path, "r");
                    int sourceStartFrame = (int) Math.max(0, Math.round((copyStartSec - source.startSec) * format.sampleRate));
                    int sourceEndFrame = (int) Math.max(sourceStartFrame, Math.round((copyEndSec - source.startSec) * format.sampleRate));
                    long bytesToCopy = (long) (sourceEndFrame - sourceStartFrame) * format.bytesPerFrame;
                    long maxAvailable = Math.max(0, input.length() - WAV_HEADER_BYTES - (long) sourceStartFrame * format.bytesPerFrame);
                    bytesToCopy = Math.min(bytesToCopy, maxAvailable);
                    input.seek(WAV_HEADER_BYTES + (long) sourceStartFrame * format.bytesPerFrame);
                    while (bytesToCopy > 0) {
                        int read = input.read(buffer, 0, (int) Math.min(buffer.length, bytesToCopy));
                        if (read <= 0) {
                            break;
                        }
                        output.write(buffer, 0, read);
                        dataBytes += read;
                        bytesToCopy -= read;
                    }
                } finally {
                    if (input != null) {
                        input.close();
                    }
                }
            }
            if (dataBytes <= 0) {
                return false;
            }
            output.seek(0);
            writeWavHeader(output, format.sampleRate, format.channels, format.sampleWidthBytes, dataBytes);
            return true;
        } finally {
            if (output != null) {
                output.close();
            }
            if (dataBytes <= 0 && outputPath.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outputPath.delete();
            }
        }
    }

    private static void writeWavHeader(RandomAccessFile file, int sampleRate, int channels, int sampleWidthBytes, long dataBytes) throws IOException {
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

    private static WavFormat readFormat(File path) throws IOException {
        RandomAccessFile file = null;
        try {
            file = new RandomAccessFile(path, "r");
            if (file.length() < WAV_HEADER_BYTES) {
                return null;
            }
            WavFormat format = new WavFormat();
            file.seek(22);
            format.channels = readLittleEndianShort(file);
            file.seek(24);
            format.sampleRate = readLittleEndianInt(file);
            file.seek(34);
            int bitsPerSample = readLittleEndianShort(file);
            format.sampleWidthBytes = Math.max(1, bitsPerSample / 8);
            format.bytesPerFrame = format.channels * format.sampleWidthBytes;
            return format;
        } finally {
            if (file != null) {
                file.close();
            }
        }
    }

    private static int readLittleEndianInt(RandomAccessFile file) throws IOException {
        int b0 = file.read();
        int b1 = file.read();
        int b2 = file.read();
        int b3 = file.read();
        return (b0 & 0xFF)
                | ((b1 & 0xFF) << 8)
                | ((b2 & 0xFF) << 16)
                | ((b3 & 0xFF) << 24);
    }

    private static int readLittleEndianShort(RandomAccessFile file) throws IOException {
        int b0 = file.read();
        int b1 = file.read();
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8);
    }

    private static void writeLittleEndianInt(RandomAccessFile file, int value) throws IOException {
        file.write(value & 0xFF);
        file.write((value >> 8) & 0xFF);
        file.write((value >> 16) & 0xFF);
        file.write((value >> 24) & 0xFF);
    }

    private static void writeLittleEndianShort(RandomAccessFile file, short value) throws IOException {
        file.write(value & 0xFF);
        file.write((value >> 8) & 0xFF);
    }

    private static class WavFormat {
        int sampleRate;
        int channels;
        int sampleWidthBytes;
        int bytesPerFrame;
    }
}
