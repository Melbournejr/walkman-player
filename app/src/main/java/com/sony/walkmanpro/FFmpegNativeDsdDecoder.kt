package com.sony.walkmanpro

import android.util.Log

internal interface NativePcmCallback {
    fun isCancelled(): Boolean
    fun onNativeFormat(decoderRate: Int, sourceChannels: Int, durationMs: Long): Int
    fun getPlaybackCommand(): Int
    fun onNativeEnd(): Int
    fun onNativePcmChunk(pcmBuffer: FloatArray, floatSampleCount: Int, startPositionMs: Long): Int
}

internal object FFmpegNativeDsdDecoder {
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("avutil")
            System.loadLibrary("swresample")
            System.loadLibrary("avcodec")
            System.loadLibrary("avformat")
            System.loadLibrary("walkman_ffmpeg_jni")
            isLoaded = true
            Log.i("WalkmanFFmpeg", "Loaded Native FFmpeg: ${nativeGetFFmpegVersion()}")
        } catch (t: Throwable) {
            isLoaded = false
            Log.e("WalkmanFFmpeg", "Failed to load FFmpeg native libraries", t)
        }
    }

    fun isAvailable(): Boolean = isLoaded

    @JvmStatic
    external fun nativeGetFFmpegVersion(): String

    @JvmStatic
    external fun nativeDecodeDsdFile(
        fd: Int,
        callback: NativePcmCallback
    ): Int
}
