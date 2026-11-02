#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <atomic>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>
#include <libavutil/mathematics.h>
}

#define LOG_TAG "WalkmanFFmpegJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// =========================================================================================
// BỘ LỌC BIQUAD 64-BIT DOUBLE PRECISION CHUẨN PHÒNG THU (ROBERT BRISTOW-JOHNSON COOKBOOK)
// Đảm bảo pha tuyến tính mượt, không làm lùng bùng bass, giữ dải mid ngọt và tách lớp nhạc cụ
// =========================================================================================
struct BiquadFilter64 {
    double b0 = 1.0, b1 = 0.0, b2 = 0.0;
    double a1 = 0.0, a2 = 0.0;
    double z1L = 0.0, z2L = 0.0;
    double z1R = 0.0, z2R = 0.0;

    void reset() {
        z1L = z2L = z1R = z2R = 0.0;
    }

    // High-pass Butterworth (Cắt đuôi sub-bass gây ù lùng bùng dưới 22Hz)
    void setHighPass(double fs, double f0, double q) {
        double w0 = 2.0 * M_PI * f0 / fs;
        double cosw0 = std::cos(w0);
        double sinw0 = std::sin(w0);
        double alpha = sinw0 / (2.0 * q);
        double a0 = 1.0 + alpha;
        b0 = ((1.0 + cosw0) * 0.5) / a0;
        b1 = -(1.0 + cosw0) / a0;
        b2 = ((1.0 + cosw0) * 0.5) / a0;
        a1 = (-2.0 * cosw0) / a0;
        a2 = (1.0 - alpha) / a0;
    }

    // Low-pass Butterworth (Cắt sạch nhiễu siêu âm DSD trên 30kHz)
    void setLowPass(double fs, double f0, double q) {
        double w0 = 2.0 * M_PI * f0 / fs;
        double cosw0 = std::cos(w0);
        double sinw0 = std::sin(w0);
        double alpha = sinw0 / (2.0 * q);
        double a0 = 1.0 + alpha;
        b0 = ((1.0 - cosw0) * 0.5) / a0;
        b1 = (1.0 - cosw0) / a0;
        b2 = ((1.0 - cosw0) * 0.5) / a0;
        a1 = (-2.0 * cosw0) / a0;
        a2 = (1.0 - alpha) / a0;
    }

    // Peaking EQ (Dùng cho Tight Punchy Bass @ 62Hz, Sweet Vocal Mid @ 1.6kHz, Air Layering @ 12kHz)
    void setPeakingEQ(double fs, double f0, double q, double gainDb) {
        if (std::abs(gainDb) < 0.05) {
            b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0;
            return;
        }
        double A = std::pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * M_PI * f0 / fs;
        double cosw0 = std::cos(w0);
        double sinw0 = std::sin(w0);
        double alpha = sinw0 / (2.0 * q);
        double a0 = 1.0 + alpha / A;
        b0 = (1.0 + alpha * A) / a0;
        b1 = (-2.0 * cosw0) / a0;
        b2 = (1.0 - alpha * A) / a0;
        a1 = (-2.0 * cosw0) / a0;
        a2 = (1.0 - alpha / A) / a0;
    }

    inline void processStereo(double& L, double& R) {
        double outL = b0 * L + z1L;
        z1L = b1 * L - a1 * outL + z2L;
        z2L = b2 * L - a2 * outL;
        L = outL;

        double outR = b0 * R + z1R;
        z1R = b1 * R - a1 * outR + z2R;
        z2R = b2 * R - a2 * outR;
        R = outR;
    }
};

// Chuỗi Mastering Chain 64-bit cho 2 kênh Stereo
class AudiophileMasteringChain {
public:
    BiquadFilter64 subsonicCut;     // Cắt 20Hz chống ù nền
    BiquadFilter64 mudCutFilter;    // Khử 220Hz (-1.5dB) chống đục bass lấn mid
    BiquadFilter64 tightBassFilter; // Punchy Bass ở 60Hz (Q=1.15 - Gọn và sâu)
    BiquadFilter64 sweetMidFilter;  // Ngọt giọng hát ở 1650Hz (Q=0.80)
    BiquadFilter64 airDetailFilter; // Tách lớp nhạc cụ ở 11500Hz (Q=0.90)
    BiquadFilter64 ultrasonicCut1;  // Lọc siêu âm DSD tầng 1 (31kHz)
    BiquadFilter64 ultrasonicCut2;  // Lọc siêu âm DSD tầng 2 (31kHz)

    double currentFs = 0.0;
    float lastBassLevel = -1.0f;
    bool lastClearAudio = false;
    bool lastDsee = false;
    bool lastVinyl = false;

