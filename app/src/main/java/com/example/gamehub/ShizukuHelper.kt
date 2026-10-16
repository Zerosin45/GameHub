package com.example.gamehub

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Thin wrapper around Shizuku: lets Game Hub run shell commands with ADB-level rights. */
object ShizukuHelper {
    const val REQUEST_CODE = 1001
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** True when the Shizuku service is started (via wireless debugging or root). */
    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** True when the Shizuku service is running AND this app was granted access. */
    fun hasPermission(): Boolean = try {
        isRunning() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {
        }
    }

    /**
     * Runs a shell command with Shizuku's privileges. Shizuku 13.x made newProcess()
     * private, so it is called through reflection (the widely used workaround).
     */
    fun exec(command: String): Boolean = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        process.waitFor() == 0
    } catch (_: Throwable) {
        false
    }
}
