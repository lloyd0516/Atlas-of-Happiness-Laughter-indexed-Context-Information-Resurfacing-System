package com.hry.camera.usbcamerademo;

import android.Manifest;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Handler;
import android.os.Build;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.hry.camera.usbcamera.IPictureCallback;
import com.hry.camera.usbcamera.IPreviewCallback;
import com.hry.camera.usbcamera.IKeyCallback;
import com.hry.camera.usbcamera.USBCamera;
import com.hry.camera.usbcamerautil.FileUtil;
import com.hry.camera.usbcamerautil.MediaEncoder;
import com.hry.camera.usbcamerautil.JoyfulExternalPcmAudioEncoder;
import com.hry.camera.usbcamerautil.MediaMuxerWrapper;
import com.hry.camera.usbcamerautil.MediaVideoBufferEncoder;
import com.hry.camera.usbcamerautil.PropertyInfo;
import com.hry.camera.usbcamerautil.Thumbnail;
import com.hry.camera.usbcamerautil.Util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements JoyfulMomentController.HostCallbacks {

    private static final String TAG = "USBCamera.MainActivity";

    private static final int VIDEO_W = 640;
    private static final int VIDEO_H = 480;
    private static final int VIDEO_FORMAT = USBCameraAPI.VIDEOFORMAT_MJPG;

    // 注意:
    // 通过设定VIDEO_ORIENTATION=0,90,180,270，以及设定screenOrientation为横屏或竖屏，并配合硬件模组的摆放方向，来决定成像方向

    private static final int VIDEO_ORIENTATION = USBCameraAPI.ROTATION_0;
    private static final int VIDEO_MINFPS = 0;
    private static final int VIDEO_MAXFPS = 30;
    private static final float VIDEO_BANDWIDTH = 1.0f;    // 0.0 ~1.0f

    private static final int DEFAULT_THUMBNAIL_WIDTH = 80;
    private static final int JOYFUL_AUDIO_SAMPLE_RATE = 16000;
    private static final int JOYFUL_AUDIO_CHANNEL_COUNT = 1;
    private static final int JOYFUL_AUDIO_BIT_RATE = 64000;

    USBCameraThread mCameraThread;
    FileUtil mFileUtil;
    Thumbnail mThumbnail;
    ContentResolver mContentResolver;

    Surface mSurface;
    SurfaceHolder mSurfaceHolder;
    SurfaceView mSurfaceView;
    ImageView mThumbnailView;
    TextView mPreviewTip;
    Button mPreviewBtn;
    Button mCaptureBtn;
    Button mRecordBtn;
    Button mPropertyBtn;
    LinearLayout mRecordingLayout;
    ImageView mRecordingImage;
    TextView mRecordingText;
    LinearLayout mSeekBarLayout;
    SeekBar mSeekBar;
    TextView mSeekBarText;
    JoyfulMomentController mJoyfulController;
    private boolean mJoyfulAutoRecording = false;
    private boolean mLastRecordWasJoyfulAuto = false;
    private final AtlasAutoCaptureQueue mJoyfulAutoCaptureQueue =
            new AtlasAutoCaptureQueue();
    private long mActiveJoyfulAutoVideoCaptureTimeMs = 0L;
    private boolean mManualPreviewRequested = false;
    private boolean mCameraOpenedForAutoOnly = false;
    private boolean mJoyfulAutoOpenInFlight = false;

    // Requirement 1 (redesign pass): recording页面.jpg — glow halo + button, today's stats card,
    // recent-records card. The live camera SurfaceView only exists inside the preview dialog.
    private FrameLayout mRecordCircleFrame;
    private ImageView mRecordCircleGlow;
    private Button mRecordCircleButton;
    private TextView mRecordingPrompt;
    private TextView mTxtRecordingPromptArt;
    private TextView mTxtDeviceStatus;
    private View mDeviceStatusDot;
    private AtlasDeviceConnectionState.State mDeviceConnectionState =
            AtlasDeviceConnectionState.State.DISCONNECTED;
    private TextView mTxtStatLaughterCount;
    private TextView mTxtStatDuration;
    private TextView mTxtStatEventCount;
    private LinearLayout mRecentRecordsContainer;
    private TextView mRecentRecordsEmpty;
    private android.widget.HorizontalScrollView mManualControlsPanel;
    private android.app.Dialog mPreviewDialog;
    private AtlasReviewRepository mReviewRepository;
    private final SimpleDateFormat mLastLaughterTimeFormat = new SimpleDateFormat("HH:mm", java.util.Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        AtlasDevLogger.session(this, TAG, AtlasDevLogger.buildSessionBanner("MainActivity.onCreate"));
        setContentView(R.layout.activity_main);

        // 界面控件初始化 —— recording 主页面（圆形按钮 + 实时状态），摄像头 SurfaceView 只在预览弹窗中才创建
        mRecordCircleFrame = (FrameLayout) findViewById(R.id.recordCircleFrame);
        mRecordCircleGlow = (ImageView) findViewById(R.id.recordCircleGlow);
        mRecordCircleButton = (Button) findViewById(R.id.btnRecordCircle);
        mRecordCircleButton.setOnClickListener(mButtonClickListener);
        mRecordCircleButton.setOnLongClickListener(mRecordCircleLongClickListener);
        mRecordingPrompt = (TextView) findViewById(R.id.recordingPrompt);
        mTxtRecordingPromptArt = (TextView) findViewById(R.id.txtRecordingPromptArt);
        mTxtDeviceStatus = (TextView) findViewById(R.id.txtDeviceStatus);
        mDeviceStatusDot = findViewById(R.id.deviceStatusDot);
        renderDeviceConnectionState();

        mRecordingLayout = (LinearLayout) findViewById(R.id.recordingLayout);
        mRecordingImage = (ImageView) findViewById(R.id.recordingImage);
        mRecordingText = (TextView) findViewById(R.id.recordingText);

        mTxtStatLaughterCount = (TextView) findViewById(R.id.txtStatLaughterCount);
        mTxtStatDuration = (TextView) findViewById(R.id.txtStatDuration);
        mTxtStatEventCount = (TextView) findViewById(R.id.txtStatEventCount);
        mRecentRecordsContainer = (LinearLayout) findViewById(R.id.recentRecordsContainer);
        mRecentRecordsEmpty = (TextView) findViewById(R.id.recentRecordsEmpty);
        mReviewRepository = new AtlasReviewRepository(this);

        mThumbnailView = (ImageView) findViewById(R.id.thumbnailView);
        mThumbnailView.setOnClickListener(mButtonClickListener);
        mThumbnailView.setClickable(true);

        mPreviewBtn = (Button) findViewById(R.id.btnPreview);
        mPreviewBtn.setOnClickListener(mButtonClickListener);
        mCaptureBtn = (Button) findViewById(R.id.btnCapture);
        mCaptureBtn.setOnClickListener(mButtonClickListener);
        mRecordBtn = (Button) findViewById(R.id.btnRecord);
        mRecordBtn.setOnClickListener(mButtonClickListener);
        mPropertyBtn = (Button) findViewById(R.id.btnProperty);
        mPropertyBtn.setOnClickListener(mButtonClickListener);

        mSeekBarLayout = (LinearLayout) findViewById(R.id.seekbarLayout);
        mSeekBar = (SeekBar) findViewById(R.id.seekBar);
        mSeekBar.setOnSeekBarChangeListener(mOnSeekBarChangeListener);
        mSeekBarText = (TextView) findViewById(R.id.seekBarText);

        // 初始化ContentResolver
        mContentResolver = getContentResolver();

        // 存储工具类初始化
        mFileUtil = new FileUtil(this);

        // 摄像头类初始化
        mCameraThread = new USBCameraThread(this, mStateChangeListener, mUSBCameraThreadState);
        mCameraThread.start();
        mJoyfulController = new JoyfulMomentController(this, this);
        onJoyfulStatusChanged("Joyful: idle / " + mJoyfulController.getConfig().toSummaryText());
        updateRecordCircleUi();
        refreshTodayStats();

        // 需求6：底部导航栏（记录/回顾/我的）
        AtlasBottomNav.setup(this, AtlasBottomNav.TAB_RECORD);

        // 申请权限
        mHandler.sendEmptyMessage(MSG_REUQEST_PERMISSION);
    }

    /**
     * Requirement 1: tapping the circle opens a small popup preview (same aspect ratio as the
     * physical camera, VIDEO_W:VIDEO_H) so the user can check the headset camera angle before
     * confirming. Confirming closes the popup, asks for the existing participant number (unchanged
     * flow), then starts the Joyful session. While a session is running, tapping the circle stops it.
     */
    private void onRecordCircleClicked() {
        if (mJoyfulController != null && mJoyfulController.isSessionRunning()) {
            toggleJoyfulSession();
            return;
        }
        showCameraPreviewDialog();
    }

    private void showCameraPreviewDialog() {
        if (mPreviewDialog != null && mPreviewDialog.isShowing()) {
            return;
        }
        final View dialogView = getLayoutInflater().inflate(R.layout.dialog_camera_preview, null);
        mSurfaceView = (SurfaceView) dialogView.findViewById(R.id.surfaceView);
        mPreviewTip = (TextView) dialogView.findViewById(R.id.previewTip);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(mSurfaceHolderCallback);
        mSurfaceView.post(new Runnable() {
            @Override
            public void run() {
                updatePreviewSurfaceLayout(VIDEO_H, VIDEO_W);
            }
        });

        mPreviewDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        mPreviewDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (mSurfaceHolder != null) {
                    mSurfaceHolder.removeCallback(mSurfaceHolderCallback);
                }
                mSurfaceView = null;
                mPreviewTip = null;
                mPreviewDialog = null;
            }
        });

        Button confirmBtn = (Button) dialogView.findViewById(R.id.btnPreviewConfirmStart);
        Button cancelBtn = (Button) dialogView.findViewById(R.id.btnPreviewCancel);
        confirmBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mPreviewDialog != null) {
                    mPreviewDialog.dismiss();
                }
                showJoyfulParticipantDialog();
            }
        });
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mPreviewDialog != null) {
                    mPreviewDialog.dismiss();
                }
            }
        });

        mPreviewDialog.show();

        if (mCameraThread != null) {
            if (mCameraThread.isOpen()) {
                mManualPreviewRequested = true;
                mCameraOpenedForAutoOnly = false;
                mCameraThread.setPreviewSurface(mSurface);
                refreshPreviewUi();
            } else {
                mManualPreviewRequested = true;
                mCameraOpenedForAutoOnly = false;
                chooseCameraDevice();
            }
        }
    }

    private void updateRecordCircleUi() {
        boolean running = mJoyfulController != null && mJoyfulController.isSessionRunning();
        mRecordCircleButton.setText(running ? R.string.recording_btn_stop : R.string.recording_idle_prompt);
        mRecordCircleGlow.setBackgroundResource(running ? R.drawable.atlas_record_glow_halo_active : R.drawable.atlas_record_glow_halo);
        mRecordCircleButton.setBackgroundResource(running ? R.drawable.atlas_record_button_active : R.drawable.atlas_record_button_idle);
        mRecordingPrompt.setVisibility(running ? View.VISIBLE : View.GONE);
        mTxtRecordingPromptArt.setText(running ? R.string.recording_active_headline : R.string.recording_idle_headline);
    }

    /** Requirement 1.II: "今日数据" — laughter count / recorded duration / joyful moments, today only, live-updated. */
    private void refreshTodayStats() {
        if (mTxtStatLaughterCount == null) {
            return;
        }
        AtlasReviewRepository repository = new AtlasReviewRepository(this);
        AtlasReviewRepository.TodayStats stats = repository.computeTodayStats();
        mTxtStatLaughterCount.setText(String.valueOf(stats.laughterCount));
        mTxtStatDuration.setText(getString(R.string.today_stats_duration_value, stats.recordedMinutes));
        mTxtStatEventCount.setText(String.valueOf(stats.joyfulMomentCount));
        refreshRecentRecords(repository);
    }

    private void refreshRecentRecords(AtlasReviewRepository repository) {
        java.util.List<AtlasReviewRepository.EventSummary> events = repository.loadEventSummaries();
        mRecentRecordsContainer.removeAllViews();
        int shown = 0;
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < events.size() && shown < 3; i++) {
            final AtlasReviewRepository.EventSummary event = events.get(i);
            View card = inflater.inflate(R.layout.item_recent_record_card, mRecentRecordsContainer, false);
            TextView title = (TextView) card.findViewById(R.id.txtRecentTitle);
            TextView meta = (TextView) card.findViewById(R.id.txtRecentMeta);
            title.setText(!TextUtils.isEmpty(event.locationName) ? event.locationName : event.eventId);
            String laughterCountText =
                    event.laughterClipCount > 0
                            ? getString(
                            R.string.event_laughter_count,
                            event.laughterClipCount)
                            : getString(
                            R.string.event_laughter_count_empty);
            meta.setText(
                    event.timeRangeText
                            + "  ·  "
                            + laughterCountText);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(MainActivity.this, EventDetailActivity.class);
                    intent.putExtra("event_id", event.eventId);
                    intent.putExtra("session_id", event.sessionId);
                    startActivity(intent);
                }
            });
            mRecentRecordsContainer.addView(card);
            shown++;
        }
        mRecentRecordsContainer.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
        mRecentRecordsEmpty.setVisibility(shown > 0 ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onLastLaughterDetected(final long timestampMs) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                refreshTodayStats();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        AtlasDevLogger.i(this, TAG, "onResume joyful_running="
                + (mJoyfulController != null && mJoyfulController.isSessionRunning()));
    }

    @Override
    protected void onPause() {
        AtlasDevLogger.i(this, TAG, "onPause joyful_running="
                + (mJoyfulController != null && mJoyfulController.isSessionRunning()));
        super.onPause();
    }

    @Override
    protected void onStop() {
        AtlasDevLogger.i(this, TAG, "onStop joyful_running="
                + (mJoyfulController != null && mJoyfulController.isSessionRunning()));
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        AtlasDevLogger.i(this, TAG, "onDestroy joyful_running="
                + (mJoyfulController != null && mJoyfulController.isSessionRunning()));

        // 取消所有消息
        mHandler.removeCallbacksAndMessages(null);

        // 置空工具类句柄
        mFileUtil = null;

        // 关闭摄像头并清空资源
        if (mCameraThread != null) {
            mCameraThread.closeCamera(USBCameraThread.CLOSECAMERA_TYPE_NORMAL);
            mCameraThread.deleteDevice();
            try {
                mCameraThread.join();
            } catch (Exception ex) {

            }
            mCameraThread = null;
        }
        if (mJoyfulController != null) {
            mJoyfulController.stopSession("activity_destroyed");
        }
        AtlasForegroundService.stopKeepAlive(this);

        super.onDestroy();
    }

    USBCameraThreadState mUSBCameraThreadState = new USBCameraThreadState() {
        @Override
        public void onUSBCameraState(int state) {
            switch (state) {
                case USBCameraThread.ERR_NONE:
                    break;
                case USBCameraThread.ERR_CHOOSE_DEVICE:
                    postDeviceConnectionEvent(AtlasDeviceConnectionState.Event.NO_DEVICE);
                    mJoyfulAutoOpenInFlight = false;
                    clearPendingJoyfulAutoRequests("choose_device_failed");
                    mHandler.obtainMessage(MSG_SHOWMSG, "没有找到摄像头!").sendToTarget();
                    break;
                case USBCameraThread.ERR_OPEN_DEVICE:
                    postDeviceConnectionEvent(
                            AtlasDeviceConnectionState.Event.CAMERA_OPEN_FAILED);
                    mJoyfulAutoOpenInFlight = false;
                    clearPendingJoyfulAutoRequests("open_camera_failed");
                    mHandler.obtainMessage(MSG_SHOWMSG, "打开摄像头错误!").sendToTarget();
                    break;
                case USBCameraThread.ERR_START_PREVIEW:
                    postDeviceConnectionEvent(AtlasDeviceConnectionState.Event.PREVIEW_FAILED);
                    mJoyfulAutoOpenInFlight = false;
                    clearPendingJoyfulAutoRequests("start_preview_failed");
                    mHandler.obtainMessage(MSG_SHOWMSG, "摄像头开启预览错误!").sendToTarget();
                    break;
                case USBCameraThread.SUCCESS_READY:
                    mHandler.sendEmptyMessage(MSG_CREATE_DEVICE);
                    break;

                case USBCameraThread.SUCCESS_OPEN_CAMERA:
                    postDeviceConnectionEvent(AtlasDeviceConnectionState.Event.CAMERA_OPENED);
                    mHandler.sendEmptyMessage(MSG_CAMERA_OPENED);
                    break;
            }
        }
    };

    // Surface Holder　回调函数
    SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {

            Canvas canvas = holder.lockCanvas();
            canvas.drawColor(Color.WHITE);
            holder.unlockCanvasAndPost(canvas);

            mSurface = holder.getSurface();
            if (mCameraThread != null && mManualPreviewRequested) {
                mCameraThread.setPreviewSurface(mSurface);
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            updatePreviewSurfaceLayout(getDisplayPreviewWidth(), getDisplayPreviewHeight());
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (mCameraThread != null && mManualPreviewRequested) {
                mCameraThread.setPreviewSurface(null);
            }
            mSurface = null;
        }
    };

    // 界面按键事件处理
    View.OnClickListener mButtonClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            switch (v.getId()) {
                case R.id.thumbnailView:
                    if (mThumbnail != null) {
                        Util.viewUri(mThumbnail.getUri(), MainActivity.this);
                    }
                    break;

                case R.id.btnPreview:
                    if (mCameraThread == null)
                        return;
                    if (mCameraThread.isOpen()) {
                        if (mCameraOpenedForAutoOnly && !mManualPreviewRequested) {
                            mManualPreviewRequested = true;
                            mCameraOpenedForAutoOnly = false;
                            mCameraThread.setPreviewSurface(mSurface);
                            refreshPreviewUi();
                        } else {
                            if (m_bIsRecording) {
                                stopRecord();
                            }
                            mManualPreviewRequested = false;
                            mCameraOpenedForAutoOnly = false;
                            mJoyfulAutoOpenInFlight = false;
                            mCameraThread.closeCamera(USBCameraThread.CLOSECAMERA_TYPE_NORMAL);
                            refreshPreviewUi();
                        }
                    } else {
                        mManualPreviewRequested = true;
                        mCameraOpenedForAutoOnly = false;
                        chooseCameraDevice();
                    }
                    break;

                case R.id.btnCapture:
                    if (mCameraThread == null)
                        return;
                    if (!mCameraThread.isOpen())
                        return;
                    mCameraThread.stillCapture(mPictureCallback);
                    break;

                case R.id.btnRecord:
                    if (!m_bIsRecording) {
                        startRecord();
                    } else {
                        stopRecord();
                    }
                    break;

                case R.id.btnProperty:
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    break;

                case R.id.btnRecordCircle:
                    onRecordCircleClicked();
                    break;
            }
        }
    };

    View.OnLongClickListener mRecordCircleLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            if (mJoyfulController != null) {
                mJoyfulController.debugSimulateLaughterNow();
                Toast.makeText(MainActivity.this, "Joyful debug trigger sent", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        }
    };

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ///
    /// 消息处理
    ///

    private final static int MSG_SHOWMSG = 0x01;
    private final static int MSG_CREATE_DEVICE = 0x02;
    private final static int MSG_CAMERA_OPENED = 0x03;
    private final static int MSG_REUQEST_PERMISSION = 0x04;
    private final static int MSG_UPDATE_THUMBNAIL = 0x05;
    private final static int MSG_UPDATE_RECORD_TIME = 0x06;
    private final static int MSG_RECORD_PREPARED = 0x07;
    private final static int MSG_RECORD_STOPPED = 0x08;
    private final static int MSG_JOYFUL_AUTO_STOP_RECORD = 0x09;
    private final static int MSG_DEVICE_CONNECTION_EVENT = 0x0A;

    Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {

            switch (msg.what) {
                case MSG_SHOWMSG:
                    // 显示消息
                    Toast.makeText(MainActivity.this, msg.obj.toString(), Toast.LENGTH_SHORT).show();
                    break;

                case MSG_CREATE_DEVICE:
                    // 创建ＵＳＢ设备
                    if (mCameraThread != null) {
                        mCameraThread.createDevice();
                    }
                    break;

                case MSG_CAMERA_OPENED:
                    mJoyfulAutoOpenInFlight = false;
                    updatePreviewSurfaceLayout(getDisplayPreviewWidth(), getDisplayPreviewHeight());
                    refreshPreviewUi();
                    Toast.makeText(MainActivity.this,
                            mManualPreviewRequested ? "USB camera preview opened" : "USB camera ready for joyful auto capture",
                            Toast.LENGTH_SHORT).show();

                    // 初始化属性栏
                    if (mCameraThread != null && mSeekBar != null) {
                        int v = getSeekBarValue(mCameraThread.getBrightnessVal(),
                                mCameraThread.getBrightnessMin(),
                                mCameraThread.getBrightnessMax());
                        mSeekBar.setProgress(v);
                    }
                    applySavedCameraBrightness();
                    drainPendingJoyfulAutoActions();
                    break;

                case MSG_REUQEST_PERMISSION:
                    requestPermission();
                    break;

                case MSG_UPDATE_THUMBNAIL:

                    mPictureSaver.updateThumbnail();

                    if (mThumbnail != null) {
                        Uri uri = mThumbnail.getUri();
                        if (uri != null) {
                            Toast.makeText(MainActivity.this, "图片已保存", Toast.LENGTH_SHORT).show();
                        }
                    }
                    break;

                case MSG_UPDATE_RECORD_TIME:
                    updateRecordingTime();
                    break;
                case MSG_RECORD_PREPARED:
                    recordPrepared();
                    break;
                case MSG_RECORD_STOPPED:
                    recordStopped();
                    break;

                case MSG_JOYFUL_AUTO_STOP_RECORD:
                    if (mJoyfulAutoRecording) {
                        stopRecord();
                    }
                    break;
                case MSG_DEVICE_CONNECTION_EVENT:
                    applyDeviceConnectionEvent((AtlasDeviceConnectionState.Event) msg.obj);
                    break;

                default:
                    super.handleMessage(msg);
                    break;

            }
        }
    };

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ///
    /// 申请存储权限和录音权限
    ///
    private String[] permissions = new String[]{
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
    };

    private List<String> mNoPermissionList = new ArrayList<>();

    private final int REQUEST_PERMISSION = 0x100;
    private final int REQUEST_REMINDER_LOCATION = 0x101;
    private final int REQUEST_REMINDER_BACKGROUND_LOCATION = 0x102;
    private final int REQUEST_REMINDER_NOTIFICATIONS = 0x103;

    private boolean mHasStorageAndAudioPermission = false;

    private void requestPermission() {
        mHasStorageAndAudioPermission = false;
        mNoPermissionList.clear();

        for (int i = 0; i < permissions.length; i++) {
            if (ContextCompat.checkSelfPermission(this, permissions[i]) != PackageManager.PERMISSION_GRANTED) {
                mNoPermissionList.add(permissions[i]);
            }
        }

        if (mNoPermissionList.size() > 0) {
            ActivityCompat.requestPermissions(
                    this,
                    mNoPermissionList.toArray(new String[mNoPermissionList.size()]),
                    REQUEST_PERMISSION);
        } else {
            afterGetStorageAndAudioPermissions();
            requestInitialReminderPermissions();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSION) {
            boolean permissionDenied = false;
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    permissionDenied = true;
                    break;
                }
            }
            if (permissionDenied) {
                showSystemPermissionDialog();
            } else {
                afterGetStorageAndAudioPermissions();
                requestInitialReminderPermissions();
            }
            return;
        }
        if (requestCode == REQUEST_REMINDER_LOCATION) {
            if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    && Build.VERSION.SDK_INT >= 29
                    && !hasPermission("android.permission.ACCESS_BACKGROUND_LOCATION")) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{"android.permission.ACCESS_BACKGROUND_LOCATION"},
                        REQUEST_REMINDER_BACKGROUND_LOCATION);
            } else {
                requestInitialNotificationPermission();
            }
            AtlasResurfacingManager.refreshLocationsAsync(this);
        } else if (requestCode == REQUEST_REMINDER_BACKGROUND_LOCATION) {
            requestInitialNotificationPermission();
            AtlasResurfacingManager.refreshLocationsAsync(this);
        }
    }

    private void requestInitialReminderPermissions() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_REMINDER_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= 29
                && !hasPermission("android.permission.ACCESS_BACKGROUND_LOCATION")) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{"android.permission.ACCESS_BACKGROUND_LOCATION"},
                    REQUEST_REMINDER_BACKGROUND_LOCATION);
            return;
        }
        requestInitialNotificationPermission();
        AtlasResurfacingManager.refreshLocationsAsync(this);
    }

    private void requestInitialNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && !hasPermission("android.permission.POST_NOTIFICATIONS")) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQUEST_REMINDER_NOTIFICATIONS);
        }
    }

    /// 跳转到系统权限界面
    AlertDialog mSystemPermissionDialog;
    private void showSystemPermissionDialog() {
        if (mSystemPermissionDialog == null) {
            mSystemPermissionDialog = new AlertDialog.Builder(this)
                    .setMessage("已禁用权限，请手动授予")
                    .setPositiveButton("设置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            closeSystemPermissionDialog();   // 关闭权限提示窗口
                            finish();    // 退出程序

                            Uri packageURI = Uri.parse("package:" + getPackageName());
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageURI);
                            startActivity(intent);
                        }
                    })
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            closeSystemPermissionDialog();
                            finish();
                        }
                    })
                    .create();
        }
        mSystemPermissionDialog.show();
    }

    // 关闭系统权限窗口
    private void closeSystemPermissionDialog() {
        if (mSystemPermissionDialog != null) {
            mSystemPermissionDialog.cancel();
            mSystemPermissionDialog = null;
        }
    }

    // 获得权限后进行相应处理，比如有的应用需要直接打开摄像头
    private void afterGetStorageAndAudioPermissions() {
        mHasStorageAndAudioPermission = true;

    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasJoyfulCameraPermissions() {
        return hasPermission(Manifest.permission.CAMERA)
                && hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private void chooseCameraDevice() {
        if (mCameraThread == null) {
            return;
        }
        postDeviceConnectionEvent(AtlasDeviceConnectionState.Event.CONNECT_REQUESTED);
        mCameraThread.chooseDevice();
    }

    private void postDeviceConnectionEvent(AtlasDeviceConnectionState.Event event) {
        mHandler.obtainMessage(MSG_DEVICE_CONNECTION_EVENT, event).sendToTarget();
    }

    private void applyDeviceConnectionEvent(AtlasDeviceConnectionState.Event event) {
        mDeviceConnectionState = AtlasDeviceConnectionState.transition(
                mDeviceConnectionState, event);
        renderDeviceConnectionState();
    }

    private void renderDeviceConnectionState() {
        if (mTxtDeviceStatus == null || mDeviceStatusDot == null) {
            return;
        }
        switch (mDeviceConnectionState) {
            case CONNECTED:
                mTxtDeviceStatus.setText(R.string.device_connected);
                mDeviceStatusDot.setBackgroundResource(
                        R.drawable.atlas_device_dot_connected);
                break;
            case CONNECTING:
                mTxtDeviceStatus.setText(R.string.device_connecting);
                mDeviceStatusDot.setBackgroundResource(
                        R.drawable.atlas_device_dot_connecting);
                break;
            case DISCONNECTED:
            default:
                mTxtDeviceStatus.setText(R.string.device_disconnected);
                mDeviceStatusDot.setBackgroundResource(
                        R.drawable.atlas_device_dot_disconnected);
                break;
        }
    }

    private void refreshPreviewUi() {
        boolean showPreview = mManualPreviewRequested && mCameraThread != null && mCameraThread.isOpen();
        updatePreviewSurfaceLayout(getDisplayPreviewWidth(), getDisplayPreviewHeight());
        if (mPreviewBtn != null) {
            mPreviewBtn.setText(showPreview ? R.string.btn_preview_stop : R.string.btn_preview_start);
        }
        if (mPreviewTip != null) {
            mPreviewTip.setVisibility(showPreview ? View.GONE : View.VISIBLE);
            if (!showPreview) {
                mPreviewTip.setText(R.string.preview_tip);
            }
        }
    }

    private int getDisplayPreviewWidth() {
        return mCameraThread != null && mCameraThread.isOpen() ? mCameraThread.getNewVideoW() : VIDEO_H;
    }

    private int getDisplayPreviewHeight() {
        return mCameraThread != null && mCameraThread.isOpen() ? mCameraThread.getNewVideoH() : VIDEO_W;
    }

    private void updatePreviewSurfaceLayout(int sourceWidth, int sourceHeight) {
        if (mSurfaceView == null) {
            return;
        }
        View parent = (View) mSurfaceView.getParent();
        if (parent == null || parent.getWidth() <= 0 || parent.getHeight() <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }
        int availableWidth = Math.max(1, parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight());
        int availableHeight = Math.max(1, parent.getHeight() - parent.getPaddingTop() - parent.getPaddingBottom());
        float sourceAspect = (float) sourceWidth / (float) sourceHeight;
        int targetWidth = availableWidth;
        int targetHeight = Math.round(targetWidth / sourceAspect);
        if (targetHeight > availableHeight) {
            targetHeight = availableHeight;
            targetWidth = Math.round(targetHeight * sourceAspect);
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mSurfaceView.getLayoutParams();
        if (params.width != targetWidth || params.height != targetHeight) {
            params.width = targetWidth;
            params.height = targetHeight;
            params.gravity = android.view.Gravity.CENTER;
            mSurfaceView.setLayoutParams(params);
        }
        if (mSurfaceHolder != null) {
            mSurfaceHolder.setFixedSize(sourceWidth, sourceHeight);
        }
    }

    private void applySavedCameraBrightness() {
        if (mCameraThread == null || !mCameraThread.isOpen()) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(JoyfulMomentConfig.PREF_NAME, MODE_PRIVATE);
        int percent = prefs.getInt(JoyfulMomentConfig.PREF_CAMERA_BRIGHTNESS, 50);
        percent = Math.max(0, Math.min(100, percent));
        int value = getPropertyValue(percent, mCameraThread.getBrightnessMin(), mCameraThread.getBrightnessMax());
        mCameraThread.setBrightnessVal(value);
        if (mSeekBar != null) {
            mSeekBar.setProgress(percent);
        }
        if (mSeekBarText != null) {
            mSeekBarText.setText(String.valueOf(value));
        }
    }

    private void clearPendingJoyfulAutoRequests(String reason) {
        for (AtlasCaptureBundleRequest request
                : mJoyfulAutoCaptureQueue.drainAllVideos()) {
            if (mJoyfulController != null) {
                mJoyfulController.onAutoVideoCaptureSkipped(
                        request,
                        reason);
            }
        }
        for (AtlasCaptureBundleRequest.PhotoRequest request
                : mJoyfulAutoCaptureQueue.drainAllPhotos()) {
            if (mJoyfulController != null) {
                mJoyfulController.onAutoPhotoCaptureSkipped(
                        request,
                        reason);
            }
        }
        mActiveJoyfulAutoVideoCaptureTimeMs = 0L;
    }

    private void ensureCameraReadyForJoyfulAuto() {
        if (mCameraThread == null) {
            clearPendingJoyfulAutoRequests("camera_thread_missing");
            return;
        }
        if (mCameraThread.isOpen()) {
            drainPendingJoyfulAutoActions();
            return;
        }
        if (mJoyfulAutoOpenInFlight) {
            return;
        }
        mCameraOpenedForAutoOnly = !mManualPreviewRequested;
        mJoyfulAutoOpenInFlight = true;
        chooseCameraDevice();
    }

    private void drainPendingJoyfulAutoActions() {
        if (mCameraThread == null || !mCameraThread.isOpen()) {
            return;
        }

        if (mJoyfulAutoCaptureQueue.pendingVideoCount() > 0
                && !m_bIsRecording) {
            AtlasCaptureBundleRequest activeVideo =
                    mJoyfulAutoCaptureQueue.activateNextVideo();
            int durationSec = activeVideo.videoDurationSec;
            mJoyfulAutoRecording = true;
            mActiveJoyfulAutoVideoCaptureTimeMs = System.currentTimeMillis();
            if (mJoyfulController != null) {
                mJoyfulController.onAutoVideoCaptureStarted(
                        activeVideo,
                        mActiveJoyfulAutoVideoCaptureTimeMs);
            }
            Toast.makeText(MainActivity.this, "Joyful auto video started", Toast.LENGTH_SHORT).show();
            startRecord();
            if (mVideoEncoder != null) {
                mHandler.removeMessages(MSG_JOYFUL_AUTO_STOP_RECORD);
                mHandler.sendEmptyMessageDelayed(MSG_JOYFUL_AUTO_STOP_RECORD, durationSec * 1000L);
            } else {
                mJoyfulAutoRecording = false;
                AtlasCaptureBundleRequest failedVideo =
                        mJoyfulAutoCaptureQueue
                                .completeActiveVideo();
                mActiveJoyfulAutoVideoCaptureTimeMs = 0L;
                if (mJoyfulController != null) {
                    mJoyfulController.onAutoVideoCaptureSkipped(
                            failedVideo,
                            "record_start_failed");
                }
            }
        }

        while (mJoyfulAutoCaptureQueue.queuedPhotoCount() > 0
                && mCameraThread.isOpen()) {
            mJoyfulAutoCaptureQueue.dispatchNextPhoto();
            mCameraThread.stillCapture(mPictureCallback);
        }
        maybeSleepJoyfulAutoCamera();
    }

    private void maybeSleepJoyfulAutoCamera() {
        if (mManualPreviewRequested || !mCameraOpenedForAutoOnly || mJoyfulAutoOpenInFlight) {
            return;
        }
        if (mCameraThread == null || !mCameraThread.isOpen()) {
            return;
        }
        if (m_bIsRecording || mJoyfulAutoRecording) {
            return;
        }
        if (mJoyfulAutoCaptureQueue.hasWork()) {
            return;
        }
        mCameraOpenedForAutoOnly = false;
        mCameraThread.closeCamera(USBCameraThread.CLOSECAMERA_TYPE_NORMAL);
        refreshPreviewUi();
    }


    ///
    /// 摄像头状态事件处理
    ///
    USBCameraAPI.StateChangeListener mStateChangeListener = new USBCameraAPI.StateChangeListener() {
        @Override
        public void OnStateChange(int state) {
            switch (state) {
                case USBCameraAPI.STATE_DEVICE_ARRIVAL:
                    Log.e(TAG, "device arrival");
                    postDeviceConnectionEvent(
                            AtlasDeviceConnectionState.Event.USB_ARRIVED);
                    break;

                case USBCameraAPI.STATE_DEVICE_REMOVAL:
                    Log.e(TAG, "device removal");
                    postDeviceConnectionEvent(
                            AtlasDeviceConnectionState.Event.USB_REMOVED);
                    mJoyfulAutoOpenInFlight = false;
                    mCameraOpenedForAutoOnly = false;

                    // 关闭录像
                    if (m_bIsRecording) {
                        stopRecord();
                    }

                    // 关闭摄像头
                    if (mCameraThread != null) {
                        mCameraThread.closeCamera(USBCameraThread.CLOSECAMERA_TYPE_HOTPLUG);
                    }
                    refreshPreviewUi();
                    break;

                case USBCameraAPI.STATE_NO_PERMISSION:
                    postDeviceConnectionEvent(
                            AtlasDeviceConnectionState.Event.USB_PERMISSION_DENIED);
                    mJoyfulAutoOpenInFlight = false;
                    clearPendingJoyfulAutoRequests("usb_permission_denied");
                    mHandler.obtainMessage(MSG_SHOWMSG, "USB device permission denied").sendToTarget();
                    break;

                case USBCameraAPI.STATE_HAS_PERMISSION:
                    postDeviceConnectionEvent(
                            AtlasDeviceConnectionState.Event.USB_PERMISSION_GRANTED);
                    if (mCameraThread!=null) {
                        mCameraThread.openCamera(VIDEO_W, VIDEO_H, VIDEO_FORMAT,
                                VIDEO_ORIENTATION, VIDEO_MINFPS, VIDEO_MAXFPS, VIDEO_BANDWIDTH,
                                mManualPreviewRequested ? mSurface : null);
                    }
                    break;
            }
        }
    };

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ///
    /// 视频录制
    ///
    boolean m_bIsRecording = false;
    boolean mUpdateRecordingTimeFlag = false;

    private long mRecordingStartTime;

    private long mMediaVideoDateTaken;
    private String mMediaVideoTitle;
    private String mMediaVideoPath;

    private MediaMuxerWrapper mMuxer;
    private MediaVideoBufferEncoder mVideoEncoder;
    private JoyfulExternalPcmAudioEncoder mJoyfulAudioEncoder;

    private int mRecordW;
    private int mRecordH;

    // 开始录制
    private void startRecord()
    {
        if (m_bIsRecording)
            return; // 已经是录像状态
        if (mCameraThread == null)
            return;
        if (!mCameraThread.isOpen())
            return;

        // 视频文件路径及名称
        mMediaVideoDateTaken = System.currentTimeMillis();
        mMediaVideoTitle = mFileUtil.createMp4Name(mMediaVideoDateTaken);
        mMediaVideoPath = mFileUtil.generateMp4Path(mMediaVideoTitle);
        mLastRecordWasJoyfulAuto = mJoyfulAutoRecording;

        mRecordW = mCameraThread.getNewVideoW();
        mRecordH = mCameraThread.getNewVideoH();

        // 创建视频混合器
        try {
            mMuxer = new MediaMuxerWrapper(mMediaVideoPath);
            mVideoEncoder = new MediaVideoBufferEncoder(mMuxer, mRecordW, mRecordH, mMediaEncoderListener);
            mJoyfulAudioEncoder = new JoyfulExternalPcmAudioEncoder(
                    mMuxer,
                    mMediaEncoderListener,
                    JOYFUL_AUDIO_SAMPLE_RATE,
                    JOYFUL_AUDIO_CHANNEL_COUNT,
                    JOYFUL_AUDIO_BIT_RATE
            );
            mMuxer.prepare();
            mMuxer.startRecording();
        } catch (final IOException e) {
            Log.e(TAG, "startRecording error, ", e);
            mJoyfulAudioEncoder = null;
            return;
        }

        mCameraThread.setPreviewCallback(mPreviewCallback);
    }

    // 录制已经就绪
    private void recordPrepared() {
        mUpdateRecordingTimeFlag = true;
        mRecordingStartTime = SystemClock.uptimeMillis();
        showRecordingUI(true);
        updateRecordingTime();

        m_bIsRecording = true;
        this.mRecordBtn.setText("停止录像");
    }

    // 停止录像
    private void stopRecord()
    {
        mHandler.removeMessages(MSG_JOYFUL_AUTO_STOP_RECORD);
        mJoyfulAudioEncoder = null;
        if (mMuxer != null) {
            mMuxer.stopRecording();
            mMuxer = null;
        }
        mVideoEncoder = null;
        mJoyfulAutoRecording = false;
    }

    // 录制已经停止
    private void recordStopped() {
        if (!m_bIsRecording)
            return;

        if (mCameraThread != null) {
            mCameraThread.setPreviewCallback(null);
        }

        try {
            // 隐藏录制提示窗口
            mHandler.removeMessages(MSG_UPDATE_RECORD_TIME);
            showRecordingUI(false);

            Uri uri = null;
              AtlasCaptureBundleRequest activeVideo =
                      mJoyfulAutoCaptureQueue.activeVideo();
              if (!TextUtils.isEmpty(mMediaVideoPath)) {
                  // 保存到媒体库
                 uri = mFileUtil.insertVideoToMediaStore(mContentResolver, mMediaVideoTitle, mMediaVideoDateTaken, mMediaVideoPath,
                          new File(mMediaVideoPath).length(), mRecordW, mRecordH);
                  if (uri != null) {
                    mFileUtil.broadcastNewVideo(MainActivity.this, uri);
                }

                // 更新缩略图
                updateVideoThumbnail(uri);

                  // 显示提示消息
                  Toast.makeText(MainActivity.this, "视频已完成录制!", Toast.LENGTH_SHORT).show();
                  if (mLastRecordWasJoyfulAuto && mJoyfulController != null) {
                      mJoyfulController.onAutoVideoSaved(
                              activeVideo,
                              mMediaVideoPath,
                              uri != null ? uri.toString() : null,
                              mActiveJoyfulAutoVideoCaptureTimeMs);
                      Toast.makeText(MainActivity.this, "Joyful auto video saved", Toast.LENGTH_SHORT).show();
                  }
              } else if (mLastRecordWasJoyfulAuto
                      && mJoyfulController != null) {
                  mJoyfulController.onAutoVideoSaved(
                          activeVideo,
                          null,
                          null,
                          mActiveJoyfulAutoVideoCaptureTimeMs);
              }

            // 设置停止标记
              m_bIsRecording = false;
              mJoyfulAutoRecording = false;
              mLastRecordWasJoyfulAuto = false;
              mJoyfulAutoCaptureQueue.completeActiveVideo();
              mActiveJoyfulAutoVideoCaptureTimeMs = 0L;

            this.mRecordBtn.setText("录像");
            drainPendingJoyfulAutoActions();
            maybeSleepJoyfulAutoCamera();
        } catch (final Exception e) {

        }
    }

    // 编码回调函数
    private final MediaEncoder.MediaEncoderListener mMediaEncoderListener = new MediaEncoder.MediaEncoderListener() {
        @Override
        public void onPrepared(final MediaEncoder encoder) {
            mHandler.removeMessages(MSG_RECORD_PREPARED);
            mHandler.sendEmptyMessage(MSG_RECORD_PREPARED);
        }

        @Override
        public void onStopped(final MediaEncoder encoder) {
            mHandler.removeMessages(MSG_RECORD_STOPPED);
            mHandler.sendEmptyMessage(MSG_RECORD_STOPPED);
        }
    };

    //　显示或隐藏正在录像窗口
    private void showRecordingUI(boolean recording)
    {
        if (recording) {
            mRecordingLayout.setVisibility(View.VISIBLE);
        } else {
            mRecordingLayout.setVisibility(View.GONE);
            mRecordingText.setText("");
        }
    }

    // 更新录制时间
    private void updateRecordingTime() {

        long targetNextUpdateDelay = 1000;

        if (m_bIsRecording) {
            long now = SystemClock.uptimeMillis();
            long deltaAdjusted = now - mRecordingStartTime;

            String text = Util.millisecondToTimeString(deltaAdjusted, false);
            mRecordingText.setText(text);
            if (mUpdateRecordingTimeFlag)
                mRecordingImage.setVisibility(View.VISIBLE);
            else
                mRecordingImage.setVisibility(View.INVISIBLE);
            mUpdateRecordingTimeFlag = !mUpdateRecordingTimeFlag;
        }

        mHandler.sendEmptyMessageDelayed(
                MSG_UPDATE_RECORD_TIME, targetNextUpdateDelay);

    }

    // 创建视频缩略图
    private void updateVideoThumbnail(Uri uri) {
        Bitmap videoFrame = Thumbnail.createVideoThumbnail(mMediaVideoPath, DEFAULT_THUMBNAIL_WIDTH);
        if (videoFrame != null) {
            // 点击时需要从mThumbnail获取图像，所以这边要设置图像
            mThumbnail = new Thumbnail(uri, videoFrame, 0);

            // 显示缩略图
            Bitmap bmp = mFileUtil.createSqureBitmap(mThumbnail.getBitmap());
            mThumbnailView.setImageBitmap(bmp);
        }
    }

    // 视频回调
    private final IPreviewCallback mPreviewCallback = new IPreviewCallback() {
        @Override
        public void onPreviewFrame(final ByteBuffer frame) {
            if (m_bIsRecording) {
                if (mVideoEncoder != null) {
                    // 编码视频帧
                    mVideoEncoder.frameAvailableSoon();
                    mVideoEncoder.encode(frame);
                }
            }
        }
    };

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ///
    /// 拍照
    ///

    PictureSaver mPictureSaver = new PictureSaver();

    // 拍照视频帧回调函数
    IPictureCallback mPictureCallback = new IPictureCallback() {
        @Override
        public void onPictureFrame(int[] pbuf, int w, int h) {

            // 只存储一张图，所以置空
            if (mCameraThread != null) {
                mCameraThread.setPictureCallback(null);
            }

            // 保存图片数据
            mPictureSaver.addImage(pbuf, w, h);
        }
    };

    // 图片数据类
    private static class PictureData {
        int[] data;
        int width;
        int height;
        long dateTaken;
    }

    // 图像保存线程类
    private class PictureSaver extends Thread {

        private static final int QUEUE_LIMIT = 3;   // 图片存储缓冲区大小

        private ArrayList<PictureData> mQueue;
        private Bitmap mPendingBitmap;
        private Object mUpdateThumbnailLock = new Object();
        private boolean mStop;

        // 构造函数，在主线程调用
        public PictureSaver() {
            mQueue = new ArrayList<PictureData>();
            start();
        }

        // 添加图像数据，在主线程调用
        public void addImage(final int[] data, int width, int height) {
            PictureData r = new PictureData();
            r.data = data;
            r.width = width;
            r.height = height;
            r.dateTaken = System.currentTimeMillis();

            synchronized (this) {
                while (mQueue.size() >= QUEUE_LIMIT) {
                    try {
                        wait();
                    } catch (InterruptedException ex) {
                        // ignore.
                    }
                }
                mQueue.add(r);
                notifyAll();  // 通知 PictureSaver 线程，有新数据到来
            }
        }

        // PictureSaver 线程调用
        @Override
        public void run() {
            while (true) {
                PictureData r;
                synchronized (this) {
                    if (mQueue.isEmpty()) {
                        notifyAll();  // 通知主线程，可以添加数据

                        // 退出保存线程
                        if (mStop) break;

                        try {
                            wait();
                        } catch (InterruptedException ex) {
                            // ignore.
                        }
                        continue;
                    }
                    r = mQueue.get(0);
                }
                storeImage(r.data, r.width, r.height, r.dateTaken);
                synchronized(this) {
                    mQueue.remove(0);
                    notifyAll();  // 通知主线程，有空了
                }
            }
        }

        // 主线程调用，等待队列空；队列空后刷新缩略图
        public void waitDone() {
            synchronized (this) {
                while (!mQueue.isEmpty()) {
                    try {
                        wait();
                    } catch (InterruptedException ex) {
                        // ignore.
                    }
                }
            }
            // 刷新缩略图
            updateThumbnail();
        }

        // 主线程调用，退出保存线程
        public void finish() {
            waitDone();
            synchronized (this) {
                mStop = true;
                notifyAll();
            }
            try {
                join();
            } catch (InterruptedException ex) {
                // ignore.
            }
        }

        // 主线程调用，刷新缩略图
        public void updateThumbnail() {
            Bitmap bmp;
            synchronized (mUpdateThumbnailLock) {
                mHandler.removeMessages(MSG_UPDATE_THUMBNAIL);
                bmp = mPendingBitmap;
                mPendingBitmap = null;
            }

            if (bmp != null) {
                mPendingBitmap = bmp;
                mThumbnailView.setImageBitmap(mPendingBitmap);
            }

        }

        // PictureSaver 线程调用
        private void storeImage(final int[] data, int w, int h, long dateTaken) {

            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(data, 0, w, 0, 0, w, h);
            final AtlasCaptureBundleRequest.PhotoRequest
                    joyfulAutoPhotoRequest =
                    consumePendingJoyfulAutoPhoto();

            String title = mFileUtil.createJpgName(dateTaken);
            String path = mFileUtil.generateJpgPath(title);
            if (!mFileUtil.writeBitmap(bitmap, path)) {
                if (joyfulAutoPhotoRequest != null
                        && mJoyfulController != null) {
                    mJoyfulController.onAutoPhotoSaved(
                            joyfulAutoPhotoRequest,
                            null,
                            dateTaken);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        maybeSleepJoyfulAutoCamera();
                    }
                });
                return;
            }

            Uri uri = mFileUtil.insertImageToMediaStore(mContentResolver, title, dateTaken, path, bitmap.getByteCount(), w, h);
            if (joyfulAutoPhotoRequest != null
                    && mJoyfulController != null) {
                mJoyfulController.onAutoPhotoSaved(
                        joyfulAutoPhotoRequest,
                        path,
                        dateTaken);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "Joyful auto photo saved", Toast.LENGTH_SHORT).show();
                        maybeSleepJoyfulAutoCamera();
                    }
                });
            }
            if (uri != null) {

                mFileUtil.broadcastNewImage(MainActivity.this, uri);

                boolean needThumbnail;
                synchronized (this) {
                    // If the number of requests in the queue (include the
                    // current one) is greater than 1, we don't need to generate
                    // thumbnail for this image. Because we'll soon replace it
                    // with the thumbnail for some image later in the queue.
                    needThumbnail = (mQueue.size() <= 1);
                }

                if (needThumbnail) {
                    // Create a thumbnail whose width is equal or bigger than
                    // that of the preview.
                    int inSampleSize = mFileUtil.calcImSampleSize(w, DEFAULT_THUMBNAIL_WIDTH);
                    Bitmap thumbnail1 = mFileUtil.createThumbnailBitmap(path, inSampleSize);
                    Bitmap thumbnail2 = mFileUtil.createSqureBitmap(thumbnail1);

                    mThumbnail = new Thumbnail(uri, thumbnail2, 0);

                    synchronized (mUpdateThumbnailLock) {
                        // We need to update the thumbnail in the main thread,
                        // so send a message to run updateThumbnail().
                        mPendingBitmap = thumbnail2;
                        mHandler.sendEmptyMessage(MSG_UPDATE_THUMBNAIL);


                    }
                }

            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ///
    /// 硬件按键处理事件
    ///
    private final IKeyCallback mKeyCallback = new IKeyCallback() {
         @Override
        public void onKeyPressed() {
             // 有需要可以进行按键拍照之类的操作
        }
    };

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ///
    /// 属性调整处理事件
    ///
    int getSeekBarValue(int propertyVal, int min, int max) {
        return (propertyVal-min) * 100 / (max - min);
    }

    int getPropertyValue(int seekbarVal, int min, int max) {
        return seekbarVal * (max - min) / 100 + min;
    }

    SeekBar.OnSeekBarChangeListener mOnSeekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (mCameraThread != null) {
                int v = getPropertyValue(progress, mCameraThread.getBrightnessMin(), mCameraThread.getBrightnessMax());
                mCameraThread.setBrightnessVal(v);
                mSeekBarText.setText(String.valueOf(v));
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {

        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {

        }
    };

    private void toggleJoyfulSession() {
        if (mJoyfulController == null) {
            return;
        }
        if (mJoyfulController.isSessionRunning()) {
            String endedSessionId = mJoyfulController.getSessionId();
            mJoyfulController.stopSession("user");
            AtlasForegroundService.stopKeepAlive(this);
            AtlasDevLogger.session(this, TAG, "Joyful session stopped by user");
            Toast.makeText(this, "Joyful session stopped", Toast.LENGTH_SHORT).show();
            // Requirement 2: after a manual stop, offer to add notes to any laughter moment
            // captured this session (picker lets the user skip without going through each one).
            if (endedSessionId != null && endedSessionId.length() > 0) {
                Intent intent = new Intent(this, SupplementPickerActivity.class);
                intent.putExtra("session_id", endedSessionId);
                ResearchNavigation.withSource(intent, "session_stop");
                startActivity(intent);
            }
        } else {
            showJoyfulParticipantDialog();
        }
        updateRecordCircleUi();
    }

    private void showJoyfulParticipantDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("00");
        SharedPreferences prefs = getSharedPreferences(JoyfulMomentConfig.PREF_NAME, MODE_PRIVATE);
        input.setText(prefs.getString("joyful_participant_number", "00"));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("what's your number?")
                .setView(input)
                .setPositiveButton("Start", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String participantNumber = normalizeParticipantNumber(input.getText().toString());
                        getSharedPreferences(JoyfulMomentConfig.PREF_NAME, MODE_PRIVATE)
                                .edit()
                                .putString("joyful_participant_number", participantNumber)
                                .apply();
                        startJoyfulSession(participantNumber);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startJoyfulSession(String participantNumber) {
        AtlasForegroundService.startKeepAlive(this);
        requestIgnoreBatteryOptimizationsIfNeeded();
        mJoyfulController.startSession(participantNumber);
        AtlasDevLogger.session(this, TAG, "Joyful session started by user; participant=" + participantNumber + "; keepalive foreground service requested");
        Toast.makeText(this, "Joyful session started: " + participantNumber, Toast.LENGTH_SHORT).show();
        updateRecordCircleUi();
    }

    private String normalizeParticipantNumber(String raw) {
        String value = raw == null ? "" : raw.trim();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            }
        }
        if (digits.length() == 0) {
            return "00";
        }
        if (digits.length() == 1) {
            return "0" + digits.toString();
        }
        return digits.toString();
    }

    private void requestIgnoreBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                AtlasDevLogger.i(this, TAG, "requested ignore battery optimizations");
            }
        } catch (Exception e) {
            AtlasDevLogger.e(this, TAG, "request ignore battery optimizations failed", e);
        }
    }

    private void showJoyfulConfigDialog() {
        if (mJoyfulController == null) {
            return;
        }
        final JoyfulMomentConfig current = mJoyfulController.getConfig();
        final String[] levels = new String[] {"frequent", "medium", "sparse", "custom"};
        new AlertDialog.Builder(this)
                .setTitle("Joyful detection level")
                .setItems(levels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String level = levels[which];
                        if ("custom".equals(level)) {
                            showJoyfulCustomConfigDialog(current);
                            return;
                        }
                        mJoyfulController.updateConfig(JoyfulMomentConfig.preset(level));
                        Toast.makeText(MainActivity.this, "Switched to " + level, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showJoyfulCustomConfigDialog(final JoyfulMomentConfig current) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        final EditText clipInput = new EditText(this);
        clipInput.setHint("clipDurationSec");
        clipInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        clipInput.setText(String.valueOf(current.clipDurationSec));
        layout.addView(clipInput);

        final EditText neighborInput = new EditText(this);
        neighborInput.setHint("contextNeighborClips");
        neighborInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        neighborInput.setText(String.valueOf(current.contextNeighborClips));
        layout.addView(neighborInput);

        final EditText eventInput = new EditText(this);
        eventInput.setHint("eventWindowSec");
        eventInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        eventInput.setText(String.valueOf(current.eventWindowSec));
        layout.addView(eventInput);

        new AlertDialog.Builder(this)
                .setTitle("Custom Joyful config")
                .setView(layout)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        JoyfulMomentConfig config = new JoyfulMomentConfig();
                        config.detectionLevel = "custom";
                        config.clipDurationSec = parsePositiveInt(clipInput.getText().toString(), current.clipDurationSec);
                        config.contextNeighborClips = parsePositiveInt(neighborInput.getText().toString(), current.contextNeighborClips);
                        config.eventWindowSec = parsePositiveInt(eventInput.getText().toString(), current.eventWindowSec);
                        config.chunkMs = current.chunkMs;
                        config.laughterConfidenceThresholdPct = current.laughterConfidenceThresholdPct;
                        config.laughterMinDurationMs = current.laughterMinDurationMs;
                        config.triggerVideoDurationSec = current.triggerVideoDurationSec;
                        config.triggerPhotoCount = current.triggerPhotoCount;
                        config.speechmaticsLanguage = current.speechmaticsLanguage;
                        config.speechmaticsEventTypes = current.speechmaticsEventTypes;
                        mJoyfulController.updateConfig(config);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int parsePositiveInt(String text, int fallback) {
        try {
            int parsed = Integer.parseInt(text);
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Override
    public void onJoyfulStatusChanged(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AtlasDevLogger.i(MainActivity.this, TAG, "joyful status: " + text);
                updateRecordCircleUi();
            }
        });
    }

    @Override
    public void onJoyfulPromptRequested(final String periodId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("joyful moment detected")
                        .setMessage("prompt : joyful moment detected ! would you like to note something for this special moment ? ^_^")
                        .setPositiveButton("Y", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                showJoyfulNoteChoiceDialog(periodId);
                            }
                        })
                        .setNegativeButton("N", null)
                        .show();
            }
        });
    }

    private void showJoyfulNoteChoiceDialog(final String periodId) {
        String eventId = mJoyfulController != null ? mJoyfulController.getLastTriggeredEventId() : null;
        Intent intent;
        if (eventId != null && eventId.length() > 0) {
            intent = new Intent(this, EventDetailActivity.class);
            intent.putExtra("event_id", eventId);
        } else {
            intent = new Intent(this, ReviewShellActivity.class);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    @Override
    public void onJoyfulAutoVideoRequested(
            final AtlasCaptureBundleRequest request) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!hasJoyfulCameraPermissions()) {
                    if (mJoyfulController != null) {
                        mJoyfulController.onAutoVideoCaptureSkipped(
                                request,
                                "camera_permission_missing");
                    }
                    Toast.makeText(MainActivity.this, "Joyful auto video skipped: camera permission missing", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (m_bIsRecording && !mJoyfulAutoRecording) {
                    if (mJoyfulController != null) {
                        mJoyfulController.onAutoVideoCaptureSkipped(
                                request,
                                "recording_busy");
                    }
                    return;
                }
                mJoyfulAutoCaptureQueue.enqueueVideo(request);
                ensureCameraReadyForJoyfulAuto();
            }
        });
    }

    @Override
    public void onJoyfulAutoPhotoRequested(
            final AtlasCaptureBundleRequest.PhotoRequest request) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!hasJoyfulCameraPermissions()) {
                    if (mJoyfulController != null) {
                        mJoyfulController.onAutoPhotoCaptureSkipped(
                                request,
                                "camera_permission_missing");
                    }
                    Toast.makeText(MainActivity.this, "Joyful auto photo skipped: camera permission missing", Toast.LENGTH_SHORT).show();
                    return;
                }
                mJoyfulAutoCaptureQueue.enqueuePhoto(request);
                ensureCameraReadyForJoyfulAuto();
            }
        });
    }

    @Override
    public void onJoyfulUsbAudioChunk(byte[] pcm16le, int byteLen, int sampleRate, int channelCount) {
        JoyfulExternalPcmAudioEncoder encoder = mJoyfulAudioEncoder;
        if (encoder == null || pcm16le == null || byteLen <= 0) {
            return;
        }
        if (sampleRate != JOYFUL_AUDIO_SAMPLE_RATE || channelCount != JOYFUL_AUDIO_CHANNEL_COUNT) {
            return;
        }
        encoder.offerPcm(pcm16le, byteLen);
    }

    private synchronized AtlasCaptureBundleRequest.PhotoRequest
            consumePendingJoyfulAutoPhoto() {
        return mJoyfulAutoCaptureQueue.completeNextPhoto();
    }


}
