package com.hry.camera.usbcamera;

public interface IPictureCallback {
    void onPictureFrame(int[] pbuf, int w, int h);
}
