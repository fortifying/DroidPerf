package com.droidperf.settings

import android.content.Context
import android.content.SharedPreferences
import com.droidperf.di.ServiceLocator
import com.droidperf.domain.AccessMode
import com.droidperf.system.shell.RootShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which metric groups the user wants shown, and in which preset. */
enum class OverlayPreset { MINIMAL, GAMING, DETAILED, THERMAL, BATTERY, CUSTOM }

/**
 * Visual renderer for the overlay:
 * - CLASSIC: original single-color list
 * - MODERN: modern rounded horizontal pill HUD with color-coded chips
 * - MINIMAL: ultra-compact single badge
 * - RTSS: MSI Afterburner / RTSS two-column OSD
 */
enum class OsdStyle { CLASSIC, MODERN, RTSS }

/** Predefined screen anchors for the overlay. */
enum class OverlayPosition {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    CENTER_LEFT, CENTER, CENTER_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,
    CUSTOM;

    val label: String
        get() = when (this) {
            TOP_LEFT -> "Top Left"
            TOP_CENTER -> "Top Center"
            TOP_RIGHT -> "Top Right"
            CENTER_LEFT -> "Center Left"
            CENTER -> "Center"
            CENTER_RIGHT -> "Center Right"
            BOTTOM_LEFT -> "Bottom Left"
            BOTTOM_CENTER -> "Bottom Center"
            BOTTOM_RIGHT -> "Bottom Right"
            CUSTOM -> "Custom"
        }
}

data class OverlayConfig(
    val enabled: Boolean = false,
    val preset: OverlayPreset = OverlayPreset.GAMING,
    val osdStyle: OsdStyle = OsdStyle.MODERN,
    val position: OverlayPosition = OverlayPosition.TOP_RIGHT,
    val marginPx: Int = 24,
    val x: Int = 24,
    val y: Int = 120,
    val landscapeX: Int = -1,
    val landscapeY: Int = -1,
    val textSizeSp: Float = 11f,
    val textOpacity: Float = 1.0f,
    val bgOpacity: Float = 0.85f,
    val opacity: Float = 1.0f,
    val backgroundColor: Int = 0xDE0B0F15.toInt(),
    val textColor: Int = 0xFF7CFC00.toInt(),
    val compactMode: Boolean = false,
    val showFps: Boolean = true,
    val showCpu: Boolean = true,
    val showPerCore: Boolean = false,
    val showGpu: Boolean = true,
    val showRam: Boolean = true,
    val showBatteryTemp: Boolean = true,
    val showCpuTemp: Boolean = true,
    val showTemperature: Boolean = true,
    val showFrameTime: Boolean = true,
    val showBattery: Boolean = false,
    val showNetwork: Boolean = false,
    val showDisplay: Boolean = false,
    val showApp: Boolean = true,
    val networkEnabled: Boolean = false,
    val latencyEnabled: Boolean = false,
    val sampleIntervalMs: Long = 1000L,
    val startOnLaunch: Boolean = false,
)

