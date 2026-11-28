package com.littlebit.chat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Single reusable slider used by Settings and Image Studio.
 *
 * Deliberately thin: a 5 dp rounded track, accent fill, muted remainder, no
 * tick dots and no stop indicator. The 16 dp thumb never clips because the row
 * is at least 44 dp tall and the thumb centre stays inside the track bounds.
 * Discrete `steps` snap with a subtle haptic tick, and every value is exposed to
 * accessibility services with `ProgressBarRangeInfo` + `setProgress`. RTL drags
 * and system scaling keep working because the track is measured in pixels. The
 * 48 dp control keeps the slider's touch target accessible.
 */
@Composable
fun LittleBitSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    display: String? = null,
    contentDescription: String? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val intervals = (steps + 1).coerceAtLeast(1)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    var lastStep by remember { mutableIntStateOf(-1) }

    fun emit(raw: Float) {
        val clamped = raw.coerceIn(0f, 1f)
        val snapped = if (steps <= 0) clamped else (clamped * intervals).roundToInt().toFloat() / intervals
        val stepIndex = (snapped * intervals).roundToInt()
        if (steps > 0 && stepIndex != lastStep) {
            lastStep = stepIndex
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        onValueChange(valueRange.start + snapped * span)
    }

    val active = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f)
    val inactive = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 1f else 0.4f)
    val ring = MaterialTheme.colorScheme.surface

    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .pointerInput(enabled, valueRange, steps, rtl) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val raw = if (rtl) 1f - offset.x / width else offset.x / width
                        emit(raw)
                        onValueChangeFinished?.invoke()
                    }
                }
                .pointerInput(enabled, valueRange, steps, rtl) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            val raw = if (rtl) 1f - offset.x / width else offset.x / width
                            emit(raw)
                        },
                        onDragEnd = { onValueChangeFinished?.invoke() },
                        onDragCancel = { onValueChangeFinished?.invoke() },
                    ) { change, _ ->
                        change.consume()
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val raw = if (rtl) 1f - change.position.x / width else change.position.x / width
                        emit(raw)
                    }
                }
                .semantics {
                    if (contentDescription != null) this.contentDescription = contentDescription
                    progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                    setProgress { target ->
                        emit(((target - valueRange.start) / span).coerceIn(0f, 1f))
                        onValueChangeFinished?.invoke()
                        true
                    }
                },
        ) {
            val track = 5.dp.toPx()
            val thumbRadius = 8.dp.toPx()
            val centerY = size.height / 2f
            val usable = (size.width - thumbRadius * 2f).coerceAtLeast(1f)
            val centerX = thumbRadius + usable * fraction
            val radius = track / 2f
            drawRoundRect(
                color = inactive,
                topLeft = Offset(0f, centerY - radius),
                size = Size(size.width, track),
                cornerRadius = CornerRadius(radius, radius),
            )
            if (centerX > thumbRadius) {
                drawRoundRect(
                    color = active,
                    topLeft = Offset(thumbRadius, centerY - radius),
                    size = Size(centerX - thumbRadius, track),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
            drawCircle(
                color = Color.Black.copy(alpha = 0.12f),
                radius = thumbRadius + 1.5.dp.toPx(),
                center = Offset(centerX, centerY + 1.dp.toPx() / 2f),
            )
            drawCircle(color = ring, radius = thumbRadius + 1.5.dp.toPx(), center = Offset(centerX, centerY))
            drawCircle(color = active, radius = thumbRadius, center = Offset(centerX, centerY))
        }
        if (display != null) {
            Spacer(Modifier.width(10.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    display,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
