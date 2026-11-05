package com.my.procty;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Điều khiển cảm ứng qua MaaTouch.
 *
 * QUAN TRỌNG: MaaTouch KHÔNG mở cổng TCP/socket nào (không có cổng 1111).
 * Nó đọc lệnh từ STDIN và in header ("^ <contacts> <maxX> <maxY> <pressure>" và "$ <pid>")
 * ra STDOUT. Vì vậy ta chạy nó bằng Shizuku.newProcess() (quyền shell) rồi ghi lệnh
 * thẳng vào stdin của chính process đó.
 */
public class MinitouchController {
    private static final String TAG = "ProcTy_Minitouch";

    private static final String DEX_ASSET = "maatouch.dex";
    private static final String REMOTE_DIR = "/data/local/tmp";
    private static final String REMOTE_DEX = REMOTE_DIR + "/maatouch.dex";
    private static final String MAIN_CLASS = "com.shxyke.MaaTouch.App";

    private static final long START_TIMEOUT_MS = 6000;
    private static final long RESTART_COOLDOWN_MS = 5000;

    private static final Object LOCK = new Object();

    private static Context appContext;
    private static Process maaProcess;
    private static OutputStream maaStdin;
    private static volatile boolean ready = false;
    private static volatile boolean starting = false;
    private static volatile long lastStartMs = 0;

    /** true khi MaaTouch đang chạy và sẵn sàng nhận lệnh. */
    public static boolean isReady() {
        return ready;
    }

    /** Khởi động MaaTouch (an toàn khi gọi nhiều lần: đang chạy/đang khởi động thì bỏ qua). */
    public static void init(final Context context) {
        synchronized (LOCK) {
            if (context != null) appContext = context.getApplicationContext();
            if (ready || starting || appContext == null) return;
            starting = true;
            lastStartMs = System.currentTimeMillis();
        }
        Thread t = new Thread(() -> {
            try {
                start(appContext);
            } catch (Throwable e) {
                Log.e(TAG, "Lỗi khởi tạo MaaTouch: " + e, e);
            } finally {
                starting = false;
            }
        }, "MaaTouchStarter");
        t.setDaemon(true);
        t.start();
    }

    private static void start(Context ctx) throws Exception {
        // 0. Kiểm tra Shizuku trước, báo lỗi rõ ràng thay vì im lặng
        if (!Shizuku.pingBinder()) {
            Log.e(TAG, "Shizuku chưa chạy (pingBinder = false). Hãy bật Shizuku rồi thử lại.");
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Chưa được cấp quyền Shizuku cho ProcTy.");
            return;
        }

        // 1. Đẩy maatouch.dex vào /data/local/tmp qua STDIN của tiến trình shell.
        //    (Không dùng 'cp' từ thư mục cache của app: user shell không đọc được /data/data/<app>.)
        pushDex(ctx);

        // 2. Chạy MaaTouch; stdin của process này chính là kênh gửi lệnh cảm ứng.
        final Process p = newShellProcess("CLASSPATH=" + REMOTE_DEX
                + " exec app_process " + REMOTE_DIR + " " + MAIN_CLASS);

        final CountDownLatch headerSeen = new CountDownLatch(1);
        final StringBuilder errTail = new StringBuilder();
        pump(p.getInputStream(), "out", line -> {
            // MaaTouch in "^ ..." (thông số) và "$ <pid>" khi đã sẵn sàng
            if (line.startsWith("^") || line.startsWith("$")) headerSeen.countDown();
        });
        pump(p.getErrorStream(), "err", line -> {
            synchronized (errTail) {
                if (errTail.length() < 2000) errTail.append(line).append('\n');
            }
        });

        boolean gotHeader = headerSeen.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!isAlive(p)) {
            synchronized (errTail) {
                Log.e(TAG, "MaaTouch thoát ngay sau khi chạy. stderr:\n" + errTail);
            }
            return;
        }
        if (!gotHeader) {
            Log.w(TAG, "Chưa thấy header của MaaTouch sau " + START_TIMEOUT_MS
                    + "ms nhưng process vẫn sống, coi như sẵn sàng.");
        }

