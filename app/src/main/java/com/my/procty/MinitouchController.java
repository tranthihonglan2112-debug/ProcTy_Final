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

    public static void init(final Context context) {
        new Thread(() -> {
            try {
                // 1. Copy file maatouch.dex từ assets vào cache
                File dexFile = new File(context.getCacheDir(), "maatouch.dex");
                if (!dexFile.exists()) {
                    InputStream is = context.getAssets().open("maatouch.dex");
                    FileOutputStream fos = new FileOutputStream(dexFile);
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = is.read(buffer)) > 0) {
                        fos.write(buffer, 0, length);
                    }
                    fos.close();
                    is.close();
                }

                // 2. Dùng Shizuku để copy file vào /data/local/tmp/ và khởi chạy Server
                Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
                Method newProcessMethod = shizukuClass.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                newProcessMethod.setAccessible(true);

                // Copy file
                String copyCmd = "cp " + dexFile.getAbsolutePath() + " /data/local/tmp/maatouch.dex && chmod 644 /data/local/tmp/maatouch.dex";
                Process copyProcess = (Process) newProcessMethod.invoke(null, new String[]{"sh", "-c", copyCmd}, null, null);
                if (copyProcess != null) copyProcess.waitFor();
                Log.d(TAG, "Đã copy maatouch.dex vào /data/local/tmp/");

                // Khởi chạy Server
                String startCmd = "~/rish -c 'export CLASSPATH=/data/local/tmp/maatouch.dex; nohup app_process /data/local/tmp com.shxyke.MaaTouch.App > /dev/null 2>&1 &'";
                newProcessMethod.invoke(null, new String[]{"sh", "-c", startCmd}, null, null);
                
                // 3. Đợi 2 giây cho Server khởi động
                Thread.sleep(2000);

                // 4. Kết nối Socket tới cổng 1111
                boolean connected = false;
                for (int i = 0; i < 3; i++) {
                    try {
                        socket = new Socket("127.0.0.1", 1111);
                        outputStream = socket.getOutputStream();
                        connected = true;
                        break;
                    } catch (Exception e) {
                        Log.w(TAG, "Thử kết nối lần " + (i + 1) + " thất bại: " + e.getMessage());
                        Thread.sleep(1000);
                    }
                }

                if (connected) {
                    Log.i(TAG, "MaaTouch Server đã sẵn sàng!");
                } else {
                    Log.e(TAG, "Lỗi: Không thể kết nối tới MaaTouch Server!");
                }

            } catch (Exception e) {
                Log.e(TAG, "Lỗi khởi tạo: " + e.getMessage());
                e.printStackTrace();
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
            Log.e(TAG, "Lỗi gửi lệnh: " + e.getMessage());
        }
    }
    
    public static void close() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        outputStream = null;
    }
                    }
