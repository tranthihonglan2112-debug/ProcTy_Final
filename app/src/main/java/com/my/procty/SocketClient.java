package com.my.procty;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client TCP gửi khung hình tới server_orb.py.
 *
 * Giao thức (LITTLE-ENDIAN, khớp struct.unpack("<iiii") bên Python):
 *   Phone -> PC : int32 width, int32 height, int32 rowStride, int32 dataLen, dataLen byte
 *   PC -> Phone : int32 x, int32 y
 *
 * Khác bản cũ:
 *  - Bản cũ dùng DataOutputStream.writeInt (BIG-endian) nên server đọc ra data_len rác và ngắt kết nối.
 *  - Tự kết nối lại liên tục (timeout 3s) thay vì thử đúng 1 lần rồi bỏ.
 *  - Mỗi lúc chỉ có 1 gói đang bay -> không bao giờ ghi chồng lên nhau làm lệch luồng.
 *  - Ưu tiên đi qua mạng Wi-Fi (tránh Android đẩy sang 4G khi Wi-Fi "không có Internet").
 */
public class SocketClient {
    private static final String TAG = "ProcTy_Socket";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final long RETRY_DELAY_MS = 2000;

    public interface OnResultListener {
        void onResult(int targetX, int targetY);
    }

    /** Báo trạng thái kết nối (gọi từ luồng nền; chỉ gọi khi trạng thái đổi). */
    public interface OnStatusListener {
        void onStatus(boolean connected, String message);
    }

    private final Context appContext;
    private final String host;
    private final int port;
    private final OnStatusListener statusListener;

    private final Object lock = new Object();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ProcTy-SocketSend");
        t.setDaemon(true);
        return t;
    });
    private final Thread connectThread;

    private Socket socket;
    private DataOutputStream out;
    private DataInputStream in;

    private volatile boolean connected = false;
    private volatile boolean closed = false;
    private volatile int lastReported = -1; // -1: chưa báo, 0: mất kết nối, 1: đã kết nối

    public SocketClient(Context context, String ip, int port, OnStatusListener statusListener) {
        this.appContext = context.getApplicationContext();
        this.host = ip == null ? "" : ip.trim();
        this.port = port;
        this.statusListener = statusListener;
        this.connectThread = new Thread(this::connectLoop, "ProcTy-SocketConnect");
        this.connectThread.setDaemon(true);
        this.connectThread.start();
    }

    public boolean isConnected() {
        return connected && !closed;
    }

    // ----------------------------------------------------------------- kết nối
    private void connectLoop() {
        while (!closed) {
            if (!connected) {
                if (!tryConnect()) {
                    sleepQuiet(RETRY_DELAY_MS);
                    continue;
                }
            }
            sleepQuiet(1000); // đang nối: chỉ kiểm tra lại mỗi giây (sendFrame sẽ tự đánh dấu mất kết nối)
        }
    }

    private boolean tryConnect() {
        if (host.isEmpty()) {
            report(false, "Chưa nhập IP máy tính");
            return false;
        }
        Network wifi = findWifiNetwork();
        Exception last = null;
        // Lần 1: ép đi qua Wi-Fi (nếu có). Lần 2: mạng mặc định (USB tethering, ethernet...).
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt == 1 && wifi == null) break;
            Network bind = (attempt == 0) ? wifi : null;
            Socket s = new Socket();
            try {
                if (bind != null) bind.bindSocket(s);
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                s.setSoTimeout(READ_TIMEOUT_MS);
                DataOutputStream o = new DataOutputStream(
                        new BufferedOutputStream(s.getOutputStream(), 64 * 1024));
                DataInputStream i = new DataInputStream(s.getInputStream());
                synchronized (lock) {
                    if (closed) {
                        closeQuietly(s);
                        return false;
                    }
                    closeQuietly(socket);
                    socket = s;
                    out = o;
                    in = i;
                }
                connected = true;
                report(true, "Đã kết nối PC " + host + ":" + port);
                return true;
            } catch (Exception e) {
                last = e;
                closeQuietly(s);
            }
        }
        report(false, "Không kết nối được " + host + ":" + port
                + (last != null ? " (" + last.getMessage() + ")" : ""));
        return false;
    }

    private Network findWifiNetwork() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Không tìm được mạng Wi-Fi: " + t);
        }
        return null;
    }

    // ------------------------------------------------------------------- gửi
    /**
     * Gửi 1 khung. Trả về true nếu khung được nhận gửi đi; false nếu chưa kết nối
     * hoặc gói trước chưa xong (khung bị bỏ để không chồng gói). Không chặn luồng gọi.
     */
    public boolean sendFrame(final byte[] rawData, final int width, final int height,
                             final int rowStride, final OnResultListener listener) {
        if (closed || !connected) return false;
        if (!busy.compareAndSet(false, true)) return false;

        final DataOutputStream o;
        final DataInputStream i;
        final Socket s;
        synchronized (lock) {
            o = out;
            i = in;
            s = socket;
        }
        if (o == null || i == null) {
            busy.set(false);
            return false;
        }

        try {
            sender.execute(() -> {
                int tx = -1, ty = -1;
                try {
                    ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                    header.putInt(width).putInt(height).putInt(rowStride).putInt(rawData.length);
                    o.write(header.array());
                    o.write(rawData);
                    o.flush();

                    byte[] reply = new byte[8];
                    i.readFully(reply);
                    ByteBuffer rb = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN);
                    tx = rb.getInt();
                    ty = rb.getInt();
                } catch (Exception e) {
                    Log.e(TAG, "Lỗi gửi/nhận: " + e);
                    markDisconnected(s);
                    busy.set(false);
                    return;
                }
                try {
                    if (listener != null) listener.onResult(tx, ty);
                } catch (Throwable t) {
                    Log.e(TAG, "Lỗi xử lý kết quả: " + t);
                } finally {
                    busy.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            busy.set(false);
            return false;
        }
        return true;
    }

    private void markDisconnected(Socket s) {
        synchronized (lock) {
            if (socket == s) {
                connected = false;
                closeQuietly(socket);
                socket = null;
                out = null;
                in = null;
            }
        }
        if (!closed) report(false, "Mất kết nối, đang thử lại...");
    }

    public void close() {
        closed = true;
        connected = false;
        connectThread.interrupt();
        sender.shutdownNow();
        synchronized (lock) {
            closeQuietly(socket);
            socket = null;
            out = null;
            in = null;
        }
    }

    // ------------------------------------------------------------- tiện ích
    private void report(boolean ok, String msg) {
        if (ok) Log.i(TAG, msg); else Log.w(TAG, msg);
        int state = ok ? 1 : 0;
        if (state == lastReported) return; // chỉ báo khi trạng thái đổi, tránh spam Toast
        lastReported = state;
        OnStatusListener l = statusListener;
        if (l != null) {
            try { l.onStatus(ok, msg); } catch (Throwable ignored) {}
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) {}
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
