#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <cstdlib>
#include <vector>
#include <algorithm>
#include <opencv2/opencv.hpp>

#define LOG_TAG "ProcTyNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// ẢNH MẪU: giải mã 1 lần, thu phóng sẵn nhiều tỉ lệ, LƯU Ở NỬA ĐỘ PHÂN GIẢI
// (khớp ở nửa độ phân giải nhanh hơn ~6 lần mà độ chính xác gần như không đổi)
//
// MỚI: nếu PNG có kênh alpha (nền đã xóa), alpha được dùng làm MASK khi matchTemplate,
// nên vùng trong suốt KHÔNG còn bị tính là "màu đen" nữa.
// ---------------------------------------------------------------------------
static std::vector<cv::Mat> g_tpls;        // RGB, đã nhân sẵn DOWNSCALE
static std::vector<cv::Mat> g_masks;       // CV_8UC1 (0/255) cùng kích thước g_tpls[i]; Mat rỗng = không dùng mask
static std::vector<double> g_tplScales;    // tỉ lệ so với ảnh mẫu gốc (để log)
static bool g_templateLoaded = false;
static bool g_hasMask = false;
static bool g_matchFailLogged = false;
static std::vector<cv::Mat> g_tplHist;     // MỚI: histogram HSV (trong mask) của từng tỉ lệ – dùng để phân biệt avatar khác màu
static cv::Mat g_calib[2];                 // MỚI: 2 khung xám nửa độ phân giải để đo tỉ lệ vuốt (hiệu chuẩn)

static const double DOWNSCALE = 0.5;       // hệ số thu nhỏ khi so khớp


static const double MATCH_THRESHOLD = 0.65;

// TM_CCOEFF_NORMED có mask. Nếu bản OpenCV của bạn báo lỗi ở dòng log "matchTemplate lỗi",
// đổi sang cv::TM_CCORR_NORMED (điểm cao hơn hẳn, cần nâng ngưỡng thêm).
static const int MATCH_METHOD = cv::TM_CCOEFF_NORMED;

static const int ALPHA_THRESHOLD = 128;    // alpha > 128 coi là pixel của mục tiêu
static const int MIN_MASK_PX = 20;         // scale nào còn ít pixel mask hơn mức này thì bỏ
static const double SCALE_STEP = 1.12;     // bước tỉ lệ ~12%
static const int SCALE_MIN_K = -6;         // 1.12^-6 ~ 0.51x
static const int SCALE_MAX_K = 6;          // 1.12^6  ~ 1.97x (có đúng 1.0x ở k=0)
static const int MIN_TPL_PX = 8;           // template nhỏ hơn mức này (sau thu nhỏ) thì bỏ
static const double COLOR_MIN_CORREL = 0.5; // MỚI: tương quan histogram HSV tối thiểu giữa ứng viên và ảnh mẫu

// MỚI: histogram HSV 18x8 (H,S) trong vùng mask, chuẩn hoá L1. Ảnh vào là RGB.
static cv::Mat colorHist(const cv::Mat &rgb, const cv::Mat &mask) {
    cv::Mat hsv, hist;
    cv::cvtColor(rgb, hsv, cv::COLOR_RGB2HSV);
    int channels[] = {0, 1};
    int histSize[] = {18, 8};
    float hr[] = {0.f, 180.f}, sr[] = {0.f, 256.f};
    const float *ranges[] = {hr, sr};
    cv::calcHist(&hsv, 1, channels, mask, hist, 2, histSize, ranges);
    cv::normalize(hist, hist, 1.0, 0.0, cv::NORM_L1);
    return hist;
}

static int g_logCounter = 0;

