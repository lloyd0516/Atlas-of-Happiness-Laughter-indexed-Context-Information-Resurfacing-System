package com.hry.camera.usbcamerademo;


import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Surface;

import com.hry.camera.usbcamera.IPictureCallback;
import com.hry.camera.usbcamera.IPreviewCallback;
import com.hry.camera.usbcamerautil.FileUtil;
import com.hry.camera.usbcamerautil.PropertyInfo;
import com.hry.camera.usbcamerautil.Thumbnail;

import java.lang.ref.WeakReference;


public class USBCameraThread extends Thread {
    private final Object mSync = new Object();

    private static final String TAG = "USBCameraThread";

    public static final int CLOSECAMERA_TYPE_HOTPLUG = 0;
    public static final int CLOSECAMERA_TYPE_NORMAL = 1;

    public static final int ERR_NONE = 0;
    public static final int ERR_CHOOSE_DEVICE = 1;
    public static final int ERR_OPEN_DEVICE = 2;
    public static final int ERR_START_PREVIEW = 3;
    public static final int SUCCESS_READY = 10;
    public static final int SUCCESS_OPEN_CAMERA = 11;

    private USBCameraAPI mUSBCamera;
    private boolean m_isopen = false;

    private USBCameraAPI.StateChangeListener mUSBCB;
    private USBCameraThreadState mUSBCameraThreadStateCB;
    private WeakReference<Context> mContextWeakReference;

    private int mVideoW;
    private int mVideoH;
    private int mVideoFormat;
    private int mVideoOrientation;
    private int mVideoMinFps;
    private int mVideoMaxFps;
    private float mVideoBandWidth;
    private int mVideoWNew;
    private int mVideoHNew;
    private Surface mSurface;

    private PropertyInfo mBrightnessInfo = new PropertyInfo();

    public Handler mThreadHandler;

    public USBCameraThread(Context context, USBCameraAPI.StateChangeListener usbCB, USBCameraThreadState stateCB) {
        mContextWeakReference = new WeakReference<>(context);
        mUSBCB = usbCB;
        mUSBCameraThreadStateCB = stateCB;
    }

    public void createDevice() {
        if (mThreadHandler != null) {
            mThreadHandler.sendEmptyMessage(MSG_CREATE_DEVICE);
        }
    }

    public void deleteDevice() {
        if (mThreadHandler != null) {
            mThreadHandler.sendEmptyMessage(MSG_DELETE_DEVICE);
        }
    }

    public void chooseDevice() {
        if (mThreadHandler != null) {
            mThreadHandler.sendEmptyMessage(MSG_CHOOSE_DEVICE);
        }
    }

    public void openCamera(int w, int h, int format, int orientation, int minFps, int maxFps, float bandWidth, Surface surface) {

        mVideoW = w;
        mVideoH = h;
        mVideoFormat = format;
        mVideoOrientation = orientation;
        mVideoMinFps = minFps;
        mVideoMaxFps = maxFps;
        mVideoBandWidth = bandWidth;
        mSurface = surface;

        if (mThreadHandler != null) {
            mThreadHandler.sendEmptyMessage(MSG_OPEN_DEVICE);
        }
    }

    public void closeCamera(int type) {
        if (mThreadHandler != null) {
            mThreadHandler.obtainMessage(MSG_CLOSE_DEVICE, type).sendToTarget();
        }
    }

    public void setPreviewSurface(Surface surface) {
        mSurface = surface;
        if (mThreadHandler != null) {
            mThreadHandler.sendEmptyMessage(MSG_SET_SURFACE);
        }
    }

    public void setBrightnessVal(int v) {
        if (mThreadHandler != null) {
            mThreadHandler.obtainMessage(MSG_SET_BRIGHTNESS, v).sendToTarget();
        }
    }

    public int getBrightnessMin() {
        if (mBrightnessInfo != null)
            return mBrightnessInfo.min;
        else
            return 0;
    }
    public int getBrightnessMax() {
        if (mBrightnessInfo != null)
            return mBrightnessInfo.max;
        else
            return 0;
    }
    public int getBrightnessVal() {
        if (mBrightnessInfo != null)
            return mBrightnessInfo.cur;
        else
            return 0;
    }

