package io.github.madmax3553.staticgroot.schedule

/*
 * Port of tvOS SchedulePoolOrdering.swift: splitmix64, SeededRNG and the shuffle the
 * schedule uses. The schedule is only reproducible if these produce the same bits as
 * the Swift code, so every operation below is unsigned 64-bit arithmetic.
 *
 * Verification status: the golden vectors in SwiftRandomTest come from an independent
 * Python implementation of the same algorithm. They have NOT been checked against a
 * Swift run, because this machine cannot run Swift. The Swift test suite is the parity
 * check to run on a Mac before this is trusted.
 */
object SwiftRandom {

    /** splitmix64 as written in SchedulePoolOrdering.swift. */
    fun splitmix64(input: Long): Long {
        var z = input + GOLDEN
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** Upper 64 bits of the unsigned 128-bit product a * b. */
    internal fun unsignedMultiplyHigh(a: Long, b: Long): Long {
        var high = Math.multiplyHigh(a, b)
        if (a < 0) high += b
        if (b < 0) high += a
        return high
    }

    internal val GOLDEN = 0x9e3779b97f4a7c15uL.toLong()
    private val MIX1 = 0xbf58476d1ce4e5b9uL.toLong()
    private val MIX2 = 0x94d049bb133111ebuL.toLong()
}

/** SeededRNG from SchedulePoolOrdering.swift. xorshift64 over a splitmix64 seed. */
class SeededRng(seed: Long) {
    private var state: Long = SwiftRandom.splitmix64(seed).let { if (it == 0L) 1L else it }

    fun next(): Long {
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        return state
    }

    /**
     * Uniform in 0 until [bound], the way Swift's Int.random(in: 0..<bound, using:) draws it.
     * Lemire's multiply-and-reject method on the full 64-bit output.
     */
    fun uniform(bound: Int): Int {
        require(bound > 0)
        val n = bound.toLong()
        var x = next()
        var high = SwiftRandom.unsignedMultiplyHigh(x, n)
        var low = x * n
        if (java.lang.Long.compareUnsigned(low, n) < 0) {
            val t = java.lang.Long.remainderUnsigned(-n, n)
            while (java.lang.Long.compareUnsigned(low, t) < 0) {
                x = next()
                high = SwiftRandom.unsignedMultiplyHigh(x, n)
                low = x * n
            }
        }
        return high.toInt()
    }
}

/**
 * Swift's MutableCollection.shuffle(using:): a forward Fisher-Yates that draws each swap
 * index from the amount still unshuffled.
 */
fun <T> MutableList<T>.swiftShuffle(rng: SeededRng) {
    if (size <= 1) return
    var amount = size
    var current = 0
    while (amount > 1) {
        val random = rng.uniform(amount)
        amount -= 1
        val other = current + random
        val tmp = this[current]
        this[current] = this[other]
        this[other] = tmp
        current += 1
    }
}
