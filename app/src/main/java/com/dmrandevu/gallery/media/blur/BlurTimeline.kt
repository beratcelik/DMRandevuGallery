package com.dmrandevu.gallery.media.blur

import android.graphics.RectF
import kotlin.math.hypot

/**
 * Where the faces are, over time.
 *
 * Detection only runs on frames sampled every [SAMPLE_PERIOD_MS], so this fills the gaps: boxes
 * are padded, chained into tracks, smoothed, interpolated between samples, and extended by a
 * lead-in and a hold so a face is already covered on the frames around the ones it was seen in.
 *
 * Coordinates are normalized to the frame and y-down, matching the detector's bitmap space.
 */
class BlurTimeline private constructor(private val tracks: List<Track>) {

    /** What a covered region is shaped like. A head is round; a numberplate is not. */
    enum class Shape { ELLIPSE, RECTANGLE }

    /** One subject followed across samples. [from]/[to] already include lead-in and hold. */
    private class Track(
        val from: Long,
        val to: Long,
        val times: LongArray,
        val boxes: Array<RectF>,
        val shape: Shape
    )

    val isEmpty: Boolean get() = tracks.isEmpty()

    /**
     * Writes the regions visible at [presentationTimeUs] into [out] as `[cx, cy, halfW, halfH]`
     * quadruples, and each one's [Shape] into [shapes] as 0 for an ellipse and 1 for a rectangle.
     * Returns how many there are, or [COUNT_BLUR_ALL] when there are more than the shader can
     * take — the caller then blurs the whole frame rather than dropping any.
     *
     * [out] must hold at least `MAX_REGIONS * 4` floats, [shapes] at least [MAX_REGIONS].
     */
    fun boxesAt(presentationTimeUs: Long, out: FloatArray, shapes: FloatArray? = null): Int {
        var count = 0
        for (track in tracks) {
            if (presentationTimeUs < track.from || presentationTimeUs > track.to) continue
            if (count == MAX_REGIONS) return COUNT_BLUR_ALL
            val box = track.boxAt(presentationTimeUs)
            val i = count * 4
            out[i] = box.centerX()
            out[i + 1] = box.centerY()
            out[i + 2] = box.width() / 2f
            out[i + 3] = box.height() / 2f
            shapes?.set(count, if (track.shape == Shape.RECTANGLE) 1f else 0f)
            count++
        }
        return count
    }

    /**
     * Where the subject is at [timeUs]: interpolated between the samples that bracket it, and
     * carried along its own motion outside them.
     *
     * Freezing the box at the first and last detection is what made a passing car slide out from
     * under its own mosaic — the detector loses the plate while the car is still moving, and a
     * stationary blur then uncovers it just before it leaves the frame. Extrapolating keeps the
     * cover travelling with it.
     */
    private fun Track.boxAt(timeUs: Long): RectF {
        if (timeUs <= times.first()) return extrapolated(anchor = 0, neighbour = 1, timeUs)
        val last = times.size - 1
        if (timeUs >= times.last()) return extrapolated(anchor = last, neighbour = last - 1, timeUs)
        var high = times.binarySearch(timeUs)
        if (high >= 0) return boxes[high]
        high = -high - 1
        val low = high - 1
        val span = (times[high] - times[low]).toFloat()
        val t = if (span <= 0f) 0f else (timeUs - times[low]) / span
        return lerp(boxes[low], boxes[high], t)
    }

