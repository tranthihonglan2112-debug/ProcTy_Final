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

    // SỬA ĐỔI: Tách riêng hàm nạp ảnh mẫu và hàm tìm kiếm
    private native boolean nativeLoadTemplate(byte[] templateData);
    // buffer = TOÀN BỘ khung hình; (roiX,roiY,roiW,roiH) = vùng quét. Cắt ROI ở C++ bằng cv::Mat(stride thật).
    // MỚI: đo tỉ lệ vuốt (hiệu chuẩn)
    private native boolean nativeCalibStore(ByteBuffer buffer, int fullW, int fullH, int rowStride, int slot);
    private native long nativeCalibShift();
    private native long nativeFindTarget(ByteBuffer buffer, int fullW, int fullH, int rowStride,
                                         int roiX, int roiY, int roiW, int roiH,
                                         int mode, int cR, int cG, int cB);

    private static final String TAG_FRAME = "ProcTyFrame";
    private static final String TAG_ERROR = "ProcTyError";

    private static final boolean REQUIRE_LANDSCAPE = true;
    private static final long MIN_SWIPE_INTERVAL_MS = 100; 

    // ---- MỚI: vuốt TƯƠNG ĐỐI có hiệu chuẩn (thay cho "kéo tới toạ độ tuyệt đối của mục tiêu") ----
    private static final int SWIPE_START_X = 1299;      // giữ nguyên điểm đầu cũ
    private static final int SWIPE_START_Y = 360;
    private static final double DEAD_ZONE_PX = 6.0;     // mục tiêu đã sát tâm thì KHÔNG vuốt
    private static final double DAMP = 0.95;
    private static final double MAX_DRAG_PX = 450.0;
    private static final int SWIPE_STEPS = 5;           // 5 điểm MOVE thay vì 1
    private static final int SWIPE_STEP_MS = 8;         // tổng ~50ms
    private static final int CONFIRM_PX = 80;
    private static final int CAL_DRAG_PX = 40;          // vuốt thử khi hiệu chuẩn
    private static final int CAL_REPEATS = 3;           // mỗi trục đo 3 lần rồi lấy trung vị
    private static final long CAL_SETTLE_MS = 450;      // chờ camera đứng yên rồi mới chụp khung B

    private static boolean crashHandlerInstalled = false;

    private WindowManager windowManager;
    private View roiView;
    private View debugDotView; // BIẾN MỚI: Dùng để vẽ chấm tròn debug
    
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
    private final int roiBoxSize = 300; 

    private int roiRealX = 0;
    private int roiRealY = 0;

    // TỐI ƯU: Lưu ảnh mẫu vào bộ nhớ 1 lần
    private byte[] cachedTemplateData = null;

    // MỚI: tỉ lệ vuốt g = px cảnh dịch / px ngón tay (0 = chưa hiệu chuẩn), tách trục X/Y
    private double aimGx = 0, aimGy = 0;
    private int calStep = 0, calPhase = 0, calSign = 1, calFail = 0;
    private long calReadyAt = 0;
    private final double[] calG = new double[CAL_REPEATS];
    private int calGn = 0;
    private boolean maaRestartTried = false;
    private int lastDx = Integer.MIN_VALUE, lastDy = 0;     // xác nhận 2 lần quét liên tiếp
    private long scanNs = 0;
    private int scanN = 0;
    private int lastLoadedMode = -1;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        installCrashLogger();
        super.onCreate();
        prefs = getSharedPreferences("proc_ty_settings", MODE_PRIVATE);
        aimGx = prefs.getFloat("aim_gx", 0f);
        aimGy = prefs.getFloat("aim_gy", 0f);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        try {
            displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);

            captureThread = new HandlerThread("ProcTyCapture");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());

            startForegroundWithAppropriateType();

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

            drawRoiBox(captureWidth, captureHeight);
            
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Lỗi initScreenCapture: " + e, e);
        }
    }
    
    private void drawRoiBox(int screenW, int screenH) {
        try {
            roiView = new View(this);
            GradientDrawable border = new GradientDrawable();
            border.setColor(0x00000000); 
            border.setStroke(5, 0xFF00D2FF); 
            roiView.setBackground(border);

            int layoutParamType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    roiBoxSize, roiBoxSize,
                    layoutParamType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.LEFT;
            
            params.x = (screenW / 2) - (roiBoxSize / 2) - 30; 
            params.y = (screenH / 2) - (roiBoxSize / 2);

            roiRealX = params.x;
            roiRealY = params.y;

            windowManager.addView(roiView, params);
            Log.i(TAG_FRAME, "Đã vẽ khung ROI tại X=" + params.x + ", Y=" + params.y);

            roiView.post(() -> {
                try {
                    int[] loc = new int[2];
                    roiView.getLocationOnScreen(loc);
                    if (loc[0] != roiRealX || loc[1] != roiRealY) {
                        Log.w(TAG_FRAME, "ROI lệch: params=(" + roiRealX + "," + roiRealY + ") thật=(" + loc[0] + "," + loc[1] + ") -> dùng toạ độ thật");
                    }
                    roiRealX = loc[0];
                    roiRealY = loc[1];
                } catch (Exception e) {
                    Log.e(TAG_ERROR, "Không lấy được vị trí ROI thật: " + e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Lỗi vẽ khung ROI: " + e.getMessage());
        }
    }

    // HÀM MỚI: Vẽ chấm tròn màu đỏ để debug
    private void showDebugDot(int x, int y) {
        try {
            if (debugDotView == null) {
                debugDotView = new View(this);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL); 
                circle.setColor(0xFFFF0000); // Màu đỏ
                circle.setStroke(3, 0xFFFFFFFF); 
                debugDotView.setBackground(circle);

                int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        20, 20, 
                        type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.TOP | Gravity.LEFT;
                windowManager.addView(debugDotView, params);
            }
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) debugDotView.getLayoutParams();
            p.x = x - 10; 
            p.y = y - 10;
            windowManager.updateViewLayout(debugDotView, p);
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Lỗi vẽ chấm debug: " + e.getMessage());
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

            frameCount++;
            if (frameCount % 6 != 0) return;

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

            final int roiInset = 5;
            final int roiX = roiRealX + roiInset;
            final int roiY = roiRealY + roiInset;
            final int roiW = roiBoxSize - 2 * roiInset;
            final int roiH = roiBoxSize - 2 * roiInset;
            if (roiX < 0 || roiY < 0 || roiX + roiW > imgW || roiY + roiH > imgH) {
                Log.e(TAG_ERROR, "ROI nằm ngoài khung hình: roi=(" + roiX + "," + roiY + " " + roiW + "x" + roiH
                        + ") khung=" + imgW + "x" + imgH);
                return;
            }

            if (calibrateStep(buffer, imgW, imgH, rowStride)) return;

            if (mode == 2) {
                if (lastLoadedMode != 2 || cachedTemplateData == null) {
                    File tplFile = new File("/storage/emulated/0/Download/head_front.png");
                    if (tplFile.exists()) {
                        try {
                            byte[] data = java.nio.file.Files.readAllBytes(tplFile.toPath()); 
                            if (nativeLoadTemplate(data)) {   
                                cachedTemplateData = data;
                                lastLoadedMode = 2;
                                Log.d(TAG_FRAME, "Đã nạp ảnh mẫu (" + data.length + " byte)");
                            } else {
                                Log.e(TAG_ERROR, "C++ KHÔNG giải mã được ảnh mẫu (" + data.length + " byte): " + tplFile);
                            }
                        } catch (Exception e) {
                            Log.e(TAG_ERROR, "Lỗi đọc ảnh mẫu: " + e.getMessage());
                        }
                    } else {
                        Log.e(TAG_ERROR, "Không tìm thấy file ảnh mẫu!");
                    }
                }
            } else {
                if (lastLoadedMode != 1) {
                    nativeLoadTemplate(null); 
                    lastLoadedMode = 1;
                }
            }

            long t0 = System.nanoTime();
            long result = nativeFindTarget(buffer, imgW, imgH, rowStride, roiX, roiY, roiW, roiH, mode, cR, cG, cB);
            scanNs += System.nanoTime() - t0;
            if (++scanN >= 30) {                      
                Log.d("ProcTyPerf", "so khớp TB " + (scanNs / scanN) / 1000000.0 + " ms/lần (30 lần gần nhất)");
                scanN = 0;
                scanNs = 0;
            }

            if (result != 0) {
                int deltaX = (int) (result >> 32) - 2000;
                int deltaY = (int) (result & 0xFFFFFFFFL) - 2000;
                double dist = Math.hypot(deltaX, deltaY);

                boolean confirmed = lastDx != Integer.MIN_VALUE
                        && Math.abs(deltaX - lastDx) <= CONFIRM_PX && Math.abs(deltaY - lastDy) <= CONFIRM_PX;
                lastDx = deltaX;
                lastDy = deltaY;

                int targetX = roiRealX + (roiBoxSize / 2) + deltaX;
                int targetY = roiRealY + (roiBoxSize / 2) + deltaY;

                // GỌI HÀM VẼ CHẤM ĐỎ DEBUG Ở ĐÂY
                showDebugDot(targetX, targetY); 

                long now = SystemClock.uptimeMillis();
                if (confirmed && dist >= DEAD_ZONE_PX && now - lastSwipeUptime >= MIN_SWIPE_INTERVAL_MS) {
                    lastSwipeUptime = now;
                    int[] end = AimMath.relativeEnd(SWIPE_START_X, SWIPE_START_Y, deltaX, deltaY,
                            aimGx, aimGy, DAMP, MAX_DRAG_PX, imgW, imgH);
                    Log.d("ProcTyTarget", "delta=(" + deltaX + "," + deltaY + ") g=(" + aimGx + "," + aimGy
                            + ") -> vuốt " + SWIPE_START_X + "," + SWIPE_START_Y + " => " + end[0] + "," + end[1]);
                    MinitouchController.swipePath(
                            AimMath.easedPath(SWIPE_START_X, SWIPE_START_Y, end[0], end[1], SWIPE_STEPS), SWIPE_STEP_MS);
                    lastDx = Integer.MIN_VALUE;       
                }
            } else {
                lastDx = Integer.MIN_VALUE;           
            }
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Lỗi frame: " + e, e);
        } finally {
            if (image != null) image.close();
        }
    };

    private boolean calibrateStep(ByteBuffer buffer, int imgW, int imgH, int rowStride) {
        if (aimGx != 0 && aimGy != 0) return false;
        if (!MinitouchController.isReady()) {
            MinitouchController.ensureStarted();
            return true;
        }

        long now = SystemClock.uptimeMillis();
        boolean horizontal = calStep < CAL_REPEATS;

        if (calPhase == 0) {
            if (calStep == 0) {
                int mx = MinitouchController.getMaxX(), my = MinitouchController.getMaxY();
                if (mx > 0 && (mx != imgW || my != imgH)) {
                    Log.e(TAG_ERROR, "MaaTouch dùng toạ độ " + mx + "x" + my + " nhưng khung chụp là " + imgW + "x" + imgH
                            + " => toạ độ vuốt sẽ SAI. " + (maaRestartTried ? "Đã khởi động lại 1 lần mà vẫn lệch." : "Khởi động lại MaaTouch."));
                    if (!maaRestartTried) {
                        maaRestartTried = true;
                        MinitouchController.close();
                        MinitouchController.init(this);
                        return true;
                    }
                }
            }
            if (!nativeCalibStore(buffer, imgW, imgH, rowStride, 0)) return true;
            calSign = ((calStep % 2) == 0) ? 1 : -1;       
            int ex = SWIPE_START_X + (horizontal ? calSign * CAL_DRAG_PX : 0);
            int ey = SWIPE_START_Y + (horizontal ? 0 : calSign * CAL_DRAG_PX);
            MinitouchController.swipePath(AimMath.easedPath(SWIPE_START_X, SWIPE_START_Y, ex, ey, SWIPE_STEPS), SWIPE_STEP_MS);
            calReadyAt = now + CAL_SETTLE_MS;
            calPhase = 1;
            return true;
        }

        if (now < calReadyAt) return true;
        calPhase = 0;
        if (!nativeCalibStore(buffer, imgW, imgH, rowStride, 1)) return true;
        long r = nativeCalibShift();

        double g = Double.NaN;
        if (r != 0) {
            double sx = ((int) (r >> 32) - 200000) / 10.0;
            double sy = ((int) (r & 0xFFFFFFFFL) - 200000) / 10.0;
            g = AimMath.gainFromShift(horizontal ? sx : sy, calSign * (double) CAL_DRAG_PX);
            Log.d("ProcTyCal", (horizontal ? "X" : "Y") + " lần " + (calStep % CAL_REPEATS + 1)
                    + ": cảnh dịch (" + sx + "," + sy + ") px => g=" + g);
        } else {
            Log.w("ProcTyCal", "không đo được dịch chuyển (cảnh quá phẳng/ít chi tiết?)");
        }
        if (!Double.isNaN(g)) calG[calGn++] = g; else calFail++;

        boolean groupDone = ((calStep + 1) % CAL_REPEATS) == 0;
        calStep++;
        if (groupDone) {
            double med = (calGn >= 2) ? AimMath.median(calG, calGn) : Double.NaN;
            if (Double.isNaN(med)) {
                Log.e("ProcTyCal", "Hiệu chuẩn trục " + (horizontal ? "X" : "Y") + " THẤT BẠI -> tạm dùng g=1.0 (không lưu)");
                med = 1.0;
            }
            if (horizontal) aimGx = med; else aimGy = med;
            calGn = 0;
            if (!horizontal) {
                Log.i("ProcTyCal", "Hiệu chuẩn xong: gx=" + aimGx + " gy=" + aimGy);
                if (calFail == 0) prefs.edit().putFloat("aim_gx", (float) aimGx).putFloat("aim_gy", (float) aimGy).apply();
            } else {
                aimGy = 0;   
            }
        }
        return true;
    }

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
            maaRestartTried = false;
            MinitouchController.close();
            MinitouchController.init(this);
        } catch (Exception e) {
            Log.e(TAG_ERROR, "Lỗi resize capture: " + e, e);
        }
    }

    private synchronized void releaseCapture() {
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); } catch (Exception ignored) {}
        }
        
        if (roiView != null && windowManager != null) {
            try { windowManager.removeView(roiView); } catch (Exception ignored) {}
            roiView = null;
        }

        // XÓA CHẤM ĐỎ KHI TẮT
        if (debugDotView != null && windowManager != null) {
            try { windowManager.removeView(debugDotView); } catch (Exception ignored) {}
            debugDotView = null;
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