    void configureIfNeeded(double fs, float megaBassLevel, bool clearAudio, bool dsee, bool vinyl, const float* eq10) {
        bool fsChanged = (std::abs(fs - currentFs) > 1.0);
        if (fsChanged) {
            currentFs = fs;
            subsonicCut.setHighPass(fs, 20.0, 0.7071);
            double lpCut = std::min(31000.0, fs * 0.42);
            ultrasonicCut1.setLowPass(fs, lpCut, 0.5412);
            ultrasonicCut2.setLowPass(fs, lpCut, 1.3065);
            subsonicCut.reset();
            mudCutFilter.reset();
            tightBassFilter.reset();
            sweetMidFilter.reset();
            airDetailFilter.reset();
            ultrasonicCut1.reset();
            ultrasonicCut2.reset();
        }

        double eqLowDb = (eq10[0] + eq10[1]) * 0.5;
        double eqMidDb = (eq10[4] + eq10[5] + eq10[6]) / 3.0;
        double eqHighDb = (eq10[8] + eq10[9]) * 0.5;

        // Bass gọn (Tight Bass @ 60Hz, Q=1.15) kèm khử lùng bùng (Mud cut @ 210Hz)
        double bassDb = (megaBassLevel * 5.5) + (eqLowDb * 0.6);
        tightBassFilter.setPeakingEQ(fs, 60.0, 1.15, bassDb);
        mudCutFilter.setPeakingEQ(fs, 210.0, 1.40, clearAudio ? -1.8 : -0.8);

        // Mid ngọt và rõ nét (1650Hz, Q=0.80)
        double midDb = (clearAudio ? 1.8 : 0.6) + (vinyl ? 0.8 : 0.0) + (eqMidDb * 0.6);
        sweetMidFilter.setPeakingEQ(fs, 1650.0, 0.80, midDb);

        // Dải cao tách bạch và mở rộng không gian (11500Hz)
        double highDb = (dsee ? 2.4 : 0.5) + (eqHighDb * 0.6);
        airDetailFilter.setPeakingEQ(fs, 11500.0, 0.90, highDb);
    }

    inline void processSample(
        double& L,
        double& R,
        bool pureDsd,
        bool clearAudio,
        double masterVol,
        double leftPan,
        double rightPan,
        double phaseSign
    ) {
        // Luôn lọc siêu âm DSD (>31kHz) và DC/Subsonic (<20Hz) để bảo vệ màng loa và giữ nền âm tĩnh
        subsonicCut.processStereo(L, R);
        ultrasonicCut1.processStereo(L, R);
        ultrasonicCut2.processStereo(L, R);

        if (!pureDsd) {
            mudCutFilter.processStereo(L, R);
            tightBassFilter.processStereo(L, R);
            sweetMidFilter.processStereo(L, R);
            airDetailFilter.processStereo(L, R);

            // Tách lớp không gian Mid/Side chính xác (Tăng độ rộng sân khấu mà không làm mỏng giọng hát)
            if (clearAudio) {
                double mid = (L + R) * 0.5;
                double side = (L - R) * 0.5 * 1.18;
                L = mid + side;
                R = mid - side;
            }
        }

        // Bù biên độ chuẩn SACD (+6dB = 1.995) * Master Volume
        double gain = 1.95 * masterVol * phaseSign;
        L *= (gain * leftPan);
        R *= (gain * rightPan);

        // Transparent Mastering Limiter (Chỉ giới hạn mềm khi vượt ngưỡng 0.96, giữ nguyên 100% dải động dưới 0.96)
        if (std::abs(L) > 0.96) {
            double s = (L > 0.0) ? 1.0 : -1.0;
            L = s * (0.96 + 0.04 * std::tanh((std::abs(L) - 0.96) / 0.04));
        }
        if (std::abs(R) > 0.96) {
            double s = (R > 0.0) ? 1.0 : -1.0;
            R = s * (0.96 + 0.04 * std::tanh((std::abs(R) - 0.96) / 0.04));
        }
    }
};

extern "C" JNIEXPORT jstring JNICALL
Java_com_sony_walkmanpro_FFmpegNativeDsdDecoder_nativeGetFFmpegVersion(JNIEnv* env, jclass clazz) {
    char info[256];
    snprintf(
        info, sizeof(info),
        "FFmpeg %s (avformat %u.%u, avcodec %u.%u, swresample %u.%u)",
        av_version_info(),
        LIBAVFORMAT_VERSION_MAJOR, LIBAVFORMAT_VERSION_MINOR,
        LIBAVCODEC_VERSION_MAJOR, LIBAVCODEC_VERSION_MINOR,
        LIBSWRESAMPLE_VERSION_MAJOR, LIBSWRESAMPLE_VERSION_MINOR
    );
    return env->NewStringUTF(info);
}

