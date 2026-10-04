package com.droidperf.monitoring

import android.content.Context
import com.droidperf.di.ServiceLocator
import com.droidperf.domain.AccessLevel
import com.droidperf.domain.AccessMode
import com.droidperf.domain.GpuVendor
import com.droidperf.domain.Metric
import com.droidperf.domain.MetricsSnapshot
import com.droidperf.domain.valueOrNull
import com.droidperf.monitoring.app.ForegroundAppDetector
import com.droidperf.monitoring.battery.BatteryMonitor
import com.droidperf.monitoring.cpu.CpuMonitor
import com.droidperf.monitoring.display.DisplayMonitor
import com.droidperf.monitoring.fps.FpsMonitor
import com.droidperf.monitoring.fps.FpsStatsTracker
import com.droidperf.monitoring.gpu.GpuMonitor
import com.droidperf.monitoring.memory.RamMonitor
import com.droidperf.monitoring.network.LatencyProbe
import com.droidperf.monitoring.network.NetworkMonitor
import com.droidperf.monitoring.thermal.ThermalMonitor
import com.droidperf.system.SysFs
import com.droidperf.system.capability.Capabilities
import com.droidperf.system.capability.CapabilityDetector
import com.droidperf.system.shell.SafeCommands
import com.droidperf.system.shell.RootShell
import com.droidperf.system.shell.Shell
import com.droidperf.system.shell.ShizukuShell
import com.droidperf.system.shell.StandardShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single place that decides which access level is in force, then samples every
 * metric independently. Each metric is wrapped so one failing interface can never take
 * down the rest; a failure becomes an explained Unavailable, never a crash.
 */
class MetricsRepository(private val context: Context) {

    private val standardShell = StandardShell()
    private val shizukuShell = ShizukuShell()
    private val rootShell = RootShell()

    private val cpu = CpuMonitor()
    private val ram = RamMonitor(context)
    private val battery = BatteryMonitor(context)
    private val thermal = ThermalMonitor(context)
    private val gpu = GpuMonitor()
    private val display = DisplayMonitor(context)
    private val network = NetworkMonitor()

    private val foreground = ForegroundAppDetector(context) { activeShell() }
    private val fps = FpsMonitor { activeShell() }

    private val fpsStats = FpsStatsTracker()
    private var fpsStatsPkg: String? = null

    init {
        wirePrivilegedSysFs()
    }

    /**
     * Route sysfs/procfs reads that the app process is denied (SELinux blocks KGSL,
     * cpufreq and /proc/stat on many Android 12+ builds, rooted or not) through the
     * highest privileged shell. Every command is validated by SafeCommands, so this
     * widens what we can *read*, never what we may *execute*.
     */
    private fun wirePrivilegedSysFs() {
        SysFs.privilegedRead = { path -> privilegedCat(path) }
        SysFs.privilegedList = { path -> privilegedLs(path) }
        SysFs.privilegedExists = { path -> privilegedExists(path) }
    }

    private fun getPreferredMode(): AccessMode {
        return runCatching { ServiceLocator.settings.getPreferredAccessMode() }.getOrDefault(
            if (RootShell.hasSuBinary()) AccessMode.ROOT else AccessMode.SHIZUKU
        )
    }

    /** Standard (app-UID) shell gains nothing; privileged shell is chosen according to preferred mode. */
    private fun privilegedSysShell(): Shell? {
        return if (getPreferredMode() == AccessMode.ROOT) {
            when {
                rootShell.isAvailable() -> rootShell
                shizukuShell.isAvailable() -> shizukuShell
                else -> null
            }
        } else {
            when {
                shizukuShell.isAvailable() -> shizukuShell
                rootShell.isAvailable() -> rootShell
                else -> null
            }
        }
    }

    private fun privilegedCat(path: String): String? {
        val shell = privilegedSysShell() ?: return null
        val cmd = SafeCommands.catNode(path) ?: return null
        return shell.exec(cmd)?.takeIf { it.ok }?.stdout?.joinToString("\n")
    }

    private fun privilegedLs(path: String): List<String>? {
        val shell = privilegedSysShell() ?: return null
        val cmd = SafeCommands.lsNode(path) ?: return null
        return shell.exec(cmd)?.takeIf { it.ok }?.stdout?.map { it.trim() }?.filter { it.isNotEmpty() }
    }

