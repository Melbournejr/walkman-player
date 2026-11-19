package com.sony.walkmanpro

import com.sony.walkmanpro.audio.dsp.DspSettings
import com.sony.walkmanpro.audio.dsp.PlaybackDsp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class PlaybackDspTest {
    private val neutral = DspSettings(bass = 0f, clarity = false, treble = false, vinyl = false)
    private val rates = listOf(22_050, 44_100, 48_000, 88_200, 96_000, 176_400, 192_000)

    @Test
    fun directAndNeutralPreserveFloatBitsAcrossRatesAndChannelCounts() {
        for (rate in rates) for (channels in listOf(1, 2, 6, 8)) {
            val samples = FloatArray(100 * channels) { i ->
                when (i % 5) {
                    0 -> -0f
                    1 -> -1f
                    2 -> 1f
                    3 -> Float.fromBits(0x7fc00011)
                    else -> 0.000001f
                }
            }
            val before = samples.map(Float::toRawBits).toIntArray()
            val dsp = PlaybackDsp(rate, channels)
            dsp.processInterleaved(samples, samples.size, DspSettings(direct = true, invert = true, balance = 0.5f))
            dsp.processInterleaved(samples, samples.size, neutral)
            assertArrayEquals(before, samples.map(Float::toRawBits).toIntArray())
        }
    }

    @Test
    fun peakingEqRespondsAtConfiguredFrequencyUsingActualRate() {
        for (rate in rates) {
            val samples = FloatArray(rate / 2) { (0.1 * sin(2.0 * PI * 1000 * it / rate)).toFloat() }
            val dsp = PlaybackDsp(rate, 1)
            val eq = neutral.copy(eq = List(10) { if (it == 5) -6f else 0f })
            val expected = rms(samples) * 10.0.pow(-6.0 / 20.0)
            dsp.processInterleaved(samples, samples.size, eq)
            assertEquals("sample rate $rate", expected, rms(samples), 0.0003)
        }
    }

    @Test
    fun highBandIsSkippedWhenCenterIsAboveNyquist() {
        val samples = FloatArray(128) { 0.1f }
        val settings = neutral.copy(eq = List(10) { if (it == 9) 8f else 0f })
        val dsp = PlaybackDsp(22_050, 1)
        dsp.processInterleaved(samples, samples.size, settings)
        assertTrue(samples.last().isFinite())
        // An out-of-band peak must never turn into an unstable filter.
        assertTrue(samples.all { abs(it) <= 0.11f })
    }

    @Test
    fun fullScaleImpulseSweepNoiseAndSilenceStayFiniteAndBounded() {
        val random = Random(52)
        for (rate in rates) {
            val channels = 6
            val frames = rate / 20
            val growth = ln(10_000.0 / 40.0)
            var pink = 0.0
            val samples = FloatArray(channels * frames) { i ->
                val t = (i / channels).toDouble() / frames
                when (i % channels) {
                    0 -> if (i == 0) 1f else 0f
                    1 -> sin(2 * PI * 40 * (10.0.pow(t * growth / ln(10.0)) - 1) / growth * frames / rate).toFloat()
                    2 -> if (i % 4 == 0) 1f else -1f
                    3 -> random.nextFloat() * 2f - 1f
                    4 -> {
                        pink = pink * 0.96 + (random.nextFloat() * 2 - 1) * 0.04
                        pink.toFloat()
                    }
                    else -> if (i == 5) Float.POSITIVE_INFINITY else 0f
                }
            }
            val dsp = PlaybackDsp(rate, channels)
            dsp.processInterleaved(samples, samples.size, DspSettings(bass = 1f, eq = List(10) { 10f }))
            assertTrue(samples.all { it.isFinite() && abs(it) <= 1f })
            for (channel in 0 until 5) assertTrue((channel until samples.size step channels).any { samples[it] != 0f })
            assertTrue((5 until samples.size step channels).all { samples[it] == 0f })
        }
    }

    @Test
    fun phaseChangesCrossfadeAndBalancePreservesOtherChannels() {
        val dsp = PlaybackDsp(48_000, 6)
        val frame = floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f)
        dsp.processInterleaved(frame, frame.size, neutral)
        val changed = FloatArray(480 * 6) { 0.25f }
        dsp.processInterleaved(changed, changed.size, neutral.copy(balance = 0.5f, invert = true))
        assertEquals(0.25f, changed[0], 0f)
        assertEquals(-0.125f, changed[changed.size - 6], 0f)
        assertEquals(-0.25f, changed.last(), 0f)
    }

    private fun rms(samples: FloatArray): Double {
        val start = samples.size / 2
        return sqrt((start until samples.size).sumOf { samples[it].toDouble().pow(2) } / (samples.size - start))
    }
}
