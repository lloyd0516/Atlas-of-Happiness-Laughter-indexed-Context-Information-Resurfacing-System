package com.hry.camera.usbcamerautil;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;

public class JoyfulExternalPcmAudioEncoder extends MediaAudioEncoder {
    private static final String TAG = "JoyfulExternalAudio";
    private static final String MIME_TYPE = "audio/mp4a-latm";

    private final int sampleRate;
    private final int channelCount;
    private final int bitRate;
    private long basePtsNs = -1L;
    private long prevPtsUs = 0L;

    public JoyfulExternalPcmAudioEncoder(
            final MediaMuxerWrapper muxer,
            final MediaEncoderListener listener,
            final int sampleRate,
            final int channelCount,
            final int bitRate
    ) {
        super(muxer, listener);
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        this.bitRate = bitRate;
    }

    @Override
    protected void prepare() throws IOException {
        mTrackIndex = -1;
        mMuxerStarted = mIsEOS = false;

        final MediaCodecInfo audioCodecInfo = selectAudioCodec(MIME_TYPE);
        if (audioCodecInfo == null) {
            throw new IOException("Unable to find codec for " + MIME_TYPE);
        }

        final MediaFormat audioFormat = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channelCount);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK,
                channelCount == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount);

        mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
        mMediaCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mMediaCodec.start();

        if (mListener != null) {
            try {
                mListener.onPrepared(this);
            } catch (final Exception e) {
                Log.e(TAG, "prepare", e);
            }
        }
    }

    @Override
    protected void startRecording() {
        synchronized (mSync) {
            mIsCapturing = true;
            mRequestStop = false;
            isPause = false;
            basePtsNs = System.nanoTime();
            prevPtsUs = 0L;
            mSync.notifyAll();
        }
    }

    public void offerPcm(final byte[] pcm16le, final int length) {
        if (pcm16le == null || length <= 0) {
            return;
        }
        synchronized (mSync) {
            if (!mIsCapturing || mRequestStop || mMediaCodec == null) {
                return;
            }
        }
        encode(pcm16le, length, getPTSUs());
        frameAvailableSoon();
    }

    @Override
    protected long getPTSUs() {
        long now = System.nanoTime();
        if (basePtsNs < 0L) {
            basePtsNs = now;
        }
        long ptsUs = (now - basePtsNs) / 1000L;
        if (ptsUs < prevPtsUs) {
            ptsUs = prevPtsUs;
        } else {
            prevPtsUs = ptsUs;
        }
        return ptsUs;
    }

    private static MediaCodecInfo selectAudioCodec(final String mimeType) {
        final int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            final MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            final String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }
}
