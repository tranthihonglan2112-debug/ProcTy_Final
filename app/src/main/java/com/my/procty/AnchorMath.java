package com.my.procty;

/**
 * Toán học toạ độ thuần Java (không phụ thuộc Android) để kiểm thử được trên máy tính,
 * cùng kiểu với AimMath.
 *
 * HỢP ĐỒNG với native-lib.cpp (nativeFindTarget):
 *   - C++ trả về jlong = ((dx + 2000) << 32) | (dy + 2000), hoặc 0 nếu không thấy mục tiêu.
 *   - (dx, dy) = (điểm Neo) - (TÂM ROI), tính bằng px màn hình thật.
 *   - TÂM ROI = (roiW / 2, roiH / 2) tính từ góc trên-trái ROI  (chia nguyên, giống hệt C++).
 *
 * => Toạ độ Neo trên màn hình = (roiX + roiW/2 + dx, roiY + roiH/2 + dy).
 *    KHÔNG phải (roiX + dx, roiY + dy): đó là lỗi lệch (roiW/2, roiH/2) px.
 */
public final class AnchorMath {
    private AnchorMath() {}

    /** Phải trùng với hằng số cộng trong packDelta() của native-lib.cpp. */
    public static final int PACK_OFFSET = 2000;

    /** Giải mã kết quả C++ -> {dx, dy}; trả null nếu result == 0 (không thấy mục tiêu). */
    public static int[] decodeDelta(long packed) {
        if (packed == 0) return null;
        int dx = (int) (packed >> 32) - PACK_OFFSET;
        int dy = (int) (packed & 0xFFFFFFFFL) - PACK_OFFSET;
        return new int[]{dx, dy};
    }

    /** Toạ độ màn hình của TÂM ROI, tính đúng như C++ (roiX/roiY là góc trên-trái vùng quét THỰC SỰ). */
    public static int[] roiCenter(int roiX, int roiY, int roiW, int roiH) {
        return new int[]{roiX + roiW / 2, roiY + roiH / 2};
    }

    /** Toạ độ tuyệt đối (màn hình) của điểm Neo = tâm ROI + delta. */
    public static int[] toScreen(int roiX, int roiY, int roiW, int roiH, int dx, int dy) {
        int[] c = roiCenter(roiX, roiY, roiW, roiH);
        return new int[]{c[0] + dx, c[1] + dy};
    }
}