    /**
     * The [anchor] sample carried forward (or back) at the speed it was moving relative to
     * [neighbour]. A track seen only once has no speed to carry, so it stays put.
     */
    private fun Track.extrapolated(anchor: Int, neighbour: Int, timeUs: Long): RectF {
        val box = boxes[anchor]
        if (neighbour !in boxes.indices) return box
        val span = (times[anchor] - times[neighbour]).toFloat()
        if (span == 0f) return box
        val other = boxes[neighbour]
        // Capped so a badly-placed final detection cannot fling the cover across the frame.
        val steps = ((timeUs - times[anchor]) / span).coerceIn(-MAX_EXTRAPOLATION, MAX_EXTRAPOLATION)
        val dx = (box.centerX() - other.centerX()) * steps
        val dy = (box.centerY() - other.centerY()) * steps
        return RectF(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
    }

    /**
     * Collects detections sample by sample. [videoDurationUs] bounds the lead-in and hold so a
     * track never claims time the video does not have.
     */
    class Builder(private val videoDurationUs: Long) {

        /** Always holds at least one sample by the time it is a candidate for chaining. */
        private class OpenTrack(val shape: Shape) {
            val times = ArrayList<Long>()
            val boxes = ArrayList<RectF>()
            var smoothed: RectF? = null
        }

        private val open = ArrayList<OpenTrack>()

        /** [regions] are what one finder saw in the frame at [timeUs]. */
        fun addSample(timeUs: Long, regions: List<Region>): Builder {
            val unmatched = open.toMutableList()
            for (region in regions) {
                val padded = region.box.padded(region.shape)
                val track = unmatched.claim(timeUs, padded)
                    ?: OpenTrack(region.shape).also { open.add(it) }
                unmatched.remove(track)
                val smoothed = track.smoothed?.let { padded.sizeSmoothedFrom(it) } ?: padded
                track.smoothed = smoothed
                track.times.add(timeUs)
                track.boxes.add(smoothed)
            }
            return this
        }

        /**
         * Picks the track this detection continues. Only tracks seen within [MAX_TRACK_GAP_MS]
         * qualify — chaining onto one that went quiet long ago would splice two different
         * subjects together and blur the empty stretch between them.
         */
        private fun List<OpenTrack>.claim(timeUs: Long, box: RectF): OpenTrack? {
            val alive = filter { timeUs - it.times.last() <= MAX_TRACK_GAP_MS * 1_000 }

            // Overlap first: when two sightings of the same thing overlap, that is the surest
            // match there is.
            var best: OpenTrack? = null
            var bestIou = MIN_IOU
            for (candidate in alive) {
                val overlap = iou(candidate.boxes.last(), box)
                if (overlap > bestIou) {
                    bestIou = overlap
                    best = candidate
                }
            }
            if (best != null) return best

            // Nothing overlapped, which does not mean it is something new. A plate on a passing
            // car crosses more than its own width between samples, and treating each sighting as
            // a fresh track leaves every one of them a lone box with no motion to carry it — the
            // blur then sits still while the car drives out from under it. So fall back to the
            // nearest sighting that could plausibly have travelled here in the time available.
            var bestDistance = Float.MAX_VALUE
            for (candidate in alive) {
                val previous = candidate.boxes.last()
                if (!comparableSize(previous, box)) continue
                val samples = ((timeUs - candidate.times.last()).toFloat() /
                    (SAMPLE_PERIOD_MS * 1_000)).coerceAtLeast(1f)
                val reach = maxOf(previous.width(), previous.height(), box.width(), box.height()) *
                    TRAVEL_PER_SAMPLE * samples
                val distance = hypot(
                    previous.centerX() - box.centerX(),
                    previous.centerY() - box.centerY()
                )
                if (distance <= reach && distance < bestDistance) {
                    bestDistance = distance
                    best = candidate
                }
            }
            return best
        }

        fun build(): BlurTimeline {
            val tracks = open.map { track ->
                BlurTimeline.Track(
                    from = (track.times.first() - LEAD_MS * 1_000).coerceAtLeast(0),
                    to = (track.times.last() + HOLD_MS * 1_000).coerceAtMost(videoDurationUs),
                    times = track.times.toLongArray(),
                    boxes = track.boxes.toTypedArray(),
                    shape = track.shape
                )
            }
            return BlurTimeline(tracks)
        }

        /**
         * Eases the box size toward this detection while keeping the freshly detected centre.
         *
         * Detector boxes pulse in size between samples, which reads as a shivering blur. The
         * centre is deliberately left alone: smoothing it too would make the box trail a moving
         * face, and at a fast pan that lag outruns the padding and leaves part of the face out
         * in the open.
         */
        private fun RectF.sizeSmoothedFrom(previous: RectF): RectF {
            val halfWidth = (previous.width() + (width() - previous.width()) * SMOOTHING) / 2f
            val halfHeight = (previous.height() + (height() - previous.height()) * SMOOTHING) / 2f
            return RectF(
                centerX() - halfWidth,
                centerY() - halfHeight,
                centerX() + halfWidth,
                centerY() + halfHeight
            )
        }

        /**
         * Grows the box to cover what the detector's box leaves out.
         *
         * A face box hugs the features, so it is grown generously in both directions to take in
         * hair, ears and chin. A plate box hugs the characters, and the plate is only a little
         * wider and taller than they are — growing that one by the same amount would paint a
         * patch far bigger than the plate over the car.
         */
        private fun RectF.padded(shape: Shape): RectF {
            val padX: Float
            val padY: Float
            if (shape == Shape.RECTANGLE) {
                padX = width() * PLATE_PAD_X
                padY = height() * PLATE_PAD_Y
            } else {
                padX = maxOf(width(), height()) * FACE_PAD
                padY = padX
            }
            // Clamped on both sides: the detector happily reports a box that runs off the edge,
            // and clamping only one side of it leaves the rect inside-out with a negative width.
            return RectF(
                (left - padX).coerceIn(0f, 1f),
                (top - padY).coerceIn(0f, 1f),
                (right + padX).coerceIn(0f, 1f),
                (bottom + padY).coerceIn(0f, 1f)
            )
        }
    }

    /** One thing to cover in one frame, normalized to it. */
    data class Region(val box: RectF, val shape: Shape)

    companion object {
        fun empty() = BlurTimeline(emptyList())

        /** Everything [parts] cover, as one timeline the shader can read in a single pass. */
        fun of(parts: List<BlurTimeline>) = BlurTimeline(parts.flatMap { it.tracks })

        /** Regions the shader can blur individually; past this the whole frame goes. */
        const val MAX_REGIONS = 16
        const val COUNT_BLUR_ALL = -1

        /**
         * The nominal gap between samples, which the timeline's travel and gap allowances are
         * reckoned in. Each [RegionFinder] picks its own rate around this.
         */
        const val SAMPLE_PERIOD_MS = 200L

        private const val FACE_PAD = 0.30f

        /** Per-axis for plates, so the cover stays the shape of the plate rather than a blob. */
        private const val PLATE_PAD_X = 0.10f
        private const val PLATE_PAD_Y = 0.30f

        /** How many sample intervals a track may be carried past its last detection. */
        private const val MAX_EXTRAPOLATION = 6f
        private const val SMOOTHING = 0.5f
        private const val MIN_IOU = 0.2f

        /** How far a subject may travel between samples, in multiples of its own size. */
        private const val TRAVEL_PER_SAMPLE = 2.5f

        /** Two sightings of one subject do not change size wildly between samples. */
        private const val MAX_SIZE_RATIO = 3f

        private fun comparableSize(a: RectF, b: RectF): Boolean {
            val one = maxOf(a.width(), a.height())
            val other = maxOf(b.width(), b.height())
            if (one <= 0f || other <= 0f) return false
            return maxOf(one, other) / minOf(one, other) <= MAX_SIZE_RATIO
        }

        /**
         * A track quiet for longer than this is done; the next detection starts a new one.
         *
         * Generous on purpose. The detector regularly loses a face that never left — turned away,
         * behind a hand, briefly out of focus — and reacquires it a second or two later. Bridging
         * that gap keeps the blur on it throughout instead of uncovering the face for the whole
         * dropout. The cost is over-blurring a stretch where the person really did leave and
         * someone else stepped into the same spot, which is the safe direction to be wrong in.
         */
        private const val MAX_TRACK_GAP_MS = 5_000L

        /**
         * Blur starts this long before the first detection and lingers this long after the last.
         *
         * Generous, because a subject is legible for a while before the detector can name it and
         * for a while after it loses it — a plate is readable well before it is big and square-on
         * enough to be read, and again as it swings away. Extrapolation keeps the cover on the
         * subject's path through both windows rather than parking it where the subject used to be.
         */
        private const val LEAD_MS = 700L
        private const val HOLD_MS = 900L

        private fun lerp(a: RectF, b: RectF, t: Float) = RectF(
            a.left + (b.left - a.left) * t,
            a.top + (b.top - a.top) * t,
            a.right + (b.right - a.right) * t,
            a.bottom + (b.bottom - a.bottom) * t
        )

        private fun iou(a: RectF, b: RectF): Float {
            val left = maxOf(a.left, b.left)
            val top = maxOf(a.top, b.top)
            val right = minOf(a.right, b.right)
            val bottom = minOf(a.bottom, b.bottom)
            if (right <= left || bottom <= top) return 0f
            val intersection = (right - left) * (bottom - top)
            val union = a.width() * a.height() + b.width() * b.height() - intersection
            return if (union <= 0f) 0f else intersection / union
        }
    }
}
