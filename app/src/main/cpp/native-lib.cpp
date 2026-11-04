#include <jni.h>
#include <cstdint>
#include <opencv2/opencv.hpp>

extern "C" JNIEXPORT jlong JNICALL
Java_com_my_procty_FloatingService_nativeFindTarget(JNIEnv *env, jobject thiz, jobject buffer, jint width, jint height, jint rowStride,
                                                     jint mode, jint cR, jint cG, jint cB,
                                                     jbyteArray templateData, jint tplW, jint tplH) {
    uint8_t *pixels = (uint8_t *) env->GetDirectBufferAddress(buffer);
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    
    if (pixels == nullptr || capacity <= 0) return 0;

    // TỰ ĐỘNG LẤY TÂM MÀN HÌNH
    int centerX = (width / 2) - 30;
    int centerY = height / 2;

    // ================== MODE 1: LỌC MÀU ==================
    if (mode == 1) { 
        int boxSize = 300; 
        int startX = centerX - (boxSize / 2);
        int startY = centerY - (boxSize / 2);
        int endX = centerX + (boxSize / 2);
        int endY = centerY + (boxSize / 2);

        if (startX < 0) startX = 0;
        if (startY < 0) startY = 0;
        if (endX > width) endX = width;
        if (endY > height) endY = height;

        long sumX = 0, sumY = 0;
        int count = 0;

        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int offset = y * rowStride + x * 4;
                if (offset + 3 >= capacity) continue;

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
    else if (mode == 2 && templateData != nullptr) {
        cv::Mat screenMat(height, width, CV_8UC4, pixels, rowStride);
        
        // CHUYỂN ẢNH MÀN HÌNH SANG 3 KÊNH MÀU (RGB)
        cv::Mat screenMatRGB;
        cv::cvtColor(screenMat, screenMatRGB, cv::COLOR_RGBA2RGB);
        
        jbyte *tplBytes = env->GetByteArrayElements(templateData, nullptr);
        std::vector<uchar> vecData(tplBytes, tplBytes + env->GetArrayLength(templateData));
        env->ReleaseByteArrayElements(templateData, tplBytes, JNI_ABORT);
        
        cv::Mat tplMatOriginal = cv::imdecode(vecData, cv::IMREAD_COLOR);
        if (tplMatOriginal.empty()) return 0;

        // BẢO HIỂM: Đảm bảo ảnh mẫu luôn là 3 kênh màu (RGB) giống ảnh màn hình
        if (tplMatOriginal.channels() == 4) {
            cv::cvtColor(tplMatOriginal, tplMatOriginal, cv::COLOR_RGBA2RGB);
        } else if (tplMatOriginal.channels() == 1) {
            cv::cvtColor(tplMatOriginal, tplMatOriginal, cv::COLOR_GRAY2RGB);
        }

        // MẢNG THU PHÓNG (ĐÃ SỬA LỖI DẤU PHẨY)
        double scales[] = {1.0, 0.5}; 
        double bestMatchVal = 0.0;
        cv::Point bestMatchLoc;

        for (double scale : scales) {
            cv::Mat tplMat;
            if (scale == 1.0) {
                tplMat = tplMatOriginal;
            } else {
                cv::resize(tplMatOriginal, tplMat, cv::Size(), scale, scale, cv::INTER_LINEAR);
            }

            if (tplMat.cols >= width || tplMat.rows >= height) continue;

            cv::Mat resultMat;
            // SO KHỚP TRÊN ẢNH RGB
            cv::matchTemplate(screenMatRGB, tplMat, resultMat, cv::TM_CCOEFF_NORMED);

            double minVal, maxVal;
            cv::Point minLoc, maxLoc;
            cv::minMaxLoc(resultMat, &minVal, &maxVal, &minLoc, &maxLoc);

            if (maxVal > bestMatchVal) {
                bestMatchVal = maxVal;
                bestMatchLoc = cv::Point(maxLoc.x + (tplMat.cols / 2), maxLoc.y + (tplMat.rows / 2));
            }
        }

        if (bestMatchVal >= 0.5) {
            int matchX = bestMatchLoc.x;
            int matchY = bestMatchLoc.y;

            uint32_t uX = (uint32_t) ((matchX - centerX) + 2000); 
            uint32_t uY = (uint32_t) ((matchY - centerY) + 2000);
            return ((jlong) uX << 32) | uY;
        }
    }

    return 0;
}