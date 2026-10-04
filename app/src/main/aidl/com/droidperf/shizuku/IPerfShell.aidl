// AIDL interface for our Shizuku user service, which runs in a separate process with
// shell (uid 2000) privilege. Shizuku reserves transaction code 16777114 for destroy().
package com.droidperf.shizuku;

interface IPerfShell {

    /** Reserved destroy method defined by the Shizuku server. Must keep this exact id. */
    void destroy() = 16777114;

    /** Exit method defined by this app. */
    void exit() = 1;

    /**
     * Run one allow-listed, read-only command as the shell user.
     * Returns [exitCode, stdout, stderr] so the caller can tell "unsupported" from "error".
     */
    String[] exec(String command) = 2;
}
