package com.my.procty;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {
    private static final int OVERLAY_REQ_CODE = 123;
    private static final int MEDIA_PROJECTION_REQ_CODE = 456;
    private MediaProjectionManager projectionManager;

    private Button btnCheckShizuku, btnStartProcTy, btnSaveIp;
    private EditText edtServerIp;
    private LinearLayout mainLayout, loadingLayout;
    private TextView tvLoadingText;
    private Handler handler = new Handler();

    private final Shizuku.OnRequestPermissionResultListener PERMISSION_LISTENER = (requestCode, grantResult) -> {
        runOnUiThread(() -> {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku đã cấp quyền! ⚡", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Quyền Shizuku bị từ chối!", Toast.LENGTH_SHORT).show();
            }
        });
    };

    private final Shizuku.OnBinderReceivedListener BINDER_RECEIVED_LISTENER = () -> {
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) return;
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(0);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Shizuku.addRequestPermissionResultListener(PERMISSION_LISTENER);
        Shizuku.addBinderReceivedListener(BINDER_RECEIVED_LISTENER);

        setContentView(R.layout.activity_main);

        mainLayout = findViewById(R.id.mainLayout);
        loadingLayout = findViewById(R.id.loadingLayout);
        tvLoadingText = findViewById(R.id.tvLoadingText);
        btnCheckShizuku = findViewById(R.id.btnCheckShizuku);
        btnStartProcTy = findViewById(R.id.btnStartProcTy);
        btnSaveIp = findViewById(R.id.btnSaveIp);
        edtServerIp = findViewById(R.id.edtServerIp);

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        boolean isFirstRun = prefs.getBoolean("isFirstRun", true);
        
        // Load IP đã lưu (nếu có)
        String savedIp = prefs.getString("server_ip", "192.168.1.5");
        edtServerIp.setText(savedIp);

        if (isFirstRun) {
            loadingLayout.setVisibility(LinearLayout.VISIBLE);
            mainLayout.setVisibility(LinearLayout.GONE);
            startFakeLoading(prefs);
        } else {
            loadingLayout.setVisibility(LinearLayout.GONE);
            mainLayout.setVisibility(LinearLayout.VISIBLE);
        }

        checkShizukuStatus(false);
        btnCheckShizuku.setOnClickListener(v -> checkShizukuStatus(true));

        // Xử lý nút Lưu IP
        btnSaveIp.setOnClickListener(v -> {
            String ip = edtServerIp.getText().toString().trim();
            if (TextUtils.isEmpty(ip)) {
                Toast.makeText(this, "Vui lòng nhập IP!", Toast.LENGTH_SHORT).show();
                return;
            }
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("server_ip", ip);
            editor.apply();
            Toast.makeText(this, "Đã lưu IP: " + ip, Toast.LENGTH_SHORT).show();
        });

        btnStartProcTy.setOnClickListener(v -> {
            if (!ShizukuChecker.hasShizukuPermission()) {
                Toast.makeText(this, "Chưa kết nối hoặc cấp quyền Shizuku!", Toast.LENGTH_SHORT).show();
                return;
            }
            checkOverlayPermission();
        });
    }

    private void startFakeLoading(SharedPreferences prefs) {
        String[] steps = {
            "[*] Khởi tạo môi trường hệ thống...",
            "[*] Kết nối máy chủ ProcTy ...",
            "[*] Tải module Native C++ (libprocty.so)...",
            "[*] Đồng bộ hóa lõi Shizuku...",
            "[*] Tối ưu hóa tài nguyên...",
            "[*] Bypass Security Check...",
            "[*] Cấp quyền Rootless...",
            "[+] Hoàn tất! Chào mừng Commander."
        };

        handler.postDelayed(new Runnable() {
            int stepIndex = 0;
            @Override
            public void run() {
                if (stepIndex < steps.length) {
                    tvLoadingText.setText(steps[stepIndex]);
                    stepIndex++;
                    handler.postDelayed(this, 1000); 
                } else {
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putBoolean("isFirstRun", false);
                    editor.apply();
                    
                    loadingLayout.setVisibility(LinearLayout.GONE);
                    mainLayout.setVisibility(LinearLayout.VISIBLE);
                }
            }
        }, 800);
    }

    private void checkShizukuStatus(boolean showToastOnSuccess) {
        if (ShizukuChecker.isShizukuRunning()) {
            if (ShizukuChecker.hasShizukuPermission()) {
                if (showToastOnSuccess) {
                    Toast.makeText(this, "Shizuku đang hoạt động! 🟢", Toast.LENGTH_SHORT).show();
                }
            } else {
                Shizuku.requestPermission(0);
            }
        } else {
            if (showToastOnSuccess) Toast.makeText(this, "Chưa bật Shizuku!", Toast.LENGTH_LONG).show();
        }
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_REQ_CODE);
        } else {
            requestScreenCapturePermission();
        }
    }

    private void requestScreenCapturePermission() {
        if (projectionManager != null) {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), MEDIA_PROJECTION_REQ_CODE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_REQ_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                requestScreenCapturePermission();
            } else {
                Toast.makeText(this, "Chưa cấp quyền vẽ đè!", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == MEDIA_PROJECTION_REQ_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent menuIntent = new Intent(this, FloatingMenuService.class);
                menuIntent.putExtra("code", resultCode);
                menuIntent.putExtra("data", data);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(menuIntent);
                } else {
                    startService(menuIntent);
                }
                
                Toast.makeText(this, "ProcTy đã sẵn sàng!", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                Toast.makeText(this, "Cần cấp quyền quay màn hình!", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(PERMISSION_LISTENER);
        Shizuku.removeBinderReceivedListener(BINDER_RECEIVED_LISTENER);
    }
}