package com.copyparty.zflip5

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

data class LanInterface(
    val name: String,
    val ip: String
) {
    fun label(): String = "$name — $ip"
}

object NetworkUtils {

    /** Hard-exclude virtual / container / VPN-looking iface name prefixes. */
    private val EXCLUDED_NAME_PREFIXES = listOf(
        "docker", "veth", "br-", "tun", "tap", "vpn", "dummy",
        "rmnet_data", "rmnet", "ccmni", "p2p", "ap", "swlan", "sit", "ip6tnl",
        "clat", "lxc", "virbr", "vmnet", "vboxnet", "zt", "wg", "tailscale"
    )

    fun listUsableIpv4(context: Context): List<LanInterface> {
        val preferredFromCm = connectivityPreferredIps(context).toSet()
        val hasWifiPreferred = preferredFromCm.isNotEmpty()

        val found = mutableListOf<LanInterface>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name ?: continue
                if (isExcludedName(name, hasWifiPreferred)) continue
                val addrs = ni.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                for (addr in addrs) {
                    val ip = addr.hostAddress ?: continue
                    if (ip == "0.0.0.0") continue
                    found.add(LanInterface(name, ip))
                }
            }
        } catch (_: Exception) {
        }

        // Deduplicate by IP (keep first name), then rank
        val byIp = linkedMapOf<String, LanInterface>()
        for (item in found) {
            if (!byIp.containsKey(item.ip)) byIp[item.ip] = item
        }
        return byIp.values.sortedWith(compareByDescending<LanInterface> {
            score(it, preferredFromCm)
        }.thenBy { it.name })
    }

    /**
     * Best display / advertise IPv4 for LAN URL.
     * Uses saved bindIp when set and still present; otherwise auto ranking.
     */
    fun displayIpv4(context: Context, prefs: ServerPreferences): String {
        val selected = prefs.bindIp?.trim().orEmpty()
        if (selected.isNotEmpty()) {
            val stillThere = listUsableIpv4(context).any { it.ip == selected }
            if (stillThere) return selected
            // Fall through to auto if saved IP disappeared
        }
        return autoPickIpv4(context) ?: "0.0.0.0"
    }

    fun autoPickIpv4(context: Context): String? =
        listUsableIpv4(context).firstOrNull()?.ip

    /**
     * Host passed to copyparty `-i`.
     * Empty / auto → 0.0.0.0 (listen all); specific → that IP.
     */
    fun bindHostForCopyparty(prefs: ServerPreferences): String {
        val selected = prefs.bindIp?.trim().orEmpty()
        return if (selected.isEmpty()) "0.0.0.0" else selected
    }

    fun baseUrl(context: Context, prefs: ServerPreferences): String {
        val ip = displayIpv4(context, prefs)
        return "http://$ip:${prefs.port}/"
    }

    fun baseUrl(context: Context, port: Int): String {
        // Backward-compatible: auto display IP + given port
        val ip = autoPickIpv4(context) ?: "0.0.0.0"
        return "http://$ip:$port/"
    }

    private fun connectivityPreferredIps(context: Context): List<String> {
        val out = mutableListOf<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // Prefer Wi-Fi / Ethernet networks (active + all)
            val networks = buildList {
                cm.activeNetwork?.let { add(it) }
                cm.allNetworks?.let { addAll(it) }
            }.distinct()
            for (network in networks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val wifiOrEth =
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!wifiOrEth) continue
                val lp = cm.getLinkProperties(network) ?: continue
                for (la in lp.linkAddresses) {
                    val addr = la.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        addr.hostAddress?.let { out.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.distinct()
    }

    private fun isExcludedName(name: String, hasWifiPreferred: Boolean): Boolean {
        val lower = name.lowercase()
        for (p in EXCLUDED_NAME_PREFIXES) {
            if (lower.startsWith(p)) {
                // Always drop docker/veth/br-/tun/tap/vpn/dummy
                if (p.startsWith("docker") || p.startsWith("veth") || p.startsWith("br-") ||
                    p.startsWith("tun") || p.startsWith("tap") || p.startsWith("vpn") ||
                    p.startsWith("dummy") || p.startsWith("virbr") || p.startsWith("lxc") ||
                    p.startsWith("vmnet") || p.startsWith("vbox") || p.startsWith("zt") ||
                    p.startsWith("wg") || p.startsWith("tailscale") || p.startsWith("sit") ||
                    p.startsWith("ip6tnl") || p.startsWith("clat")
                ) {
                    return true
                }
                // Cellular (rmnet*) only excluded when we already have Wi-Fi/Ethernet candidates
                if (hasWifiPreferred && (p.startsWith("rmnet") || p == "ccmni")) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Higher score = better auto pick.
     * Ranking:
     *  1) ConnectivityManager Wi-Fi/Ethernet IPs
     *  2) iface name looks like wlan*, eth*, en*
     *  3) RFC1918 192.168.x then 10.x then 172.16-31.x
     *  4) Strong penalty for 172.19.x (common Docker bridge) and other virtual-ish
     */
    private fun score(item: LanInterface, preferredIps: Set<String>): Int {
        var s = 0
        val ip = item.ip
        val name = item.name.lowercase()

        if (ip in preferredIps) s += 10_000

        when {
            name.startsWith("wlan") || name.startsWith("wifi") -> s += 3_000
            name.startsWith("eth") || name.startsWith("en") -> s += 2_800
            name.startsWith("ap") || name.startsWith("swlan") -> s += 500
        }

        val parts = ip.split('.')
        if (parts.size == 4) {
            val a = parts[0].toIntOrNull() ?: 0
            val b = parts[1].toIntOrNull() ?: 0
            when {
                a == 192 && b == 168 -> s += 2_000
                a == 10 -> s += 1_500
                a == 172 && b in 16..31 -> {
                    s += 800
                    // Never prefer Docker-ish 172.19.x when anything else exists
                    if (b == 19) s -= 5_000
                    if (b == 17 || b == 18) s -= 1_500 // common virt bridges
                }
                else -> s += 100 // public / other
            }
        }

        // Soft demote leftover virtual-ish names that slipped through
        if (name.contains("docker") || name.contains("veth") || name.startsWith("br")) {
            s -= 8_000
        }
        return s
    }
}
