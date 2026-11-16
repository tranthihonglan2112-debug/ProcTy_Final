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

public class SocketClient {
    private static final String TAG = "ProcTy_Socket";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final long RETRY_DELAY_MS = 2000;

    public interface OnResultListener {
        // Sửa ở đây: Thêm tham số w, h
        void onResult(int targetX, int targetY, int w, int h);
    }

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
    private volatile int lastReported = -1;

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

    private void connectLoop() {
        while (!closed) {
            if (!connected) {
                if (!tryConnect()) {
                    sleepQuiet(RETRY_DELAY_MS);
                    continue;
                }
            }
            sleepQuiet(1000);
        }
    }

    private boolean tryConnect() {
        if (host.isEmpty()) {
            report(false, "Chưa nhập IP máy tính");
            return false;
        }
        Network wifi = findWifiNetwork();
        Exception last = null;
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
                int tx = -1, ty = -1, tw = 0, th = 0;
                try {
                    ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                    header.putInt(width).putInt(height).putInt(rowStride).putInt(rawData.length);
                    o.write(header.array());
                    o.write(rawData);
                    o.flush();

                    // Đọc 16 byte thay vì 8 byte
                    byte[] reply = new byte[16];
                    i.readFully(reply);
                    ByteBuffer rb = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN);
                    tx = rb.getInt();
                    ty = rb.getInt();
                    tw = rb.getInt();
                    th = rb.getInt();
                } catch (Exception e) {
                    Log.e(TAG, "Lỗi gửi/nhận: " + e);
                    markDisconnected(s);
                    busy.set(false);
                    return;
                }
                try {
                    // Gọi listener với 4 tham số
                    if (listener != null) listener.onResult(tx, ty, tw, th);
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

    private void report(boolean ok, String msg) {
        if (ok) Log.i(TAG, msg); else Log.w(TAG, msg);
        int state = ok ? 1 : 0;
        if (state == lastReported) return;
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