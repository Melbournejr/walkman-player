package com.sony.walkmanpro

import android.util.Log

interface NativePcmCallback {
    fun onNativePcmChunk(pcmBuffer: FloatArray, floatSampleCount: Int, currentPositionMs: Long): Int
    fun getRealtimeDspParams(outParams: FloatArray): Int
}

object FFmpegNativeDsdDecoder {
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
        startMs: Int,
        targetPcmSampleRate: Int,
        callback: NativePcmCallback
    ): Int
}
