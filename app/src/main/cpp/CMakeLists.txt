cmake_minimum_required(VERSION 3.22.1)
project("walkman_ffmpeg_jni")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -fPIC")

set(FFMPEG_JNI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
set(FFMPEG_INC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/include)

include_directories(${FFMPEG_INC_DIR})

add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${FFMPEG_JNI_DIR}/libavutil.so)

add_library(swresample SHARED IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${FFMPEG_JNI_DIR}/libswresample.so)

add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${FFMPEG_JNI_DIR}/libavcodec.so)

add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${FFMPEG_JNI_DIR}/libavformat.so)

add_library(
    walkman_ffmpeg_jni
    SHARED
    walkman_ffmpeg_jni.cpp
)

find_library(log-lib log)

target_link_libraries(
    walkman_ffmpeg_jni
    avformat
    avcodec
    swresample
    avutil
    ${log-lib}
)
