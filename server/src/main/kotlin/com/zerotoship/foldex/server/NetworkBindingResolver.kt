// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
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
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiLinkProperties = ConcurrentHashMap<Network, LinkProperties>()

    /**
     * Wi-Fi は既定回線とは限らない。モバイル回線が既定のときやインターネット接続のない
     * ローカル Wi-Fi でもサーバーを公開できるよう、該当する全 Wi-Fi 回線を追跡する。
     */
    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            wifiLinkProperties[network] = linkProperties
        }

        override fun onLost(network: Network) {
            wifiLinkProperties.remove(network)
        }
    }

    init {
        runCatching {
            connectivityManager?.registerNetworkCallback(WIFI_NETWORK_REQUEST, wifiNetworkCallback)
        }
    }

    fun resolve(bindAddress: String): String? = when (bindAddress) {
        "wifi_only" -> currentWifiIPv4()
        "0.0.0.0" -> "0.0.0.0"
        else -> runCatching { InetAddress.getByName(bindAddress).hostAddress }.getOrNull()
    }

    fun isWifiConnected(): Boolean =
        activeWifiLinkProperties() != null || wifiLinkProperties.isNotEmpty()

    /** UI 表示用。Wi-Fi の現在 IPv4。なければ null。 */
    fun currentWifiIPv4(): String? {
        // コールバック登録直後は初回通知が届くまで短い空白があるため、既定回線が
        // Wi-Fi なら同期取得で補完する。その後、既定回線以外の Wi-Fi もキャッシュから探す。
        activeWifiLinkProperties()?.let { props ->
            firstIPv4(props)?.let { return it }
        }
        return wifiLinkProperties.values.firstNotNullOfOrNull(::firstIPv4)
    }

    private fun activeWifiLinkProperties(): LinkProperties? {
        val cm = connectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        val capabilities = cm.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return cm.getLinkProperties(network)
    }

    private fun firstIPv4(props: LinkProperties): String? {
        for (addr: LinkAddress in props.linkAddresses) {
            val ip = addr.address
            if (ip is Inet4Address) return ip.hostAddress
        }
        return null
    }

    private companion object {
        val WIFI_NETWORK_REQUEST: NetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
    }
}
