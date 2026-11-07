package com.my.procty;

/**
 * Toán học vuốt thuần Java (không phụ thuộc Android) để kiểm thử được trên máy tính.
 *
 * Quy ước: delta = (vị trí mục tiêu - tâm quét) tính bằng px màn hình.
 * gain g  = (px cảnh dịch chuyển) / (px ngón tay vuốt), đo riêng từng trục (có thể âm nếu game đảo trục).
 * Muốn cảnh dịch đúng bằng delta thì ngón tay phải vuốt delta / g  (KHÔNG phải toạ độ tuyệt đối của mục tiêu).
 */
public final class AimMath {
    private AimMath() {}

    public static final double MIN_ABS_GAIN = 0.05;
    public static final double MAX_ABS_GAIN = 20.0;

    public static boolean gainValid(double g) {
        double a = Math.abs(g);
        return !Double.isNaN(g) && a >= MIN_ABS_GAIN && a <= MAX_ABS_GAIN;
    }

    /** g = -shift / drag  (ngón tay vuốt +drag thì cảnh dịch -g*drag). Trả NaN nếu không đáng tin. */
    public static double gainFromShift(double sceneShift, double dragPx) {
        if (Math.abs(dragPx) < 1e-6) return Double.NaN;
        double g = -sceneShift / dragPx;
        return gainValid(g) ? g : Double.NaN;
    }

    public static double median(double[] v, int n) {
        if (n <= 0) return Double.NaN;
        double[] c = java.util.Arrays.copyOf(v, n);
        java.util.Arrays.sort(c);
        return (n % 2 == 1) ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]);
    }

    /**
     * Điểm cuối vuốt = điểm đầu + damp * (delta / g), giới hạn độ dài maxLen và nằm trong màn hình.
     * Trả {endX, endY}.
     */
    public static int[] relativeEnd(int sx, int sy, double dx, double dy, double gx, double gy,
                                    double damp, double maxLen, int screenW, int screenH) {
        double vx = damp * dx / gx;
        double vy = damp * dy / gy;
        double len = Math.hypot(vx, vy);
        if (len > maxLen && len > 0) {
            vx *= maxLen / len;
            vy *= maxLen / len;
        }
        int ex = (int) Math.round(sx + vx);
        int ey = (int) Math.round(sy + vy);
        ex = Math.max(0, Math.min(screenW - 1, ex));
        ey = Math.max(0, Math.min(screenH - 1, ey));
        return new int[]{ex, ey};
    }

    /** Đường vuốt nhiều điểm với vận tốc mượt (smoothstep) thay vì NHẢY 1 phát. Điểm 0 = điểm đầu. */
    public static int[][] easedPath(int sx, int sy, int ex, int ey, int steps) {
        int n = Math.max(1, steps);
        int[][] p = new int[n + 1][2];
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double e = t * t * (3.0 - 2.0 * t);
            p[i][0] = (int) Math.round(sx + (ex - sx) * e);
            p[i][1] = (int) Math.round(sy + (ey - sy) * e);
        }
        return p;
    }
}
