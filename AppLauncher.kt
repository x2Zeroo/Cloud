package com.cloud.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object AppLauncher {
    private val PACKAGE_MAP = mapOf(
        "youtube" to "com.google.android.youtube",
        "line" to "jp.naver.line.android",
        "facebook" to "com.facebook.katana",
        "instagram" to "com.instagram.android"
    )

    fun open(context: Context, name: String) {
        val key = name.lowercase().trim()
        try {
            when (key) {
                "youtube", "line", "facebook", "instagram" -> {
                    val pkg = PACKAGE_MAP[key] ?: return
                    context.packageManager.getLaunchIntentForPackage(pkg)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { context.startActivity(it) }
                }
                "chrome" -> context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "maps" -> context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "phone" -> context.startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "sms" -> context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("sms:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "email" -> context.startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "settings" -> context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (e: ActivityNotFoundException) {
            // Target app not installed — best-effort, same as original JS scheme fallback.
        }
    }
}
