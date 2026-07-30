package com.ppnam.station2aa.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ppnam.station2aa.ui.theme.AmberPrimary
import com.ppnam.station2aa.ui.theme.DangerRed
import com.ppnam.station2aa.ui.theme.GraphiteBorder
import com.ppnam.station2aa.ui.theme.GraphiteSurface
import com.ppnam.station2aa.ui.theme.SuccessGreen
import com.ppnam.station2aa.ui.theme.TextMuted
import com.ppnam.station2aa.ui.theme.WarningOrange

/**
 * The five states a status-driven card can be in, shared across every screen that shows job
 * cards, equipment, or areas as color-coded cards (design: 2026-07-30-android-ui-ux-overhaul §3.4).
 */
enum class StatusTone {
    Ready, Running, Warning, Danger, Idle;

    fun color(): Color = when (this) {
        Ready -> SuccessGreen
        Running -> AmberPrimary
        Warning -> WarningOrange
        Danger -> DangerRed
        Idle -> TextMuted
    }
}

/**
 * The shared color-coded card shell: a tone-tinted border on the graphite surface. Chrome
 * (border/background/shape/click) is shared; layout inside is not, since different callers (a
 * job card, a machine card, an area card) need a different number of lines — `content` gets the
 * tone's accent color so callers can tint their own status text consistently with the border.
 */
@Composable
fun StatusCard(
    tone: StatusTone = StatusTone.Idle,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    /**
     * Whether this is the current scan/tap target, independent of [tone]. A card can be e.g.
     * `Warning`-toned AND highlighted at once — status (what state something is in) and highlight
     * (is this the thing to act on right now) are two different questions, so they get two
     * parameters rather than overloading [tone] to answer both.
     */
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(accent: Color) -> Unit,
) {
    val accentColor = tone.color()
    val borderColor = when {
        highlighted -> AmberPrimary
        tone == StatusTone.Idle -> GraphiteBorder
        else -> accentColor
    }
    val borderWidth = when {
        highlighted -> 3.dp
        tone == StatusTone.Idle -> 1.dp
        else -> 2.dp
    }
    val shape = RoundedCornerShape(16.dp)
    var cardModifier = modifier
        .fillMaxWidth()
        .clip(shape)
    if (onClick != null) {
        cardModifier = cardModifier.clickable(enabled = enabled, onClick = onClick)
    }
    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        shape = shape,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content(accentColor)
        }
    }
}
