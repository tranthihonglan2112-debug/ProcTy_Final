#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <cstdlib>
#include <vector>
#include <opencv2/opencv.hpp>

#define LOG_TAG "ProcTyNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// ẢNH MẪU: giải mã 1 lần, thu phóng sẵn nhiều tỉ lệ, LƯU Ở NỬA ĐỘ PHÂN GIẢI
// (khớp ở nửa độ phân giải nhanh hơn ~6 lần mà độ chính xác gần như không đổi)
// ---------------------------------------------------------------------------
static std::vector<cv::Mat> g_tpls;        // RGB, đã nhân sẵn DOWNSCALE
static std::vector<double> g_tplScales;    // tỉ lệ so với ảnh mẫu gốc (để log)
static bool g_templateLoaded = false;

static const double DOWNSCALE = 0.5;       // hệ số thu nhỏ khi so khớp
static const double MATCH_THRESHOLD = 0.65;
static const double SCALE_STEP = 1.12;     // bước tỉ lệ ~12%
static const int SCALE_MIN_K = -6;         // 1.12^-6 ~ 0.51x
static const int SCALE_MAX_K = 6;          // 1.12^6  ~ 1.97x (có đúng 1.0x ở k=0)
static const int MIN_TPL_PX = 8;           // template nhỏ hơn mức này (sau thu nhỏ) thì bỏ

static int g_logCounter = 0;

