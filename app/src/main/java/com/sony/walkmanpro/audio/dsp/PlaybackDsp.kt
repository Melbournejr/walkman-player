package com.sony.walkmanpro.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

internal data class DspSettings(
    val direct: Boolean = false,
    val bass: Float = 0.25f,
    val clarity: Boolean = true,
    val treble: Boolean = true,
    val vinyl: Boolean = true,
    val balance: Float = 0f,
    val invert: Boolean = false,
    val eq: List<Float> = List(10) { 0f }
) {
    val hasEffects = bass >= 0.02f || clarity || treble || vinyl || eq.any { abs(it) >= 0.05f }
    val neutral = !hasEffects && abs(balance) < 0.03f && !invert
}

internal class PlaybackDsp(private val sampleRate: Int, val channels: Int) {
    private var current = Chain(sampleRate, channels)
    private var previous = Chain(sampleRate, channels)
    private var currentSettings: DspSettings? = null
    private var remaining = 0
    private val transitionFrames = (sampleRate / 125).coerceAtLeast(1)
    private val oldFrame = DoubleArray(channels)
    val frame = DoubleArray(channels)

    fun reset() {
        currentSettings = null
        remaining = 0
    }

    fun prepare(settings: DspSettings): Boolean {
        if (settings.direct) {
            reset()
            return false
        }
        if (settings != currentSettings && remaining == 0) {
            val hadSettings = currentSettings != null
            val reuse = previous
            previous = current
            current = reuse
            current.configure(settings)
            currentSettings = settings
            if (hadSettings) remaining = transitionFrames
        }
        return remaining > 0 || currentSettings?.neutral == false
    }

    fun processFrame() {
        if (remaining > 0) {
            frame.copyInto(oldFrame)
            previous.process(oldFrame)
        }
        current.process(frame)
        if (remaining > 0) {
            val oldWeight = remaining--.toDouble() / transitionFrames
            for (channel in frame.indices) {
                frame[channel] += (oldFrame[channel] - frame[channel]) * oldWeight
            }
        }
    }

    fun processInterleaved(samples: FloatArray, count: Int, settings: DspSettings) {
        if (!prepare(settings)) return
        var offset = 0
        while (offset + channels <= count) {
            for (channel in frame.indices) frame[channel] = samples[offset + channel].toDouble()
            processFrame()
            for (channel in frame.indices) samples[offset + channel] = frame[channel].toFloat()
            offset += channels
        }
    }

    private class Biquad(channels: Int) {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private val z1 = DoubleArray(channels)
        private val z2 = DoubleArray(channels)
        var active = false
            private set

        fun disable() {
            active = false
            z1.fill(0.0)
            z2.fill(0.0)
        }

        fun setHighPass(fs: Double, f0: Double, q: Double) {
            val w0 = 2.0 * PI * f0 / fs
            val cosw0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = ((1.0 + cosw0) * 0.5) / a0
            b1 = -(1.0 + cosw0) / a0
            b2 = ((1.0 + cosw0) * 0.5) / a0
            a1 = (-2.0 * cosw0) / a0
            a2 = (1.0 - alpha) / a0
            active = true
        }

        fun setPeaking(fs: Double, f0: Double, q: Double, gainDb: Double) {
            if (abs(gainDb) < 0.03 || f0 >= fs * 0.47) {
                disable()
                return
            }
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * f0 / fs
            val cosw0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha / a
            b0 = (1.0 + alpha * a) / a0
            b1 = (-2.0 * cosw0) / a0
            b2 = (1.0 - alpha * a) / a0
            a1 = (-2.0 * cosw0) / a0
            a2 = (1.0 - alpha / a) / a0
            active = true
        }

        fun setHighShelf(fs: Double, f0: Double, q: Double, gainDb: Double) {
            if (abs(gainDb) < 0.03 || f0 >= fs * 0.47) {
                disable()
                return
            }
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * f0 / fs
            val cosw0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            val a0 = (a + 1.0) - (a - 1.0) * cosw0 + twoSqrtAAlpha
            b0 = (a * ((a + 1.0) + (a - 1.0) * cosw0 + twoSqrtAAlpha)) / a0
            b1 = (-2.0 * a * ((a - 1.0) + (a + 1.0) * cosw0)) / a0
            b2 = (a * ((a + 1.0) + (a - 1.0) * cosw0 - twoSqrtAAlpha)) / a0
            a1 = (2.0 * ((a - 1.0) - (a + 1.0) * cosw0)) / a0
            a2 = ((a + 1.0) - (a - 1.0) * cosw0 - twoSqrtAAlpha) / a0
            active = true
        }

