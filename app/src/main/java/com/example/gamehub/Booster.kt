package com.example.gamehub

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings

data class GameApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
)

data class SystemStats(
    val ramUsedPct: Int,
    val ramUsedGb: Float,
    val ramTotalGb: Float,
    val batteryPct: Int,
    val charging: Boolean,
    val tempC: Float,
    val storageUsedPct: Int,
    val storageFreeGb: Float,
)

data class GameSettings(
    val forceStop: Boolean,
    val silent: Boolean,
)

class Booster(val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val prefs: SharedPreferences =
        context.getSharedPreferences("gamehub", Context.MODE_PRIVATE)

    // ---------- games ----------

    /** Installed launchable apps flagged as games by their developer. */
    fun findGames(): List<GameApp> =
        launchableApps()
            .filter { it.second.category == ApplicationInfo.CATEGORY_GAME }
            .map { (label, info) -> GameApp(info.packageName, label, info.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }

    private fun launchableApps(): List<Pair<String, ApplicationInfo>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .map { pm.getApplicationLabel(it).toString() to it }
    }

    fun launch(game: GameApp) {
        pm.getLaunchIntentForPackage(game.packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }

    // ---------- per-game settings and history ----------

    fun settings(pkg: String): GameSettings = GameSettings(
        forceStop = prefs.getBoolean("fs_$pkg", true),
        silent = prefs.getBoolean("silent_$pkg", false),
    )

    fun setForceStop(pkg: String, on: Boolean) {
        prefs.edit().putBoolean("fs_$pkg", on).apply()
    }

    fun setSilent(pkg: String, on: Boolean) {
        prefs.edit().putBoolean("silent_$pkg", on).apply()
    }

    fun launchCount(pkg: String): Int = prefs.getInt("launches_$pkg", 0)

    fun lastGame(): String? = prefs.getString("last_game", null)

    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay", false)
        set(value) {
            prefs.edit().putBoolean("overlay", value).apply()
        }

    // ---------- live system stats ----------

    fun readStats(): SystemStats {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = 1024f * 1024f * 1024f
        val total = mi.totalMem.toFloat()
        val used = total - mi.availMem.toFloat()

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val fs = StatFs(Environment.getDataDirectory().path)
        val storageTotal = fs.totalBytes.toFloat()
        val storageFree = fs.availableBytes.toFloat()

        return SystemStats(
            ramUsedPct = if (total > 0f) (used / total * 100f).toInt() else 0,
            ramUsedGb = used / gb,
            ramTotalGb = total / gb,
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else 0,
            charging = charging,
            tempC = tempTenths / 10f,
            storageUsedPct = if (storageTotal > 0f) ((storageTotal - storageFree) / storageTotal * 100f).toInt() else 0,
            storageFreeGb = storageFree / gb,
        )
    }

    // ---------- boosting ----------

    /** Apps we never stop: ourselves, Shizuku, the keyboard and the home launcher. */
    private fun protectedPackages(): Set<String> {
        val skip = mutableSetOf(context.packageName, ShizukuHelper.SHIZUKU_PACKAGE)
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.let { skip += it }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.let { skip += it }
        return skip
    }

    /**
     * Stops every other user-installed app.
     * With Shizuku power mode: a real `am force-stop` on each app.
     * Without it: killBackgroundProcesses(), which is only best-effort.
     * Returns how many apps were targeted.
     */
    fun clearBackgroundApps(keepPackage: String?): Int {
        val skip = protectedPackages() + listOfNotNull(keepPackage)
        val targets = launchableApps()
            .map { it.second }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { it.packageName }
            .filter { it !in skip }

        if (targets.isEmpty()) return 0

        if (ShizukuHelper.hasPermission()) {
            ShizukuHelper.exec(targets.joinToString("; ") { "am force-stop $it" })
        } else {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            targets.forEach { am.killBackgroundProcesses(it) }
        }
        return targets.size
    }

    /** Asks Android's Game Mode for the performance profile (Android 12+, needs Shizuku). */
    fun setPerformanceMode(packageName: String) {
        if (ShizukuHelper.hasPermission()) {
            ShizukuHelper.exec("cmd game mode performance $packageName")
        }
    }

    /** One-tap flow: clean up, silence, switch profile, show panel, launch. */
    fun startGame(game: GameApp): String {
        val s = settings(game.packageName)
        val parts = mutableListOf<String>()

        if (s.forceStop) {
            val n = clearBackgroundApps(game.packageName)
            parts += "stopped $n apps"
        }
        if (s.silent) {
            parts += if (enableGameDnd()) "notifications silenced" else "no Do Not Disturb access"
        }
        setPerformanceMode(game.packageName)

        prefs.edit()
            .putString("last_game", game.packageName)
            .putInt("launches_${game.packageName}", launchCount(game.packageName) + 1)
            .apply()

        if (overlayEnabled && canDrawOverlays()) {
            OverlayService.start(context)
            parts += "panel on"
        }

        launch(game)
        return game.label + ": " + if (parts.isEmpty()) "launched" else parts.joinToString(", ")
    }

    fun restoreAll() {
        restoreDnd()
        OverlayService.stop(context)
    }

    // ---------- overlay permission ----------

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---------- Do Not Disturb ----------

    fun hasDndAccess(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    fun dndSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun isDndOn(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val f = nm.currentInterruptionFilter
        return f != NotificationManager.INTERRUPTION_FILTER_ALL &&
            f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /** Silences notifications (priority-only) so nothing interrupts the game. */
    fun enableGameDnd(): Boolean {
        if (!hasDndAccess()) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!prefs.contains("prev_filter")) {
            prefs.edit().putInt("prev_filter", nm.currentInterruptionFilter).apply()
        }
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        return true
    }

    fun restoreDnd() {
        if (!hasDndAccess()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val prev = prefs.getInt("prev_filter", NotificationManager.INTERRUPTION_FILTER_ALL)
        val target =
            if (prev == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                NotificationManager.INTERRUPTION_FILTER_ALL
            } else {
                prev
            }
        nm.setInterruptionFilter(target)
        prefs.edit().remove("prev_filter").apply()
    }
}