static inline jlong packDelta(int dx, int dy) {
    uint32_t uX = (uint32_t) (dx + 2000);
    uint32_t uY = (uint32_t) (dy + 2000);
    return ((jlong) uX << 32) | uY;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_my_procty_FloatingService_nativeLoadTemplate(JNIEnv *env, jobject /*thiz*/, jbyteArray templateData) {
    g_templateLoaded = false;
    g_tpls.clear();
    g_tplScales.clear();

    if (templateData == nullptr) {          // Java chủ động xoá ảnh mẫu (chuyển sang mode màu)
        return JNI_TRUE;
    }

    jsize len = env->GetArrayLength(templateData);
    jbyte *bytes = env->GetByteArrayElements(templateData, nullptr);
    if (bytes == nullptr || len <= 0) {
        LOGE("Ảnh mẫu rỗng (len=%d)", (int) len);
        return JNI_FALSE;
    }
    std::vector<uchar> data(bytes, bytes + len);
    env->ReleaseByteArrayElements(templateData, bytes, JNI_ABORT);

    cv::Mat bgr = cv::imdecode(data, cv::IMREAD_COLOR);
    if (bgr.empty()) {
        LOGE("imdecode THẤT BẠI (len=%d byte) - file không phải PNG/JPG hợp lệ?", (int) len);
        return JNI_FALSE;
    }
    cv::Mat rgb;
    cv::cvtColor(bgr, rgb, cv::COLOR_BGR2RGB);   // màn hình là RGBA -> so khớp trên RGB

    for (int k = SCALE_MIN_K; k <= SCALE_MAX_K; k++) {
        double s = std::pow(SCALE_STEP, k);
        double f = s * DOWNSCALE;
        cv::Mat t;
        cv::resize(rgb, t, cv::Size(), f, f, cv::INTER_AREA);
        if (t.cols < MIN_TPL_PX || t.rows < MIN_TPL_PX) continue;
        g_tpls.push_back(t);
        g_tplScales.push_back(s);
    }

    g_templateLoaded = !g_tpls.empty();
    LOGD("Nạp ảnh mẫu %dx%d -> %d tỉ lệ (nửa độ phân giải)", rgb.cols, rgb.rows, (int) g_tpls.size());
    return g_templateLoaded ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// buffer  : ByteBuffer RGBA_8888 của TOÀN BỘ khung hình (plane[0].getBuffer())
// fullW/H : kích thước khung hình;  rowStride: byte/hàng THẬT (có thể có padding)
// roiX/Y/W/H : vùng cần quét, toạ độ trong khung hình
//
// LƯU Ý QUAN TRỌNG: GetDirectBufferAddress luôn trả về địa chỉ GỐC của buffer,
// bỏ qua ByteBuffer.position(). Vì vậy KHÔNG cắt ROI bằng position() bên Java;
// phải truyền toạ độ ROI sang đây và cắt bằng cv::Mat (không sao chép dữ liệu).
//
// Trả về: (deltaX+2000)<<32 | (deltaY+2000), delta tính từ TÂM ROI; 0 nếu không thấy.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_my_procty_FloatingService_nativeFindTarget(JNIEnv *env, jobject /*thiz*/, jobject buffer,
                                                     jint fullW, jint fullH, jint rowStride,
                                                     jint roiX, jint roiY, jint roiW, jint roiH,
                                                     jint mode, jint cR, jint cG, jint cB) {
    uint8_t *pixels = (uint8_t *) env->GetDirectBufferAddress(buffer);
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (pixels == nullptr || capacity <= 0) return 0;

    if (roiW <= 0 || roiH <= 0 || roiX < 0 || roiY < 0 ||
        roiX + roiW > fullW || roiY + roiH > fullH) {
        LOGE("ROI ngoài khung hình: roi=(%d,%d %dx%d) khung=%dx%d", roiX, roiY, roiW, roiH, fullW, fullH);
        return 0;
    }
    jlong lastByte = (jlong) (roiY + roiH - 1) * rowStride + (jlong) (roiX + roiW) * 4;
    if (lastByte > capacity) {
        LOGE("ROI vượt quá buffer: cần %lld byte, buffer có %lld", (long long) lastByte, (long long) capacity);
        return 0;
    }

    // Zero-copy: Mat trỏ thẳng vào buffer với step = rowStride THẬT, rồi cắt ROI.
    cv::Mat screen(fullH, fullW, CV_8UC4, pixels, (size_t) rowStride);
    cv::Mat roi = screen(cv::Rect(roiX, roiY, roiW, roiH));

    const int centerX = roiW / 2;
    const int centerY = roiH / 2;

    // ================== MODE 1: LỌC MÀU ==================
    if (mode == 1) {
        long sumX = 0, sumY = 0;
        int count = 0;
        for (int y = 0; y < roiH; y++) {
            const uint8_t *p = roi.ptr<uint8_t>(y);          // RGBA
            for (int x = 0; x < roiW; x++, p += 4) {
                if (std::abs(p[0] - cR) < 30 && std::abs(p[1] - cG) < 30 && std::abs(p[2] - cB) < 30) {
                    count++; sumX += x; sumY += y;
                }
            }
        }
        if ((++g_logCounter % 10) == 0) LOGD("mode1: %d pixel khớp màu (cần >=5)", count);
        if (count >= 5) {
            return packDelta((int) (sumX / count) - centerX, (int) (sumY / count) - centerY);
        }
        return 0;
    }

    // ================== MODE 2: SO KHỚP ẢNH MẪU ==================
    if (mode == 2) {
        if (!g_templateLoaded || g_tpls.empty()) {
            if ((++g_logCounter % 30) == 0) LOGE("mode 2 nhưng CHƯA nạp được ảnh mẫu");
            return 0;
        }

        cv::Mat rgb, small;
        cv::cvtColor(roi, rgb, cv::COLOR_RGBA2RGB);
        cv::resize(rgb, small, cv::Size(), DOWNSCALE, DOWNSCALE, cv::INTER_AREA);

        double bestVal = 0.0, bestScale = 0.0;
        cv::Point bestLoc(0, 0);

        for (size_t i = 0; i < g_tpls.size(); i++) {
            const cv::Mat &t = g_tpls[i];
            if (t.cols >= small.cols || t.rows >= small.rows) continue;   // template to hơn ROI

            cv::Mat result;
            cv::matchTemplate(small, t, result, cv::TM_CCOEFF_NORMED);
            double maxVal;
            cv::Point maxLoc;
            cv::minMaxLoc(result, nullptr, &maxVal, nullptr, &maxLoc);

            if (maxVal > bestVal) {
                bestVal = maxVal;
                bestScale = g_tplScales[i];
                bestLoc = cv::Point(cvRound((maxLoc.x + t.cols * 0.5) / DOWNSCALE),
                                    cvRound((maxLoc.y + t.rows * 0.5) / DOWNSCALE));
            }
        }

        // Log điểm cao nhất (thưa) để biết "gần ngưỡng" hay "hoàn toàn không khớp"
        if ((++g_logCounter % 10) == 0) {
            LOGD("mode2: best=%.3f scale=%.2f (ngưỡng %.2f)", bestVal, bestScale, MATCH_THRESHOLD);
        }

        if (bestVal >= MATCH_THRESHOLD) {
            return packDelta(bestLoc.x - centerX, bestLoc.y - centerY);
        }
    }

    return 0;
}
