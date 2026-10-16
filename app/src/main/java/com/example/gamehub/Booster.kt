package com.example.gamehub

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings

data class GameApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
)

class Booster(val context: Context) {

    private val pm: PackageManager = context.packageManager
    private var previousFilter: Int? = null

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
     * Without it: killBackgroundProcesses(), which Android only honours for
     * apps that are in the background, and which many apps undo by restarting.
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

    fun hasDndAccess(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    fun dndSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Silences notifications (priority-only) so nothing interrupts the game. */
    fun enableGameDnd(): Boolean {
        if (!hasDndAccess()) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (previousFilter == null) previousFilter = nm.currentInterruptionFilter
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        return true
    }

    fun restoreDnd() {
        if (!hasDndAccess()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        previousFilter?.let { nm.setInterruptionFilter(it) }
        previousFilter = null
    }

    fun launch(game: GameApp) {
        pm.getLaunchIntentForPackage(game.packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }
}
