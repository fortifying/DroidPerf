package com.droidperf.system.shell

/**
 * The complete set of commands this app is ever allowed to run. Centralizing them here
 * means no string from settings, the UI, or any external source can ever reach a shell.
 *
 * Everything is read-only: no command here writes to the filesystem or changes device
 * state. This app monitors, it does not tune.
 */
object SafeCommands {

    /** Foreground package name via dumpsys activity (needs shell/root on modern Android). */
    const val FOREGROUND_ACTIVITY =
        "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity'"

    /** SurfaceFlinger layer list, used to find the target app's layer. */
    const val SF_LAYER_LIST = "dumpsys SurfaceFlinger --list"

    /**
     * Frame latency for one layer. The layer name is looked up from SF_LAYER_LIST and
     * validated to a safe character set before being interpolated (see [layerLatency]).
     */
    fun layerLatency(layer: String): String = "dumpsys SurfaceFlinger --latency '$layer'"

    /** Per-process framestats for a package (Android 6+). */
    fun gfxInfoFramestats(pkg: String): String = "dumpsys gfxinfo '$pkg' framestats"

    /**
     * SurfaceFlinger timestats: the only per-layer FPS source on Android 14+ builds
     * (incl. HyperOS) where the legacy `--latency` output no longer reports frames.
     * Stateful by design: enable once, then dump+clear each sample window, disable on
     * shutdown. All four forms are fixed commands; nothing is interpolated.
     */
    const val SF_TIMESTATS_ENABLE = "dumpsys SurfaceFlinger --timestats -enable"
    const val SF_TIMESTATS_DUMP = "dumpsys SurfaceFlinger --timestats -dump"
    const val SF_TIMESTATS_CLEAR = "dumpsys SurfaceFlinger --timestats -clear"
    const val SF_TIMESTATS_DISABLE = "dumpsys SurfaceFlinger --timestats -disable"

    /** Android standard Thermal HAL service (Android 10+). */
    const val THERMAL_SERVICE = "dumpsys thermalservice"

    /** CPU frequency snapshot across all cores. */
    const val CPU_FREQ =
        "cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq"

    /** GPU clock via devfreq, if present. */
    const val GPU_DEVFREQ_CUR_FREQ = "cat /sys/class/devfreq/*/cur_freq"

    /** GPU busy percentage via devfreq, if present. */
    const val GPU_DEVFREQ_LOAD = "cat /sys/class/devfreq/*/load"

    /** KGSL (Adreno) GPU busy percentage. */
    const val KGSL_GPU_BUSY = "cat /sys/class/kgsl/kgsl-3d0/gpubusy"

    /** KGSL (Adreno) current clock. */
    const val KGSL_GPU_CLOCK = "cat /sys/class/kgsl/kgsl-3d0/gpuclk"

    /** Full /proc/stat snapshot, for CPU ticks on kernels that block it for apps. */
    const val PROC_STAT = "cat /proc/stat"

    /**
     * Validate a sysfs/procfs node path before it is interpolated into a cat/ls command.
     * Only absolute /sys or /proc paths over a safe charset (no spaces, quotes, globs,
     * or shell metacharacters) are accepted, so injection is impossible.
     */
    private val NODE_PATH = Regex("""/(sys|proc)/[A-Za-z0-9/._-]{1,200}""")

    fun isSafeNodePath(path: String): Boolean = NODE_PATH.matches(path)

    /** Read-only cat of one validated node path. */
    fun catNode(path: String): String? = if (isSafeNodePath(path)) "cat $path" else null

    /** Read-only ls of one validated node path (existence check / directory listing). */
    fun lsNode(path: String): String? = if (isSafeNodePath(path)) "ls $path" else null

    /**
     * Validate a SurfaceFlinger layer name before it is interpolated into a command.
     * Real layer names look like `SurfaceView[com.pkg/Activity]#0`, so brackets, `#`, and
     * spaces must be allowed. Everything else (quotes, `;`, `|`, `&`, backticks, `$`,
     * newlines) is rejected rather than escaped, so injection is impossible.
     */
    fun isSafeLayerName(name: String): Boolean =
        name.isNotBlank() &&
            name.length <= 200 &&
            name.all { it.isLetterOrDigit() || it in "/._-:#@ []()" }

    fun isSafePackageName(name: String): Boolean =
        name.isNotBlank() &&
            name.length <= 200 &&
            name.all { it.isLetterOrDigit() || it in "._-" }

    /**
     * The complete allow-list of command *shapes* this app may run through a privileged
     * shell. The user service re-checks every command against this, so even a
     * compromised client cannot escalate to arbitrary execution.
     *
     * Each entry is a regex that matches a whole command. Anything not matching is refused.
     */
    private val ALLOWED_PATTERNS: List<Regex> = listOf(
        // dumpsys activity (foreground package)
        Regex("""dumpsys activity activities \| grep -E 'mResumedActivity\|topResumedActivity\|ResumedActivity'"""),
        // SurfaceFlinger layer list and per-layer latency. Layer names include
        // parentheses on Android 12+ ("...(BLAST)#0"), so ( ) are allowed.
        Regex("""dumpsys SurfaceFlinger --list"""),
        Regex("""dumpsys SurfaceFlinger --latency '[A-Za-z0-9/._:#@\[\]\- ()]{1,200}'"""),
        // SurfaceFlinger timestats (fixed forms only)
        Regex("""dumpsys SurfaceFlinger --timestats -enable"""),
        Regex("""dumpsys SurfaceFlinger --timestats -dump"""),
        Regex("""dumpsys SurfaceFlinger --timestats -clear"""),
        Regex("""dumpsys SurfaceFlinger --timestats -disable"""),
        // Android Thermal HAL service
        Regex("""dumpsys thermalservice"""),
        // Per-process frame stats
        Regex("""dumpsys gfxinfo '[A-Za-z0-9._\-]{1,200}' framestats"""),
        // Read-only sysfs/proc snapshots
        Regex("""cat /sys/devices/system/cpu/cpu\*/cpufreq/scaling_cur_freq"""),
        Regex("""cat /sys/class/devfreq/\*/cur_freq"""),
        Regex("""cat /sys/class/devfreq/\*/load"""),
        Regex("""cat /sys/class/kgsl/kgsl-3d0/gpubusy"""),
        Regex("""cat /sys/class/kgsl/kgsl-3d0/gpuclk"""),
        // Validated sysfs/procfs reads used as the privileged fallback for nodes that
        // SELinux blocks for the app process (e.g. /proc/stat, KGSL on HyperOS).
        Regex("""cat /(sys|proc)/[A-Za-z0-9/._\-]{1,200}"""),
        Regex("""ls /(sys|proc)/[A-Za-z0-9/._\-]{1,200}"""),
    )

    /** True only for a command this app itself defined. */
    fun isAllowedCommand(command: String): Boolean =
        ALLOWED_PATTERNS.any { it.matches(command) }
}
