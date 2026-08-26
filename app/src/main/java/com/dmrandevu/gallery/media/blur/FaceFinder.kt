package com.dmrandevu.gallery.media.blur

import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/** Finds faces. */
class FaceFinder : RegionFinder {

    override val samplePeriodMs = BlurTimeline.SAMPLE_PERIOD_MS

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            // ACCURATE, not FAST: on real dashcam footage FAST walks past faces turned even
            // slightly away from the camera, and a missed face is the one failure this feature
            // cannot have.
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            // Head width as a fraction of frame width; a bystander down the street still counts.
            .setMinFaceSize(0.05f)
            // Tracking deliberately left off. It is built for a live camera stream, and on a
            // decoded file it both suppresses detections and makes repeat runs over the same
            // video disagree with each other — measured at 140, 160 and 156 hits out of 238
            // samples across three runs of one clip. The timeline chains boxes by overlap
            // anyway, so the tracking ids bought little.
            .build()
    )

    override suspend fun regionsIn(frame: ScannedFrame): List<BlurTimeline.Region> {
        // Native size: a head is far bigger than the detector's minimum already, so there is
        // nothing to gain from enlarging the picture first.
        val image = frame.original()
        return detector.process(image).await().map {
            BlurTimeline.Region(it.boundingBox.normalizedIn(image), BlurTimeline.Shape.ELLIPSE)
        }
    }

    override fun close() = detector.close()
}
