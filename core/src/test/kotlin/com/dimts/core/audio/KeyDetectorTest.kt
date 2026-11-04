package com.dimts.core.audio

import com.dimts.core.audio.SyntheticAudio.SOUND_A
import com.dimts.core.audio.SyntheticAudio.SOUND_B
import com.dimts.core.audio.SyntheticAudio.SOUND_C
import com.dimts.core.audio.SyntheticAudio.padded
import com.dimts.core.audio.SyntheticAudio.render
import com.dimts.core.audio.SyntheticAudio.runDetector
import com.dimts.core.audio.SyntheticAudio.silence
import com.dimts.core.audio.SyntheticAudio.takes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Random

class KeyDetectorTest {

    private fun trainA(): SoundKey {
        val result = KeyTrainer.train("wake", takes(SOUND_A, 4, seed = 1))
        assertTrue("training failed: $result", result is TrainResult.Success)
        return (result as TrainResult.Success).key
    }

    private fun variant(parts: List<SyntheticAudio.Part>, seed: Long, noise: Double = 0.003): ShortArray {
        val r = Random(seed)
        val tempo = 1.0 + 0.12 * r.nextGaussian().coerceIn(-1.5, 1.5)
        val pitch = 1.0 + 0.04 * r.nextGaussian().coerceIn(-1.5, 1.5)
        return padded(render(parts, tempo, pitch, 0.3 + 0.4 * r.nextDouble(), noise, seed), noise = noise, seed = seed)
    }

    // ---- training -------------------------------------------------------------------------

    @Test
    fun trainerNeedsEnoughClips() {
        val result = KeyTrainer.train("k", takes(SOUND_A, 2))
        assertEquals(TrainResult.Failure(TrainFailure.TOO_FEW_CLIPS), result)
    }

    @Test
    fun trainerRejectsSilentClip() {
        val clips = takes(SOUND_A, 2) + listOf(silence(2.5, 0.003))
        val result = KeyTrainer.train("k", clips)
        assertEquals(TrainResult.Failure(TrainFailure.NO_SOUND_IN_CLIP, 2), result)
    }

    @Test
    fun trainerRejectsTooShortClip() {
        val clips = takes(SOUND_A, 3).toMutableList()
        clips[1] = ShortArray(300)
        val result = KeyTrainer.train("k", clips)
        assertEquals(TrainResult.Failure(TrainFailure.CLIP_TOO_SHORT, 1), result)
    }

    @Test
    fun trainerRejectsOneDifferentSoundAmongTakes() {
        val clips = takes(SOUND_A, 3, seed = 1) + listOf(padded(render(SOUND_B)))
        val result = KeyTrainer.train("k", clips)
        assertTrue("got $result", result is TrainResult.Failure)
        result as TrainResult.Failure
        assertEquals(TrainFailure.INCONSISTENT, result.reason)
        assertEquals(3, result.clipIndex)
    }

    @Test
    fun trainerRejectsEndlessSound() {
        val longTone = padded(render(listOf(SyntheticAudio.Part(900.0, 900.0, 6.0))))
        val clips = takes(SOUND_A, 2) + listOf(longTone)
        val result = KeyTrainer.train("k", clips)
        assertEquals(TrainResult.Failure(TrainFailure.CLIP_TOO_LONG, 2), result)
    }

    @Test
    fun trainedKeyHasSensibleThreshold() {
        val result = KeyTrainer.train("k", takes(SOUND_A, 4, seed = 1)) as TrainResult.Success
        val report = result.report
        assertEquals(4, report.leaveOneOut.size)
        assertTrue(report.threshold >= report.leaveOneOut.max())
        assertTrue(report.threshold <= report.leaveOneOut.max() * 1.5 + 1.0)
        assertTrue(result.key.templates.all { it.size in 40..70 })
    }

    // ---- detection ------------------------------------------------------------------------

    @Test
    fun detectsHeldOutTakesOfTheKeySound() {
        val key = trainA()
        var hits = 0
        for (seed in 1000L until 1030L) {
            val detector = KeyDetector(listOf(key))
            if (runDetector(detector, variant(SOUND_A, seed)).size == 1) hits++
        }
        assertTrue("only $hits/30 held-out takes were recognised", hits >= 29)
    }

    @Test
    fun ignoresOtherSoundsSilenceAndNoise() {
        val key = trainA()
        var falseAccepts = 0
        for (seed in 2000L until 2020L) {
            falseAccepts += runDetector(KeyDetector(listOf(key)), variant(SOUND_B, seed)).size
            falseAccepts += runDetector(KeyDetector(listOf(key)), variant(SOUND_C, seed + 100)).size
        }
        assertEquals("false accepts on other sounds", 0, falseAccepts)

        assertTrue(runDetector(KeyDetector(listOf(key)), silence(8.0, 0.003)).isEmpty())
        assertTrue(runDetector(KeyDetector(listOf(key)), silence(8.0, 0.05)).isEmpty()) // steady hiss
        assertTrue(runDetector(KeyDetector(listOf(key)), ShortArray(16_000 * 3)).isEmpty()) // digital silence
    }

    @Test
    fun findsTheKeyInsideALongerStreamOfOtherSounds() {
        val key = trainA()
        val stream = silence(1.0, 0.003) + render(SOUND_B, seed = 1) + silence(1.2, 0.003) +
            render(SOUND_A, tempo = 1.05, seed = 2) + silence(1.0, 0.003) +
            render(SOUND_C, seed = 3) + silence(1.0, 0.003)
        val found = runDetector(KeyDetector(listOf(key)), stream)
        assertEquals(1, found.size)
        assertEquals("wake", found[0].keyName)
        assertTrue(found[0].distance <= found[0].threshold)
    }

