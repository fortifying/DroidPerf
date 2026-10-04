package com.droidperf.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Preset definitions are pure data, so they can be verified without a Context. */
class OverlayPresetTest {

    private fun presetFlags(p: OverlayPreset): BooleanArray = when (p) {
        OverlayPreset.MINIMAL -> booleanArrayOf(true, true, false, true, true, false, false, false, false, true)
        OverlayPreset.GAMING -> booleanArrayOf(true, true, true, true, true, true, false, false, false, true)
        OverlayPreset.DETAILED -> booleanArrayOf(true, true, true, true, true, true, true, true, true, true)
        OverlayPreset.THERMAL -> booleanArrayOf(false, true, false, false, true, true, false, false, false, true)
        OverlayPreset.BATTERY -> booleanArrayOf(false, false, false, false, false, false, true, false, false, false)
        OverlayPreset.CUSTOM -> booleanArrayOf()
    }

    @Test
    fun `minimal hides network and display`() {
        val f = presetFlags(OverlayPreset.MINIMAL)
        assertFalse(f[7]) // network
        assertFalse(f[8]) // display
        assertTrue(f[0])  // fps
    }

    @Test
    fun `detailed shows everything`() {
        assertTrue(presetFlags(OverlayPreset.DETAILED).all { it })
    }

    @Test
    fun `battery preset shows only battery`() {
        val f = presetFlags(OverlayPreset.BATTERY)
        assertEquals(1, f.count { it })
        assertTrue(f[6]) // battery
    }

    @Test
    fun `thermal preset focuses on temperature and cpu`() {
        val f = presetFlags(OverlayPreset.THERMAL)
        assertTrue(f[5])  // temperature
        assertTrue(f[1])  // cpu
        assertFalse(f[0]) // fps
    }
}
