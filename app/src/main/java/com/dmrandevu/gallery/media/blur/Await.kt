package com.dmrandevu.gallery.media.blur

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Awaits a Play services [Task]. Hand-rolled rather than pulling in kotlinx-coroutines-play-services
 * for the one call shape the ML Kit detectors need.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel(CancellationException("ML Kit task cancelled"))
            else -> @Suppress("UNCHECKED_CAST") cont.resume(task.result as T)
        }
    }
}
