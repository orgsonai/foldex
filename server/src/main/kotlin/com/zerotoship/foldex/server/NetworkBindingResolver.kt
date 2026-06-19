// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `bindAddress` の解決と Wi-Fi 接続状態の判定。
 *
 * - "wifi_only": Wi-Fi が接続中なら IPv4 アドレスを返す。未接続なら null。
 * - "0.0.0.0": そのまま (全インターフェース)。
 * - 特定 IP/ホスト: getByName 経由で解決。失敗時は null。
 */
@Singleton
class NetworkBindingResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun resolve(bindAddress: String): String? = when (bindAddress) {
        "wifi_only" -> currentWifiIPv4()
        "0.0.0.0" -> "0.0.0.0"
        else -> runCatching { InetAddress.getByName(bindAddress).hostAddress }.getOrNull()
    }

    fun isWifiConnected(): Boolean = currentWifiNetwork() != null

    /** UI 表示用。Wi-Fi の現在 IPv4。なければ null。 */
    fun currentWifiIPv4(): String? {
        val (_, props) = currentWifiNetwork() ?: return null
        return firstIPv4(props)
    }

    private fun currentWifiNetwork(): Pair<Network, LinkProperties>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val networks = cm.allNetworks
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            ) {
                // Wi-Fi だが ローカルのみ など — 利用可能とみなす
            }
            val props = cm.getLinkProperties(network) ?: continue
            return network to props
        }
        return null
    }

    private fun firstIPv4(props: LinkProperties): String? {
        for (addr: LinkAddress in props.linkAddresses) {
            val ip = addr.address
            if (ip is Inet4Address) return ip.hostAddress
        }
        return null
    }
}