    public int getNewVideoW() {
        return mVideoWNew;
    }

    public int getNewVideoH() {
        return mVideoHNew;
    }

    public boolean isOpen() {
        return m_isopen;
    }

    public void stillCapture(IPictureCallback cb) {
        if (mThreadHandler != null) {
            mThreadHandler.obtainMessage(MSG_STILL_CAPTURE, cb).sendToTarget();
        }
    }

    public void setPictureCallback(IPictureCallback cb) {
        if (mThreadHandler != null) {
            mThreadHandler.obtainMessage(MSG_SET_PICTURE_CALLBACK, cb).sendToTarget();
        }
    }

    public void setPreviewCallback(IPreviewCallback cb) {
        if (mThreadHandler != null) {
            mThreadHandler.obtainMessage(MSG_SET_PREVIEW_CALLBACK, cb).sendToTarget();
        }
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    ///
    /// thread process functions
    ///

    public void handleCreateUSBDevice() {
        if (mUSBCamera != null)
            return;
        Context context = mContextWeakReference.get();
        if (context == null)
            return;
        synchronized (mSync) {
            mUSBCamera = new USBCameraAPI(context);
            mUSBCamera.setActivityDestroy(false);
            mUSBCamera.registerReceiver();
            mUSBCamera.setOnStateChangeListener(mUSBCB);
        }
    }

    public void handleDeleteUSBDevice() {
        if (mUSBCamera == null)
            return;
        synchronized (mSync) {
            mUSBCamera.setActivityDestroy(true);
            mUSBCamera.unregisterReceiver();
            mUSBCamera = null;
        }
        Looper.myLooper().quit();
    }

    public void handleChooseDevice() {
        if (mUSBCamera == null)
            return;
        synchronized (mSync) {
            // 按编号选择设备
            if (mUSBCamera.chooseDeviceByIndex(0) < 0) {
                if (mUSBCameraThreadStateCB != null) {
                    mUSBCameraThreadStateCB.onUSBCameraState(ERR_CHOOSE_DEVICE);
                }
                return;
            }

            // 获取编号后，可以对指定设备获取权限
            mUSBCamera.setDoRequest(true);  // 因为获取权限消息有时间差，如果某些情况下不再需要接收权限消息，则设置为false
            mUSBCamera.requestPermission();
        }
    }

    public void handleOpenCamera() {
        if (mUSBCamera == null)
            return;
        if (mUSBCamera.isOpen())
            return;

        synchronized (mSync) {
            if (!mUSBCamera.openCamera()) {
                if (mUSBCameraThreadStateCB != null) {
                    mUSBCameraThreadStateCB.onUSBCameraState(ERR_OPEN_DEVICE);
                }
                return;
            }

            // 设置影像方向
            mUSBCamera.setPreviewOrientation(mVideoOrientation);

            if (mVideoOrientation==USBCameraAPI.ROTATION_90 || mVideoOrientation==USBCameraAPI.ROTATION_270) {
                mVideoWNew = mVideoH;
                mVideoHNew = mVideoW;
            } else {
                mVideoWNew = mVideoW;
                mVideoHNew = mVideoH;
            }

            // 开启预览
            if (!mUSBCamera.startPreview(mVideoFormat, mVideoW, mVideoH, mVideoMinFps, mVideoMaxFps, mVideoBandWidth)) {
                mUSBCamera.closeCamera(CLOSECAMERA_TYPE_NORMAL);
                if (mUSBCameraThreadStateCB != null) {
                    mUSBCameraThreadStateCB.onUSBCameraState(ERR_START_PREVIEW);
                }
                return;
            }

            // 设置预览窗口
            if (mSurface != null) {
                mUSBCamera.setPreviewSurface(mSurface, mVideoWNew, mVideoHNew);
            }

            // 设置按键回调函数
            //mUSBCamera.setKeyCallback(mKeyCallback);

            // 初始化属性 (目前支持亮度，对比度，饱和度)
            mBrightnessInfo = mUSBCamera.queryBrightnessInfo();
            mBrightnessInfo.cur = mUSBCamera.getBrightness();

            m_isopen = true;

            if (mUSBCameraThreadStateCB != null) {
                mUSBCameraThreadStateCB.onUSBCameraState(SUCCESS_OPEN_CAMERA);
            }
        }
    }

    public void handleCloseCamera(int type) {
        if (mUSBCamera == null)
            return;
        if (!mUSBCamera.isOpen())
            return;

        synchronized (mSync) {
            mUSBCamera.setPreviewSurface(null, 0, 0);

            mUSBCamera.stopPreview();
            mUSBCamera.closeCamera(type);

            m_isopen = false;
        }
    }

    public void handleSetPreviewSurface() {
        if (mUSBCamera == null)
            return;

        synchronized (mSync) {
            mUSBCamera.setPreviewSurface(mSurface, mVideoWNew, mVideoHNew);
        }
    }

    public void handleSetBrightness(int v) {
        if (mUSBCamera == null)
            return;

        synchronized (mSync) {
            mBrightnessInfo.cur = v;
            mUSBCamera.setBrightness(v);
        }
    }

    public void handleStillCapture(IPictureCallback cb) {
        if (mUSBCamera == null)
            return;
        if (!m_isopen)
            return;

        synchronized (mSync) {
            mUSBCamera.setPictureCallback(cb);
            mUSBCamera.takePicture();
        }
    }

    public void handleSetPictureCallback(IPictureCallback cb) {
        if (mUSBCamera == null)
            return;

        synchronized (mSync) {
            mUSBCamera.setPictureCallback(cb);
        }
    }

    public void handleSetPreviewCallback(IPreviewCallback cb) {
        if (mUSBCamera == null)
            return;

        synchronized (mSync) {
            mUSBCamera.setPreviewCallback(cb);
        }
    }
    private static final int MSG_CREATE_DEVICE = 0x01;
    private static final int MSG_DELETE_DEVICE = 0x02;
    private static final int MSG_CHOOSE_DEVICE = 0x03;
    private static final int MSG_OPEN_DEVICE = 0x04;
    private static final int MSG_CLOSE_DEVICE = 0x05;
    private static final int MSG_SET_SURFACE = 0x06;
    private static final int MSG_SET_BRIGHTNESS = 0x07;
    private static final int MSG_STILL_CAPTURE = 0x08;
    private static final int MSG_SET_PICTURE_CALLBACK = 0x09;
    private static final int MSG_SET_PREVIEW_CALLBACK = 0x10;

    @Override
    public void run() {
        Looper.prepare();

        mThreadHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_CREATE_DEVICE:
                        handleCreateUSBDevice();
                        break;

                    case MSG_DELETE_DEVICE:
                        handleDeleteUSBDevice();
                        break;

                    case MSG_CHOOSE_DEVICE:
                        handleChooseDevice();
                        break;

                    case MSG_OPEN_DEVICE:
                        handleOpenCamera();
                        break;

                    case MSG_CLOSE_DEVICE:
                        handleCloseCamera(Integer.parseInt(msg.obj.toString()));
                        break;

                    case MSG_SET_SURFACE:
                        handleSetPreviewSurface();
                        break;

                    case MSG_SET_BRIGHTNESS:
                        handleSetBrightness(Integer.parseInt(msg.obj.toString()));
                        break;

                    case MSG_STILL_CAPTURE:
                        handleStillCapture((IPictureCallback)msg.obj);
                        break;

                    case MSG_SET_PICTURE_CALLBACK:
                        handleSetPictureCallback((IPictureCallback)msg.obj);
                        break;

                    case MSG_SET_PREVIEW_CALLBACK:
                        handleSetPreviewCallback((IPreviewCallback) msg.obj);
                        break;

                    default:
                        super.handleMessage(msg);
                        break;
                }
            }
        };

        if (mUSBCameraThreadStateCB != null) {
            mUSBCameraThreadStateCB.onUSBCameraState(SUCCESS_READY);
        }

        Looper.loop();
    }
}
