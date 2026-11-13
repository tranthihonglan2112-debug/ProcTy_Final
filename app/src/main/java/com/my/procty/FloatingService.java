package com.my.procty;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileWriter;
import java.nio.ByteBuffer;

public class FloatingService extends Service {
    private static final String TAG_FRAME = "ProcTyFrame";
    private static final String TAG_ERROR = "ProcTyError";
    private static final boolean REQUIRE_LANDSCAPE = true;

    // --- CẤU HÌNH MẠNG ---
    private static final int SERVER_PORT = 12345;
    private String serverIp = "192.168.1.5"; // Giá trị mặc định

    // --- CẤU HÌNH VUỐT ---
    private static final int SWIPE_START_X = 1299;
    private static final int SWIPE_START_Y = 360;
    private static final int SWIPE_STEPS = 8;
    private static final int SWIPE_STEP_MS = 16;
    private static final long MIN_SWIPE_INTERVAL_MS = (SWIPE_STEPS + 1) * SWIPE_STEP_MS + 60;

    private static final boolean DEBUG_DOTS_DEFAULT = false;
    private volatile boolean debugDots = DEBUG_DOTS_DEFAULT;
    private static final int FF_CROSSHAIR_X = 800;
    private static final int FF_CROSSHAIR_Y = 360;

    private static boolean crashHandlerInstalled = false;

    private WindowManager windowManager;
    private View roiView;
    private View debugDotView;
    private View debugCenterView;

    private DisplayManager displayManager;
    private MediaProjection mediaProjection;
    private volatile VirtualDisplay virtualDisplay;
    private volatile ImageReader imageReader;

    private HandlerThread captureThread;
    private Handler captureHandler;

    private int captureWidth = 0;
    private int captureHeight = 0;
    private int captureDpi = 0;

    private volatile boolean isScanning = false;
    private long lastSwipeUptime = 0;
    private SharedPreferences prefs;
    private final int roiBoxSize = 300;

