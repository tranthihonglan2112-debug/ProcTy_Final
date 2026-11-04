package com.my.procty;

import android.util.Log;
import java.io.DataOutputStream;
import java.lang.reflect.Method;

public class InputInjector {
    private static final String TAG = "ProcTy_Injector";
    private static Process shizukuProcess = null;
    private static DataOutputStream os = null;

    // Hàm khởi tạo (Được gọi 1 lần trong FloatingService)
    public static void init() {
        if (shizukuProcess != null) return; 
        try {
            Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            Method method = shizukuClass.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            method.setAccessible(true);
            
            shizukuProcess = (Process) method.invoke(null, new String[]{"sh"}, null, null);
            if (shizukuProcess != null) {
                os = new DataOutputStream(shizukuProcess.getOutputStream());
                Log.i(TAG, "Đã mở luồng Shizuku thành công!");
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi khởi tạo Shizuku: " + e.getMessage());
        }
    }

    // Hàm bắn (Sẽ được gọi liên tục khi thấy địch)
    public static void swipe(int startX, int startY, int endX, int endY, int durationMs) {
        if (os == null) init();
        if (os == null) return;

        try {
            String cmd = String.format("input swipe %d %d %d %d %d\n", startX, startY, endX, endY, durationMs);
            os.writeBytes(cmd);
            os.flush(); 
        } catch (Exception e) {
            Log.e(TAG, "Lỗi bơm lệnh: " + e.getMessage());
            os = null;
            shizukuProcess = null;
        }
    }

    public static void snapToTarget(int screenWidth, int screenHeight) {
        int centerX = screenWidth / 2;
        int centerY = screenHeight / 2;
        swipe(centerX, centerY, centerX, centerY + 10, 1); 
    }
}