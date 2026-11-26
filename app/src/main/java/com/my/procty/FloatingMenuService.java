package com.my.procty;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;

public class FloatingMenuService extends Service {
    private WindowManager windowManager;
    private View menuView;
    private TextView btnToggle;
    private boolean isScanning = false;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        
        // Khởi tạo Foreground Service để không bị kill
        String channelId = "menu_service_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "ProcTy Menu", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("ProcTy Menu Active")
                .setContentText("Nút điều khiển đang chạy...")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
        startForeground(2, notification);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        initMenu();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Khởi chạy FloatingService (Aimbot) khi nhận quyền
        if (intent != null && intent.hasExtra("code") && intent.hasExtra("data")) {
            int resultCode = intent.getIntExtra("code", 0);
            Intent data = intent.getParcelableExtra("data");

            if (resultCode != 0 && data != null) {
                Intent serviceIntent = new Intent(this, FloatingService.class);
                serviceIntent.putExtra("code", resultCode);
                serviceIntent.putExtra("data", data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            }
        }
        return START_NOT_STICKY;
    }

    private void initMenu() {
        LayoutInflater inflater = LayoutInflater.from(this);
        // Dùng layout mới chỉ có 1 nút
        menuView = inflater.inflate(R.layout.floating_menu, null);

        btnToggle = menuView.findViewById(R.id.btnToggle);

        // Đọc trạng thái cũ
        SharedPreferences prefs = getSharedPreferences("proc_ty_settings", MODE_PRIVATE);
        isScanning = prefs.getBoolean("is_scanning", false);
        updateButtonUI();

        // Sự kiện Bấm nút
        btnToggle.setOnClickListener(v -> {
            isScanning = !isScanning;
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("is_scanning", isScanning);
            editor.apply();
            
            updateButtonUI();
        });

        int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 300;

        // Sự kiện Kéo thả nút (Drag)
        menuView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x; initialY = params.y;
                        initialTouchX = event.getRawX(); initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(menuView, params);
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(menuView, params);
    }

    // Cập nhật giao diện nút mỗi khi Bật/Tắt
    private void updateButtonUI() {
        if (isScanning) {
            btnToggle.setText("ON");
            btnToggle.setTextColor(0xFF00D2FF); // Xanh Neon
            btnToggle.setBackgroundResource(R.drawable.bg_neon_on);
        } else {
            btnToggle.setText("OFF");
            btnToggle.setTextColor(0xFFAAAAAA); // Xám mờ
            btnToggle.setBackgroundResource(R.drawable.bg_neon_off);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (windowManager != null && menuView != null) windowManager.removeView(menuView);
    }
}