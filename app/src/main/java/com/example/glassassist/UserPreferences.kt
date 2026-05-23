package com.example.glassassist

import android.content.Context

class UserPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("glassassist_prefs", Context.MODE_PRIVATE)

    var userId: String?
        get() = prefs.getString("user_id", null)
        set(value) = prefs.edit().putString("user_id", value).apply()

    var apiUrl: String
        get() = prefs.getString("api_url", "http://175.196.239.135:8000") ?: "http://175.196.239.135:8000"
        set(value) = prefs.edit().putString("api_url", value).apply()

    var wsUrl: String
        get() = prefs.getString("ws_url", "ws://175.196.239.135:8765") ?: "ws://175.196.239.135:8765"
        set(value) = prefs.edit().putString("ws_url", value).apply()

    var handoverMemo: String?
        get() = prefs.getString("handover_memo", null)
        set(value) = prefs.edit().putString("handover_memo", value).apply()

    var dispatchWsUrl: String?
        get() = prefs.getString("dispatch_ws_url", null)
        set(value) = prefs.edit().putString("dispatch_ws_url", value).apply()
}
