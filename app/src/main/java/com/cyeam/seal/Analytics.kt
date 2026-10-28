package com.cyeam.seal

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

class Analytics(context: Context) {
    private val firebase = FirebaseAnalytics.getInstance(context)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("analytics", true)
        set(value) { prefs.edit().putBoolean("analytics", value).apply(); firebase.setAnalyticsCollectionEnabled(value) }
    init { firebase.setAnalyticsCollectionEnabled(enabled) }
    fun event(name: String, vararg parameters: Pair<String, Int>) {
        if (!enabled) return
        // Counts/ranks only. Never send images, recognized text, file names, or file URIs.
        runCatching { firebase.logEvent(name, Bundle().apply { parameters.forEach { putLong(it.first, it.second.toLong()) } }) }
    }
}
