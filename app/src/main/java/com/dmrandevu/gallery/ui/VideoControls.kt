package com.dmrandevu.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dmrandevu.gallery.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import java.util.Locale

/** The red of a marked stretch, on the scrubber and on the button that makes them. */
val MarkColour = Color(0xFFE53935)

/**
 * The scrubber under the video: where you are, how much is left, and a bar to move about with.
 *
 * Kept out of [ConversationPage] because that file already carries the whole screen, and this
 * needs to know nothing about players or downloads.
 */
@Composable
fun VideoScrubber(
    positionMs: Long,
    durationMs: Long,
    onScrubTo: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
    /** Stretches marked for beeping, drawn along the track so they can be seen and aimed at. */
    marks: List<LongRange> = emptyList()
) {
    // Until the player reports a duration there is nothing meaningful to scrub along.
    if (durationMs <= 0) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = clock(positionMs),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            // Under the slider rather than over it: the thumb has to stay findable while a mark
            // is being aimed at.
            if (marks.isNotEmpty()) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.Center)
                ) {
                    for (mark in marks) {
                        val from = (mark.first.toFloat() / durationMs).coerceIn(0f, 1f)
                        val to = (mark.last.toFloat() / durationMs).coerceIn(0f, 1f)
                        drawRect(
                            color = MarkColour,
                            topLeft = Offset(from * size.width, 0f),
                            // A brief mark still has to be visible, so it never draws thinner
                            // than a couple of pixels.
                            size = Size(maxOf((to - from) * size.width, 3f), size.height)
                        )
                    }
                }
            }
            Slider(
                value = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                onValueChange = { onScrubTo((it * durationMs).toLong()) },
                onValueChangeFinished = onScrubFinished,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(alpha = 0.75f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
        }
        Text(
            // Counting down rather than up: how much is left is the thing worth knowing.
            text = "-${clock(durationMs - positionMs)}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/** The badge shown while the screen is held down and the video is running fast. */
@Composable
fun SpeedBadge(speed: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = stringResource(R.string.playback_speed, speed),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

private fun clock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 500) / 1000
    return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}
