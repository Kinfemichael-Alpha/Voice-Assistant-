package com.dimts.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class DspTest {
    @Test
    fun fftMatchesNaiveDft() {
        val n = 64
        val re = DoubleArray(n) { sin(2 * PI * 5 * it / n) + 0.3 * cos(2 * PI * 11 * it / n) + 0.01 * it }
        val im = DoubleArray(n)
        val expectedRe = DoubleArray(n)
        val expectedIm = DoubleArray(n)
        for (k in 0 until n) {
            for (t in 0 until n) {
                val angle = -2 * PI * k * t / n
                expectedRe[k] += re[t] * cos(angle)
                expectedIm[k] += re[t] * sin(angle)
            }
        }
        Fft(n).transform(re, im)
        for (k in 0 until n) {
            assertEquals(expectedRe[k], re[k], 1e-9)
            assertEquals(expectedIm[k], im[k], 1e-9)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun fftRejectsNonPowerOfTwo() {
        Fft(100)
    }

    @Test
    fun dtwOfIdenticalSequencesIsZero() {
        val a = Array(20) { i -> doubleArrayOf(sin(i / 3.0), cos(i / 5.0)) }
        assertEquals(0.0, Dtw.distance(a, a), 1e-12)
    }

    @Test
    fun dtwIsSymmetricAndToleratesTimeStretch() {
        val a = Array(40) { i -> doubleArrayOf(sin(i / 6.0), cos(i / 9.0)) }
        val stretched = Array(52) { i -> doubleArrayOf(sin(i / 7.8), cos(i / 11.7)) } // same curve, 1.3x slower
        val other = Array(40) { i -> doubleArrayOf(cos(i / 2.0) * 2, sin(i / 3.0) * 2) }
        val dStretch = Dtw.distance(a, stretched)
        val dOther = Dtw.distance(a, other)
        assertEquals(dStretch, Dtw.distance(stretched, a), 1e-9)
        assertTrue("stretched copy ($dStretch) must be much closer than a different curve ($dOther)", dStretch * 4 < dOther)
    }

    @Test
    fun dtwEarlyAbandonReturnsInfinityOnlyWhenOverLimit() {
        val a = Array(30) { doubleArrayOf(0.0) }
        val b = Array(30) { doubleArrayOf(5.0) }
        assertEquals(5.0 * 2 * 30 / 60, Dtw.distance(a, b), 1e-9) // diagonal path, cost 2*5 per step / (n+m)
        assertTrue(Dtw.distance(a, b, abandonAbove = 1.0).isInfinite())
        assertEquals(5.0, Dtw.distance(a, b, abandonAbove = 100.0), 1e-9)
    }

    @Test
    fun dtwHandlesVeryDifferentLengthsWithoutDisconnectedBand() {
        val a = Array(8) { doubleArrayOf(it.toDouble()) }
        val b = Array(60) { doubleArrayOf(it * 8.0 / 60) }
        assertTrue(Dtw.distance(a, b).isFinite())
    }

    @Test
    fun mfccIsNearlyIndependentOfLoudness() {
        val extractor = FeatureExtractor()
        val tone = DoubleArray(512) { 0.3 * sin(2 * PI * 880 * it / 16000.0) + 0.1 * sin(2 * PI * 2000 * it / 16000.0) }
        val quiet = DoubleArray(512) { tone[it] * 0.05 }
        val a = extractor.extract(tone)
        val b = extractor.extract(quiet)
        for (i in a.indices) assertEquals(a[i], b[i], 0.05)
    }

    @Test
    fun mfccDiffersBetweenDifferentTones() {
        val extractor = FeatureExtractor()
        val low = DoubleArray(512) { 0.3 * sin(2 * PI * 500 * it / 16000.0) }
        val high = DoubleArray(512) { 0.3 * sin(2 * PI * 3000 * it / 16000.0) }
        val a = extractor.extract(low)
        val b = extractor.extract(high)
        var d = 0.0
        for (i in a.indices) d += (a[i] - b[i]) * (a[i] - b[i])
        assertTrue("distance $d", d > 4.0)
    }

    @Test
    fun melScaleRoundTrips() {
        for (hz in listOf(100.0, 440.0, 1000.0, 7000.0)) {
            assertEquals(hz, FeatureExtractor.melToHz(FeatureExtractor.hzToMel(hz)), 1e-6)
        }
    }
}