/**
 * Persisted settings backed by SharedPreferences, exposed as a StateFlow so the overlay
 * reacts to changes without polling.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("perf_overlay_prefs", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<OverlayConfig> = _config.asStateFlow()

    fun current(): OverlayConfig = _config.value

    fun update(transform: (OverlayConfig) -> OverlayConfig) {
        val next = transform(_config.value)
        _config.value = next
        save(next)
    }

    fun hasSeenPrivilegedPrompt(): Boolean = prefs.getBoolean("has_seen_privileged_prompt", false)

    fun setPrivilegedPromptSeen(seen: Boolean = true) {
        prefs.edit().putBoolean("has_seen_privileged_prompt", seen).apply()
    }

    fun isSetupWizardCompleted(): Boolean = prefs.getBoolean("is_setup_wizard_completed", false)

    fun setSetupWizardCompleted(completed: Boolean = true) {
        prefs.edit().putBoolean("is_setup_wizard_completed", completed).apply()
    }

    fun getPreferredAccessMode(): AccessMode {
        val explicit = prefs.getBoolean("user_explicitly_chose_mode", false)
        val name = prefs.getString("preferred_access_mode", null)
        if (name != null && explicit) {
            try {
                return AccessMode.valueOf(name)
            } catch (_: Throwable) {}
        }
        return when {
            RootShell.hasSuBinary() -> AccessMode.ROOT
            ServiceLocator.shizuku.hasPermission() -> AccessMode.SHIZUKU
            name != null -> try { AccessMode.valueOf(name) } catch (_: Throwable) { AccessMode.ROOT }
            else -> AccessMode.ROOT
        }
    }

    fun setPreferredAccessMode(mode: AccessMode) {
        prefs.edit()
            .putString("preferred_access_mode", mode.name)
            .putBoolean("user_explicitly_chose_mode", true)
            .apply()
    }

    fun setPreset(preset: OverlayPreset) {
        if (preset == OverlayPreset.CUSTOM) {
            update { it.copy(preset = preset) }
            return
        }
        val v = when (preset) {
            OverlayPreset.MINIMAL -> booleanArrayOf(true, true, false, true, true, false, false, false, false, true)
            OverlayPreset.GAMING -> booleanArrayOf(true, true, true, true, true, true, false, false, false, true)
            OverlayPreset.DETAILED -> booleanArrayOf(true, true, true, true, true, true, true, true, true, true)
            OverlayPreset.THERMAL -> booleanArrayOf(false, true, false, false, true, true, false, false, false, true)
            OverlayPreset.BATTERY -> booleanArrayOf(false, false, false, false, false, false, true, false, false, false)
            OverlayPreset.CUSTOM -> return
        }
        update {
            it.copy(
                preset = preset,
                showFps = v[0], showCpu = v[1], showPerCore = v[2], showGpu = v[3],
                showRam = v[4], showBatteryTemp = v[5], showCpuTemp = v[5], showTemperature = v[5], showBattery = v[6],
                showNetwork = v[7], showDisplay = v[8], showApp = v[9],
            )
        }
    }

    private fun load(): OverlayConfig {
        val d = OverlayConfig()
        return OverlayConfig(
            enabled = prefs.getBoolean("enabled", d.enabled),
            preset = runCatching { OverlayPreset.valueOf(prefs.getString("preset", d.preset.name)!!) }
                .getOrDefault(d.preset),
            osdStyle = runCatching { OsdStyle.valueOf(prefs.getString("osdStyle", d.osdStyle.name)!!) }
                .getOrDefault(d.osdStyle),
            position = runCatching { OverlayPosition.valueOf(prefs.getString("position", d.position.name)!!) }
                .getOrDefault(d.position),
            marginPx = prefs.getInt("marginPx", d.marginPx),
            x = prefs.getInt("x", d.x),
            y = prefs.getInt("y", d.y),
            landscapeX = prefs.getInt("landscapeX", d.landscapeX),
            landscapeY = prefs.getInt("landscapeY", d.landscapeY),
            textSizeSp = prefs.getFloat("textSizeSp", d.textSizeSp),
            textOpacity = prefs.getFloat("textOpacity", prefs.getFloat("opacity", d.textOpacity)),
            bgOpacity = prefs.getFloat("bgOpacity", d.bgOpacity),
            opacity = prefs.getFloat("textOpacity", prefs.getFloat("opacity", d.opacity)),
            backgroundColor = prefs.getInt("backgroundColor", d.backgroundColor),
            textColor = prefs.getInt("textColor", d.textColor),
            compactMode = prefs.getBoolean("compactMode", d.compactMode),
            showFps = prefs.getBoolean("showFps", d.showFps),
            showCpu = prefs.getBoolean("showCpu", d.showCpu),
            showPerCore = prefs.getBoolean("showPerCore", d.showPerCore),
            showGpu = prefs.getBoolean("showGpu", d.showGpu),
            showRam = prefs.getBoolean("showRam", d.showRam),
            showBatteryTemp = prefs.getBoolean("showBatteryTemp", prefs.getBoolean("showTemperature", d.showBatteryTemp)),
            showCpuTemp = prefs.getBoolean("showCpuTemp", d.showCpuTemp),
            showTemperature = prefs.getBoolean("showTemperature", d.showTemperature),
            showFrameTime = prefs.getBoolean("showFrameTime", d.showFrameTime),
            showBattery = prefs.getBoolean("showBattery", d.showBattery),
            showNetwork = prefs.getBoolean("showNetwork", d.showNetwork),
            showDisplay = prefs.getBoolean("showDisplay", d.showDisplay),
            showApp = prefs.getBoolean("showApp", d.showApp),
            networkEnabled = prefs.getBoolean("networkEnabled", d.networkEnabled),
            latencyEnabled = prefs.getBoolean("latencyEnabled", d.latencyEnabled),
            sampleIntervalMs = prefs.getLong("sampleIntervalMs", d.sampleIntervalMs),
            startOnLaunch = prefs.getBoolean("startOnLaunch", d.startOnLaunch),
        )
    }

    private fun save(c: OverlayConfig) {
        prefs.edit().apply {
            putBoolean("enabled", c.enabled)
            putString("preset", c.preset.name)
            putString("osdStyle", c.osdStyle.name)
            putString("position", c.position.name)
            putInt("marginPx", c.marginPx)
            putInt("x", c.x); putInt("y", c.y)
            putInt("landscapeX", c.landscapeX); putInt("landscapeY", c.landscapeY)
            putFloat("textSizeSp", c.textSizeSp)
            putFloat("textOpacity", c.textOpacity)
            putFloat("bgOpacity", c.bgOpacity)
            putFloat("opacity", c.textOpacity)
            putInt("backgroundColor", c.backgroundColor)
            putInt("textColor", c.textColor)
            putBoolean("compactMode", c.compactMode)
            putBoolean("showFps", c.showFps)
            putBoolean("showCpu", c.showCpu)
            putBoolean("showPerCore", c.showPerCore)
            putBoolean("showGpu", c.showGpu)
            putBoolean("showRam", c.showRam)
            putBoolean("showBatteryTemp", c.showBatteryTemp)
            putBoolean("showCpuTemp", c.showCpuTemp)
            putBoolean("showTemperature", c.showBatteryTemp)
            putBoolean("showFrameTime", c.showFrameTime)
            putBoolean("showBattery", c.showBattery)
            putBoolean("showNetwork", c.showNetwork)
            putBoolean("showDisplay", c.showDisplay)
            putBoolean("showApp", c.showApp)
            putBoolean("networkEnabled", c.networkEnabled)
            putBoolean("latencyEnabled", c.latencyEnabled)
            putLong("sampleIntervalMs", c.sampleIntervalMs)
            putBoolean("startOnLaunch", c.startOnLaunch)
        }.apply()
    }
}
