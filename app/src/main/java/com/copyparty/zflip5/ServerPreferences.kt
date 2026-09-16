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

    /**
     * Comma-separated bind targets.
     * - empty / blank → Auto (listen 0.0.0.0, display best LAN IP)
     * - "0.0.0.0" → explicit all IPv4
     * - "a,b,c" → specific IPs for copyparty -i
     *
     * Migrates legacy single [KEY_BIND_IP] on first read if [KEY_BIND_IPS] missing.
     */
    var bindIps: String
        get() {
            if (!prefs.contains(KEY_BIND_IPS)) {
                migrateLegacyBind()
            }
            return prefs.getString(KEY_BIND_IPS, "")?.trim().orEmpty()
        }
        set(value) {
            val normalized = normalizeBindIps(value)
            val ed = prefs.edit().putString(KEY_BIND_IPS, normalized)
            // Keep legacy keys in sync for older readers / debugging
            when {
                normalized.isEmpty() -> {
                    ed.remove(KEY_BIND_IP)
                    ed.remove(KEY_BIND_IFACE)
                }
                normalized == BIND_ALL -> {
                    ed.putString(KEY_BIND_IP, BIND_ALL)
                    ed.remove(KEY_BIND_IFACE)
                }
                else -> {
                    val first = normalized.split(',').firstOrNull()?.trim().orEmpty()
                    if (first.isNotEmpty()) ed.putString(KEY_BIND_IP, first)
                    else ed.remove(KEY_BIND_IP)
                    // iface unknown for multi; clear
                    ed.remove(KEY_BIND_IFACE)
                }
            }
            ed.apply()
        }

    /** Parsed list of specific IPs (empty when auto or explicit 0.0.0.0). */
    fun selectedBindIpList(): List<String> {
        val raw = bindIps
        if (raw.isEmpty() || raw == BIND_ALL) return emptyList()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != BIND_ALL }
            .distinct()
    }

    fun isBindAuto(): Boolean = bindIps.isEmpty()

    fun isBindAll(): Boolean = bindIps == BIND_ALL

    /**
     * Backward-compatible single bind IP.
     * null/blank = auto; "0.0.0.0" = all; otherwise first selected IP.
     */
    var bindIp: String?
        get() {
            val ips = bindIps
            return when {
                ips.isEmpty() -> null
                ips == BIND_ALL -> BIND_ALL
                else -> ips.split(',').firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            }
        }
        set(value) {
            val v = value?.trim()?.ifBlank { null }
            bindIps = when {
                v == null -> ""
                v == BIND_ALL -> BIND_ALL
                else -> v
            }
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

    private fun migrateLegacyBind() {
        val legacy = prefs.getString(KEY_BIND_IP, null)?.trim()?.takeIf { it.isNotBlank() }
        val migrated = when {
            legacy == null -> ""
            legacy == BIND_ALL -> BIND_ALL
            else -> legacy
        }
        prefs.edit().putString(KEY_BIND_IPS, migrated).apply()
    }

    companion object {
        const val PREFS = "server_prefs"
        const val KEY_PORT = "port"
        const val KEY_READ_ONLY = "read_only"
        const val KEY_PASSWORD = "password"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_BIND_IP = "bind_ip"
        const val KEY_BIND_IFACE = "bind_iface"
        const val KEY_BIND_IPS = "bind_ips"
        const val BIND_ALL = "0.0.0.0"
        const val DEFAULT_PORT = 3923

        fun normalizeBindIps(raw: String?): String {
            val parts = raw.orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (parts.isEmpty()) return ""
            if (parts.any { it == BIND_ALL }) return BIND_ALL
            return parts.joinToString(",")
        }
    }
}
