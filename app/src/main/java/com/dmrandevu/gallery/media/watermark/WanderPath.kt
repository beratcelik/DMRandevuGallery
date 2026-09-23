package com.dmrandevu.gallery.media.watermark

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Where the watermark is at a given moment, as an offset from the centre in -1..1 on each axis.
 *
 * Each path is drawn at random when it is made: where it starts, which way it sets off, and how
 * fast it goes. Every video used to get the same path — same starting point, same figure traced
 * at the same pace — so the label sat in the same place at the same second of every clip, which
 * makes it easy to learn and edit around.
 *
 * Still smooth. Each axis is a slow sine carrying a smaller, quicker one, all with periods drawn
 * from ranges and phases drawn from the full turn. Summed, they wander without settling into a
 * visible loop, and nothing ever jumps — a random position per frame would strobe and could not
 * be read. The weights add up to one, so the offset never leaves -1..1.
 */
class WanderPath(random: Random = Random.Default) {

    private val x = Axis.draw(random, SLOW_X_SECONDS)
    private val y = Axis.draw(random, SLOW_Y_SECONDS)

    fun x(seconds: Double): Double = x.at(seconds)
    fun y(seconds: Double): Double = y.at(seconds)

    private class Axis(
        private val slowPeriod: Double,
        private val slowPhase: Double,
        private val quickPeriod: Double,
        private val quickPhase: Double
    ) {
        fun at(seconds: Double): Double =
            SLOW_WEIGHT * sin(seconds * TAU / slowPeriod + slowPhase) +
                QUICK_WEIGHT * sin(seconds * TAU / quickPeriod + quickPhase)

        companion object {
            fun draw(random: Random, slow: ClosedFloatingPointRange<Double>) = Axis(
                slowPeriod = random.nextDouble(slow.start, slow.endInclusive),
                slowPhase = random.nextDouble(TAU),
                quickPeriod = random.nextDouble(QUICK_SECONDS.start, QUICK_SECONDS.endInclusive),
                quickPhase = random.nextDouble(TAU)
            )
        }
    }

    private companion object {
        const val TAU = 2 * PI

        // The slow sweeps, one per axis, around the 31 s and 23 s the fixed path used. The two
        // ranges do not overlap, so the axes never fall into step and trace a plain diagonal.
        val SLOW_X_SECONDS = 27.0..37.0
        val SLOW_Y_SECONDS = 18.0..25.0

        /** The smaller, quicker sway on top, which is what keeps the path from looking drawn. */
        val QUICK_SECONDS = 10.0..16.0

        // Mostly the slow sweep, so the label still drifts rather than darts: at these weights it
        // moves at most about a fifth of the frame per second along an axis, and usually far
        // less. The fixed path peaked at about a seventh.
        const val SLOW_WEIGHT = 0.8
        const val QUICK_WEIGHT = 1 - SLOW_WEIGHT
    }
}
