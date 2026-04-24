package com.hry.camera.usbcamera;

import android.view.Surface;



public class USBCamera {
    static {
        System.loadLibrary("USBCamera101");
    }

    public static native int openCamera(final int fd, final int vid, final int pid, final int busNum, final int devAddr, final String fsName);
    public static native void closeCamera(int type);
    public static native int startPreview(final int format, final int w, final int h, final int minFps, final int maxFps, float bandwidth);
    public static native void stopPreview();
    public static native void setPreviewOrientation(final int orientation);
    public static native void setPreviewSurface(final Surface surface, int w, int h);
    public static native void setPreviewCallback(final IPreviewCallback cb);
    public static native void setPictureCallback(final IPictureCallback cb);
    public static native void takePicture();
    public static native void dspRegW(int addr, byte[] pval, int len);
    public static native void dspRegR(int addr, byte[] pval, int len);
    public static native int getBrightness();
    public static native int setBrightness(final int brightness);
    public static native int updateBrightnessLimit(int[] info);
    public static native int getContrast();
    public static native int setContrast(final int contrast);
    public static native int updateContrastLimit(int[] info);
    public static native int getSaturation();
    public static native int setSaturation(final int saturation);
    public static native int updateSaturationLimit(int[] info);
    public static native void setKeyCallback(final IKeyCallback cb);
}
