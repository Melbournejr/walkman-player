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

static const double EQ10_FREQS[10] = {
    31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
};

struct BiquadFilter64 {
    double b0 = 1.0, b1 = 0.0, b2 = 0.0;
    double a1 = 0.0, a2 = 0.0;
    double z1L = 0.0, z2L = 0.0;
    double z1R = 0.0, z2R = 0.0;

    void reset() {
        z1L = z2L = z1R = z2R = 0.0;
    }

    void setBypass() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0;
    }

    void setHighPass(double fs, double f0, double q) {
        if (f0 <= 0.0 || f0 >= fs * 0.48) { setBypass(); return; }
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

    void setLowPass(double fs, double f0, double q) {
        if (f0 <= 0.0 || f0 >= fs * 0.48) { setBypass(); return; }
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

    void setPeakingEQ(double fs, double f0, double q, double gainDb) {
        if (std::abs(gainDb) < 0.03 || f0 <= 0.0 || f0 >= fs * 0.48) {
            setBypass();
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

    void setLowShelf(double fs, double f0, double q, double gainDb) {
        if (std::abs(gainDb) < 0.03 || f0 <= 0.0 || f0 >= fs * 0.48) {
            setBypass();
            return;
        }
        double A = std::pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * M_PI * f0 / fs;
        double cosw0 = std::cos(w0);
        double sinw0 = std::sin(w0);
        double alpha = sinw0 / (2.0 * q);
        double twoSqrtAAlpha = 2.0 * std::sqrt(A) * alpha;
        double a0 = (A + 1.0) + (A - 1.0) * cosw0 + twoSqrtAAlpha;
        b0 = (A * ((A + 1.0) - (A - 1.0) * cosw0 + twoSqrtAAlpha)) / a0;
        b1 = (2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0)) / a0;
        b2 = (A * ((A + 1.0) - (A - 1.0) * cosw0 - twoSqrtAAlpha)) / a0;
        a1 = (-2.0 * ((A - 1.0) + (A + 1.0) * cosw0)) / a0;
        a2 = ((A + 1.0) + (A - 1.0) * cosw0 - twoSqrtAAlpha) / a0;
    }

    void setHighShelf(double fs, double f0, double q, double gainDb) {
        if (std::abs(gainDb) < 0.03 || f0 <= 0.0 || f0 >= fs * 0.48) {
            setBypass();
            return;
        }
        double A = std::pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * M_PI * f0 / fs;
        double cosw0 = std::cos(w0);
        double sinw0 = std::sin(w0);
        double alpha = sinw0 / (2.0 * q);
        double twoSqrtAAlpha = 2.0 * std::sqrt(A) * alpha;
        double a0 = (A + 1.0) - (A - 1.0) * cosw0 + twoSqrtAAlpha;
        b0 = (A * ((A + 1.0) + (A - 1.0) * cosw0 + twoSqrtAAlpha)) / a0;
        b1 = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0)) / a0;
        b2 = (A * ((A + 1.0) + (A - 1.0) * cosw0 - twoSqrtAAlpha)) / a0;
        a1 = (2.0 * ((A - 1.0) - (A + 1.0) * cosw0)) / a0;
        a2 = ((A + 1.0) - (A - 1.0) * cosw0 - twoSqrtAAlpha) / a0;
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

class AudiophileMasteringChain {
public:
    BiquadFilter64 subsonicCut;       // 20Hz Butterworth High-Pass
    BiquadFilter64 ultrasonicCut1;    // 32kHz DSD Ultrasonic Low-Pass Stage 1
    BiquadFilter64 ultrasonicCut2;    // 32kHz DSD Ultrasonic Low-Pass Stage 2

    // 10-Band Graphic Equalizer độc lập
    BiquadFilter64 eqBands[10];

    // Mega Bass (Sub Shelf + Punch Peak + Mud Cut)
    BiquadFilter64 megaBassSubShelf;
    BiquadFilter64 megaBassPunchPeak;
    BiquadFilter64 megaBassMudCut;

    // Clear Audio+ (Contour Bass + Vocal Presence + Air)
    BiquadFilter64 clearBassFilter;
    BiquadFilter64 clearVocalFilter;
    BiquadFilter64 clearAirFilter;

    // DSEE Ultimate (High-Frequency Harmonic Extractor + Air Shelf)
    BiquadFilter64 dseeExtractorHp;
    BiquadFilter64 dseeAirShelf;

    // Vinyl Processor (Analog Warmth Body + High Roll-off)
    BiquadFilter64 vinylWarmthPeak;
    BiquadFilter64 vinylRolloffShelf;

    double currentFs = 0.0;

    void configure(
        double fs,
        float megaBassLevel,
        bool clearAudio,
        bool dsee,
        bool vinyl,
        const float* eq10
    ) {
        if (std::abs(fs - currentFs) > 1.0) {
            currentFs = fs;
            subsonicCut.setHighPass(fs, 20.0, 0.7071);
            double lpCut = std::min(32000.0, fs * 0.43);
            ultrasonicCut1.setLowPass(fs, lpCut, 0.5412);
            ultrasonicCut2.setLowPass(fs, lpCut, 1.3065);
            dseeExtractorHp.setHighPass(fs, 3600.0, 0.7071);

            subsonicCut.reset();
            ultrasonicCut1.reset();
            ultrasonicCut2.reset();
            for (int i = 0; i < 10; ++i) eqBands[i].reset();
            megaBassSubShelf.reset();
            megaBassPunchPeak.reset();
            megaBassMudCut.reset();
            clearBassFilter.reset();
            clearVocalFilter.reset();
            clearAirFilter.reset();
            dseeExtractorHp.reset();
            dseeAirShelf.reset();
            vinylWarmthPeak.reset();
            vinylRolloffShelf.reset();
        }

        // 1. Cấu hình 10-Band EQ thật (Từng cần gạt tác động đúng tần số của nó)
        for (int i = 0; i < 10; ++i) {
            eqBands[i].setPeakingEQ(fs, EQ10_FREQS[i], 1.414, static_cast<double>(eq10[i]));
        }

        // 2. Cấu hình Mega Bass (Khi > 0: Nâng Sub-bass 55Hz tới +9.5dB, Punch 90Hz tới +4.5dB, cắt đục 230Hz -2.2dB)
        if (megaBassLevel > 0.01f) {
            double lv = static_cast<double>(megaBassLevel);
            megaBassSubShelf.setLowShelf(fs, 55.0, 0.75, lv * 9.5);
            megaBassPunchPeak.setPeakingEQ(fs, 90.0, 1.15, lv * 4.5);
            megaBassMudCut.setPeakingEQ(fs, 230.0, 1.30, -lv * 2.2);
        } else {
            megaBassSubShelf.setBypass();
            megaBassPunchPeak.setBypass();
            megaBassMudCut.setBypass();
        }

        // 3. Cấu hình Clear Audio+ (Đường cong rõ nét + Mở rộng sân khấu Stereo)
        if (clearAudio) {
            clearBassFilter.setPeakingEQ(fs, 65.0, 1.0, 2.6);
            clearVocalFilter.setPeakingEQ(fs, 2600.0, 0.85, 3.2);
            clearAirFilter.setHighShelf(fs, 10500.0, 0.7071, 3.5);
        } else {
            clearBassFilter.setBypass();
            clearVocalFilter.setBypass();
            clearAirFilter.setBypass();
        }

        // 4. Cấu hình DSEE (Khôi phục dải tần cao & hài âm không gian)
        if (dsee) {
            dseeAirShelf.setHighShelf(fs, 8500.0, 0.75, 4.5);
        } else {
            dseeAirShelf.setBypass();
        }

        // 5. Cấu hình Vinyl Processor (Chất âm mộc ấm áp của đĩa than)
        if (vinyl) {
            vinylWarmthPeak.setPeakingEQ(fs, 165.0, 0.85, 3.2);
            vinylRolloffShelf.setHighShelf(fs, 13000.0, 0.7071, -2.2);
        } else {
            vinylWarmthPeak.setBypass();
            vinylRolloffShelf.setBypass();
        }
    }

    inline void processSample(
        double& L,
        double& R,
        bool pureDsd,
        float megaBassLevel,
        bool clearAudio,
        bool dsee,
        bool vinyl,
        bool hasActiveEq,
        double masterVol,
        double leftPan,
        double rightPan,
        double phaseSign
    ) {
        // Lọc nhiễu siêu âm DSD (>32kHz) và khử DC (<20Hz)
        subsonicCut.processStereo(L, R);
        ultrasonicCut1.processStereo(L, R);
        ultrasonicCut2.processStereo(L, R);

        // Nếu Pure DSD Mode BẬT -> Bypass 100% toàn bộ DSP!
        if (!pureDsd) {
            // 1. Áp dụng 10-Band EQ
            if (hasActiveEq) {
                for (int i = 0; i < 10; ++i) {
                    eqBands[i].processStereo(L, R);
                }
            }

            // 2. Áp dụng Mega Bass
            if (megaBassLevel > 0.01f) {
                megaBassSubShelf.processStereo(L, R);
                megaBassPunchPeak.processStereo(L, R);
                megaBassMudCut.processStereo(L, R);
            }

            // 3. Áp dụng Vinyl Processor (Warmth Body + Even-Order Tube Saturation)
            if (vinyl) {
                vinylWarmthPeak.processStereo(L, R);
                vinylRolloffShelf.processStereo(L, R);
                // Bão hòa hài bậc chẵn (Even-Order Harmonic Warmth)
                double driveL = L * 1.15;
                double driveR = R * 1.15;
                L = std::tanh(driveL) + 0.045 * (driveL * driveL) * (L >= 0.0 ? 1.0 : -1.0);
                R = std::tanh(driveR) + 0.045 * (driveR * driveR) * (R >= 0.0 ? 1.0 : -1.0);
            }

            // 4. Áp dụng DSEE (Tái tạo hài âm tần số cao + Air Shelf)
            if (dsee) {
                double hfL = L;
                double hfR = R;
                dseeExtractorHp.processStereo(hfL, hfR);
                // Tạo hài âm bậc cao tự nhiên bổ sung vào tín hiệu gốc
                double excL = std::tanh(hfL * 2.4) * 0.22;
                double excR = std::tanh(hfR * 2.4) * 0.22;
                L += excL;
                R += excR;
                dseeAirShelf.processStereo(L, R);
            }

            // 5. Áp dụng Clear Audio+ (Mở rộng không gian 3D Stereo Mid/Side + Contour)
            if (clearAudio) {
                clearBassFilter.processStereo(L, R);
                clearVocalFilter.processStereo(L, R);
                clearAirFilter.processStereo(L, R);
                double mid = (L + R) * 0.5;
                double side = (L - R) * 0.5 * 1.38; // Mở rộng âm trường rõ rệt
                L = mid * 0.96 + side;
                R = mid * 0.96 - side;
            }
        }

        // Bù biên độ chuẩn SACD (+6dB) + Master Volume + Channel Balance + Phase Invert
        double gain = 1.95 * masterVol * phaseSign;
        L *= (gain * leftPan);
        R *= (gain * rightPan);

        // Soft-Knee Limiter bảo vệ chống vỡ tiếng khi tăng nhiều EQ/Bass
        if (std::abs(L) > 0.92) {
            double s = (L > 0.0) ? 1.0 : -1.0;
            L = s * (0.92 + 0.08 * std::tanh((std::abs(L) - 0.92) / 0.08));
        }
        if (std::abs(R) > 0.92) {
            double s = (R > 0.0) ? 1.0 : -1.0;
            R = s * (0.92 + 0.08 * std::tanh((std::abs(R) - 0.92) / 0.08));
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
        avformat_close_input(&fmtCtx);
        return -2;
    }

    int audioStreamIdx = av_find_best_stream(fmtCtx, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (audioStreamIdx < 0) {
        avformat_close_input(&fmtCtx);
        return -3;
    }

    AVStream* audioStream = fmtCtx->streams[audioStreamIdx];
    const AVCodec* decoder = avcodec_find_decoder(audioStream->codecpar->codec_id);
    if (!decoder) {
        avformat_close_input(&fmtCtx);
        return -4;
    }

    codecCtx = avcodec_alloc_context3(decoder);
    avcodec_parameters_to_context(codecCtx, audioStream->codecpar);
    codecCtx->thread_count = 2;

    if (avcodec_open2(codecCtx, decoder, nullptr) < 0) {
        avcodec_free_context(&codecCtx);
        avformat_close_input(&fmtCtx);
        return -5;
    }

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
        if (swrCtx) swr_free(&swrCtx);
        avcodec_free_context(&codecCtx);
        avformat_close_input(&fmtCtx);
        return -6;
    }

    jclass cbClass = env->GetObjectClass(callbackObj);
    jmethodID onPcmMethod = env->GetMethodID(cbClass, "onNativePcmChunk", "([FIJ)I");
    jmethodID getParamsMethod = env->GetMethodID(cbClass, "getRealtimeDspParams", "([F)I");

    if (startMs > 0) {
        int64_t seekTs = av_rescale_q(startMs, AVRational{1, 1000}, audioStream->time_base);
        av_seek_frame(fmtCtx, audioStreamIdx, seekTs, AVSEEK_FLAG_BACKWARD);
        avcodec_flush_buffers(codecCtx);
    }

    pkt = av_packet_alloc();
    frame = av_frame_alloc();

    const int maxOutFrames = 8192;
    jfloatArray jPcmArray = env->NewFloatArray(maxOutFrames * 2);
    // Đủ 19 phần tử: 9 thông số cơ bản + đủ 10 băng tần EQ (0..9)
    jfloatArray jDspParamsArray = env->NewFloatArray(19);
    float* rawSwrOut = new float[maxOutFrames * 2];

    AudiophileMasteringChain masteringChain;
    int64_t totalFramesRendered = (static_cast<int64_t>(startMs) * targetPcmSampleRate) / 1000;

    bool stopLoop = false;
    while (!stopLoop && av_read_frame(fmtCtx, pkt) >= 0) {
        if (pkt->stream_index == audioStreamIdx) {
            if (avcodec_send_packet(codecCtx, pkt) == 0) {
                while (!stopLoop && avcodec_receive_frame(codecCtx, frame) == 0) {
                    jint ctrlCmd = env->CallIntMethod(callbackObj, getParamsMethod, jDspParamsArray);
                    if (ctrlCmd == -1) {
                        stopLoop = true;
                        break;
                    } else if (ctrlCmd > 0) {
                        int64_t targetTs = av_rescale_q(ctrlCmd, AVRational{1, 1000}, audioStream->time_base);
                        av_seek_frame(fmtCtx, audioStreamIdx, targetTs, AVSEEK_FLAG_BACKWARD);
                        avcodec_flush_buffers(codecCtx);
                        totalFramesRendered = (static_cast<int64_t>(ctrlCmd) * targetPcmSampleRate) / 1000;
                        break;
                    }

                    jfloat dspParams[19];
                    env->GetFloatArrayRegion(jDspParamsArray, 0, 19, dspParams);
                    float masterVol = dspParams[0];
                    bool pureDsd = (dspParams[1] > 0.5f);
                    float megaBass = dspParams[2];
                    bool clearAudio = (dspParams[3] > 0.5f);
                    bool dsee = (dspParams[4] > 0.5f);
                    bool vinyl = (dspParams[5] > 0.5f);
                    float leftPan = dspParams[6];
                    float rightPan = dspParams[7];
                    float phaseSign = dspParams[8];
                    const float* eq10 = &dspParams[9]; // Đủ 10 băng tần 9..18

                    bool hasActiveEq = false;
                    for (int b = 0; b < 10; ++b) {
                        if (std::abs(eq10[b]) > 0.03f) {
                            hasActiveEq = true;
                            break;
                        }
                    }

                    masteringChain.configure(
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
                                L, R, pureDsd, megaBass, clearAudio, dsee, vinyl, hasActiveEq,
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
