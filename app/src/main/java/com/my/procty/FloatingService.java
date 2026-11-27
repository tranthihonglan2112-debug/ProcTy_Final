package com.my.procty;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;

public class FloatingService extends Service {
    private static final String TAG_ERROR = "ProcTyError";
    private static final int SWIPE_STEPS = 3;
    private static final int SWIPE_STEP_MS = 20;

    private WindowManager windowManager;
    private View roiView; // Vẫn giữ khung xanh 400x400 để làm mốc ngắm
    private final int roiBoxSize = 400;
    private static final int FF_CROSSHAIR_X = 800;
    private static final int FF_CROSSHAIR_Y = 360;

    private MediaProjection mediaProjection;
    private MediaProjectionManager projectionManager;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

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
                        // Vẽ khung xanh để làm mốc (không cần chụp hình nữa)
                        drawRoiBox();
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
                .setContentText("Chế độ bù trừ thủ công...")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
                
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, notification);
        }
    }

    private void drawRoiBox() {
        uiHandler.post(() -> {
            try {
                if (roiView != null) return;
                roiView = new View(this);
                android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
                border.setColor(0x00000000); 
                border.setStroke(5, 0xFF00D2FF); // Viền xanh
                roiView.setBackground(border);
                
                int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                        
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        roiBoxSize, roiBoxSize, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.x = FF_CROSSHAIR_X - roiBoxSize / 2;
                params.y = FF_CROSSHAIR_Y - roiBoxSize / 2;
                
                windowManager.addView(roiView, params);
            } catch (Exception e) {
                Log.e(TAG_ERROR, "Lỗi vẽ khung: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (roiView != null && windowManager != null) {
            try { windowManager.removeView(roiView); } catch (Exception ignored) {}
            roiView = null;
        }
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }
        MinitouchController.close();
    }
}