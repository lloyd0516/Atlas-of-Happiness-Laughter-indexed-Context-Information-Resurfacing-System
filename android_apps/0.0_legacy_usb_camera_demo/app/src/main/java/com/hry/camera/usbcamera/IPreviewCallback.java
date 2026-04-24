package com.hry.camera.usbcamera;

import java.nio.ByteBuffer;

public interface IPreviewCallback {
    void onPreviewFrame(final ByteBuffer buf);
}
