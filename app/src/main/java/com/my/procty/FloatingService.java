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
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class FloatingService extends Service {
    static {
        System.loadLibrary("procty");
    }

    private native long nativeFindTarget(ByteBuffer buffer, int width, int height, int rowStride,
                                         int mode, int cR, int cG, int cB,
                                         byte[] templateData, int tplWidth, int tplHeight);

    private static final String TAG_FRAME = "ProcTyFrame";
    private static final String TAG_ERROR = "ProcTyError";

    private static final boolean REQUIRE_LANDSCAPE = true;
    
    // Giảm khoản nghỉ vì Minitouch phản hồi nhanh
    private static final long MIN_SWIPE_INTERVAL_MS = 50; 

    private static boolean crashHandlerInstalled = false;

    private WindowManager windowManager;
    private View roiView;
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
    private long frameCount = 0;
    private long lastSwipeUptime = 0;

    private SharedPreferences prefs;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

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

            // KHỞI TẠO MINITOUCH THAY VÌ INPUTINJECTOR
            MinitouchController.init(this);

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
                for (StackTraceElement ste : e.getStackTrace()) {
                    writer.write("at " + ste.toString() + "\n");
                }
                writer.close();
            } catch (Exception ignored) {}

            if (previous != null) {
                previous.uncaughtException(t, e);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(1);
            }
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
                        } else {
                            Log.e(TAG_ERROR, "getMediaProjection() trả về null");
                        }
                    }
                } else {
                    Log.e(TAG_ERROR, "Thiếu code/data MediaProjection trong Intent (code=" + resultCode + ")");
                }
            }
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Lỗi onStartCommand: " + e, e);
        }
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
                .setContentTitle("ProcTy AI Active")
                .setContentText("Mắt thần đang hoạt động...")
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
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(frameListener, captureHandler);
        return reader;
    }

    private synchronized void initScreenCapture() {
        try {
            int[] size = queryDisplaySize();
            captureWidth = size[0];
            captureHeight = size[1];
            captureDpi = size[2];

            ImageReader reader = createReader(captureWidth, captureHeight);
            imageReader = reader;

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ProcTyScreenCapture", captureWidth, captureHeight, captureDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(),
                    virtualDisplayCallback, captureHandler
            );

            if (virtualDisplay == null) {
                Log.e(TAG_ERROR, "createVirtualDisplay() trả về null");
                return;
            }

            displayManager.registerDisplayListener(displayListener, captureHandler);
            Log.i(TAG_FRAME, "Capture sẵn sàng: " + captureWidth + "x" + captureHeight + " @" + captureDpi + "dpi");

            // VẼ KHUNG DEBUG ROI (Tự động căn giữa)
            drawRoiBox(captureWidth, captureHeight);
            
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Lỗi initScreenCapture: " + e, e);
        }
    }
    
    private void drawRoiBox(int screenW, int screenH) {
        try {
            roiView = new View(this);
            GradientDrawable border = new GradientDrawable();
            border.setColor(0x2200D2FF); // Màu xanh mờ nhẹ
            border.setStroke(5, 0xFF00D2FF); // Viền xanh dày 5px
            roiView.setBackground(border);

            int layoutParamType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

            int boxSize = 300;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    boxSize, boxSize,
                    layoutParamType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.LEFT;
            
            // SỬA LỆCH SANG TRÁI 30 PIXEL (Trừ đi 30)
            params.x = (screenW / 2) - (boxSize / 2) - 30; 
            params.y = (screenH / 2) - (boxSize / 2);

            windowManager.addView(roiView, params);
            Log.i(TAG_FRAME, "Đã vẽ khung ROI tại X=" + params.x + ", Y=" + params.y);
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Lỗi vẽ khung ROI: " + e.getMessage());
        }
    }

    private final ImageReader.OnImageAvailableListener frameListener = reader -> {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            int imgW = image.getWidth();
            int imgH = image.getHeight();
            boolean portrait = imgW < imgH;

            // GIẢM FPS XUỐNG ~20FPS (Bỏ qua 2/3 số frame)
            frameCount++;
            if (frameCount % 3 != 0) return;

            isScanning = prefs.getBoolean("is_scanning", false);
            int mode = prefs.getInt("mode", 1);
            int cR = prefs.getInt("color_r", 200);
            int cG = prefs.getInt("color_g", 50);
            int cB = prefs.getInt("color_b", 50);

            if (!isScanning) return; 
            if (REQUIRE_LANDSCAPE && portrait) return;

            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) return;

            ByteBuffer buffer = planes[0].getBuffer();
            int rowStride = planes[0].getRowStride();

            // ĐỌC ẢNH MẪU
            byte[] templateData = null; 
            int tplW = 0, tplH = 0;

            if (mode == 2) {
                File tplFile = new File("/storage/emulated/0/Download/head_front.png");
                if (tplFile.exists()) {
                    try {
                        InputStream is = new java.io.FileInputStream(tplFile);
                        templateData = new byte[(int) tplFile.length()];
                        int bytesRead = is.read(templateData);
                        is.close();
                    } catch (Exception e) {
                        Log.e(TAG_ERROR, "Lỗi đọc ảnh: " + e.getMessage());
                    }
                }
            }

            long result = nativeFindTarget(buffer, imgW, imgH, rowStride, mode, cR, cG, cB, templateData, tplW, tplH);

            if (result != 0) {
                int deltaX = (int) (result >> 32) - 2000;
                int deltaY = (int) (result & 0xFFFFFFFFL) - 2000;
                
                // Căn tâm màn hình và bù trừ độ lệch khung xanh
                int targetX = ((imgW / 2) - 30) + deltaX;
                int targetY = (imgH / 2) + deltaY;

                Log.d("ProcTyTarget", "Kéo tâm tới: X=" + targetX + ", Y=" + targetY);

                long now = SystemClock.uptimeMillis();
                if (now - lastSwipeUptime >= MIN_SWIPE_INTERVAL_MS) {
                    lastSwipeUptime = now;
                    // GỌI MINITOUCH THAY VÌ INPUTINJECTOR
                    
MinitouchController.swipe(1299, 360, targetX, targetY, 10);
                }
            }
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Lỗi frame: " + e, e);
        } finally {
            if (image != null) image.close();
        }
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            isScanning = false;
            releaseCapture();
            stopSelf();
        }
    };

    private final VirtualDisplay.Callback virtualDisplayCallback = new VirtualDisplay.Callback() {
        @Override
        public void onPaused() { Log.w(TAG_FRAME, "VirtualDisplay: PAUSED"); }
        @Override
        public void onResumed() { Log.i(TAG_FRAME, "VirtualDisplay: RESUMED"); }
        @Override
        public void onStopped() { Log.w(TAG_FRAME, "VirtualDisplay: STOPPED"); }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}
        @Override
        public void onDisplayRemoved(int displayId) {}
        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) resizeCaptureIfNeeded();
        }
    };

    private synchronized void resizeCaptureIfNeeded() {
        VirtualDisplay vd = virtualDisplay;
        if (vd == null) return;

        int[] size = queryDisplaySize();
        if (size[0] == captureWidth && size[1] == captureHeight && size[2] == captureDpi) return;

        try {
            ImageReader old = imageReader;
            ImageReader fresh = createReader(size[0], size[1]);

            vd.resize(size[0], size[1], size[2]);
            vd.setSurface(fresh.getSurface());

            imageReader = fresh;
            captureWidth = size[0];
            captureHeight = size[1];
            captureDpi = size[2];

            if (old != null) {
                old.setOnImageAvailableListener(null, null);
                old.close();
            }
            Log.i(TAG_FRAME, "Đổi hướng/kích thước -> " + captureWidth + "x" + captureHeight + " @" + captureDpi + "dpi");
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Lỗi resize capture: " + e, e);
        }
    }

    private synchronized void releaseCapture() {
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); } catch (Exception ignored) {}
        }
        
        // XÓA KHUNG DEBUG KHI TẮT
        if (roiView != null && windowManager != null) {
            try { windowManager.removeView(roiView); } catch (Exception ignored) {}
            roiView = null;
        }

        ImageReader reader = imageReader;
        VirtualDisplay vd = virtualDisplay;
        imageReader = null;
        virtualDisplay = null;

        if (reader != null) reader.setOnImageAvailableListener(null, null);
        if (vd != null) {
            try { vd.release(); } catch (Exception ignored) {}
        }
        if (reader != null) {
            try { reader.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isScanning = false;

        if (captureHandler != null) captureHandler.removeCallbacksAndMessages(null);
        releaseCapture();

        if (mediaProjection != null) {
            try { mediaProjection.unregisterCallback(projectionCallback); } catch (Exception ignored) {}
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }

        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }
        
        MinitouchController.close();
    }
}