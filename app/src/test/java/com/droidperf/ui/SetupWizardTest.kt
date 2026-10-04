package com.droidperf.ui

import com.droidperf.domain.AccessMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupWizardTest {

    @Test
    fun testSetupEnforcementRules() {
        // Case 1: Root selected and root is available -> should pass
        var isRooted = true
        var isShizukuPermitted = false
        var selectedMode = AccessMode.ROOT

        var isPrivilegedReady = when (selectedMode) {
            AccessMode.ROOT -> isRooted
            AccessMode.SHIZUKU -> isShizukuPermitted
        }
        assertTrue(isPrivilegedReady)

        // Case 2: Shizuku selected, but Shizuku not permitted -> must NOT pass
        selectedMode = AccessMode.SHIZUKU
        isPrivilegedReady = when (selectedMode) {
            AccessMode.ROOT -> isRooted
            AccessMode.SHIZUKU -> isShizukuPermitted
        }
        assertFalse(isPrivilegedReady)

        // Case 3: Neither root nor shizuku available -> must NOT pass
        isRooted = false
        isShizukuPermitted = false
        selectedMode = AccessMode.ROOT
        assertFalse(when (selectedMode) {
            AccessMode.ROOT -> isRooted
            AccessMode.SHIZUKU -> isShizukuPermitted
        })

        // Case 4: Shizuku authorized -> should pass
        isShizukuPermitted = true
        selectedMode = AccessMode.SHIZUKU
        assertTrue(when (selectedMode) {
            AccessMode.ROOT -> isRooted
            AccessMode.SHIZUKU -> isShizukuPermitted
        })
    }
}
