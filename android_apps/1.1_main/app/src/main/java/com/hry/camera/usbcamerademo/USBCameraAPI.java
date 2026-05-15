package com.hry.camera.usbcamerademo;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.hry.camera.usbcamera.IKeyCallback;
import com.hry.camera.usbcamera.IPictureCallback;
import com.hry.camera.usbcamera.IPreviewCallback;
import com.hry.camera.usbcamera.USBCamera;
import com.hry.camera.usbcamerautil.PropertyInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class USBCameraAPI {
    private  final static String TAG = "USBCamera";

    public static final int VIDEOFORMAT_MJPG = 0;
    public static final int VIDEOFORMAT_YUY2 = 1;

    public static final int ROTATION_0 = 0;
    public static final int ROTATION_90 = 90;
    public static final int ROTATION_180 = 180;
    public static final int ROTATION_270 = 270;

    public static final int STATE_HAS_PERMISSION = 0;
    public static final int STATE_NO_PERMISSION = 1;
    public static final int STATE_DEVICE_ARRIVAL = 2;
    public static final int STATE_DEVICE_REMOVAL = 3;

    private static final String ACTION_USB_PERMISSION = "REQUEST_USB_PERMISSION";

    private UsbManager mUsbManager;
    private UsbDevice mUsbDevice = null;
    private UsbDeviceConnection mConnection = null;
    private int m_fd = -1;
    private int m_vid = 0;
    private int m_pid = 0;

    private boolean m_bIsOpen = false;
    private boolean m_bIsPreview = false;

    private boolean mIsActivityDestroy = false;
    private boolean mDoRequest = false;
    private final WeakReference<Context> mWeakContext;

    ///
    /// 状态回调函数
    ///
    private PendingIntent mPermissionIntent = null;

    public interface StateChangeListener {
        void OnStateChange(int state);
    }

    private StateChangeListener stateChangeListener;

    public void setOnStateChangeListener(StateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    ///
    /// 构造函数
    ///
    public USBCameraAPI(Context context)
    {
        mWeakContext = new WeakReference<Context>(context);
        mUsbManager = (UsbManager)context.getSystemService(Context.USB_SERVICE);
    }

    ///
    /// 注册USB消息接收器
    ///

    private boolean m_bReceiverRegisterd = false;

    public void registerReceiver() {
        if (!m_bReceiverRegisterd) {
            final Context context = mWeakContext.get();
            if (context != null) {
                IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
                filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
                filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
                context.registerReceiver(mUsbReceiver, filter);
                m_bReceiverRegisterd = true;
            }
        }
    }

    ///
    /// 释放USB消息接收器
    ///
    public void unregisterReceiver() {
        if (m_bReceiverRegisterd) {
            final Context context = mWeakContext.get();
            try {
                if (context != null) {
                    context.unregisterReceiver(mUsbReceiver);
                }
            } catch (final Exception e) {
                Log.w(TAG, e);
            }
            m_bReceiverRegisterd = false;
        }
    }

    ///
    /// 获取指定设备
    ///
    public int chooseDeviceByIndex(int index)
    {
        final HashMap<String, UsbDevice> devMap = mUsbManager.getDeviceList();
        final List<UsbDevice> devList = new ArrayList<UsbDevice>();
        devList.addAll(devMap.values());
        if (devList.size() > 0) {
            UsbDevice preferred = null;
            for (UsbDevice device : devList) {
                Log.d(TAG, "USB device candidate: " + describeDevice(device));
                if (isLikelyCameraDevice(device)) {
                    preferred = device;
                    break;
                }
            }
            if (preferred == null) {
                if (index >= 0 && index < devList.size()) {
                    preferred = devList.get(index);
                } else {
                    preferred = devList.get(0);
                }
            }
            mUsbDevice = preferred;
            Log.d(TAG, "USB device chosen: " + describeDevice(mUsbDevice));
            return 0;
        }
        return -1;
    }

    public int chooseDeviceById(int vid, int pid)
    {
        int i;

        final HashMap<String, UsbDevice> devMap = mUsbManager.getDeviceList();
        final List<UsbDevice> devList = new ArrayList<UsbDevice>();
        devList.addAll(devMap.values());

        for (i=0; i<devList.size(); i++) {
            int vvid = devList.get(i).getVendorId();
            int vpid = devList.get(i).getProductId();
            if (devList.get(i).getVendorId() == vid &&  devList.get(i).getProductId() == pid) {
                mUsbDevice = devList.get(i);
                return 0;
            }
        }
        return -1;
    }

    ///
    /// 设置显示窗口
    ///
    public synchronized void setPreviewSurface(Surface surface, int w, int h)
    {
        USBCamera.setPreviewSurface(surface, w, h);
    }

    ///
    /// 设置旋转属性
    ///
    public synchronized void setPreviewOrientation(int orientation) {
        USBCamera.setPreviewOrientation(orientation);
    }

    ///
    /// 摄像头是否打开
    ///
    public boolean isOpen()
    {
        return m_bIsOpen;
    }


    ///
    /// 打开摄像头
    ///
    public synchronized boolean openCamera() {
        if (m_bIsOpen)
            return true;

        if (mUsbDevice == null)
            return false;

        final String name = mUsbDevice.getDeviceName();
        final String[] v = !TextUtils.isEmpty(name) ? name.split("/") : null;
        if (v == null)
            return false;


        final StringBuilder sb = new StringBuilder(v[0]);
        for (int i=1; i<v.length-2; i++)
            sb.append("/").append(v[i]);
        String fsName = sb.toString();

        int busnum = Integer.parseInt(v[v.length - 2]);
        int devnum = Integer.parseInt(v[v.length - 1]);

        mConnection = mUsbManager.openDevice(mUsbDevice);
        if (mConnection == null)
            return false;

        m_fd = mConnection.getFileDescriptor();
        if (m_fd == -1) {
            mConnection.close();
            m_fd = -1;
            return false;
        }

        if (USBCamera.openCamera(m_fd, m_vid, m_pid, busnum, devnum, fsName) < 0) {
            mConnection.close();
            m_fd = -1;
            return false;
        }

        m_bIsOpen = true;
        return true;
    }

    ///
    /// 关闭摄像头
    ///
    public synchronized void closeCamera(int type)
    {
        if (m_bIsOpen) {
            USBCamera.closeCamera(type);
            mConnection.close();
            m_fd = -1;
            m_bIsOpen = false;
        }
    }

    ///
    /// 摄像头是否预览
    ///
    public boolean isPreview() {return m_bIsPreview;}


    ///
    /// 开启预览
    ///
    public synchronized boolean startPreview(int format, int w, int h, int minFps, int maxFps, float bandwidth) {
        if (m_bIsPreview)
            return true;

        if (USBCamera.startPreview(format, w, h, minFps, maxFps, bandwidth) < 0) {
            USBCamera.closeCamera(1);
            mConnection.close();
            m_fd = -1;
            return false;
        }

        m_bIsPreview = true;
        return true;
    }

    ///
    /// 关闭预览
    ///
    public synchronized void stopPreview() {
        if (m_bIsPreview) {
            USBCamera.stopPreview();
            m_bIsPreview = false;
        }
    }

    ///
    /// 拍照
    ///
    public synchronized  void takePicture()
    {
        USBCamera.takePicture();
    }

    ///
    /// 设置拍照回调函数
    ///
    public void setPreviewCallback(IPreviewCallback cb)
    {
        USBCamera.setPreviewCallback(cb);
    }
    public void setPictureCallback(IPictureCallback cb)
    {
        USBCamera.setPictureCallback(cb);
    }



    ///
    /// 请求权限
    ///
    public void requestPermission()
    {
        if (mUsbDevice == null)
            return;

        if (!mUsbManager.hasPermission(mUsbDevice)) {
            final Context context = mWeakContext.get();
            if (context != null) {
                int flags = 0;
                if (Build.VERSION.SDK_INT >= 31) {
                    flags |= 0x02000000; // PendingIntent.FLAG_MUTABLE, unavailable at compileSdkVersion 28
                }
                mPermissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);
                mUsbManager.requestPermission(mUsbDevice, mPermissionIntent);
            }
        } else {
            stateChangeListener.OnStateChange(STATE_HAS_PERMISSION);
        }
    }

    ///
    /// 消息接收器
    ///
    public void setDoRequest(boolean can) {
        mDoRequest = can;
    }

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (mIsActivityDestroy)
                return;


            final String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (mDoRequest) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        stateChangeListener.OnStateChange(STATE_HAS_PERMISSION);
                    } else {
                        stateChangeListener.OnStateChange(STATE_NO_PERMISSION);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                stateChangeListener.OnStateChange(STATE_DEVICE_ARRIVAL);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                stateChangeListener.OnStateChange(STATE_DEVICE_REMOVAL);
            }
        }
    };

    ///
    /// 设置Activity状态
    ///
    public void setActivityDestroy(boolean destroy) {
        mIsActivityDestroy = destroy;
    }

    ///
    /// 亮度
    ///
    public PropertyInfo queryBrightnessInfo() {
        int[] pbuf = new int[4];
        if (USBCamera.updateBrightnessLimit(pbuf) == 0) {
            PropertyInfo info = new PropertyInfo(pbuf[0], pbuf[1], pbuf[2], pbuf[3]);
            return info;
        } else {
            return null;
        }
    }
    public int getBrightness() {
        return USBCamera.getBrightness();
    }
    public int setBrightness(int brightness) {
        return USBCamera.setBrightness(brightness);
    }

    ///
    /// 对比度
    ///
    public PropertyInfo queryContrastInfo() {
        int[] pbuf = new int[4];
        if (USBCamera.updateContrastLimit(pbuf) == 0) {
            PropertyInfo info = new PropertyInfo(pbuf[0], pbuf[1], pbuf[2], pbuf[3]);
            return info;
        } else {
            return null;
        }
    }
    public int getContrast() {
        return USBCamera.getContrast();
    }
    public int setContrast(int contrast) {
        return USBCamera.setContrast(contrast);
    }

    ///
    /// 饱和度
    ///
    public PropertyInfo querySaturationInfo() {
        int[] pbuf = new int[4];
        if (USBCamera.updateSaturationLimit(pbuf) == 0) {
            PropertyInfo info = new PropertyInfo(pbuf[0], pbuf[1], pbuf[2], pbuf[3]);
            return info;
        } else {
            return null;
        }
    }
    public int getSaturation() {
        return USBCamera.getSaturation();
    }
    public int setSaturation(int saturation) {
        return USBCamera.setSaturation(saturation);
    }

    ///
    /// 按键回调
    ///
    public void setKeyCallback(IKeyCallback cb)
    {
        USBCamera.setKeyCallback(cb);
    }

    private boolean isLikelyCameraDevice(UsbDevice device) {
        if (device == null) {
            return false;
        }
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO) {
            return true;
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                return true;
            }
        }
        String name = String.valueOf(device.getProductName()).toLowerCase();
        return name.contains("camera") || name.contains("uvc");
    }

    private String describeDevice(UsbDevice device) {
        if (device == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("name=").append(device.getDeviceName())
                .append(", vid=").append(device.getVendorId())
                .append(", pid=").append(device.getProductId())
                .append(", class=").append(device.getDeviceClass())
                .append(", subclass=").append(device.getDeviceSubclass())
                .append(", product=").append(device.getProductName())
                .append(", interfaces=");
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            sb.append(device.getInterface(i).getInterfaceClass());
            if (i < device.getInterfaceCount() - 1) {
                sb.append("|");
            }
        }
        return sb.toString();
    }
}
