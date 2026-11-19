package com.sony.walkmanpro

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.sony.walkmanpro.audio.dsp.DspSettings
import com.sony.walkmanpro.audio.dsp.PlaybackDsp
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal class FiioDirectDsdEngine(
    private val context: Context,
    private val settings: () -> DspSettings,
    private val onFinished: (position: Int, error: String?) -> Unit
) {
    data class PcmOutput(val decoderRate: Int, val sampleRate: Int, val sourceChannels: Int)

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Walkman DSD") }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var current: Session? = null
    @Volatile var volume: Float = 0.75f
    val isPlaying: Boolean get() = current?.playing == true
    val positionMs: Int get() = current?.positionMs ?: 0
    val durationMs: Int get() = current?.durationMs ?: 0
    val output: PcmOutput? get() = current?.output
    val routedDevice: AudioDeviceInfo? get() = runCatching { current?.track?.routedDevice }.getOrNull()

    fun start(uri: Uri, startMs: Int, play: Boolean) {
        stop()
        val session = Session(startMs, play)
        current = session
        worker.execute { decode(uri, session) }
    }

    fun setPlaying(play: Boolean) {
        current?.let { session ->
            synchronized(session.monitor) {
                session.playing = play
                session.monitor.notifyAll()
            }
        }
    }

    fun seekTo(positionMs: Int) {
        current?.let { session ->
            synchronized(session.monitor) {
                val target = if (session.durationMs > 0) positionMs.coerceIn(0, session.durationMs)
                    else positionMs.coerceAtLeast(0)
                session.positionMs = target
                session.seek.set(target)
                session.monitor.notifyAll()
            }
        }
    }

    fun stop() {
        val session = current ?: return
        current = null
        synchronized(session.monitor) {
            session.cancelled = true
            session.monitor.notifyAll()
        }
    }

    fun release() {
        stop()
        worker.shutdown()
    }

    private class Session(start: Int, play: Boolean) {
        val monitor = java.lang.Object()
        val seek = AtomicInteger(if (start > 0) start else NO_SEEK)
        @Volatile var cancelled = false
        @Volatile var playing = play
        @Volatile var positionMs = start
        @Volatile var durationMs = 0
        @Volatile var track: AudioTrack? = null
        @Volatile var output: PcmOutput? = null
    }

    private fun decode(uri: Uri, session: Session) {
        if (session.cancelled) return
        var problem: String? = null
        try {
            check(FFmpegNativeDsdDecoder.isAvailable()) { "FFmpeg JNI chưa được nạp." }
            val callback = OutputCallback(session)
            val descriptor = checkNotNull(context.contentResolver.openFileDescriptor(uri, "r")) {
                "Không mở được tệp DSD."
            }
            val result = descriptor.use { FFmpegNativeDsdDecoder.nativeDecodeDsdFile(it.fd, callback) }
            if (!session.cancelled) {
                check(result == 0) { "Giải mã hoặc tua DSD thất bại ($result)." }
            }
        } catch (e: Exception) {
            problem = e.localizedMessage ?: "Không thể phát DSD."
        } finally {
            val track = session.track
            session.track = null
            track?.release()
        }
        main.post {
            if (current === session) {
                val seek = session.seek.get()
                if (problem == null && seek != NO_SEEK) start(uri, seek, session.playing)
                else {
                    current = null
                    onFinished(session.positionMs, problem)
                }
            }
        }
    }

    private inner class OutputCallback(private val session: Session) : NativePcmCallback {
        private lateinit var dsp: PlaybackDsp
        private var rate = 0
        private var channels = 0
        private var anchorMs = 0L
        private var written = 0L
        private var baseHead = 0L
        private var appliedVolume = -1f
        private var anchored = false

        override fun isCancelled() = session.cancelled

        override fun onNativeFormat(decoderRate: Int, sourceChannels: Int, durationMs: Long): Int {
            if (session.cancelled) return CANCELLED
            channels = sourceChannels.coerceAtMost(2)
            val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val family = if (decoderRate % 44_100 == 0) 44_100 else 48_000
            val preferred = if (decoderRate >= family * 16) family * 4 else family * 2
            for (candidate in listOf(preferred, family * 2, family, 48_000, 44_100).distinct()) {
                val minimum = AudioTrack.getMinBufferSize(candidate, mask, AudioFormat.ENCODING_PCM_FLOAT)
                if (minimum <= 0) continue
                val track = runCatching {
                    AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(candidate).setChannelMask(mask)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                        .setBufferSizeInBytes(maxOf(minimum * 2, candidate * channels * 4 / 12))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                }.getOrNull() ?: continue
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    track.release()
                    continue
                }
                session.track = track
                rate = track.sampleRate
                dsp = PlaybackDsp(rate, channels)
                session.durationMs = durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                session.output = PcmOutput(decoderRate, rate, sourceChannels)
                return rate
            }
            error("Thiết bị không mở được AudioTrack PCM float.")
        }

        override fun getPlaybackCommand(): Int {
            val track = checkNotNull(session.track)
            val state = awaitPlayback(track)
            if (state == CANCELLED) return CANCELLED
            val target = session.seek.getAndSet(NO_SEEK)
            if (target != NO_SEEK) {
                track.pause()
                track.flush()
                written = 0
                anchored = false
                dsp.reset()
            }
            return target
        }

        override fun onNativePcmChunk(pcmBuffer: FloatArray, floatSampleCount: Int, startPositionMs: Long): Int {
            val track = checkNotNull(session.track)
            if (!anchored) {
                anchorMs = startPositionMs
                baseHead = head(track)
                anchored = true
            }
            dsp.processInterleaved(pcmBuffer, floatSampleCount, settings())
            var offset = 0
            var deadline = SystemClock.elapsedRealtime() + 2_000
            while (offset < floatSampleCount) {
                val start = SystemClock.elapsedRealtime()
                val state = awaitPlayback(track)
                deadline += SystemClock.elapsedRealtime() - start
                if (state != 0) return state
                if (volume != appliedVolume) {
                    appliedVolume = volume
                    track.setVolume(appliedVolume)
                }
                val count = track.write(pcmBuffer, offset, floatSampleCount - offset, AudioTrack.WRITE_NON_BLOCKING)
                check(count >= 0 && count % channels == 0) { "AudioTrack lỗi ($count)." }
                offset += count
                written += count / channels
                updatePosition(track)
                if (count > 0) deadline = SystemClock.elapsedRealtime() + 2_000
                else {
                    check(SystemClock.elapsedRealtime() < deadline) { "AudioTrack không nhận dữ liệu." }
                    waitForChange(8)
                }
            }
            return 0
        }

        override fun onNativeEnd(): Int {
            val track = checkNotNull(session.track)
            var deadline = SystemClock.elapsedRealtime() + 2_000
            var previousHead = head(track)
            while (played(track) < written && !session.cancelled) {
                val start = SystemClock.elapsedRealtime()
                val state = awaitPlayback(track)
                deadline += SystemClock.elapsedRealtime() - start
                if (state != 0) return state
                updatePosition(track)
                val nextHead = head(track)
                if (previousHead != nextHead) {
                    previousHead = nextHead
                    deadline = SystemClock.elapsedRealtime() + 2_000
                }
                check(SystemClock.elapsedRealtime() < deadline) { "AudioTrack ngừng nhận dữ liệu." }
                waitForChange(12)
            }
            updatePosition(track)
            return when {
                session.cancelled -> CANCELLED
                session.seek.get() != NO_SEEK -> SEEK_PENDING
                else -> 0
            }
        }

        private fun awaitPlayback(track: AudioTrack): Int {
            synchronized(session.monitor) {
                while (!session.cancelled) {
                    if (session.seek.get() != NO_SEEK) return SEEK_PENDING
                    if (session.playing) {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        return 0
                    }
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                    session.monitor.wait()
                }
            }
            return CANCELLED
        }

        private fun waitForChange(ms: Long) {
            synchronized(session.monitor) {
                if (!session.cancelled && session.seek.get() == NO_SEEK) session.monitor.wait(ms)
            }
        }

        private fun head(track: AudioTrack) = track.playbackHeadPosition.toLong() and 0xffffffffL
        private fun played(track: AudioTrack) = ((head(track) - baseHead) and 0xffffffffL).coerceAtMost(written)

        private fun updatePosition(track: AudioTrack) {
            if (session.seek.get() != NO_SEEK) return
            val limit = session.durationMs.takeIf { it > 0 } ?: Int.MAX_VALUE
            session.positionMs = (anchorMs + played(track) * 1000 / rate).coerceIn(0, limit.toLong()).toInt()
        }
    }

    private companion object {
        const val CANCELLED = -1
        const val NO_SEEK = -2
        const val SEEK_PENDING = 1
    }
}
