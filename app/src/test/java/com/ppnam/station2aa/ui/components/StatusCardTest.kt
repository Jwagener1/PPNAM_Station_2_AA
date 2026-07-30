package com.ppnam.station2aa.ui.components

import com.ppnam.station2aa.ui.theme.AmberPrimary
import com.ppnam.station2aa.ui.theme.DangerRed
import com.ppnam.station2aa.ui.theme.SuccessGreen
import com.ppnam.station2aa.ui.theme.TextMuted
import com.ppnam.station2aa.ui.theme.WarningOrange
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusCardTest {

    @Test
    fun `each tone maps to its designed color`() {
        assertEquals(SuccessGreen, StatusTone.Ready.color())
        assertEquals(AmberPrimary, StatusTone.Running.color())
        assertEquals(WarningOrange, StatusTone.Warning.color())
        assertEquals(DangerRed, StatusTone.Danger.color())
        assertEquals(TextMuted, StatusTone.Idle.color())
    }
}
