package com.my.procty;

import android.util.Log;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;

public class SocketClient {
    private static final String TAG = "ProcTy_Socket";
    private Socket socket;
    private DataOutputStream out;
    private DataInputStream in;
    private final String ip;
    private final int port;
    private boolean connected = false;

    public interface OnResultListener {
        void onResult(int targetX, int targetY);
    }

    public SocketClient(String ip, int port) {
        this.ip = ip;
        this.port = port;
        new Thread(this::connect).start();
    }

    private void connect() {
        try {
            socket = new Socket(ip, port);
            socket.setTcpNoDelay(true); // Tắt Nagle để giảm độ trễ
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());
            connected = true;
            Log.i(TAG, "Đã kết nối tới Server " + ip + ":" + port);
        } catch (Exception e) {
            Log.e(TAG, "Lỗi kết nối: " + e.getMessage());
            connected = false;
        }
    }

    // Sửa lại hàm sendFrame để nhận dữ liệu thô (Raw Data)
    public void sendFrame(byte[] rawData, int width, int height, int rowStride, OnResultListener listener) {
        if (!connected) {
            connect();
            if (!connected) return;
        }

        new Thread(() -> {
            try {
                // Gửi thông số ảnh (width, height, rowStride)
                out.writeInt(width);
                out.writeInt(height);
                out.writeInt(rowStride);
                
                // Gửi dữ liệu ảnh thô
                out.writeInt(rawData.length);
                out.write(rawData);
                out.flush();

                // Nhận kết quả từ Server
                int targetX = in.readInt();
                int targetY = in.readInt();
                
                if (listener != null) {
                    listener.onResult(targetX, targetY);
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi gửi/nhận: " + e.getMessage());
                connected = false;
            }
        }).start();
    }

    public void close() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        connected = false;
    }
}