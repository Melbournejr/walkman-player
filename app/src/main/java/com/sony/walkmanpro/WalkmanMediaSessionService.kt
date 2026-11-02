package com.sony.walkmanpro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tanh

// BỘ XỬ LÝ DSP BẢN 7.0/9.0 NGUYÊN BẢN

@UnstableApi
class SonyRealTimeDspProcessor : androidx.media3.common.audio.BaseAudioProcessor() {
    @Volatile var megaBassLevel: Float = 0.45f
    @Volatile var vinylEnabled: Boolean = true
    @Volatile var clearAudioEnabled: Boolean = true
    @Volatile var dseeEnabled: Boolean = true
    @Volatile var channelBalance: Float = 0.0f
    @Volatile var phaseInvert: Boolean = false
    val eq10Gains = FloatArray(10) { 0f }

    private class StereoBiquad {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0
        var a1 = 0.0; var a2 = 0.0
        var z1L = 0.0; var z2L = 0.0
        var z1R = 0.0; var z2R = 0.0

        fun reset() {
            z1L = 0.0; z2L = 0.0; z1R = 0.0; z2R = 0.0
        }

        fun setHighPass(fs: Double, f0: Double, q: Double) {
            val w0 = 2.0 * kotlin.math.PI * f0 / fs
            val cosw0 = kotlin.math.cos(w0)
            val alpha = kotlin.math.sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = ((1.0 + cosw0) * 0.5) / a0
            b1 = -(1.0 + cosw0) / a0
            b2 = ((1.0 + cosw0) * 0.5) / a0
            a1 = (-2.0 * cosw0) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun setPeaking(fs: Double, f0: Double, q: Double, gainDb: Double) {
            if (kotlin.math.abs(gainDb) < 0.05) {
                b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
                return
            }
            val a = Math.pow(10.0, gainDb / 40.0)
            val w0 = 2.0 * kotlin.math.PI * f0 / fs
            val cosw0 = kotlin.math.cos(w0)
            val alpha = kotlin.math.sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha / a
            b0 = (1.0 + alpha * a) / a0
            b1 = (-2.0 * cosw0) / a0
            b2 = (1.0 - alpha * a) / a0
            a1 = (-2.0 * cosw0) / a0
            a2 = (1.0 - alpha / a) / a0
        }

        fun processL(x: Double): Double {
            val y = b0 * x + z1L
            z1L = b1 * x - a1 * y + z2L
            z2L = b2 * x - a2 * y
            return y
        }

        fun processR(x: Double): Double {
            val y = b0 * x + z1R
            z1R = b1 * x - a1 * y + z2R
            z2R = b2 * x - a2 * y
            return y
        }
    }

    private val subsonicHp = StereoBiquad()
    private val mudCutEq = StereoBiquad()
    private val tightBassEq = StereoBiquad()
    private val sweetVocalEq = StereoBiquad()
    private val airLayerEq = StereoBiquad()

