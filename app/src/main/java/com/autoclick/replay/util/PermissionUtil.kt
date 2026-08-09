package com.autoclick.replay.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

object PermissionUtil {
    fun isAccessibilityEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expected = "${context.packageName}/${serviceClass.canonicalName}"
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val colon = TextUtils.SimpleStringSplitter(':')
        colon.setString(enabled)
        while (colon.hasNext()) {
            if (colon.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
    fun openAccessibilitySettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}
