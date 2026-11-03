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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import java.io.File;

public class FloatingMenuService extends Service {
    private WindowManager windowManager;
    private View menuView;
    private TextView btnToggle, btnExpand;
    private LinearLayout menuOptions;
    private boolean isExpanded = false;
    private boolean isScanning = false;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        
        // BẮT BUỘC: Phải gọi startForeground để không bị Android kill
        String channelId = "menu_service_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "ProcTy Menu", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("ProcTy Menu Active")
                .setContentText("Menu đang chạy...")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
        startForeground(2, notification); // ID = 2

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        initMenu();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // TỰ ĐỘNG KHỞI CHẠY FLOATINGSERVICE KHI NHẬN ĐƯỢC QUYỀN TỪ MAINACTIVITY
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
        menuView = inflater.inflate(R.layout.floating_menu, null);

        btnToggle = menuView.findViewById(R.id.btnToggle);
        btnExpand = menuView.findViewById(R.id.btnExpand);
        menuOptions = menuView.findViewById(R.id.menuOptions);

        Button btnModeColor = menuView.findViewById(R.id.btnModeColor);
        Button btnModeTemplate = menuView.findViewById(R.id.btnModeTemplate);
        Button btnModeCapture = menuView.findViewById(R.id.btnModeCapture);

        btnExpand.setOnClickListener(v -> {
            if (isExpanded) {
                menuOptions.setVisibility(View.GONE);
                btnExpand.setText("≡");
            } else {
                menuOptions.setVisibility(View.VISIBLE);
                btnExpand.setText("X");
            }
            isExpanded = !isExpanded;
        });

        btnToggle.setOnClickListener(v -> {
            isScanning = !isScanning;
            SharedPreferences prefs = getSharedPreferences("proc_ty_settings", MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            if (isScanning) {
                btnToggle.setText("ON");
                btnToggle.setTextColor(0xFF00D2FF);
                btnToggle.setBackgroundResource(R.drawable.bg_floating_button);
                editor.putBoolean("is_scanning", true);
                Toast.makeText(this, "Đã bật quét!", Toast.LENGTH_SHORT).show();
            } else {
                btnToggle.setText("OFF");
                btnToggle.setTextColor(0xFFFFFFFF);
                btnToggle.setBackgroundResource(R.drawable.bg_floating_button_off);
                editor.putBoolean("is_scanning", false);
            }
            editor.apply();
        });

        btnModeColor.setOnClickListener(v -> {
            getSharedPreferences("proc_ty_settings", MODE_PRIVATE).edit()
                    .putInt("mode", 1)
                    .putInt("color_r", 200).putInt("color_g", 50).putInt("color_b", 50).apply();
            Toast.makeText(this, "Đã chọn: Lọc Màu Đỏ", Toast.LENGTH_SHORT).show();
            selectModeDone();
        });

        btnModeTemplate.setOnClickListener(v -> {
            File file = new File("/storage/emulated/0/Download/head_front.png");
            if (!file.exists()) {
                Toast.makeText(this, "Chưa có ảnh mẫu trong thư mục Downloads!", Toast.LENGTH_LONG).show();
                return;
            }
            getSharedPreferences("proc_ty_settings", MODE_PRIVATE).edit().putInt("mode", 2).apply();
            Toast.makeText(this, "Đã chọn: So khớp Ảnh Mẫu", Toast.LENGTH_SHORT).show();
            selectModeDone();
        });

        btnModeCapture.setOnClickListener(v -> {
            Toast.makeText(this, "Tính năng chụp ảnh đang phát triển...", Toast.LENGTH_SHORT).show();
        });

        int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 300;

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

    private void selectModeDone() {
        menuOptions.setVisibility(View.GONE);
        btnExpand.setText("≡");
        isExpanded = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (windowManager != null && menuView != null) windowManager.removeView(menuView);
    }
}