        synchronized (LOCK) {
            maaProcess = p;
            maaStdin = p.getOutputStream();
            ready = true;
        }
        Log.i(TAG, "MaaTouch đã sẵn sàng!");

        // 3. Theo dõi: nếu MaaTouch chết thì đánh dấu để lần sau tự khởi động lại
        Thread watcher = new Thread(() -> {
            int code = -1;
            try {
                code = p.waitFor();
            } catch (InterruptedException ignored) {
            }
            synchronized (LOCK) {
                if (maaProcess == p) {
                    ready = false;
                    maaProcess = null;
                    maaStdin = null;
                }
            }
            Log.w(TAG, "MaaTouch đã thoát (exit=" + code + ")");
        }, "MaaTouchWatcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void pushDex(Context ctx) throws Exception {
        Process p = newShellProcess("cat > " + REMOTE_DEX + " && chmod 644 " + REMOTE_DEX);
        try (InputStream in = ctx.getAssets().open(DEX_ASSET);
             OutputStream out = p.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } // đóng stdin => 'cat' kết thúc

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        InputStream es = p.getErrorStream();
        byte[] b = new byte[1024];
        int n;
        while ((n = es.read(b)) > 0) err.write(b, 0, n);

        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("Đẩy maatouch.dex vào " + REMOTE_DIR + " thất bại (exit="
                    + code + "): " + err.toString("UTF-8"));
        }
        Log.d(TAG, "Đã đẩy maatouch.dex vào " + REMOTE_DEX);
    }

    /** Shizuku 13.x để newProcess ở dạng private nên phải gọi bằng reflection. */
    private static Process newShellProcess(String shellCmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        m.setAccessible(true);
        try {
            return (Process) m.invoke(null, new Object[]{
                    new String[]{"sh", "-c", shellCmd}, null, null});
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            throw (c instanceof Exception) ? (Exception) c : e;
        }
    }

    private static boolean isAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private interface LineListener {
        void onLine(String line);
    }

    private static void pump(final InputStream in, final String tag, final LineListener l) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Log.d(TAG, "[MaaTouch " + tag + "] " + line);
                    if (l != null) l.onLine(line);
                }
            } catch (IOException ignored) {
            }
        }, "MaaTouch-" + tag);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Vuốt từ (startX,startY) tới (endX,endY). Mỗi bước có 'c' (commit) riêng để
     * game nhận được DOWN -> MOVE -> UP tách biệt thay vì dồn hết vào một lần.
     */
    public static void swipe(int startX, int startY, int endX, int endY, int durationMs) {
        if (!ready) {
            maybeRestart();
            return;
        }
        int dur = Math.max(durationMs, 10);
        String cmd = "d 0 " + startX + " " + startY + " 50\n"
                + "c\n"
                + "m 0 " + endX + " " + endY + " 50\n"
                + "c\n"
                + "w " + dur + "\n"
                + "u 0\n"
                + "c\n";
        send(cmd);
    }

    private static void send(String cmd) {
        synchronized (LOCK) {
            if (maaStdin == null) return;
            try {
                maaStdin.write(cmd.getBytes(StandardCharsets.UTF_8));
                maaStdin.flush();
            } catch (IOException e) {
                Log.e(TAG, "Lỗi gửi lệnh (MaaTouch có thể đã chết): " + e.getMessage());
                ready = false;
            }
        }
    }

    private static void maybeRestart() {
        if (starting || appContext == null) return;
        if (System.currentTimeMillis() - lastStartMs < RESTART_COOLDOWN_MS) return;
        init(appContext);
    }

    public static void close() {
        Process p;
        OutputStream os;
        synchronized (LOCK) {
            ready = false;
            p = maaProcess;
            os = maaStdin;
            maaProcess = null;
            maaStdin = null;
        }
        try {
            if (os != null) os.close(); // stdin EOF => MaaTouch tự thoát
        } catch (IOException ignored) {
        }
        if (p != null) p.destroy();
    }
}
