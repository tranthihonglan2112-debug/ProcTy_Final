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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class SocketClient {
    private static final String TAG = "ProcTy_Socket";
    // LAN: nhanh. Tunnel/Internet (vd: xxx.tun.ply.gg): cần timeout dài hơn.
    private static final int CONNECT_TIMEOUT_LAN_MS = 3000;
    private static final int CONNECT_TIMEOUT_WAN_MS = 10000;
    private static final int READ_TIMEOUT_LAN_MS = 5000;
    private static final int READ_TIMEOUT_WAN_MS = 15000;
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
    private final boolean lan; // true nếu là IP nội bộ -> ưu tiên bind Wi-Fi
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
        // Chấp nhận: "1.2.3.4", "1.2.3.4:12345", "abc.tun.ply.gg", "abc.tun.ply.gg:12345",
        // "tcp://abc.tun.ply.gg:12345", "abc.tun.ply.gg 12345"
        String[] hp = parseEndpoint(ip, port);
        this.host = hp[0];
        this.port = Integer.parseInt(hp[1]);
        this.lan = isPrivateHost(this.host);
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

    /** Tách "host[:port]" an toàn. Trả về {host, port}. */
    static String[] parseEndpoint(String raw, int defaultPort) {
        String h = raw == null ? "" : raw.trim();
        h = h.replaceFirst("(?i)^[a-z][a-z0-9+.-]*://", ""); // bỏ tcp:// http:// ...
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);           // bỏ path nếu có
        int port = defaultPort;
        int sp = h.lastIndexOf(' ');                         // "host 12345"
        if (sp > 0) h = h.substring(0, sp).trim() + ":" + h.substring(sp + 1).trim();
        int colon = h.lastIndexOf(':');
        if (colon > 0 && colon == h.indexOf(':')) {          // đúng 1 dấu ':' -> host:port (không đụng IPv6)
            try {
                int p = Integer.parseInt(h.substring(colon + 1).trim());
                if (p > 0 && p < 65536) port = p;
                h = h.substring(0, colon).trim();
            } catch (NumberFormatException ignored) {}
        }
        return new String[]{h, String.valueOf(port)};
    }

    private static boolean isPrivateHost(String h) {
        if (h == null || h.isEmpty()) return true;
        String l = h.toLowerCase();
        if (l.endsWith(".local") || l.equals("localhost")) return true;
        return l.matches("^(10\\.|192\\.168\\.|127\\.|169\\.254\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.).*");
    }

    private boolean tryConnect() {
        if (host.isEmpty()) {
            report(false, "Chưa nhập IP/domain máy tính");
            return false;
        }
        Network wifi = findWifiNetwork();

        // LAN: thử Wi-Fi trước. Domain/tunnel: dùng mạng mặc định trước (có Internet), Wi-Fi sau.
        List<Network> order = new ArrayList<>();
        if (lan) { if (wifi != null) order.add(wifi); order.add(null); }
        else     { order.add(null); if (wifi != null) order.add(wifi); }

        final int connectTimeout = lan ? CONNECT_TIMEOUT_LAN_MS : CONNECT_TIMEOUT_WAN_MS;
        final int readTimeout = lan ? READ_TIMEOUT_LAN_MS : READ_TIMEOUT_WAN_MS;
        Exception last = null;

        for (Network bind : order) {
            if (closed) return false;
            List<InetAddress> addrs;
            try {
                addrs = resolve(host, bind);
            } catch (Exception e) {
                last = e;
                continue;
            }
            for (InetAddress addr : addrs) {
                Socket s = new Socket();
                try {
                    if (bind != null) bind.bindSocket(s);
                    s.connect(new InetSocketAddress(addr, port), connectTimeout);
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    s.setSoTimeout(readTimeout);
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
        }
        report(false, "Không kết nối được " + host + ":" + port
                + (last != null ? " (" + last.getClass().getSimpleName() + ": " + last.getMessage() + ")" : ""));
        return false;
    }

    /** Phân giải DNS qua đúng mạng (nếu có), ưu tiên IPv4 vì tunnel thường chỉ mở IPv4. */
    private static List<InetAddress> resolve(String h, Network net) throws IOException {
        InetAddress[] all = (net != null) ? net.getAllByName(h) : InetAddress.getAllByName(h);
        List<InetAddress> v4 = new ArrayList<>();
        List<InetAddress> v6 = new ArrayList<>();
        for (InetAddress a : all) (a instanceof Inet4Address ? v4 : v6).add(a);
        v4.addAll(v6);
        return v4;
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