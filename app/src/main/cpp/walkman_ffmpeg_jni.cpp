#include <jni.h>
#include <algorithm>
#include <array>
#include <cstring>
#include <cerrno>
#include <poll.h>
#include <sys/stat.h>
#include <unistd.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/mathematics.h>
#include <libavutil/intreadwrite.h>
#include <libswresample/swresample.h>
}

namespace {
constexpr int kCancelled = -1;
constexpr int kNoSeek = -2;
constexpr int kFrames = 2048;
constexpr AVRational kMilliseconds{1, 1000};

struct Decoder {
    AVIOContext* io = nullptr;
    AVFormatContext* format = nullptr;
    AVCodecContext* codec = nullptr;
    SwrContext* resampler = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;

    ~Decoder() {
        av_frame_free(&frame);
        av_packet_free(&packet);
        swr_free(&resampler);
        avcodec_free_context(&codec);
        avformat_close_input(&format);
        if (io) av_freep(&io->buffer);
        avio_context_free(&io);
    }
};

struct Input {
    int fd;
    JNIEnv* env;
    jobject callback;
    jmethodID cancelled;
    bool seekable;
    std::array<uint8_t, 92> prefix{};
    size_t prefixSize = 0;

    int64_t dsfDuration() const {
        const uint8_t* data = prefix.data();
        if (prefixSize != prefix.size() || memcmp(data, "DSD ", 4) != 0 ||
            AV_RL64(data + 4) != 28 || memcmp(data + 28, "fmt ", 4) != 0 ||
            AV_RL64(data + 32) != 52 || memcmp(data + 80, "data", 4) != 0) return 0;
        const uint32_t channels = AV_RL32(data + 52);
        const uint32_t frequency = AV_RL32(data + 56);
        const uint64_t samples = AV_RL64(data + 64);
        const uint64_t size = AV_RL64(data + 84);
        if (channels == 0 || frequency == 0 || samples == 0 || samples > INT64_MAX || size <= 12 ||
            (samples - 1) / 8 + 1 > (size - 12) / channels) return 0;
        return av_rescale(samples, 1000, frequency);
    }