    private volatile int roiRealX = 0;
    private volatile int roiRealY = 0;
    private volatile int ovOffX = 0;
    private volatile int ovOffY = 0;
    private WindowManager.LayoutParams roiParams;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private static final long PROCESS_INTERVAL_MS = 50;
    private final Object imgLock = new Object();
    private Image heldImage = null;
    private long lastProcessUptime = 0;
    private volatile boolean tickRunning = false;
    private SocketClient socketClient;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        installCrashLogger();
        super.onCreate();
        prefs = getSharedPreferences("proc_ty_settings", MODE_PRIVATE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        try {
            displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            captureThread = new HandlerThread("ProcTyCapture");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());
            startForegroundWithAppropriateType();
            MinitouchController.init(this);
            
            // Đọc IP đã lưu từ SharedPreferences
            SharedPreferences appPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            serverIp = appPrefs.getString("server_ip", "192.168.1.5");
            
            // Khởi tạo kết nối Socket tới máy tính
            socketClient = new SocketClient(serverIp, SERVER_PORT);
            
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Lỗi onCreate: " + e, e);
        }
    }

    private void installCrashLogger() {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        final File logFile = new File(base, "crash_log.txt");
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                FileWriter writer = new FileWriter(logFile);
                writer.write("CRASH: " + e.toString() + "\n");
                for (StackTraceElement ste : e.getStackTrace()) writer.write("at " + ste.toString() + "\n");
                writer.close();
            } catch (Exception ignored) {}
            if (previous != null) previous.uncaughtException(t, e);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(1); }
        });
    }

    private void startForegroundWithAppropriateType() {
        Notification notification = createNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null && mediaProjection == null) {
                int resultCode = intent.getIntExtra("code", 0);
                Intent data = intent.getParcelableExtra("data");
                if (resultCode != 0 && data != null) {
                    MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                    if (projectionManager != null) {
                        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                        if (mediaProjection != null) {
                            mediaProjection.registerCallback(projectionCallback, captureHandler);
                            initScreenCapture();
                        }
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG_ERROR, "Lỗi onStartCommand: " + e, e); }
        return START_NOT_STICKY;
    }

    private Notification createNotification() {
        String channelId = "procty_service_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "ProcTy Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("ProcTy Client Active")
                .setContentText("Đang gửi ảnh tới Server...")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
    }

    @SuppressWarnings("deprecation")
    private int[] queryDisplaySize() {
        if (displayManager != null) {
            Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                DisplayMetrics dm = new DisplayMetrics();
                display.getRealMetrics(dm);
                return new int[]{dm.widthPixels, dm.heightPixels, dm.densityDpi};
            }
        }
        DisplayMetrics fallback = getResources().getDisplayMetrics();
        return new int[]{fallback.widthPixels, fallback.heightPixels, fallback.densityDpi};
    }

    private ImageReader createReader(int width, int height) {
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4);
        reader.setOnImageAvailableListener(frameListener, captureHandler);
        return reader;
    }

    private synchronized void initScreenCapture() {
        try {
            int[] size = queryDisplaySize();
            captureWidth = size[0]; captureHeight = size[1]; captureDpi = size[2];
            ImageReader reader = createReader(captureWidth, captureHeight);
            imageReader = reader;
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ProcTyScreenCapture", captureWidth, captureHeight, captureDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(),
                    virtualDisplayCallback, captureHandler
            );
            if (virtualDisplay == null) return;

            displayManager.registerDisplayListener(displayListener, captureHandler);
            drawRoiBox(captureWidth, captureHeight);
            tickRunning = true;
            captureHandler.removeCallbacks(idleTick);
            captureHandler.postDelayed(idleTick, PROCESS_INTERVAL_MS);
        } catch (Exception e) { Log.e(TAG_ERROR, "Lỗi initScreenCapture: " + e, e); }
    }

    private WindowManager.LayoutParams newOverlayParams(int w, int h) {
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        return p;
    }

    private View makeDot(int fill, int stroke, int strokePx) {
        View v = new View(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL); circle.setColor(fill); circle.setStroke(strokePx, stroke);
        v.setBackground(circle);
        return v;
    }

    private void placeCentered(View v, int capX, int capY, int size) {
        if (v == null || windowManager == null) return;
        try {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) v.getLayoutParams();
            p.x = capX - size / 2 - ovOffX; p.y = capY - size / 2 - ovOffY;
            windowManager.updateViewLayout(v, p);
        } catch (Exception ignored) {}
    }

    private void drawRoiBox(int screenW, int screenH) {
        roiRealX = FF_CROSSHAIR_X - roiBoxSize / 2;
        roiRealY = FF_CROSSHAIR_Y - roiBoxSize / 2;
        uiHandler.post(() -> {
            try {
                if (roiView != null) return;
                roiView = new View(this);
                GradientDrawable border = new GradientDrawable();
                border.setColor(0x00000000); border.setStroke(5, 0xFF00D2FF);
                roiView.setBackground(border);
                roiParams = newOverlayParams(roiBoxSize, roiBoxSize);
                roiParams.x = roiRealX - ovOffX; roiParams.y = roiRealY - ovOffY;
                windowManager.addView(roiView, roiParams);
                roiView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                    if (roiView == null || roiParams == null) return;
                    int[] loc = new int[2]; roiView.getLocationOnScreen(loc);
                    int offX = loc[0] - roiParams.x; int offY = loc[1] - roiParams.y;
                    if (offX != ovOffX || offY != ovOffY) {
                        ovOffX = offX; ovOffY = offY; repositionOverlays();
                    }
                });
                showDebugCenter(FF_CROSSHAIR_X, FF_CROSSHAIR_Y);
            } catch (Exception ignored) {}
        });
    }

    private void removeDebugDots() {
        if (windowManager == null) return;
        if (debugDotView != null) { try { windowManager.removeView(debugDotView); } catch (Exception ignored) {} debugDotView = null; }
        if (debugCenterView != null) { try { windowManager.removeView(debugCenterView); } catch (Exception ignored) {} debugCenterView = null; }
    }

    private void repositionOverlays() {
        uiHandler.post(() -> {
            if (roiView != null && roiParams != null) {
                try {
                    roiParams.x = roiRealX - ovOffX; roiParams.y = roiRealY - ovOffY;
                    windowManager.updateViewLayout(roiView, roiParams);
                } catch (Exception ignored) {}
            }
            placeCentered(debugCenterView, FF_CROSSHAIR_X, FF_CROSSHAIR_Y, 16);
        });
    }

    private void showDebugCenter(final int capX, final int capY) {
        if (!debugDots) return;
        uiHandler.post(() -> {
            try {
                if (debugCenterView == null) {
                    debugCenterView = makeDot(0xFFFFFF00, 0xFF000000, 2);
                    windowManager.addView(debugCenterView, newOverlayParams(16, 16));
                }
                placeCentered(debugCenterView, capX, capY, 16);
            } catch (Exception ignored) {}
        });
    }

    private void showDebugDot(final int capX, final int capY) {
        if (!debugDots) return;
        uiHandler.post(() -> {
            try {
                if (debugDotView == null) {
                    debugDotView = makeDot(0xFFFF0000, 0xFFFFFFFF, 3);
                    windowManager.addView(debugDotView, newOverlayParams(20, 20));
                }
                placeCentered(debugDotView, capX, capY, 20);
            } catch (Exception ignored) {}
        });
    }

    private final ImageReader.OnImageAvailableListener frameListener = reader -> {
        Image fresh = null;
        try { fresh = reader.acquireLatestImage(); } catch (Exception ignored) {}
        if (fresh == null) return;
        synchronized (imgLock) {
            if (heldImage != null) { try { heldImage.close(); } catch (Exception ignored) {} }
            heldImage = fresh;
        }
        processHeldFrame();
    };

    private final Runnable idleTick = new Runnable() {
        @Override
        public void run() {
            if (!tickRunning) return;
            processHeldFrame();
            Handler h = captureHandler;
            if (h != null && tickRunning) h.postDelayed(this, PROCESS_INTERVAL_MS);
        }
    };

    private void processHeldFrame() {
        long nowMs = SystemClock.uptimeMillis();
        synchronized (imgLock) {
            if (heldImage == null) return;
            if (nowMs - lastProcessUptime < PROCESS_INTERVAL_MS) return;
            lastProcessUptime = nowMs;
            processFrame(heldImage);
        }
    }

    private void processFrame(Image image) {
        try {
            int imgW = image.getWidth();
            int imgH = image.getHeight();
            boolean portrait = imgW < imgH;

            isScanning = prefs.getBoolean("is_scanning", false);
            boolean wantDots = prefs.getBoolean("debug_dots", DEBUG_DOTS_DEFAULT);
            if (wantDots != debugDots) {
                debugDots = wantDots;
                uiHandler.post(() -> { if (!debugDots) removeDebugDots(); else showDebugCenter(FF_CROSSHAIR_X, FF_CROSSHAIR_Y); });
            }

            if (!isScanning || (REQUIRE_LANDSCAPE && portrait)) return;

            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) return;

            ByteBuffer buffer = planes[0].getBuffer();
            int rowStride = planes[0].getRowStride();
            int pixelStride = planes[0].getPixelStride();

            final int roiInset = 5;
            final int roiX = roiRealX + roiInset;
            final int roiY = roiRealY + roiInset;
            final int roiW = roiBoxSize - 2 * roiInset;
            final int roiH = roiBoxSize - 2 * roiInset;

            if (roiX < 0 || roiY < 0 || roiX + roiW > imgW || roiY + roiH > imgH) return;

            // --- SỬA ĐỔI: LẤY DỮ LIỆU THÔ (KHÔNG DÙNG BITMAP) ---
            // Di chuyển con trỏ buffer đến đúng vị trí bắt đầu của ROI
            int startOffset = (roiY * rowStride) + (roiX * pixelStride);
            buffer.position(startOffset);

            // Tính kích thước mảng byte cần lấy cho vùng ROI
            int dataSize = (roiH - 1) * rowStride + roiW * pixelStride;
            byte[] rawData = new byte[dataSize];
            buffer.get(rawData);

            // Gửi ảnh qua Socket
            long now = SystemClock.uptimeMillis();
            if (now - lastSwipeUptime >= MIN_SWIPE_INTERVAL_MS) {
                lastSwipeUptime = now;
                // Gửi rawData thay vì bitmap
                socketClient.sendFrame(rawData, roiW, roiH, rowStride, (targetX, targetY) -> {
                    if (targetX != -1 && targetY != -1) {
                        showDebugDot(targetX, targetY);
                        MinitouchController.swipePath(
                                AimMath.easedPath(SWIPE_START_X, SWIPE_START_Y, targetX, targetY, SWIPE_STEPS),
                                SWIPE_STEP_MS
                        );
                    }
                });
            }
        } catch (Exception e) { Log.e(TAG_ERROR, "Lỗi frame: " + e.getMessage()); }
    }

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() { isScanning = false; releaseCapture(); stopSelf(); }
    };

    private final VirtualDisplay.Callback virtualDisplayCallback = new VirtualDisplay.Callback() {
        @Override public void onPaused() {}
        @Override public void onResumed() {}
        @Override public void onStopped() {}
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) {}
        @Override public void onDisplayRemoved(int displayId) {}
        @Override public void onDisplayChanged(int displayId) { if (displayId == Display.DEFAULT_DISPLAY) resizeCaptureIfNeeded(); }
    };

    private synchronized void resizeCaptureIfNeeded() {
        VirtualDisplay vd = virtualDisplay;
        if (vd == null) return;
        int[] size = queryDisplaySize();
        if (size[0] == captureWidth && size[1] == captureHeight && size[2] == captureDpi) return;
        try {
            dropHeldImage();
            ImageReader old = imageReader;
            ImageReader fresh = createReader(size[0], size[1]);
            vd.resize(size[0], size[1], size[2]);
            vd.setSurface(fresh.getSurface());
            imageReader = fresh; captureWidth = size[0]; captureHeight = size[1]; captureDpi = size[2];
            if (old != null) { old.setOnImageAvailableListener(null, null); old.close(); }
            repositionOverlays(); MinitouchController.close(); MinitouchController.init(this);
        } catch (Exception e) { Log.e(TAG_ERROR, "Lỗi resize capture: " + e, e); }
    }

    private void dropHeldImage() {
        synchronized (imgLock) { if (heldImage != null) { try { heldImage.close(); } catch (Exception ignored) {} heldImage = null; } }
    }

    private synchronized void releaseCapture() {
        tickRunning = false;
        if (captureHandler != null) captureHandler.removeCallbacks(idleTick);
        dropHeldImage();
        if (displayManager != null) { try { displayManager.unregisterDisplayListener(displayListener); } catch (Exception ignored) {} }
        if (roiView != null && windowManager != null) { try { windowManager.removeView(roiView); } catch (Exception ignored) {} roiView = null; }
        if (debugDotView != null && windowManager != null) { try { windowManager.removeView(debugDotView); } catch (Exception ignored) {} debugDotView = null; }
        if (debugCenterView != null && windowManager != null) { try { windowManager.removeView(debugCenterView); } catch (Exception ignored) {} debugCenterView = null; }
        ImageReader reader = imageReader; VirtualDisplay vd = virtualDisplay; imageReader = null; virtualDisplay = null;
        if (reader != null) reader.setOnImageAvailableListener(null, null);
        if (vd != null) { try { vd.release(); } catch (Exception ignored) {} }
        if (reader != null) { try { reader.close(); } catch (Exception ignored) {} }
        if (socketClient != null) socketClient.close();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isScanning = false;
        if (captureHandler != null) captureHandler.removeCallbacksAndMessages(null);
        releaseCapture();
        if (mediaProjection != null) { try { mediaProjection.unregisterCallback(projectionCallback); } catch (Exception ignored) {} try { mediaProjection.stop(); } catch (Exception ignored) {} mediaProjection = null; }
        if (captureThread != null) { captureThread.quitSafely(); captureThread = null; captureHandler = null; }
        MinitouchController.close();
    }
}