static inline jlong packDelta(int dx, int dy) {
    uint32_t uX = (uint32_t) (dx + 2000);
    uint32_t uY = (uint32_t) (dy + 2000);
    return ((jlong) uX << 32) | uY;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_my_procty_FloatingService_nativeLoadTemplate(JNIEnv *env, jobject /*thiz*/, jbyteArray templateData) {
    g_templateLoaded = false;
    g_hasMask = false;
    g_matchFailLogged = false;
    g_tpls.clear();
    g_masks.clear();
    g_tplScales.clear();
    g_tplHist.clear();

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

    // Giải mã GIỮ NGUYÊN kênh alpha (trước đây IMREAD_COLOR vứt alpha -> nền trong suốt thành đen)
    cv::Mat img = cv::imdecode(data, cv::IMREAD_UNCHANGED);
    if (img.empty()) {
        LOGE("imdecode THẤT BẠI (len=%d byte) - file không phải PNG/JPG hợp lệ?", (int) len);
        return JNI_FALSE;
    }

    cv::Mat bgr, alpha;
    if (img.depth() == CV_8U && img.channels() == 4) {
        cv::cvtColor(img, bgr, cv::COLOR_BGRA2BGR);
        cv::extractChannel(img, alpha, 3);
    } else {
        // Không có alpha (JPG, PNG 3 kênh, ảnh xám, 16-bit...): giải mã như cũ, không dùng mask
        bgr = cv::imdecode(data, cv::IMREAD_COLOR);
        if (bgr.empty()) {
            LOGE("imdecode IMREAD_COLOR THẤT BẠI (len=%d byte)", (int) len);
            return JNI_FALSE;
        }
    }

    cv::Mat rgb;
    cv::cvtColor(bgr, rgb, cv::COLOR_BGR2RGB);   // màn hình là RGBA -> so khớp trên RGB

    // Chỉ dùng mask khi ảnh thật sự có pixel trong suốt
    bool useMask = false;
    if (!alpha.empty()) {
        cv::Mat transparent = alpha < 255;
        useMask = cv::countNonZero(transparent) > 0;
    }

    for (int k = SCALE_MIN_K; k <= SCALE_MAX_K; k++) {
        double s = std::pow(SCALE_STEP, k);
        double f = s * DOWNSCALE;
        cv::Mat t;
        cv::resize(rgb, t, cv::Size(), f, f, cv::INTER_AREA);
        if (t.cols < MIN_TPL_PX || t.rows < MIN_TPL_PX) continue;

        cv::Mat m;                                    // rỗng nếu không dùng mask
        if (useMask) {
            cv::Mat a;
            cv::resize(alpha, a, t.size(), 0, 0, cv::INTER_AREA);
            cv::threshold(a, m, ALPHA_THRESHOLD, 255, cv::THRESH_BINARY);   // CV_8UC1: 0 hoặc 255
            if (cv::countNonZero(m) < MIN_MASK_PX) continue;
        }

        g_tpls.push_back(t);
        g_masks.push_back(m);
        g_tplScales.push_back(s);
        g_tplHist.push_back(colorHist(t, m));
    }

    g_hasMask = useMask;
    g_templateLoaded = !g_tpls.empty();
    LOGD("Nạp ảnh mẫu %dx%d -> %d tỉ lệ (nửa độ phân giải, mask alpha: %s)",
         rgb.cols, rgb.rows, (int) g_tpls.size(), useMask ? "CÓ" : "KHÔNG");
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

    // ================== MODE 2: SO KHỚP ẢNH MẪU (có mask alpha) ==================
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
        int bestIdx = -1;
        cv::Point bestRaw(0, 0);     // MỚI: góc trên-trái ứng viên (nửa độ phân giải)

        for (size_t i = 0; i < g_tpls.size(); i++) {
            const cv::Mat &t = g_tpls[i];
            const cv::Mat &mask = g_masks[i];
            if (t.cols >= small.cols || t.rows >= small.rows) continue;   // template to hơn ROI

            cv::Mat result;
            try {
                if (mask.empty()) {
                    cv::matchTemplate(small, t, result, MATCH_METHOD);
                } else {
                    cv::matchTemplate(small, t, result, MATCH_METHOD, mask);
                }
            } catch (const cv::Exception &e) {
                // Không để exception C++ xuyên qua JNI (sẽ làm app crash)
                if (!g_matchFailLogged) {
                    g_matchFailLogged = true;
                    LOGE("matchTemplate lỗi (mask=%s): %s", mask.empty() ? "không" : "có", e.what());
                }
                continue;
            }

            // Mask có thể sinh NaN/Inf ở vùng phẳng (chia cho 0): vá thành -1 để không thành "điểm cao giả"
            cv::patchNaNs(result, -1.0);
            result.setTo(-1.0, result > 1.01);

            double maxVal;
            cv::Point maxLoc;
            cv::minMaxLoc(result, nullptr, &maxVal, nullptr, &maxLoc);

            if (maxVal > bestVal) {
                bestVal = maxVal;
                bestIdx = (int) i;
                bestRaw = maxLoc;
                bestScale = g_tplScales[i];
                bestLoc = cv::Point(cvRound((maxLoc.x + t.cols * 0.5) / DOWNSCALE),
                                    cvRound((maxLoc.y + t.rows * 0.5) / DOWNSCALE));
            }
        }

        // Log điểm cao nhất (thưa) để biết "gần ngưỡng" hay "hoàn toàn không khớp"
        if ((++g_logCounter % 10) == 0) {
            LOGD("mode2: best=%.3f scale=%.2f (ngưỡng %.2f, mask=%s)",
                 bestVal, bestScale, MATCH_THRESHOLD, g_hasMask ? "có" : "không");
        }

        if (bestVal >= MATCH_THRESHOLD && bestIdx >= 0) {
            // MỚI: cổng màu. Chỉ hình dạng thì avatar khác màu vẫn đạt ~0.7-0.85 -> báo nhầm.
            const cv::Mat &t = g_tpls[bestIdx];
            cv::Rect r(bestRaw.x, bestRaw.y, t.cols, t.rows);
            if ((r & cv::Rect(0, 0, small.cols, small.rows)) == r) {
                double col = cv::compareHist(g_tplHist[bestIdx], colorHist(small(r), g_masks[bestIdx]), cv::HISTCMP_CORREL);
                if (col < COLOR_MIN_CORREL) {
                    if ((++g_logCounter % 10) == 0) LOGD("mode2: bỏ ứng viên vì sai màu (hình=%.2f, màu=%.2f)", bestVal, col);
                    return 0;
                }
            }
            return packDelta(bestLoc.x - centerX, bestLoc.y - centerY);
        }
    }

    return 0;
}


