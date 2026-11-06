#include <jni.h>
#include <cstdint>
#include <opencv2/opencv.hpp>
#include <vector>

// BIẾN LƯU ẢNH MẪU ĐÃ GIẢI MÃ (Chỉ giải mã 1 lần)
static cv::Mat g_tplMatOriginal;
static std::vector<cv::Mat> g_tplMatsScaled;
static bool g_templateLoaded = false;
static double g_scales[] = {1.0, 0.25}; 

extern "C" JNIEXPORT void JNICALL
Java_com_my_procty_FloatingService_nativeLoadTemplate(JNIEnv *env, jobject thiz, jbyteArray templateData) {
    if (templateData == nullptr) {
        g_templateLoaded = false;
        g_tplMatOriginal.release();
        g_tplMatsScaled.clear();
        return;
    }

    jbyte *tplBytes = env->GetByteArrayElements(templateData, nullptr);
    std::vector<uchar> vecData(tplBytes, tplBytes + env->GetArrayLength(templateData));
    env->ReleaseByteArrayElements(templateData, tplBytes, JNI_ABORT);

    cv::Mat tplMat = cv::imdecode(vecData, cv::IMREAD_COLOR);
    if (tplMat.empty()) {
        g_templateLoaded = false;
        return;
    }

    // SỬA LỖI BGR/RGB: OpenCV load ảnh mặc định là BGR, ta chuyển sang RGB
    cv::cvtColor(tplMat, g_tplMatOriginal, cv::COLOR_BGR2RGB);

    // Giải mã và resize sẵn các scale để tí nữa dùng luôn
    g_tplMatsScaled.clear();
    for (double scale : g_scales) {
        cv::Mat tpl;
        if (scale == 1.0) {
            tpl = g_tplMatOriginal.clone();
        } else {
            cv::resize(g_tplMatOriginal, tpl, cv::Size(), scale, scale, cv::INTER_LINEAR);
        }
        g_tplMatsScaled.push_back(tpl);
    }
    
    g_templateLoaded = true;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_my_procty_FloatingService_nativeFindTarget(JNIEnv *env, jobject thiz, jobject buffer, jint width, jint height, jint rowStride,
                                                     jint mode, jint cR, jint cG, jint cB) {
    uint8_t *pixels = (uint8_t *) env->GetDirectBufferAddress(buffer);
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    
    if (pixels == nullptr || capacity <= 0) return 0;

    int centerX = width / 2;
    int centerY = height / 2;

    // ================== MODE 1: LỌC MÀU ==================
    if (mode == 1) { 
        long sumX = 0, sumY = 0;
        int count = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = y * rowStride + x * 4;
                if (offset + 3 >= capacity) continue;

                // Android là RGBA
                uint8_t r = pixels[offset];
                uint8_t g = pixels[offset + 1];
                uint8_t b = pixels[offset + 2];

                if (std::abs(r - cR) < 30 && std::abs(g - cG) < 30 && std::abs(b - cB) < 30) {
                    count++; sumX += x; sumY += y;
                }
            }
        }

        if (count >= 5) {
            int avgX = (int) (sumX / count);
            int avgY = (int) (sumY / count);
            uint32_t uX = (uint32_t) ((avgX - centerX) + 2000); 
            uint32_t uY = (uint32_t) ((avgY - centerY) + 2000);
            return ((jlong) uX << 32) | uY;
        }
    } 
    // ================== MODE 2: SO KHỚP ẢNH MẪU ==================
    else if (mode == 2 && g_templateLoaded && !g_tplMatsScaled.empty()) {
        cv::Mat screenMat(height, width, CV_8UC4, pixels, rowStride);
        
        cv::Mat screenMatRGB;
        // CHUYỂN ẢNH MÀN HÌNH TỪ RGBA SANG RGB
        cv::cvtColor(screenMat, screenMatRGB, cv::COLOR_RGBA2RGB);

        double bestMatchVal = 0.0;
        cv::Point bestMatchLoc;

        for (size_t i = 0; i < g_tplMatsScaled.size(); i++) {
            cv::Mat& tplMat = g_tplMatsScaled[i];

            if (tplMat.cols >= width || tplMat.rows >= height) continue;

            cv::Mat resultMat;
            // Cả 2 ảnh đều đã là RGB nên so khớp cực chuẩn
            cv::matchTemplate(screenMatRGB, tplMat, resultMat, cv::TM_CCOEFF_NORMED);

            double minVal, maxVal;
            cv::Point minLoc, maxLoc;
            cv::minMaxLoc(resultMat, &minVal, &maxVal, &minLoc, &maxLoc);

            if (maxVal > bestMatchVal) {
                bestMatchVal = maxVal;
                bestMatchLoc = cv::Point(maxLoc.x + (tplMat.cols / 2), maxLoc.y + (tplMat.rows / 2));
            }
        }

        if (bestMatchVal >= 0.65) {
            int matchX = bestMatchLoc.x;
            int matchY = bestMatchLoc.y;

            uint32_t uX = (uint32_t) ((matchX - centerX) + 2000); 
            uint32_t uY = (uint32_t) ((matchY - centerY) + 2000);
            return ((jlong) uX << 32) | uY;
        }
    }

    return 0;
}