    private fun privilegedExists(path: String): Boolean? {
        val shell = privilegedSysShell() ?: return null
        val cmd = SafeCommands.lsNode(path) ?: return null
        return shell.exec(cmd)?.let { it.ok }
    }

    private val capabilityDetector = CapabilityDetector { level ->
        when (level) {
            AccessLevel.ROOT -> rootShell.isAvailable()
            AccessLevel.SHIZUKU -> shizukuShell.isAvailable()
            AccessLevel.STANDARD -> true
        }
    }

    fun shizukuShell(): ShizukuShell = shizukuShell
    fun rootShell(): RootShell = rootShell

    /** Highest capability shell currently available, honoring preferred access mode. */
    private fun activeShell(): Shell {
        return if (getPreferredMode() == AccessMode.ROOT) {
            when {
                rootShell.isAvailable() -> rootShell
                shizukuShell.isAvailable() -> shizukuShell
                else -> standardShell
            }
        } else {
            when {
                shizukuShell.isAvailable() -> shizukuShell
                rootShell.isAvailable() -> rootShell
                else -> standardShell
            }
        }
    }

    private fun activeAccessLevel(): AccessLevel {
        return if (getPreferredMode() == AccessMode.ROOT) {
            when {
                rootShell.isAvailable() -> AccessLevel.ROOT
                shizukuShell.isAvailable() -> AccessLevel.SHIZUKU
                else -> AccessLevel.STANDARD
            }
        } else {
            when {
                shizukuShell.isAvailable() -> AccessLevel.SHIZUKU
                rootShell.isAvailable() -> AccessLevel.ROOT
                else -> AccessLevel.STANDARD
            }
        }
    }

    private val _snapshot = MutableStateFlow(MetricsSnapshot())
    val snapshot: StateFlow<MetricsSnapshot> = _snapshot.asStateFlow()

    private val _capabilities = MutableStateFlow<Capabilities?>(null)
    val capabilities: StateFlow<Capabilities?> = _capabilities.asStateFlow()

    fun refreshCapabilities() {
        _capabilities.value = capabilityDetector.detect(
            accessLevel = activeAccessLevel(),
            hasUsageAccess = foreground.hasUsageAccess(),
        )
    }

    fun hasUsageAccess(): Boolean = foreground.hasUsageAccess()
    fun socDescription(): String = capabilityDetector.socDescription()
    fun gpuVendor(): GpuVendor = gpu.vendor

    /** Release device-global collection state (SurfaceFlinger timestats). */
    fun shutdown() {
        fps.shutdown()
    }

