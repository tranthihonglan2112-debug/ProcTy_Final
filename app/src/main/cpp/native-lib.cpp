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

    int centerX = width / 2;
    int centerY = height / 2;

    // ================== MODE 1: LỌC MÀU ==================
    if (mode == 1) { 
        int startX = centerX - 150;
        int startY = centerY - 150;
        int endX = centerX + 150;
        int endY = centerY + 150;

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

                // Lọc theo màu người dùng chọn (sai số 30 đơn vị)
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
    // ================== MODE 2: SO KHỚP ẢNH MẪU (ĐA TỶ LỆ) ==================
    else if (mode == 2 && templateData != nullptr) {
        // 1. Chuyển màn hình game thành ma trận OpenCV
        cv::Mat screenMat(height, width, CV_8UC4, pixels, rowStride);
        
        // 2. Giải mã mảng byte ảnh mẫu thành cv::Mat
        jbyte *tplBytes = env->GetByteArrayElements(templateData, nullptr);
        std::vector<uchar> vecData(tplBytes, tplBytes + env->GetArrayLength(templateData));
        env->ReleaseByteArrayElements(templateData, tplBytes, JNI_ABORT);
        
        cv::Mat tplMatOriginal = cv::imdecode(vecData, cv::IMREAD_COLOR);
        if (tplMatOriginal.empty()) return 0;

        // 3. TẠO 3 TỶ LỆ THU PHÓNG (70%, 100%, 130%)
        double scales[] = {0.7, 1.0, 1.3};
        double bestMatchVal = 0.0;
        cv::Point bestMatchLoc;

        for (double scale : scales) {
            cv::Mat tplMat;
            if (scale == 1.0) {
                tplMat = tplMatOriginal; // Giữ nguyên kích thước gốc
            } else {
                cv::resize(tplMatOriginal, tplMat, cv::Size(), scale, scale, cv::INTER_LINEAR);
            }

            // Nếu ảnh mẫu sau khi thu phóng to hơn màn hình game thì bỏ qua
            if (tplMat.cols >= width || tplMat.rows >= height) continue;

            // Chạy so khớp
            cv::Mat resultMat;
            cv::matchTemplate(screenMat, tplMat, resultMat, cv::TM_CCOEFF_NORMED);

            double minVal, maxVal;
            cv::Point minLoc, maxLoc;
            cv::minMaxLoc(resultMat, &minVal, &maxVal, &minLoc, &maxLoc);

            // Cập nhật kết quả tốt nhất
            if (maxVal > bestMatchVal) {
                bestMatchVal = maxVal;
                // Tính tâm của vật thể khớp
                bestMatchLoc = cv::Point(maxLoc.x + (tplMat.cols / 2), maxLoc.y + (tplMat.rows / 2));
            }
        }

        // 4. KIỂM TRA NGƯỠNG (0.5 = 50%)
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