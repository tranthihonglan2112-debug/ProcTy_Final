package com.my.procty;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;

public class FloatingService extends Service {
    private static final String TAG_ERROR = "ProcTyError";
    private static final int SWIPE_STEPS = 3;
    private static final int SWIPE_STEP_MS = 20;

    // TỌA ĐỘ NÚT BẮN (Ông chủ thay đổi cho khớp với máy mình)
    private static final int START_X = 1312;
    private static final int START_Y = 503; 
    private static final int BUTTON_SIZE = 150; // Kích thước vùng đè

    private WindowManager windowManager;
    private View touchOverlayView; // Vùng cảm ứng đè lên nút bắn
    private MediaProjection mediaProjection;
    private MediaProjectionManager projectionManager;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean isTouchEnabled = false;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        
        startForegroundWithAppropriateType();
        MinitouchController.init(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null && mediaProjection == null) {
                int resultCode = intent.getIntExtra("code", 0);
                Intent data = intent.getParcelableExtra("data");
                if (resultCode != 0 && data != null) {
                    mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                    if (mediaProjection != null) {
                        setupTouchOverlay(); // Tạo nút đè sau khi có quyền
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG_ERROR, "Lỗi onStartCommand: " + e.getMessage()); }
        return START_NOT_STICKY;
    }

    private void startForegroundWithAppropriateType() {
        String channelId = "procty_service_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "ProcTy Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("ProcTy Active")
                .setContentText("Chế độ Nút Đè đang chạy...")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
                
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, notification);
        }
    }

    private void setupTouchOverlay() {
        uiHandler.post(() -> {
            try {
                if (touchOverlayView != null) return;
                touchOverlayView = new View(this);
                
                // Tạo nền mờ mờ để ông chủ nhìn thấy vùng đè (Alpha 10%)
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0x1A00D2FF); // Xanh mờ
                bg.setStroke(3, 0x4D00D2FF); // Viền xanh mờ
                bg.setShape(android.graphics.drawable.GradientDrawable.OVAL); // Hình tròn
                touchOverlayView.setBackground(bg);

                int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                        
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        BUTTON_SIZE, BUTTON_SIZE, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // Cho phép chạm ra ngoài
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.LEFT;
                
                // Đặt vùng đè vào đúng vị trí nút bắn
                params.x = START_X - BUTTON_SIZE / 2;
                params.y = START_Y - BUTTON_SIZE / 2;
                
                // Xử lý sự kiện chạm
                touchOverlayView.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        if (event.getAction() == MotionEvent.ACTION_DOWN) {
                            // 1. Ẩn nút đè ngay lập tức để game nhận được cú chạm bắn
                            touchOverlayView.setVisibility(View.GONE);
                            
                            // 2. Thực hiện bù trừ
                            performCompensation();
                            
                            // 3. Hiện lại nút đè sau 600ms
                            uiHandler.postDelayed(() -> {
                                if (touchOverlayView != null) {
                                    touchOverlayView.setVisibility(View.VISIBLE);
                                }
                            }, 600);
                            
                            return true;
                        }
                        return false;
                    }
                });
                
                windowManager.addView(touchOverlayView, params);
            } catch (Exception e) {
                Log.e(TAG_ERROR, "Lỗi tạo Nút Đè: " + e.getMessage());
            }
        });
    }

    private void performCompensation() {
        // Đọc giá trị bù trừ từ thanh trượt (Mặc định là 40px)
        SharedPreferences prefs = getSharedPreferences("proc_ty_settings", MODE_PRIVATE);
        int pixels = prefs.getInt("compensation_px", 40);

        // Vuốt bù trừ (lên trên)
        int endX = START_X;
        int endY = START_Y - pixels;

        MinitouchController.swipePath(
                AimMath.easedPath(START_X, START_Y, endX, endY, SWIPE_STEPS),
                SWIPE_STEP_MS
        );
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (touchOverlayView != null && windowManager != null) {
            try { windowManager.removeView(touchOverlayView); } catch (Exception ignored) {}
            touchOverlayView = null;
        }
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }
        MinitouchController.close();
    }
}