// ---------------------------------------------------------------------------
// MỚI – ĐO TỈ LỆ VUỐT (hiệu chuẩn): g = (px cảnh dịch) / (px ngón tay vuốt).
// nativeCalibStore: lưu khung hiện tại (xám, nửa độ phân giải) vào slot 0 hoặc 1.
// nativeCalibShift: dịch chuyển cảnh slot0 -> slot1 (px MÀN HÌNH THẬT), đo theo 3x2 ô rồi lấy TRUNG VỊ
//                   (chịu được kẻ địch di chuyển / HUD tĩnh; phase-correlation toàn khung thì KHÔNG).
// Trả về ((sx*10 + 200000) << 32) | (sy*10 + 200000); 0 nếu không đo được.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_my_procty_FloatingService_nativeCalibStore(JNIEnv *env, jobject /*thiz*/, jobject buffer,
                                                     jint fullW, jint fullH, jint rowStride, jint slot) {
    if (slot < 0 || slot > 1) return JNI_FALSE;
    uint8_t *pixels = (uint8_t *) env->GetDirectBufferAddress(buffer);
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (pixels == nullptr || capacity < (jlong) (fullH - 1) * rowStride + (jlong) fullW * 4) return JNI_FALSE;
    cv::Mat screen(fullH, fullW, CV_8UC4, pixels, (size_t) rowStride);
    cv::Mat gray, small;
    cv::cvtColor(screen, gray, cv::COLOR_RGBA2GRAY);
    cv::resize(gray, small, cv::Size(), 0.5, 0.5, cv::INTER_AREA);
    small.convertTo(g_calib[slot], CV_32F);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_my_procty_FloatingService_nativeCalibShift(JNIEnv * /*env*/, jobject /*thiz*/) {
    const cv::Mat &a = g_calib[0], &b = g_calib[1];
    if (a.empty() || b.empty() || a.size() != b.size()) return 0;
    const int tilesX = 3, tilesY = 2;
    const int tw = a.cols / tilesX, th = a.rows / tilesY;
    if (tw < 64 || th < 64) return 0;
    cv::Mat win;
    cv::createHanningWindow(win, cv::Size(tw, th), CV_32F);
    std::vector<double> xs, ys;
    for (int ty = 0; ty < tilesY; ty++) {
        for (int tx = 0; tx < tilesX; tx++) {
            cv::Rect r(tx * tw, ty * th, tw, th);
            double resp = 0.0;
            cv::Point2d sh = cv::phaseCorrelate(a(r), b(r), win, &resp);
            if (resp > 0.05) { xs.push_back(sh.x); ys.push_back(sh.y); }
        }
    }
    if (xs.size() < 3) return 0;                       // quá ít ô tin cậy
    std::sort(xs.begin(), xs.end());
    std::sort(ys.begin(), ys.end());
    double sx = xs[xs.size() / 2] * 2.0;               // nửa độ phân giải -> màn hình thật
    double sy = ys[ys.size() / 2] * 2.0;
    jlong px = (jlong) std::llround(sx * 10.0) + 200000;
    jlong py = (jlong) std::llround(sy * 10.0) + 200000;
    return (px << 32) | py;
}
