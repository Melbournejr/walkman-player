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

    void setPeakingEQ(double fs, double f0, double q, double gainDb) {
        if (std::abs(gainDb) < 0.02) {
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
    BiquadFilter64 mudCutFilter;
    BiquadFilter64 tightBassFilter;
    BiquadFilter64 sweetMidFilter;
    BiquadFilter64 airDetailFilter;
    BiquadFilter64 ultrasonicCut1;
    BiquadFilter64 ultrasonicCut2;

    double currentFs = 0.0;

    void configureIfNeeded(double fs, float megaBassLevel, bool clearAudio, bool dsee, bool vinyl, const float* eq10) {
        if (std::abs(fs - currentFs) > 1.0) {
            currentFs = fs;
            double lpCut = std::min(32000.0, fs * 0.43);
            ultrasonicCut1.setLowPass(fs, lpCut, 0.5412);
            ultrasonicCut2.setLowPass(fs, lpCut, 1.3065);
            mudCutFilter.reset();
            tightBassFilter.reset();
            sweetMidFilter.reset();
            airDetailFilter.reset();
            ultrasonicCut1.reset();
            ultrasonicCut2.reset();
        }

        double eqLowDb = (eq10[0] + eq10[1] + eq10[2]) / 3.0;
        double eqMidDb = (eq10[3] + eq10[4] + eq10[5] + eq10[6]) / 4.0;
        double eqHighDb = (eq10[7] + eq10[8] + eq10[9]) / 3.0;

        // 1. Mega Bass: Giảm mạnh từ +5.5dB xuống tối đa +2.4dB (Q=0.85 êm, chắc, không đè mid)
        // Khi megaBassLevel = 0 và eqLowDb = 0 -> bassDb = 0.0 (Bypass hoàn toàn)
        double bassDb = (megaBassLevel * 2.4) + (eqLowDb * 0.35);
        tightBassFilter.setPeakingEQ(fs, 62.0, 0.85, bassDb);

        // 2. Clear Audio: Chỉ cắt rất nhẹ -0.5dB ở 210Hz khi bật; khi tắt trả về 0.0dB
        double mudDb = clearAudio ? -0.5 : 0.0;
        mudCutFilter.setPeakingEQ(fs, 210.0, 1.00, mudDb);

        // 3. Mid & Vinyl: Clear Audio chỉ nâng nhẹ +0.6dB, Vinyl chỉ tạo độ ấm nhẹ +0.35dB; khi tắt cả hai trả về 0.0dB
        double midDb = (clearAudio ? 0.6 : 0.0) + (vinyl ? 0.35 : 0.0) + (eqMidDb * 0.35);
        sweetMidFilter.setPeakingEQ(fs, 1500.0, 0.65, midDb);

        // 4. DSEE: Giảm mạnh độ sáng từ +2.4dB xuống +0.8dB (và trừ nhẹ -0.25dB khi bật Vinyl để âm dịu mượt)
        double highDb = (dsee ? 0.8 : 0.0) - (vinyl ? 0.25 : 0.0) + (eqHighDb * 0.35);
        airDetailFilter.setPeakingEQ(fs, 12000.0, 0.70, highDb);
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
        // Lọc nhiễu siêu âm DSD ngoài ngưỡng nghe (>32kHz)
        ultrasonicCut1.processStereo(L, R);
        ultrasonicCut2.processStereo(L, R);

        if (!pureDsd) {
            mudCutFilter.processStereo(L, R);
            tightBassFilter.processStereo(L, R);
            sweetMidFilter.processStereo(L, R);
            airDetailFilter.processStereo(L, R);

            // Clear Audio: Mở rộng không gian rất nhẹ (1.05x thay vì 1.18x) để dễ chịu, không gây gắt
            if (clearAudio) {
                double mid = (L + R) * 0.5;
                double side = (L - R) * 0.5 * 1.05;
                L = mid + side;
                R = mid - side;
            }
        }

        // Hạ hệ số gain DSD từ 1.95 xuống 1.35 để âm thanh thư giãn, không bị "đấm vào tai"
        double gain = 1.35 * masterVol * phaseSign;
        L *= (gain * leftPan);
        R *= (gain * rightPan);

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