    override fun onConfigure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_FLOAT
        ) {
            return androidx.media3.common.audio.AudioProcessor.AudioFormat.NOT_SET
        }
        val fs = inputAudioFormat.sampleRate.toDouble().coerceAtLeast(44100.0)
        subsonicHp.setHighPass(fs, 20.0, 0.7071)
        return inputAudioFormat
    }

    override fun onFlush() {
        subsonicHp.reset()
        mudCutEq.reset()
        tightBassEq.reset()
        sweetVocalEq.reset()
        airLayerEq.reset()
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val fs = inputAudioFormat.sampleRate.toDouble().coerceAtLeast(44100.0)
        val ch = inputAudioFormat.channelCount.coerceAtLeast(1)

        val eqLow = (eq10Gains[0] + eq10Gains[1]).toDouble() * 0.5
        val eqMid = (eq10Gains[4] + eq10Gains[5] + eq10Gains[6]).toDouble() / 3.0
        val eqHigh = (eq10Gains[8] + eq10Gains[9]).toDouble() * 0.5

        tightBassEq.setPeaking(fs, 60.0, 1.15, (megaBassLevel * 5.5) + (eqLow * 0.6))
        mudCutEq.setPeaking(fs, 210.0, 1.40, if (clearAudioEnabled) -1.8 else -0.8)
        sweetVocalEq.setPeaking(fs, 1650.0, 0.80, (if (clearAudioEnabled) 1.8 else 0.5) + (eqMid * 0.6))
        airLayerEq.setPeaking(fs, 11500.0, 0.90, (if (dseeEnabled) 2.4 else 0.4) + (eqHigh * 0.6))

        val bal = if (kotlin.math.abs(channelBalance) < 0.03f) 0f else channelBalance.coerceIn(-0.5f, 0.5f)
        val leftPan = if (bal > 0f) (1.0 - bal).coerceIn(0.0, 1.0) else 1.0
        val rightPan = if (bal < 0f) (1.0 + bal).coerceIn(0.0, 1.0) else 1.0
        val phase = if (phaseInvert) -1.0 else 1.0

        val outBuffer = replaceOutputBuffer(remaining).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        inputBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)

        if (inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= ch * 2) {
                var L = inputBuffer.short.toDouble() / 32768.0
                var R = if (ch >= 2) inputBuffer.short.toDouble() / 32768.0 else L
                for (c in 2 until ch) inputBuffer.short

                L = subsonicHp.processL(L)
                R = subsonicHp.processR(R)
                L = mudCutEq.processL(L)
                R = mudCutEq.processR(R)
                L = tightBassEq.processL(L)
                R = tightBassEq.processR(R)
                L = sweetVocalEq.processL(L)
                R = sweetVocalEq.processR(R)
                L = airLayerEq.processL(L)
                R = airLayerEq.processR(R)

                if (clearAudioEnabled) {
                    val mid = (L + R) * 0.5
                    val side = (L - R) * 0.5 * 1.16
                    L = mid + side
                    R = mid - side
                }

                L = L * leftPan * phase * 0.92
                R = R * rightPan * phase * 0.92

                if (kotlin.math.abs(L) > 0.95) L = (if (L > 0) 1.0 else -1.0) * (0.95 + 0.05 * kotlin.math.tanh((kotlin.math.abs(L) - 0.95) / 0.05))
                if (kotlin.math.abs(R) > 0.95) R = (if (R > 0) 1.0 else -1.0) * (0.95 + 0.05 * kotlin.math.tanh((kotlin.math.abs(R) - 0.95) / 0.05))

                val sL = (L * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                val sR = (R * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                outBuffer.putShort(sL)
                if (ch >= 2) outBuffer.putShort(sR)
                for (c in 2 until ch) outBuffer.putShort(0)
            }
        } else {
            while (inputBuffer.remaining() >= ch * 4) {
                var L = inputBuffer.float.toDouble()
                var R = if (ch >= 2) inputBuffer.float.toDouble() else L
                for (c in 2 until ch) inputBuffer.float

                L = subsonicHp.processL(L)
                R = subsonicHp.processR(R)
                L = mudCutEq.processL(L)
                R = mudCutEq.processR(R)
                L = tightBassEq.processL(L)
                R = tightBassEq.processR(R)
                L = sweetVocalEq.processL(L)
                R = sweetVocalEq.processR(R)
                L = airLayerEq.processL(L)
                R = airLayerEq.processR(R)

                if (clearAudioEnabled) {
                    val mid = (L + R) * 0.5
                    val side = (L - R) * 0.5 * 1.16
                    L = mid + side
                    R = mid - side
                }

                L = L * leftPan * phase * 0.92
                R = R * rightPan * phase * 0.92

                if (kotlin.math.abs(L) > 0.95) L = (if (L > 0) 1.0 else -1.0) * (0.95 + 0.05 * kotlin.math.tanh((kotlin.math.abs(L) - 0.95) / 0.05))
                if (kotlin.math.abs(R) > 0.95) R = (if (R > 0) 1.0 else -1.0) * (0.95 + 0.05 * kotlin.math.tanh((kotlin.math.abs(R) - 0.95) / 0.05))

                outBuffer.putFloat(L.toFloat())
                if (ch >= 2) outBuffer.putFloat(R.toFloat())
                for (c in 2 until ch) outBuffer.putFloat(0f)
            }
        }
        outBuffer.flip()
    }
}