    /**
     * Collect one snapshot across every metric. Called on a schedule by the service.
     * Each metric is sampled in isolation so a single failure stays local.
     */
    suspend fun sample(includeNetwork: Boolean, includeLatency: Boolean) {
        val level = activeAccessLevel()

        val usage = runCatching { cpu.sampleUsage() }.getOrNull()
        val freqs = runCatching { cpu.sampleFrequenciesMhz() }.getOrNull()
        val governor = runCatching { cpu.sampleGovernor() }.getOrNull()
        val ramSample = runCatching { ram.sample() }.getOrNull()
        val bat = runCatching { battery.sample() }.getOrNull()
        val temps = runCatching { thermal.sample() }.getOrNull()
        val disp = runCatching { display.sample() }.getOrNull()
        val fgPkg = runCatching { foreground.detect() }.getOrNull()
        val fpsSample = runCatching { fps.sample(fgPkg?.valueOrNull()) }.getOrNull()
        val net = if (includeNetwork) runCatching { network.sampleSpeed() }.getOrNull() else null

        // Rolling FPS statistics (average / 1% low) for the RTSS-style OSD. History is
        // per foreground app: switching apps starts a new window rather than mixing.
        val fgPkgName = fgPkg?.valueOrNull()
        if (fgPkgName != fpsStatsPkg) {
            fpsStats.clear()
            fpsStatsPkg = fgPkgName
        }
        fpsSample?.fps?.valueOrNull()?.let { fpsStats.feed(it) }
        val fpsAvgMetric = fpsStats.average()
            ?.let { Metric.Available(round1(it)) }
            ?: Metric.Unavailable("collecting frame history")
        val fpsLowMetric = fpsStats.onePercentLow()
            ?.let { Metric.Available(round1(it)) }
            ?: Metric.Unavailable("collecting frame history")

        val prev = _snapshot.value

        _snapshot.value = MetricsSnapshot(
            fps = fpsSample?.fps ?: Metric.Unavailable("FPS unavailable"),
            frameTimeMs = fpsSample?.frameTimeMs ?: Metric.Unavailable("frame time unavailable"),
            fpsAvg = fpsAvgMetric,
            fpsOnePercentLow = fpsLowMetric,
            displayRefreshRateHz = disp?.refreshRateHz ?: prev.displayRefreshRateHz,
            fpsSource = fpsSample?.source ?: "none",

            cpuTotalPercent = usage?.total ?: Metric.Unavailable("CPU sample failed"),
            perCorePercent = usage?.perCore ?: Metric.Unavailable("CPU per-core sample failed"),
            cpuFreqMhz = freqs ?: Metric.Unavailable("CPU frequency sample failed"),
            cpuGovernor = governor ?: Metric.Unavailable("governor unavailable"),
            cpuTempC = temps?.cpu ?: Metric.Unavailable("CPU temperature unavailable"),

            gpuUsagePercent = safeMetric { gpu.sampleUsage() },
            gpuFreqMhz = safeMetric { gpu.sampleFrequencyMhz() },
            gpuTempC = safeMetric { gpu.sampleTemperatureC() },
            gpuMemUsedMb = safeMetric { gpu.sampleMemoryUsedMb() },
            gpuVendor = gpu.vendor,

            ramUsedBytes = ramSample?.used ?: Metric.Unavailable("RAM sample failed"),
            ramTotalBytes = ramSample?.total ?: Metric.Unavailable("RAM sample failed"),
            ramAvailableBytes = ramSample?.available ?: Metric.Unavailable("RAM sample failed"),
            ramCachedBytes = ramSample?.cached ?: Metric.Unavailable("RAM cached unavailable"),

            deviceTempC = temps?.device ?: Metric.Unavailable("device temperature unavailable"),
            batteryTempC = if (bat?.tempC is Metric.Available) bat.tempC else (temps?.battery ?: Metric.Unavailable("battery temperature unavailable")),

            batteryLevelPercent = bat?.levelPercent ?: Metric.Unavailable("battery sample failed"),
            batteryCurrentUa = bat?.currentUa ?: Metric.Unavailable("battery current unavailable"),
            batteryVoltageMv = bat?.voltageMv ?: Metric.Unavailable("battery voltage unavailable"),
            batteryPowerW = bat?.powerW ?: Metric.Unavailable("battery power unavailable"),
            batteryCharging = bat?.charging ?: Metric.Unavailable("charging state unavailable"),

            netDownBytesPerSec = net?.downBps?.let { Metric.Available(it) }
                ?: if (includeNetwork) Metric.Unavailable("network counters unavailable") else Metric.Unavailable("network monitoring is off"),
            netUpBytesPerSec = net?.upBps?.let { Metric.Available(it) }
                ?: if (includeNetwork) Metric.Unavailable("network counters unavailable") else Metric.Unavailable("network monitoring is off"),
            netPingMs = if (includeLatency) {
                LatencyProbe.ping()?.let { Metric.Available(it) }
                    ?: Metric.Unavailable("ping failed or host unreachable")
            } else Metric.Unavailable("latency monitoring is off"),

            screenWidth = disp?.width ?: Metric.Unavailable("screen width unavailable"),
            screenHeight = disp?.height ?: Metric.Unavailable("screen height unavailable"),
            densityDpi = disp?.densityDpi ?: Metric.Unavailable("density unavailable"),

            foregroundPackage = fgPkg ?: Metric.Unavailable("foreground detection failed"),

            accessLevel = level,
            timestampMs = System.currentTimeMillis(),
        )
    }

    private fun <T> safeMetric(block: () -> Metric<T>): Metric<T> =
        runCatching(block).getOrElse { Metric.Unavailable("sample failed: ${it.javaClass.simpleName}") }

    private fun round1(v: Double) = kotlin.math.round(v * 10.0) / 10.0
}
