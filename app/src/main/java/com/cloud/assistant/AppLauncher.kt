package com.cloud.assistant

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

object AppLauncher {

    private data class InstalledApp(val label: String, val packageName: String)

    private val SYSTEM_INTENTS = setOf("maps", "phone", "sms", "email", "settings", "chrome")

    private fun queryLaunchableApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = info.loadLabel(pm)?.toString() ?: return@mapNotNull null
                InstalledApp(label, pkg)
            }
            .distinctBy { it.packageName }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[\\s._-]"), "")

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
            else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
        return dp[a.length][b.length]
    }

    /** Resolves a spoken/typed app name against actually-installed apps. Null = no confident match. */
    private fun resolvePackage(context: Context, spokenName: String): InstalledApp? {
        val apps = queryLaunchableApps(context)
        val target = normalize(spokenName)
        if (target.isEmpty() || apps.isEmpty()) return null

        apps.firstOrNull { normalize(it.label) == target }?.let { return it }
        apps.firstOrNull { normalize(it.label).contains(target) || target.contains(normalize(it.label)) }
            ?.let { return it }

        val best = apps.minByOrNull { levenshtein(normalize(it.label), target) } ?: return null
        val distance = levenshtein(normalize(best.label), target)
        val threshold = (maxOf(target.length, normalize(best.label).length) * 0.34).toInt().coerceAtLeast(1)
        return if (distance <= threshold) best else null
    }

    /** Returns true if something was actually opened. */
    fun open(context: Context, name: String): Boolean {
        val key = name.lowercase().trim()
        return try {
            if (key in SYSTEM_INTENTS) return openSystemIntent(context, key)
            val app = resolvePackage(context, name) ?: return false
            val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun openSystemIntent(context: Context, key: String): Boolean {
        val intent = when (key) {
            "maps" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
            "phone" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
            "sms" -> Intent(Intent.ACTION_VIEW, Uri.parse("sms:"))
            "email" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "chrome" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            else -> return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    /**
     * Best-effort close: only kills the target's BACKGROUND process.
     * Android has no public API to force-stop another app's foreground
     * process without root/device-owner — this is a hard OS limitation.
     */
    fun close(context: Context, name: String): Boolean {
        val app = resolvePackage(context, name) ?: return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return try {
            am.killBackgroundProcesses(app.packageName)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
