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

static const double ISO_EQ_FREQS[10] = {
    31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
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
        b0 = 1.0; b1 = 0.0; b2 = 0.0;
        a1 = 0.0; a2 = 0.0;
    }

    void setLowPass(double fs, double f0, double q) {
        double w0 = 2.0 * M_PI * std::min(f0, fs * 0.45) / fs;
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

    void setPeakingEQ(double fs, double f0, double q, double gainDb) {
        if (std::abs(gainDb) < 0.03 || f0 >= fs * 0.47) {
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

    void setHighShelf(double fs, double f0, double q, double gainDb) {
        if (std::abs(gainDb) < 0.03 || f0 >= fs * 0.47) {
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

/**
 * HỆ THỐNG XỬ LÝ MASTERING 64-BIT THỜI GIAN THỰC:
 * - 10 Bộ lọc Biquad độc lập cho 10 cần Equalizer (31Hz -> 16kHz)
 * - 5 Bộ lọc chuyên biệt cho Mega Bass, Clear Audio+, DSEE Ultimate, Vinyl Processor
 */
class AudiophileMasteringChain {
public:
    BiquadFilter64 subsonicHp;
    BiquadFilter64 ultrasonicLp1;
    BiquadFilter64 ultrasonicLp2;

    // 10-Band Independent Graphic Equalizer
    BiquadFilter64 eqBands[10];

    // Dedicated Sound Mode Filters
    BiquadFilter64 megaBassPunch;   // 55Hz Deep Punch
    BiquadFilter64 megaBassClean;   // 215Hz Anti-mud cut
    BiquadFilter64 clearAudioDip;   // 280Hz Boxiness removal
    BiquadFilter64 clearAudioVocal; // 2400Hz Vocal Presence
    BiquadFilter64 dseePresence;    // 5500Hz Detail Articulation
    BiquadFilter64 dseeAirShelf;    // 10500Hz High-Shelf Air
    BiquadFilter64 vinylWarmBody;   // 145Hz Analog Body
    BiquadFilter64 vinylSweetMid;   // 850Hz Tube Midrange

    double currentFs = 0.0;
    double autoHeadroomAtten = 1.0;

    void configure(double fs, float megaBassLevel, bool clearAudio, bool dsee, bool vinyl, const float* eq10) {
        if (std::abs(fs - currentFs) > 1.0) {
            currentFs = fs;
            subsonicHp.setHighPass(fs, 20.0, 0.7071);
            double lpCut = std::min(32000.0, fs * 0.43);
            ultrasonicLp1.setLowPass(fs, lpCut, 0.5412);
            ultrasonicLp2.setLowPass(fs, lpCut, 1.3065);

            subsonicHp.reset();
            ultrasonicLp1.reset();
            ultrasonicLp2.reset();
            for (int i = 0; i < 10; ++i) eqBands[i].reset();
            megaBassPunch.reset();
            megaBassClean.reset();
            clearAudioDip.reset();
            clearAudioVocal.reset();
            dseePresence.reset();
            dseeAirShelf.reset();
            vinylWarmBody.reset();
            vinylSweetMid.reset();
        }

        // 1. Cấu hình 10 cần Equalizer độc lập 100% (Mỗi cần 1 tần số chuẩn, đáp ứng 1:1 theo mức dB người dùng kéo)
        double maxPositiveEqBoost = 0.0;
        for (int i = 0; i < 10; ++i) {
            double gDb = static_cast<double>(eq10[i]);
            eqBands[i].setPeakingEQ(fs, ISO_EQ_FREQS[i], 1.414, gDb);
            if (gDb > maxPositiveEqBoost) maxPositiveEqBoost = gDb;
        }

        // 2. Cấu hình Mega Bass (0.0 -> 1.0 tương ứng 0.0dB -> +6.0dB tại 55Hz kèm khử đục -1.4dB tại 215Hz)
        double bassBoostDb = static_cast<double>(megaBassLevel) * 6.0;
        if (bassBoostDb > 0.05) {
            megaBassPunch.setPeakingEQ(fs, 55.0, 0.90, bassBoostDb);
            megaBassClean.setPeakingEQ(fs, 215.0, 1.35, -bassBoostDb * 0.22);
        } else {
            megaBassPunch.setBypass();
            megaBassClean.setBypass();
        }

        // 3. Cấu hình Clear Audio+ (Khử âm mũi 280Hz, đẩy giọng hát rõ nét ở 2.4kHz)
        if (clearAudio) {
            clearAudioDip.setPeakingEQ(fs, 280.0, 1.40, -1.8);
            clearAudioVocal.setPeakingEQ(fs, 2400.0, 0.85, 2.2);
        } else {
            clearAudioDip.setBypass();
            clearAudioVocal.setBypass();
        }

        // 4. Cấu hình DSEE Ultimate (Bổ sung chi tiết nhạc cụ ở 5.5kHz và dải Air long lanh trên 10.5kHz)
        if (dsee) {
            dseePresence.setPeakingEQ(fs, 5500.0, 1.00, 1.6);
            dseeAirShelf.setHighShelf(fs, 10500.0, 0.707, 3.2);
        } else {
            dseePresence.setBypass();
            dseeAirShelf.setBypass();
        }

        // 5. Cấu hình Vinyl Processor (Tạo độ ấm dày ở 145Hz & trung âm ngọt mộc mạc ở 850Hz)
        if (vinyl) {
            vinylWarmBody.setPeakingEQ(fs, 145.0, 0.85, 2.0);
            vinylSweetMid.setPeakingEQ(fs, 850.0, 0.75, 1.4);
        } else {
            vinylWarmBody.setBypass();
            vinylSweetMid.setBypass();
        }

        // Tính toán Auto-Headroom thông minh để khi kéo EQ cao hoặc bật hiệu ứng không bao giờ bị vỡ tiếng hay gắt
        double totalEstBoostDb = (maxPositiveEqBoost * 0.65) + (bassBoostDb * 0.55) +
                                 (clearAudio ? 1.0 : 0.0) + (dsee ? 1.2 : 0.0) + (vinyl ? 1.0 : 0.0);
        if (totalEstBoostDb > 1.5) {
            autoHeadroomAtten = std::pow(10.0, -(totalEstBoostDb - 1.5) / 28.0);
        } else {
            autoHeadroomAtten = 1.0;
        }
    }

    inline void processSample(
        double& L,
        double& R,
        bool pureDsd,
        bool clearAudio,
        bool dsee,
        bool vinyl,
        double masterVol,
        double leftPan,
        double rightPan,
        double phaseSign
    ) {
        subsonicHp.processStereo(L, R);
        ultrasonicLp1.processStereo(L, R);
        ultrasonicLp2.processStereo(L, R);

        if (!pureDsd) {
            // Áp dụng bù headroom tự động trước khi qua chuỗi lọc
            L *= autoHeadroomAtten;
            R *= autoHeadroomAtten;

            // 1. Chạy qua toàn bộ 10 cần Equalizer độc lập
            for (int i = 0; i < 10; ++i) {
                eqBands[i].processStereo(L, R);
            }

            // 2. Chạy qua bộ lọc Mega Bass
            megaBassClean.processStereo(L, R);
            megaBassPunch.processStereo(L, R);

            // 3. Chạy qua Clear Audio+ (Lọc tần số + Mở rộng âm trường Holographic Stereo Mid/Side)
            if (clearAudio) {
                clearAudioDip.processStereo(L, R);
                clearAudioVocal.processStereo(L, R);
                double mid = (L + R) * 0.5;
                double side = (L - R) * 0.5 * 1.24; // Mở rộng không gian Stereo 24% rõ rệt
                L = mid + side;
                R = mid - side;
            }

            // 4. Chạy qua Vinyl Processor (Warm Analog Tube Harmonics)
            if (vinyl) {
                vinylWarmBody.processStereo(L, R);
                vinylSweetMid.processStereo(L, R);
                // Hài âm bậc chẵn (Even-order tube warmth) tạo chất âm ấm mộc tự nhiên
                L = L + 0.045 * (L * L) * (L >= 0.0 ? 1.0 : -1.0);
                R = R + 0.045 * (R * R) * (R >= 0.0 ? 1.0 : -1.0);
            }

            // 5. Chạy qua DSEE Ultimate (High-Frequency Harmonic Restoration)
            if (dsee) {
                dseePresence.processStereo(L, R);
                dseeAirShelf.processStereo(L, R);
            }
        }

        // Bù biên độ chuẩn SACD (+4.5dB = 1.68) * Master Volume
        double gain = 1.68 * masterVol * phaseSign;
        L *= (gain * leftPan);
        R *= (gain * rightPan);

        // Soft-Knee Mastering Limiter bảo vệ chống clipping tuyệt đối
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
                    const float* eq10 = &dspParams[9];

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
                                L, R, pureDsd, clearAudio, dsee, vinyl,
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
