package com.droidperf.monitoring.gpu

import android.opengl.GLES20
import android.os.Build
import com.droidperf.domain.GpuVendor
import com.droidperf.domain.Metric
import com.droidperf.system.SysFs
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * GPU monitoring, vendor-aware.
 *
 * Utilization is read from vendor sysfs/procfs nodes when they exist. When they do not, we
 * report Unavailable. GPU usage is NEVER estimated from FPS, temperature, frequency or
 * CPU: those are different quantities and deriving one from another would be a fake
 * measurement.
 *
 * Known interfaces:
 *  - Adreno / KGSL:   /sys/class/kgsl/kgsl-3d0/gpubusy   ("busy total")
 *                     /sys/class/kgsl/kgsl-3d0/gpuclk
 *  - MediaTek GED:    /proc/ged/hal/gpu_utilization
 *                     /proc/ged/hal/current_freq
 *  - Samsung SGPU:    /sys/class/misc/sgpu/device/gpu_busy_percent
 *  - Generic devfreq: /sys/class/devfreq/<dev>/load or <dev>/gpu_load, and <dev>/cur_freq
 *  - Mali:            devfreq nodes (vendor dependent), GED interface on MTK
 */
class GpuMonitor {

    private val whitespaceRegex = Regex("\\s+")

    val vendor: GpuVendor by lazy { detectVendor() }

    /**
     * Authentic GPU hardware model name reported by the driver,
     * falling back to vendor display name when renderer string is absent.
     */
    val modelName: String
        get() {
            val gl = GlInfo.getGlInfo()
            if (gl != null && gl.renderer.isNotBlank()) {
                return gl.renderer
            }
            return vendor.displayName
        }

    private fun detectVendor(): GpuVendor {
        val gl = GlInfo.getGlInfo()
        if (gl != null) {
            val fromGl = GpuVendor.detect(
                hardware = Build.HARDWARE,
                renderer = gl.renderer,
                socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
                socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null,
                glVendor = gl.vendor,
            )
            if (fromGl != GpuVendor.UNKNOWN) return fromGl
        }

        val soc = SysFs.readText("/sys/devices/soc0/machine")
            ?: SysFs.readText("/sys/devices/soc0/soc_id")
        val kgsl = SysFs.exists("/sys/class/kgsl") || SysFs.exists("/dev/kgsl-3d0")
        val mali = SysFs.listDir("/sys/class/devfreq").any { it.contains("mali", true) } ||
            SysFs.exists("/sys/class/misc/mali0") ||
            SysFs.exists("/dev/mali0") ||
            SysFs.exists("/proc/ged/hal")
        val sgpu = SysFs.exists("/sys/class/misc/sgpu") || SysFs.exists("/dev/sgpu")

        return when {
            kgsl -> GpuVendor.ADRENO
            sgpu -> GpuVendor.XCLIPSE
            mali -> GpuVendor.MALI
            else -> GpuVendor.detect(
                hardware = Build.HARDWARE.ifBlank { soc },
                renderer = null,
                socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else soc,
                socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null,
            )
        }
    }

    /** Best-effort GPU usage percent. Only real vendor counters are trusted. */
    fun sampleUsage(): Metric<Double> {
        val kgsl = SysFs.readText("/sys/class/kgsl/kgsl-3d0/gpubusy")
        if (kgsl != null) {
            val parts = kgsl.trim().split(whitespaceRegex)
            val busy = parts.getOrNull(0)?.toLongOrNull()
            val total = parts.getOrNull(1)?.toLongOrNull()
            if (busy != null && total != null && total > 0) {
                return Metric.Available((busy.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0))
            }
        }

        // MediaTek GED (GPU Extension Device) interface
        val gedUsage = SysFs.readLong("/proc/ged/hal/gpu_utilization")
            ?: SysFs.readLong("/proc/ged/hal/loading")
            ?: SysFs.readLong("/proc/ged/hal/gpu_loading")
        if (gedUsage != null && gedUsage in 0..100) {
            return Metric.Available(gedUsage.toDouble())
        }

        // Samsung SGPU interface (AMD RDNA on Exynos)
        val sgpuUsage = SysFs.readLong("/sys/class/misc/sgpu/device/gpu_busy_percent")
            ?: SysFs.readLong("/sys/devices/platform/17000000.sgpu/gpu_busy_percent")
        if (sgpuUsage != null && sgpuUsage in 0..100) {
            return Metric.Available(sgpuUsage.toDouble())
        }

        // Generic devfreq load nodes: values are usually 0..1000 or 0..100.
        for (dir in SysFs.listDir("/sys/class/devfreq")) {
            val load = SysFs.readLong("/sys/class/devfreq/$dir/load")
                ?: SysFs.readLong("/sys/class/devfreq/$dir/gpu_load")
                ?: continue
            // Normalize: some report 0..100, some 0..1000, some 0..255.
            val pct = when {
                load <= 100 -> load.toDouble()
                load <= 1000 -> load / 10.0
                else -> load / 255.0 * 100.0
            }
            if (pct.isFinite() && pct in 0.0..100.0) return Metric.Available(pct)
        }

        return Metric.Unavailable(
            "Unavailable on this device because no accessible GPU utilization interface was detected."
        )
    }

