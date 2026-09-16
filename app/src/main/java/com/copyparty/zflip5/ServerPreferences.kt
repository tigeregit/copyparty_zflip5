package com.copyparty.zflip5

import android.content.Context
import android.net.Uri

class ServerPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value.coerceIn(1024, 65535)).apply()

    var readOnly: Boolean
        get() = prefs.getBoolean(KEY_READ_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_READ_ONLY, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    var treeUri: String?
        get() = prefs.getString(KEY_TREE_URI, null)
        set(value) = prefs.edit().putString(KEY_TREE_URI, value).apply()

    /** Empty / null = auto (listen 0.0.0.0, display best LAN IP). */
    var bindIp: String?
        get() = prefs.getString(KEY_BIND_IP, null)?.takeIf { it.isNotBlank() }
        set(value) {
            val v = value?.trim()?.ifBlank { null }
            val ed = prefs.edit()
            if (v == null) ed.remove(KEY_BIND_IP) else ed.putString(KEY_BIND_IP, v)
            ed.apply()
        }

    var bindIface: String?
        get() = prefs.getString(KEY_BIND_IFACE, null)?.takeIf { it.isNotBlank() }
        set(value) {
            val v = value?.trim()?.ifBlank { null }
            val ed = prefs.edit()
            if (v == null) ed.remove(KEY_BIND_IFACE) else ed.putString(KEY_BIND_IFACE, v)
            ed.apply()
        }

    fun treeUriOrNull(): Uri? = treeUri?.let { Uri.parse(it) }

    companion object {
        const val PREFS = "server_prefs"
        const val KEY_PORT = "port"
        const val KEY_READ_ONLY = "read_only"
        const val KEY_PASSWORD = "password"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_BIND_IP = "bind_ip"
        const val KEY_BIND_IFACE = "bind_iface"
        const val DEFAULT_PORT = 3923
    }
}