/**
 * GIẢI MÃ TRỰC TIẾP FILE DSD (.DSF / .DFF / DST) BẰNG THƯ VIỆN C CỦA FFMPEG
 * (libavformat -> libavcodec -> libswresample -> 64-bit Mastering Biquad -> AudioTrack Float32)
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_sony_walkmanpro_FFmpegNativeDsdDecoder_nativeDecodeDsdFile(
    JNIEnv* env,
    jclass clazz,
    jint fd,
    jint startMs,
    jint targetPcmSampleRate,
    jobject callbackObj
) {
    AVFormatContext* fmtCtx = nullptr;
    AVCodecContext* codecCtx = nullptr;
    SwrContext* swrCtx = nullptr;
    AVPacket* pkt = nullptr;
    AVFrame* frame = nullptr;

    char urlPath[64];
    snprintf(urlPath, sizeof(urlPath), "pipe:%d", fd);

    // Thử mở qua đường dẫn /proc/self/fd/<fd> để hỗ trợ Seek hai chiều hoàn hảo cho cả DSF và DFF
    char procFdPath[64];
    snprintf(procFdPath, sizeof(procFdPath), "/proc/self/fd/%d", fd);

    int ret = avformat_open_input(&fmtCtx, procFdPath, nullptr, nullptr);
    if (ret < 0) {
        ret = avformat_open_input(&fmtCtx, urlPath, nullptr, nullptr);
        if (ret < 0) {
            LOGE("avformat_open_input failed: %d", ret);
            return -1;
        }
    }

    if (avformat_find_stream_info(fmtCtx, nullptr) < 0) {
        LOGE("avformat_find_stream_info failed");
        avformat_close_input(&fmtCtx);
        return -2;
    }

    int audioStreamIdx = av_find_best_stream(fmtCtx, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (audioStreamIdx < 0) {
        LOGE("No audio stream found in DSD container");
        avformat_close_input(&fmtCtx);
        return -3;
    }

    AVStream* audioStream = fmtCtx->streams[audioStreamIdx];
    const AVCodec* decoder = avcodec_find_decoder(audioStream->codecpar->codec_id);
    if (!decoder) {
        LOGE("Unsupported codec_id: %d", audioStream->codecpar->codec_id);
        avformat_close_input(&fmtCtx);
        return -4;
    }

    codecCtx = avcodec_alloc_context3(decoder);
    avcodec_parameters_to_context(codecCtx, audioStream->codecpar);
    codecCtx->thread_count = 2;

    if (avcodec_open2(codecCtx, decoder, nullptr) < 0) {
        LOGE("avcodec_open2 failed");
        avcodec_free_context(&codecCtx);
        avformat_close_input(&fmtCtx);
        return -5;
    }

    LOGI("Opened FFmpeg decoder: %s, rate=%d, ch=%d",
         decoder->name, codecCtx->sample_rate, codecCtx->ch_layout.nb_channels);

    // Khởi tạo libswresample xuất ra Stereo 32-bit Float (AV_SAMPLE_FMT_FLT) ở targetPcmSampleRate
    AVChannelLayout outChLayout = AV_CHANNEL_LAYOUT_STEREO;
    AVChannelLayout inChLayout = codecCtx->ch_layout;
    if (inChLayout.nb_channels <= 0) {
        av_channel_layout_default(&inChLayout, 2);
    }

    ret = swr_alloc_set_opts2(
        &swrCtx,
        &outChLayout,
        AV_SAMPLE_FMT_FLT,
        targetPcmSampleRate,
        &inChLayout,
        codecCtx->sample_fmt,
        codecCtx->sample_rate,
        0,
        nullptr
    );

    if (ret < 0 || !swrCtx || swr_init(swrCtx) < 0) {
        LOGE("swr_init failed");
        if (swrCtx) swr_free(&swrCtx);
        avcodec_free_context(&codecCtx);
        avformat_close_input(&fmtCtx);
        return -6;
    }

    // Lấy các method callback sang Kotlin
    jclass cbClass = env->GetObjectClass(callbackObj);
    jmethodID onPcmMethod = env->GetMethodID(cbClass, "onNativePcmChunk", "([FIJ)I");
    jmethodID getParamsMethod = env->GetMethodID(cbClass, "getRealtimeDspParams", "([F)I");

    // Seek ban đầu nếu startMs > 0
    if (startMs > 0) {
        int64_t seekTs = av_rescale_q(startMs, AVRational{1, 1000}, audioStream->time_base);
        av_seek_frame(fmtCtx, audioStreamIdx, seekTs, AVSEEK_FLAG_BACKWARD);
        avcodec_flush_buffers(codecCtx);
    }

    pkt = av_packet_alloc();
    frame = av_frame_alloc();

    const int maxOutFrames = 8192;
    jfloatArray jPcmArray = env->NewFloatArray(maxOutFrames * 2);
    jfloatArray jDspParamsArray = env->NewFloatArray(18);
    float* rawSwrOut = new float[maxOutFrames * 2];

    AudiophileMasteringChain masteringChain;
    int64_t totalFramesRendered = (static_cast<int64_t>(startMs) * targetPcmSampleRate) / 1000;

    bool stopLoop = false;
    while (!stopLoop && av_read_frame(fmtCtx, pkt) >= 0) {
        if (pkt->stream_index == audioStreamIdx) {
            if (avcodec_send_packet(codecCtx, pkt) == 0) {
                while (!stopLoop && avcodec_receive_frame(codecCtx, frame) == 0) {
                    // Kiểm tra lệnh Điều khiển (Stop / Seek / DSP Params) từ Kotlin
                    jint ctrlCmd = env->CallIntMethod(callbackObj, getParamsMethod, jDspParamsArray);
                    if (ctrlCmd == -1) {
                        stopLoop = true;
                        break;
                    } else if (ctrlCmd > 0) {
                        // Người dùng vừa kéo thanh Seekbar tới vị trí ctrlCmd (ms)
                        int64_t targetTs = av_rescale_q(ctrlCmd, AVRational{1, 1000}, audioStream->time_base);
                        av_seek_frame(fmtCtx, audioStreamIdx, targetTs, AVSEEK_FLAG_BACKWARD);
                        avcodec_flush_buffers(codecCtx);
                        totalFramesRendered = (static_cast<int64_t>(ctrlCmd) * targetPcmSampleRate) / 1000;
                        break;
                    }

                    jfloat dspParams[18];
                    env->GetFloatArrayRegion(jDspParamsArray, 0, 18, dspParams);
                    float masterVol = dspParams[0];
                    bool pureDsd = (dspParams[1] > 0.5f);
                    float megaBass = dspParams[2];
                    bool clearAudio = (dspParams[3] > 0.5f);
                    bool dsee = (dspParams[4] > 0.5f);
                    bool vinyl = (dspParams[5] > 0.5f);
                    float leftPan = dspParams[6];
                    float rightPan = dspParams[7];
                    float phaseSign = dspParams[8];
                    const float* eq10 = &dspParams[9]; // 9..17

                    masteringChain.configureIfNeeded(
                        static_cast<double>(targetPcmSampleRate),
                        megaBass, clearAudio, dsee, vinyl, eq10
                    );

                    uint8_t* outPlanes[1] = { reinterpret_cast<uint8_t*>(rawSwrOut) };
                    int convertedFrames = swr_convert(
                        swrCtx,
                        outPlanes,
                        maxOutFrames,
                        const_cast<const uint8_t**>(frame->extended_data),
                        frame->nb_samples
                    );

                    if (convertedFrames > 0) {
                        for (int i = 0; i < convertedFrames; ++i) {
                            double L = static_cast<double>(rawSwrOut[i * 2]);
                            double R = static_cast<double>(rawSwrOut[i * 2 + 1]);

                            masteringChain.processSample(
                                L, R, pureDsd, clearAudio,
                                static_cast<double>(masterVol),
                                static_cast<double>(leftPan),
                                static_cast<double>(rightPan),
                                static_cast<double>(phaseSign)
                            );

                            rawSwrOut[i * 2] = static_cast<float>(L);
                            rawSwrOut[i * 2 + 1] = static_cast<float>(R);
                        }

                        int floatCount = convertedFrames * 2;
                        env->SetFloatArrayRegion(jPcmArray, 0, floatCount, rawSwrOut);
                        totalFramesRendered += convertedFrames;
                        int64_t currentPosMs = (totalFramesRendered * 1000L) / targetPcmSampleRate;

                        jint writeStatus = env->CallIntMethod(
                            callbackObj, onPcmMethod, jPcmArray, floatCount, static_cast<jlong>(currentPosMs)
                        );
                        if (writeStatus < 0) {
                            stopLoop = true;
                            break;
                        }
                    }
                }
            }
        }
        av_packet_unref(pkt);
    }

    delete[] rawSwrOut;
    av_frame_free(&frame);
    av_packet_free(&pkt);
    swr_free(&swrCtx);
    avcodec_free_context(&codecCtx);
    avformat_close_input(&fmtCtx);

    return stopLoop ? 1 : 0;
}
