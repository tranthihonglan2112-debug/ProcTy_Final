cmake_minimum_required(VERSION 3.22.1)
project("procty")

# BÁO CHO CMAKE BIẾT TÌM THƯ VIỆN OPENCV Ở ĐÂU
include_directories(${CMAKE_SOURCE_DIR})

# Khai báo thư viện OpenCV
add_library(lib_opencv SHARED IMPORTED)
set_target_properties(lib_opencv PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopencv_java4.so)

add_library(
    procty
    SHARED
    native-lib.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    procty
    lib_opencv # Link OpenCV vào project
    ${log-lib}
)