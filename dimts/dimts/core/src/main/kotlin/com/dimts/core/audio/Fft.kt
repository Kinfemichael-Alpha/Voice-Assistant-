package com.dimts.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 FFT.
 *
 * [size] must be a power of two. Twiddle factors and the bit-reversal table are
 * precomputed once, so [transform] allocates nothing and is cheap enough to run
 * about 60 times per second on a phone without a measurable battery cost.
 */
class Fft(val size: Int) {
    private val cosTable = DoubleArray(size / 2)
    private val sinTable = DoubleArray(size / 2)
    private val bitReverse = IntArray(size)

    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "FFT size must be a power of two, was $size" }
        for (i in 0 until size / 2) {
            val angle = -2.0 * PI * i / size
            cosTable[i] = cos(angle)
            sinTable[i] = sin(angle)
        }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            bitReverse[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    /** Forward transform of ([re], [im]), both of length [size]. Results overwrite the inputs. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size) { "Buffers must have length $size" }

        for (i in 0 until size) {
            val j = bitReverse[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += len
            }
            len *= 2
        }
    }
}
