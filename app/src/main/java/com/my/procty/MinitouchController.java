package com.my.procty;

import android.content.Context;
import android.util.Log;
import java.io.OutputStream;
import java.net.Socket;

public class MinitouchController {
    private static final String TAG = "ProcTy_Minitouch";
    private static Socket socket = null;
    private static OutputStream outputStream = null;

    public static void init(final Context context) {
        new Thread(() -> {
            try {
                // CHỈ KẾT NỐI TỚI SERVER ĐANG CHẠY (Không khởi chạy nữa)
                boolean connected = false;
                for (int i = 0; i < 3; i++) {
                    try {
                        Thread.sleep(500); 
                        socket = new Socket("127.0.0.1", 1111);
                        outputStream = socket.getOutputStream();
                        connected = true;
                        break;
                    } catch (Exception e) {
                        Log.w(TAG, "Thử kết nối lần " + (i + 1) + " thất bại: " + e.getMessage());
                    }
                }

                if (connected) {
                    Log.i(TAG, "Đã kết nối tới MaaTouch Server thành công!");
                } else {
                    Log.e(TAG, "Lỗi: Không thể kết nối. Hãy bật Server MaaTouch trong Termux trước!");
                }

            } catch (Exception e) {
                Log.e(TAG, "Lỗi khởi tạo: " + e.getMessage());
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