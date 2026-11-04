package com.my.procty;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import rikka.shizuku.Shizuku;

public class MinitouchController {
    private static final String TAG = "ProcTy_Minitouch";
    private static Socket socket = null;
    private static OutputStream outputStream = null;
    private static Process process = null;

    public static void init(Context context) {
        try {
            // 1. Giải nén file minitouch từ assets ra bộ nhớ tạm
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

            // 2. Dùng Shizuku để cấp quyền chạy file (chmod +x)
            // Lưu ý: Vì mình không dùng Root, phải dùng Shizuku để chmod và exec
            String shizukuCmd = "chmod 755 " + minitouchFile.getAbsolutePath();
            Process shizukuProcess = Shizuku.newProcess(new String[]{"sh", "-c", shizukuCmd}, null, null);
            shizukuProcess.waitFor();

            // 3. Khởi chạy Minitouch thông qua STFService (Mặc định cổng 1111)
            String startCmd = minitouchFile.getAbsolutePath() + " -i 127.0.0.1";
            process = Shizuku.newProcess(new String[]{"sh", "-c", startCmd}, null, null);
            
            // 4. Kết nối Socket tới Minitouch (Đợi 1 chút để nó khởi động)
            Thread.sleep(1000); 
            socket = new Socket("127.0.0.1", 1111);
            outputStream = socket.getOutputStream();

            Log.i(TAG, "Minitouch đã sẵn sàng!");
        } catch (Exception e) {
            Log.e(TAG, "Lỗi khởi tạo Minitouch: " + e.getMessage());
            close();
        }
    }

    // Hàm gửi lệnh vuốt cho Minitouch
    public static void swipe(int startX, int startY, int endX, int endY, int durationMs) {
        if (outputStream == null) return;
        try {
            // Cú pháp Minitouch: d (down), m (move), u (up), c (commit)
            String cmd = "d 0 " + startX + " " + startY + " 50\n" + // Chạm xuống
                         "m 0 " + endX + " " + endY + " 50\n" +   // Di chuyển
                         "u 0\n" +                                 // Nhấc lên
                         "c\n";                                    // Thực thi
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
