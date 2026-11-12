package com.my.procty;

import android.graphics.Bitmap;
import android.util.Log;
import java.io.ByteArrayOutputStream;
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
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());
            connected = true;
            Log.i(TAG, "Đã kết nối tới Server " + ip + ":" + port);
        } catch (Exception e) {
            Log.e(TAG, "Lỗi kết nối: " + e.getMessage());
            connected = false;
        }
    }

    public void sendFrame(Bitmap bitmap, int roiX, int roiY, int roiW, int roiH, OnResultListener listener) {
        if (!connected) {
            connect();
            if (!connected) return;
        }

        new Thread(() -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] data = baos.toByteArray();

                out.writeInt(data.length);
                out.write(data);
                out.flush();

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