    bool isCancelled() const {
        return env->ExceptionCheck() || env->CallBooleanMethod(callback, cancelled) ||
               env->ExceptionCheck();
    }
};

int readInput(void* opaque, uint8_t* data, int size) {
    auto& input = *static_cast<Input*>(opaque);
    while (!input.isCancelled()) {
        if (!input.seekable) {
            pollfd descriptor{input.fd, POLLIN, 0};
            const int ready = poll(&descriptor, 1, 100);
            if (ready == 0 || (ready < 0 && errno == EINTR)) continue;
            if (ready < 0) return AVERROR(errno);
        }
        const ssize_t count = read(input.fd, data, size);
        if (count > 0) {
            const size_t keep = std::min(static_cast<size_t>(count), input.prefix.size() - input.prefixSize);
            memcpy(input.prefix.data() + input.prefixSize, data, keep);
            input.prefixSize += keep;
            return static_cast<int>(count);
        }
        if (count == 0) return AVERROR_EOF;
        if (errno != EINTR && errno != EAGAIN) return AVERROR(errno);
    }
    return AVERROR_EXIT;
}

int64_t seekInput(void* opaque, int64_t offset, int mode) {
    const auto& input = *static_cast<Input*>(opaque);
    if (mode == AVSEEK_SIZE) {
        struct stat statBuffer{};
        return fstat(input.fd, &statBuffer) == 0 && statBuffer.st_size > 0
            ? statBuffer.st_size : AVERROR(ENOSYS);
    }
    const off_t position = lseek(input.fd, offset, mode & ~AVSEEK_FORCE);
    return position < 0 ? AVERROR(errno) : position;
}

int interruptInput(void* opaque) {
    return static_cast<Input*>(opaque)->isCancelled();
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_sony_walkmanpro_FFmpegNativeDsdDecoder_nativeGetFFmpegVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(av_version_info());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sony_walkmanpro_FFmpegNativeDsdDecoder_nativeDecodeDsdFile(
    JNIEnv* env, jclass, jint fd, jobject callback
) {
    const jclass callbackClass = env->GetObjectClass(callback);
    if (!callbackClass) return AVERROR(EINVAL);
    const jmethodID cancelled = env->GetMethodID(callbackClass, "isCancelled", "()Z");
    if (!cancelled) return AVERROR(EINVAL);
    const jmethodID configure = env->GetMethodID(callbackClass, "onNativeFormat", "(IIJ)I");
    if (!configure) return AVERROR(EINVAL);
    const jmethodID control = env->GetMethodID(callbackClass, "getPlaybackCommand", "()I");
    if (!control) return AVERROR(EINVAL);
    const jmethodID write = env->GetMethodID(callbackClass, "onNativePcmChunk", "([FIJ)I");
    if (!write) return AVERROR(EINVAL);
    const jmethodID drain = env->GetMethodID(callbackClass, "onNativeEnd", "()I");
    if (!drain) return AVERROR(EINVAL);

    Input input{fd, env, callback, cancelled, lseek(fd, 0, SEEK_CUR) >= 0};
    Decoder dec;
    constexpr int ioSize = 32768;
    auto* ioBuffer = static_cast<uint8_t*>(av_malloc(ioSize));
    if (!ioBuffer) return AVERROR(ENOMEM);
    dec.io = avio_alloc_context(ioBuffer, ioSize, 0, &input, readInput, nullptr,
                                input.seekable ? seekInput : nullptr);
    if (!dec.io) {
        av_free(ioBuffer);
        return AVERROR(ENOMEM);
    }
    dec.format = avformat_alloc_context();
    if (!dec.format) return AVERROR(ENOMEM);
    dec.format->pb = dec.io;
    dec.format->flags |= AVFMT_FLAG_CUSTOM_IO;
    dec.format->probesize = 512 * 1024;
    dec.format->max_analyze_duration = 2 * AV_TIME_BASE;
    dec.format->interrupt_callback = AVIOInterruptCB{interruptInput, &input};

    int result = avformat_open_input(&dec.format, nullptr, nullptr, nullptr);
    if (result < 0) return result;
    result = avformat_find_stream_info(dec.format, nullptr);
    if (result < 0) return result;
    const AVCodec* decoder = nullptr;
    const int streamIndex = av_find_best_stream(dec.format, AVMEDIA_TYPE_AUDIO, -1, -1, &decoder, 0);
    if (streamIndex < 0) return streamIndex;
    const AVStream* stream = dec.format->streams[streamIndex];
    dec.codec = avcodec_alloc_context3(decoder);
    if (!dec.codec) return AVERROR(ENOMEM);
    result = avcodec_parameters_to_context(dec.codec, stream->codecpar);
    if (result < 0) return result;
    dec.codec->thread_count = 1;
    result = avcodec_open2(dec.codec, decoder, nullptr);
    if (result < 0) return result;
    if (dec.codec->sample_rate <= 0 || dec.codec->ch_layout.nb_channels <= 0) return AVERROR_INVALIDDATA;

    const int sourceChannels = dec.codec->ch_layout.nb_channels;
    const int channels = std::min(sourceChannels, 2);
    const int64_t dsfDuration = input.dsfDuration();
    const int64_t duration = dsfDuration > 0 ? dsfDuration
        : (stream->duration > 0 ? av_rescale_q(stream->duration, stream->time_base, kMilliseconds)
           : (dec.format->duration > 0 ? dec.format->duration / 1000 : 0));
    const int rate = env->CallIntMethod(callback, configure, dec.codec->sample_rate, sourceChannels,
                                        static_cast<jlong>(duration));
    if (env->ExceptionCheck() || rate <= 0) return AVERROR_EXIT;
    AVChannelLayout layout{};
    av_channel_layout_default(&layout, channels);
    AVChannelLayout sourceLayout = dec.codec->ch_layout;
    if (sourceLayout.order == AV_CHANNEL_ORDER_UNSPEC) {
        av_channel_layout_default(&sourceLayout, sourceChannels);
    }
    result = swr_alloc_set_opts2(&dec.resampler, &layout, AV_SAMPLE_FMT_FLT, rate,
                                 &sourceLayout, dec.codec->sample_fmt, dec.codec->sample_rate, 0, nullptr);
    if (result < 0) return result;
    result = swr_init(dec.resampler);
    if (result < 0) return result;

    dec.packet = av_packet_alloc();
    dec.frame = av_frame_alloc();
    const jfloatArray output = env->NewFloatArray(kFrames * channels);
    if (!dec.packet || !dec.frame || !output) return AVERROR(ENOMEM);
    float pcm[kFrames * 2];
    const int64_t start = stream->start_time == AV_NOPTS_VALUE ? 0 : stream->start_time;
    int64_t nextFrame = 0;
    int64_t seekMinFrame = 0;
    bool alignAfterSeek = false;

    auto convert = [&](const uint8_t** samples, int count) -> int {
        do {
            uint8_t* planes[] = {reinterpret_cast<uint8_t*>(pcm)};
            const int frames = swr_convert(dec.resampler, planes, kFrames, samples, count);
            if (frames <= 0) return frames;
            const int skip = static_cast<int>(std::clamp<int64_t>(seekMinFrame - nextFrame, 0, frames));
            const int64_t position = av_rescale_q(nextFrame + skip, AVRational{1, rate}, kMilliseconds);
            nextFrame += frames;
            if (skip < frames) {
                const int size = (frames - skip) * channels;
                env->SetFloatArrayRegion(output, 0, size, pcm + skip * channels);
                if (env->ExceptionCheck()) return AVERROR_EXIT;
                const int status = env->CallIntMethod(callback, write, output, size, static_cast<jlong>(position));
                if (env->ExceptionCheck() || status < 0) return AVERROR_EXIT;
                if (status > 0) return 1; // A seek requested while writing interrupts this buffer.
            }
            if (frames < kFrames) return 0;
            // A non-null pointer with zero samples retrieves queued output without draining the filter.
            count = 0;
        } while (true);
    };

    bool demuxEnded = false;
    bool decodedAudio = false;
    while (!input.isCancelled()) {
        const int command = env->CallIntMethod(callback, control);
        if (env->ExceptionCheck() || command == kCancelled) return AVERROR_EXIT;
        if (command != kNoSeek) {
            if (!input.seekable || command < 0) return AVERROR(ENOSYS);
            const int64_t ts = start + av_rescale_q(command, kMilliseconds, stream->time_base);
            result = av_seek_frame(dec.format, streamIndex, ts, AVSEEK_FLAG_BACKWARD);
            if (result < 0) return result;
            avcodec_flush_buffers(dec.codec);
            swr_close(dec.resampler);
            result = swr_init(dec.resampler);
            if (result < 0) return result;
            seekMinFrame = av_rescale_q(command, kMilliseconds, AVRational{1, rate});
            nextFrame = seekMinFrame;
            alignAfterSeek = true;
            demuxEnded = false;
        }

        result = avcodec_receive_frame(dec.codec, dec.frame);
        if (result == AVERROR_EOF) {
            if (!decodedAudio) return AVERROR_INVALIDDATA;
            result = convert(nullptr, 0);
            if (result == 1) continue;
            if (result < 0) return result;
            const int status = env->CallIntMethod(callback, drain);
            if (env->ExceptionCheck() || status < 0) return AVERROR_EXIT;
            if (status > 0) continue;
            return 0;
        }
        if (result == 0) {
            decodedAudio = true;
            if (alignAfterSeek) {
                const int64_t ts = dec.frame->best_effort_timestamp;
                if (ts != AV_NOPTS_VALUE) {
                    nextFrame = av_rescale_q(ts - start, stream->time_base, AVRational{1, rate});
                }
                alignAfterSeek = false;
            }
            result = convert(const_cast<const uint8_t**>(dec.frame->extended_data), dec.frame->nb_samples);
            av_frame_unref(dec.frame);
            if (result < 0) return result;
            continue;
        }
        if (result != AVERROR(EAGAIN) || demuxEnded) return result;

        do {
            result = av_read_frame(dec.format, dec.packet);
            if (result < 0) break;
            if (dec.packet->stream_index == streamIndex) break;
            av_packet_unref(dec.packet);
        } while (true);
        demuxEnded = result == AVERROR_EOF;
        if (result < 0 && !demuxEnded) return result;
        result = avcodec_send_packet(dec.codec, demuxEnded ? nullptr : dec.packet);
        av_packet_unref(dec.packet);
        if (result < 0) return result;
    }
    return AVERROR_EXIT;
}