        fun process(x: Double, channel: Int): Double {
            val y = b0 * x + z1[channel]
            val next1 = b1 * x - a1 * y + z2[channel]
            val next2 = b2 * x - a2 * y
            z1[channel] = if (abs(next1) > 1e-24) next1 else 0.0
            z2[channel] = if (abs(next2) > 1e-24) next2 else 0.0
            return y
        }
    }

    private class Chain(sampleRate: Int, channels: Int) {
        private val fs = sampleRate.toDouble()
        private val filters = Array(19) { Biquad(channels) }
        private val activeFilters = ArrayList<Biquad>(filters.size)
        private val gains = DoubleArray(channels)
        private var neutral = true
        private var spread = false
        private var saturate = false
        private var headroom = 1.0

        fun configure(settings: DspSettings) {
            filters.forEach { it.disable() }
            activeFilters.clear()
            neutral = settings.neutral
            spread = settings.clarity && gains.size >= 2
            saturate = settings.vinyl
            var boost = 0.0
            if (settings.hasEffects) {
                filters[0].setHighPass(fs, 20.0, 0.7071)
                for (i in EQ_FREQUENCIES.indices) {
                    val db = settings.eq[i].toDouble().takeIf { abs(it) >= 0.05 } ?: 0.0
                    filters[i + 1].setPeaking(fs, EQ_FREQUENCIES[i], 1.414, db)
                    if (filters[i + 1].active) boost += db.coerceAtLeast(0.0)
                }
                val bass = if (settings.bass >= 0.02f) settings.bass.toDouble() * 6.0 else 0.0
                filters[11].setPeaking(fs, 215.0, 1.35, -bass * 0.22)
                filters[12].setPeaking(fs, 55.0, 0.90, bass)
                boost += bass
                if (settings.clarity) {
                    filters[13].setPeaking(fs, 280.0, 1.40, -1.8)
                    filters[14].setPeaking(fs, 2400.0, 0.85, 2.2)
                    boost += 2.2 + if (spread) 1.9 else 0.0
                }
                if (settings.vinyl) {
                    filters[15].setPeaking(fs, 145.0, 0.85, 2.0)
                    filters[16].setPeaking(fs, 850.0, 0.75, 1.4)
                    boost += 3.8
                }
                if (settings.treble) {
                    filters[17].setPeaking(fs, 5500.0, 1.00, 1.6)
                    filters[18].setHighShelf(fs, 10500.0, 0.707, 3.2)
                    boost += 4.8
                }
            }
            filters.filterTo(activeFilters) { it.active }
            headroom = 10.0.pow(-boost.coerceAtMost(24.0) / 20.0)
            val balance = if (abs(settings.balance) >= 0.03f) settings.balance.toDouble().coerceIn(-0.5, 0.5) else 0.0
            val phase = if (settings.invert) -1.0 else 1.0
            gains.fill(phase)
            if (gains.size >= 2) {
                gains[0] *= 1.0 - balance.coerceAtLeast(0.0)
                gains[1] *= 1.0 + balance.coerceAtMost(0.0)
            }
        }

        fun process(frame: DoubleArray) {
            if (neutral) return
            for (channel in frame.indices) {
                var sample = frame[channel]
                if (!sample.isFinite()) sample = 0.0
                sample *= headroom
                for (i in activeFilters.indices) sample = activeFilters[i].process(sample, channel)
                if (saturate) sample += 0.045 * sample * abs(sample)
                frame[channel] = sample
            }
            if (spread) {
                val side = (frame[0] - frame[1]) * 0.12
                frame[0] += side
                frame[1] -= side
            }
            for (channel in frame.indices) {
                val sample = frame[channel] * gains[channel]
                frame[channel] = when {
                    !sample.isFinite() -> 0.0
                    abs(sample) > 0.92 -> Math.copySign(0.92 + 0.08 * tanh((abs(sample) - 0.92) / 0.08), sample)
                    else -> sample
                }
            }
        }
    }

    companion object {
        private val EQ_FREQUENCIES = doubleArrayOf(
            31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
        )
    }
}
