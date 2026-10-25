package com.lanshare

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Page-grid caps mirroring iOS `AdaptiveLayout` (grid standard, 2026-10-24):
 * content columns cap at `wideWidth` (960dp) or `readableWidth` (640dp) and
 * centre in the canvas, so phone canvases — narrower than every cap — keep
 * their current margins and tablets read as one aligned grid where the top
 * bar, the bars and the page cards share the same left/right edges.
 */
object AdaptiveLayout {
    /** Single reading columns (settings, the paired connect page). */
    val readableWidth = 640.dp

    /** The wide connect page's two-column block and the conversation column. */
    val wideWidth = 960.dp

    /** The conversation's stream and composer column (same grid as the cards). */
    val conversationWidth = 960.dp

    /** The standard grid inset, applied outside the cap (iOS `.padding(16)` after `.frame(maxWidth:)`). */
    val gridPadding = 16.dp
}

/**
 * A full-width slot whose content is capped at `maxWidth` (plus the standard
 * grid inset outside it) and centred — the Compose twin of iOS
 * `.frame(maxWidth:).padding(16).frame(maxWidth: .infinity)`. On canvases
 * narrower than the cap the content simply keeps the inset margins.
 */
@Composable
fun GridColumn(
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val block = minOf(maxWidth + AdaptiveLayout.gridPadding * 2, this@BoxWithConstraints.maxWidth)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(block)
                .padding(horizontal = AdaptiveLayout.gridPadding),
        ) {
            content()
        }
    }
}
