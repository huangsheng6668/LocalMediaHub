package com.juziss.localmediahub.util

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Network utilities for discovering the host's real LAN IPv4 address.
 *
 * Virtual machine / container adapters (VMware vmnet*, VirtualBox vbox*, WSL
 * vEthernet*, Hyper-V, Docker) create extra network interfaces whose addresses
 * sit on isolated host-only / NAT subnets. If we scan those subnets instead of
 * the physical LAN subnet, mDNS/HTTP discovery either finds nothing or probes
 * the wrong range. [getLanIp] filters such adapters out so discovery targets
 * the actual home/office network.
 */
object NetUtil {

    /**
     * Lower-cased interface name prefixes that indicate a virtual adapter. We
     * skip any interface whose display name starts with one of these.
     */
    private val virtualAdapterPrefixes = arrayOf(
        "vmnet",       // VMware (vmnet0, vmnet1, vmnet8, ...)
        "vbox",        // VirtualBox host-only (vboxnet0)
        "vethernet",   // Hyper-V / WSL (vEthernet (WSL), vEthernet (Default Switch))
        "docker",      // Docker desktop bridge
        "virtualbox",  // alt VirtualBox naming
        "tap-",        // OpenVPN / TAP adapters
        "tun-",        // tunnel adapters
        "isatap",      // ISATAP tunneling
        "teredo",      // Teredo tunneling
    )

    /**
     * Returns the best LAN IPv4 address on this device, or null if none is
     * found. "Best" means a non-loopback, non-link-local address on a physical
     * (non-virtual) adapter that sits on a private RFC1918 subnet. If no such
     * address exists, falls back to any non-loopback IPv4 address.
     *
     * This runs on the calling thread; callers should already be on an IO
     * dispatcher since enumerating interfaces can block.
     */
    fun getLanIp(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null

        // First pass: prefer a private IPv4 on a physical adapter.
        var fallback: String? = null
        for (intf in interfaces) {
            if (isVirtualAdapter(intf)) continue
            for (addr in intf.inetAddresses) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                val ip = addr.hostAddress ?: continue
                // Skip link-local (169.254.x.x) — not routable on the LAN.
                if (isLinkLocal(ip)) continue
                if (isPrivateLan(ip)) return ip
                if (fallback == null) fallback = ip
            }
        }
        // Second pass fallback: any non-loopback IPv4 (virtual adapters
        // included) so we still return *something* rather than nothing.
        if (fallback == null) {
            for (intf in NetworkInterface.getNetworkInterfaces() ?: return null) {
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        }
        return fallback
    }

    /**
     * True if [intf] looks like a virtual machine / container / tunnel adapter
     * based on its display name (case-insensitive).
     */
    private fun isVirtualAdapter(intf: NetworkInterface): Boolean {
        val name = intf.displayName?.lowercase() ?: return false
        return virtualAdapterPrefixes.any { name.startsWith(it) }
    }

    /** APIPA / link-local 169.254.0.0/16. */
    private fun isLinkLocal(ip: String): Boolean =
        ip.startsWith("169.254.")

    /**
     * RFC1918 private address check: 10.0.0.0/8, 172.16.0.0/12 (172.16 - 172.31),
     * 192.168.0.0/16. Uses proper octet parsing rather than fragile string
     * prefix matching.
     */
    private fun isPrivateLan(ip: String): Boolean {
        val octets = ip.split(".")
        if (octets.size != 4) return false
        val a = octets[0].toIntOrNull() ?: return false
        val b = octets[1].toIntOrNull() ?: return false
        return when (a) {
            10 -> true
            172 -> b in 16..31
            192 -> b == 168
            else -> false
        }
    }
}