    /** GPU clock in MHz from kgsl, MediaTek GED, Samsung SGPU, or devfreq. */
    fun sampleFrequencyMhz(): Metric<Int> {
        // Adreno kgsl gpuclk is in Hz.
        SysFs.readLong("/sys/class/kgsl/kgsl-3d0/gpuclk")?.let { hz ->
            return Metric.Available((hz / 1_000_000L).toInt())
        }

        // MediaTek GED frequency
        val gedFreq = SysFs.readLong("/proc/ged/hal/current_freq")
            ?: SysFs.readLong("/proc/ged/hal/gpu_cur_freq")
        if (gedFreq != null && gedFreq > 0) {
            val mhz = when {
                gedFreq > 100_000_000L -> (gedFreq / 1_000_000L).toInt() // in Hz
                gedFreq > 10_000L -> (gedFreq / 1000L).toInt() // in kHz
                else -> gedFreq.toInt() // in MHz
            }
            return Metric.Available(mhz)
        }

        // Samsung SGPU frequency
        SysFs.readLong("/sys/devices/platform/17000000.sgpu/devfreq/17000000.sgpu/cur_freq")?.let { hz ->
            if (hz > 0) return Metric.Available((hz / 1_000_000L).toInt())
        }

        // devfreq cur_freq is in Hz too.
        for (dir in SysFs.listDir("/sys/class/devfreq")) {
            val hz = SysFs.readLong("/sys/class/devfreq/$dir/cur_freq") ?: continue
            if (hz > 0) return Metric.Available((hz / 1_000_000L).toInt())
        }
        return Metric.Unavailable("no GPU frequency node exposed")
    }

    /**
     * GPU memory in use, in MB, from the KGSL per-device counter when the kernel
     * exposes it (`gpu_mem`, reported in KB). Adreno uses unified memory, so there is
     * no separate total/vram figure; when the node is absent the metric stays
     * Unavailable rather than guessing.
     */
    fun sampleMemoryUsedMb(): Metric<Int> {
        val kb = SysFs.readLong("/sys/class/kgsl/kgsl-3d0/gpu_mem") ?: return Metric.Unavailable(
            "no GPU memory counter on this device"
        )
        if (kb < 0) return Metric.Unavailable("no GPU memory counter on this device")
        return Metric.Available((kb / 1024L).toInt())
    }

    /** GPU temperature via thermal zones whose label mentions the GPU. */
    fun sampleTemperatureC(): Metric<Double> {
        val base = "/sys/class/thermal"
        val zones = SysFs.listDir(base).filter { it.startsWith("thermal_zone") }
        var best = Double.NEGATIVE_INFINITY
        var found = false
        for (z in zones) {
            val type = SysFs.readText("$base/$z/type")?.lowercase() ?: continue
            if (!(type.contains("gpu") || type.contains("kgsl") || type.contains("mali") ||
                    type.contains("sgpu") || type.contains("g3d") || type.contains("gpuss"))) continue
            val raw = SysFs.readLong("$base/$z/temp") ?: continue
            val c = if (raw > 1000) raw / 1000.0 else raw.toDouble()
            if (c in -30.0..150.0 && c > best) { best = c; found = true }
        }
        return if (found) Metric.Available(best)
        else Metric.Unavailable("no thermal zone could be confidently matched to the GPU")
    }
}

/**
 * Queries hardware GPU details (GL_RENDERER, GL_VENDOR, GL_VERSION)
 * via an offscreen 1x1 EGL PBuffer surface.
 *
 * This provides the authentic hardware GPU model reported by the GPU driver
 * on any Android device without requiring root or Shizuku permissions.
 */
object GlInfo {

    data class GpuGlInfo(
        val renderer: String,
        val vendor: String,
        val version: String,
    )

    @Volatile
    private var cachedInfo: GpuGlInfo? = null

    fun getGlInfo(): GpuGlInfo? {
        val current = cachedInfo
        if (current != null) return current

        synchronized(this) {
            val checkAgain = cachedInfo
            if (checkAgain != null) return checkAgain

            val queried = queryOffscreen()
            if (queried != null) {
                cachedInfo = queried
            }
            return queried
        }
    }

    private fun queryOffscreen(): GpuGlInfo? {
        return runCatching {
            val egl = (EGLContext.getEGL() as? EGL10) ?: return null
            val display: EGLDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            if (display == EGL10.EGL_NO_DISPLAY) return null

            val version = IntArray(2)
            if (!egl.eglInitialize(display, version)) return null

            val configAttribs = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 4,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL10.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!egl.eglChooseConfig(display, configAttribs, configs, 1, numConfigs) || numConfigs[0] <= 0) {
                egl.eglTerminate(display)
                return null
            }

            val config = configs[0] ?: run {
                egl.eglTerminate(display)
                return null
            }

            val contextAttribs = intArrayOf(
                0x3098, 2,
                EGL10.EGL_NONE,
            )
            val context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttribs)
            if (context == EGL10.EGL_NO_CONTEXT) {
                egl.eglTerminate(display)
                return null
            }

            val pbufferAttribs = intArrayOf(
                EGL10.EGL_WIDTH, 1,
                EGL10.EGL_HEIGHT, 1,
                EGL10.EGL_NONE,
            )
            val surface: EGLSurface = egl.eglCreatePbufferSurface(display, config, pbufferAttribs)
            if (surface == EGL10.EGL_NO_SURFACE) {
                egl.eglDestroyContext(display, context)
                egl.eglTerminate(display)
                return null
            }

            if (!egl.eglMakeCurrent(display, surface, surface, context)) {
                egl.eglDestroySurface(display, surface)
                egl.eglDestroyContext(display, context)
                egl.eglTerminate(display)
                return null
            }

            val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty().trim()
            val glVendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty().trim()
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty().trim()

            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            egl.eglDestroySurface(display, surface)
            egl.eglDestroyContext(display, context)
            egl.eglTerminate(display)

            if (renderer.isNotEmpty()) {
                GpuGlInfo(renderer = renderer, vendor = glVendor, version = glVersion)
            } else {
                null
            }
        }.getOrNull()
    }
}