    @Test
    fun resultDoesNotDependOnChunkSize() {
        val key = trainA()
        val signal = variant(SOUND_A, 4242)
        val sizes = listOf(1, 37, 160, 513, 1024, 4096, signal.size)
        for (size in sizes) {
            val found = runDetector(KeyDetector(listOf(key)), signal, chunk = size)
            assertEquals("chunk size $size", 1, found.size)
        }
    }

    @Test
    fun recoversAfterLongLoudNoise() {
        val key = trainA()
        val rnd = Random(9)
        val loud = ShortArray(16_000 * 8) { (0.3 * 32767 * rnd.nextGaussian()).toInt().coerceIn(-32768, 32767).toShort() }
        val stream = silence(0.5, 0.003) + loud + silence(2.0, 0.003) + variant(SOUND_A, 77)
        val found = runDetector(KeyDetector(listOf(key)), stream)
        assertEquals(1, found.size)
    }

    @Test
    fun sensitivityScalesTheThreshold() {
        val key = trainA()
        val signal = variant(SOUND_A, 31)
        val strict = KeyDetector(listOf(key), DetectorConfig(thresholdScale = 0.2))
        assertTrue(runDetector(strict, signal).isEmpty())
        val normal = KeyDetector(listOf(key), DetectorConfig(thresholdScale = 1.0))
        assertEquals(1, runDetector(normal, signal).size)
    }

    @Test
    fun reportsEverySegmentToTheListener() {
        val key = trainA()
        val reports = ArrayList<SegmentReport>()
        val detector = KeyDetector(listOf(key))
        detector.listener = { reports.add(it) }
        runDetector(detector, variant(SOUND_B, 5) + variant(SOUND_A, 6))
        assertEquals(2, reports.size)
        assertFalse(reports[0].matched)
        assertTrue(reports[1].matched)
        assertEquals("wake", reports[1].bestKey)
    }

    @Test
    fun picksTheRightKeyAmongSeveral() {
        val keyA = trainA()
        val keyB = (KeyTrainer.train("dismiss", takes(SOUND_B, 4, seed = 50)) as TrainResult.Success).key
        val detectorA = KeyDetector(listOf(keyA, keyB))
        val foundA = runDetector(detectorA, variant(SOUND_A, 8))
        assertEquals(listOf("wake"), foundA.map { it.keyName })
        val foundB = runDetector(KeyDetector(listOf(keyA, keyB)), variant(SOUND_B, 9))
        assertEquals(listOf("dismiss"), foundB.map { it.keyName })
    }

    @Test
    fun emptyKeyListNeverDetectsAndNeverCrashes() {
        val detector = KeyDetector(emptyList())
        assertTrue(runDetector(detector, variant(SOUND_A, 1)).isEmpty())
    }

    @Test
    fun clapPatternWorksAsAKey() {
        // Two sharp claps about 0.25 s apart (pauses inside a key must stay shorter than the 320 ms hangover): no pitch, just broadband bursts.
        fun claps(seed: Long, gap: Double): ShortArray {
            val r = Random(seed)
            val rate = SyntheticAudio.RATE
            fun clap(): ShortArray = ShortArray((0.06 * rate).toInt()) { i ->
                val env = Math.exp(-i / (0.012 * rate))
                (0.8 * env * r.nextGaussian() * 12000).toInt().coerceIn(-32768, 32767).toShort()
            }
            val pause = ShortArray((gap * rate).toInt()) { (0.003 * 32767 * r.nextGaussian()).toInt().toShort() }
            return padded(clap() + pause + clap(), seed = seed)
        }
        val trained = KeyTrainer.train("clap", List(4) { claps(it + 1L, 0.22 + 0.02 * it) })
        assertTrue("clap training failed: $trained", trained is TrainResult.Success)
        val key = (trained as TrainResult.Success).key
        val hits = (10L until 20L).count { runDetector(KeyDetector(listOf(key)), claps(it, 0.25)).size == 1 }
        assertTrue("clap pattern recognised only $hits/10 times", hits >= 8)
    }

    // ---- persistence ----------------------------------------------------------------------

    @Test
    fun keySurvivesSerialisation() {
        val key = trainA()
        val loaded = SoundKey.fromByteArray(key.toByteArray())
        assertEquals(key.name, loaded.name)
        assertEquals(key.threshold, loaded.threshold, 1e-12)
        assertEquals(key.templates.size, loaded.templates.size)
        val signal = variant(SOUND_A, 123)
        assertEquals(1, runDetector(KeyDetector(listOf(loaded)), signal).size)
    }

    @Test
    fun keyWithDifferentAudioSettingsIsRejected() {
        val bytes = trainA().toByteArray()
        try {
            SoundKey.fromByteArray(bytes, FeatureConfig(numCoeffs = 10))
            fail("expected IOException")
        } catch (expected: IOException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun corruptKeyFileIsRejected() {
        val bytes = trainA().toByteArray()
        bytes[0] = 0
        try {
            SoundKey.fromByteArray(bytes)
            fail("expected IOException")
        } catch (expected: IOException) {
            // ok
        }
        try {
            SoundKey.fromByteArray(trainA().toByteArray().copyOf(40))
            fail("expected an exception for a truncated file")
        } catch (expected: java.io.EOFException) {
            // ok: truncated files surface as EOFException (an IOException)
        }
    }
}
