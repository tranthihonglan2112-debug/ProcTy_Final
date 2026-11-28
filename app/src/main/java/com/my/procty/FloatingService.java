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
import androidx.core.app.NotificationCompat;

public class FloatingService extends Service {
    private static final String TAG_ERROR = "ProcTyError";
    private static final int SWIPE_STEPS = 3;
    private static final int SWIPE_STEP_MS = 20;

    // TỌA ĐỘ NÚT BẮN (Ông chủ thay đổi cho khớp với máy mình)
    private static final int START_X = 1300;
    private static final int START_Y = 503; 
    private static final int BUTTON_SIZE = 160; // Tăng lên 160 để dễ chạm hơn

    private WindowManager windowManager;
    private View touchOverlayView; 
    private MediaProjection mediaProjection;
    private MediaProjectionManager projectionManager;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    
    // Biến dùng để hủy bộ đếm nếu người dùng nhấc tay sớm
    private boolean isTouching = false;

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
                        setupTouchOverlay(); 
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
                
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0x1A00D2FF); 
                bg.setStroke(3, 0x4D00D2FF); 
                bg.setShape(android.graphics.drawable.GradientDrawable.OVAL); 
                touchOverlayView.setBackground(bg);

                int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                        
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        BUTTON_SIZE, BUTTON_SIZE, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, 
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.LEFT;
                
                params.x = START_X - BUTTON_SIZE / 2;
                params.y = START_Y - BUTTON_SIZE / 2;
                
                touchOverlayView.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        if (event.getAction() == MotionEvent.ACTION_DOWN) {
                            isTouching = true;
                            // 1. Ẩn nút đè để ngón tay thật vuốt
                            touchOverlayView.setVisibility(View.GONE);
                            
                            // 2. Chờ 250ms cho ngón tay thật vuốt, rồi mới bù trừ
                            uiHandler.postDelayed(() -> {
                                if (isTouching) {
                                    performCompensation();
                                }
                            }, 250);
                            
                            // 3. Hiện lại nút đè sau 200ms
                            uiHandler.postDelayed(() -> {
                                if (touchOverlayView != null) {
                                    touchOverlayView.setVisibility(View.VISIBLE);
                                }
                            }, 200);
                            
                            return true;
                        } else if (event.getAction() == MotionEvent.ACTION_UP) {
                            // Nếu nhấc tay sớm, hủy việc bù trừ
                            isTouching = false;
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
        SharedPreferences prefs = getSharedPreferences("proc_ty_settings", MODE_PRIVATE);
        int pixels = prefs.getInt("compensation_px", 40);

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