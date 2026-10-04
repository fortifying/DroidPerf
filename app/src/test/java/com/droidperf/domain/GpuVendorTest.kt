package com.droidperf.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuVendorTest {

    @Test
    fun `detects adreno from renderer`() {
        assertEquals(GpuVendor.ADRENO, GpuVendor.detect("qcom", "Adreno (TM) 740", null))
    }

    @Test
    fun `detects mali from renderer`() {
        assertEquals(GpuVendor.MALI, GpuVendor.detect("exynos", "Mali-G78 MP14", null))
    }

    @Test
    fun `detects xclipse from hardware string`() {
        assertEquals(GpuVendor.XCLIPSE, GpuVendor.detect(null, null, "Samsung Xclipse 920"))
    }

    @Test
    fun `detects powervr`() {
        assertEquals(GpuVendor.POWERVR, GpuVendor.detect("PowerVR Rogue", null, null))
    }

    @Test
    fun `falls back to unknown instead of guessing`() {
        assertEquals(GpuVendor.UNKNOWN, GpuVendor.detect(null, null, null))
        assertEquals(GpuVendor.UNKNOWN, GpuVendor.detect("generic soc", "some renderer", null))
    }
}
