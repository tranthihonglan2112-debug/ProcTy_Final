package com.my.procty;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Socket;

public class MinitouchController {
    private static final String TAG = "ProcTy_Minitouch";
    private static Socket socket = null;
    private static OutputStream outputStream = null;
    private static Process process = null;

    public static void init(final Context context) {
        // CHẠY TRÊN LUỒNG RIÊNG ĐỂ TRÁNH CHẶN MAIN THREAD
        new Thread(() -> {
            try {
                // 1. Giải nén file minitouch từ assets
                File minitouchFile = new File(context.getFilesDir(), "minitouch");
                if (!minitouchFile.exists()) {
                    InputStream is = context.getAssets().open("minitouch");
                    FileOutputStream fos = new FileOutputStream(minitouchFile);
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = is.read(buffer)) > 0) {
                        fos.write(buffer, 0, length);
                    }
                    fos.close();
                    is.close();
                }

                // 2. Dùng Reflection gọi Shizuku.newProcess (Hàm này bị ẩn từ API 13.x)
                Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
                Method newProcessMethod = shizukuClass.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                newProcessMethod.setAccessible(true);

                // 3. Chạy lệnh chmod +x
                String chmodCmd = "chmod 755 " + minitouchFile.getAbsolutePath();
                Process chmodProcess = (Process) newProcessMethod.invoke(null, new String[]{"sh", "-c", chmodCmd}, null, null);
                if (chmodProcess != null) chmodProcess.waitFor();

                // 4. Khởi chạy Minitouch ngầm
                String startCmd = minitouchFile.getAbsolutePath() + " -i 127.0.0.1";
                process = (Process) newProcessMethod.invoke(null, new String[]{"sh", "-c", startCmd}, null, null);
                
                // 5. Đợi 1 chút rồi kết nối Socket tới cổng 1111
                Thread.sleep(1000); 
                socket = new Socket("127.0.0.1", 1111);
                outputStream = socket.getOutputStream();

                Log.i(TAG, "Minitouch đã sẵn sàng!");
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khởi tạo Minitouch: " + e.getMessage());
                close();
            }
        }).start();
    }

    public static void swipe(int startX, int startY, int endX, int endY, int durationMs) {
        if (outputStream == null) return;
        try {
            String cmd = "d 0 " + startX + " " + startY + " 50\n" +
                         "m 0 " + endX + " " + endY + " 50\n" +
                         "u 0\n" +
                         "c\n";
            outputStream.write(cmd.getBytes());
            outputStream.flush();
        } catch (Exception e) {
            Log.e(TAG, "Lỗi gửi lệnh Minitouch: " + e.getMessage());
        }
    }
    
    public static void close() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        try { if (process != null) process.destroy(); } catch (Exception ignored) {}
        socket = null;
        outputStream = null;
        process = null;
    }
    }
