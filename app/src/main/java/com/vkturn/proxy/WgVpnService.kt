package com.vkturn.proxy

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.wireguard.config.Config
import com.vkturn.proxy.UdpBridge

class WgVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        var currentConfig: Config? = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentConfig?.let { config ->
            try {
                startVpn(config)
                ProxyService.addLog("WireGuard запущен через ручной VpnService")
            } catch (e: Exception) {
                ProxyService.addLog("Ошибка запуска VPN: ${e.message}")
                e.printStackTrace()
            }
        }
        return START_STICKY
    }

    private fun startVpn(config: Config) {
        val builder = Builder()

        val iface = config.`interface`
        builder.setMtu(iface.mtu.orElse(1280))

        iface.addresses.forEach { addr ->
            builder.addAddress(addr.address, addr.mask)
        }

        iface.dnsServers.forEach { dns ->
            builder.addDnsServer(dns)
        }

        config.peers.forEach { peer ->
            peer.allowedIps.forEach { allowed ->
                builder.addRoute(allowed.address, allowed.mask)
            }
        }

        builder.setSession("vk_tunnel")

        vpnInterface = builder.establish()

        val bridge = UdpBridge()
        bridge.start()

        val fd = vpnInterface?.fd ?: throw Exception("FD is null")

        val configStr = config.toWgQuickString()

        val result = WgNative.turnOn(fd, configStr)

        if (result != 0) {
            throw Exception("wg-go failed: $result")
        }

        if (vpnInterface != null) {
            ProxyService.addLog("VPN интерфейс успешно создан (fd: ${vpnInterface?.fd})")
        } else {
            ProxyService.addLog("Не удалось создать VPN интерфейс")
        }
    }

fun Config.toWgQuickString(): String {
    return this.toString()
}
    
    override fun onDestroy() {
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
        vpnInterface = null
        ProxyService.addLog("VPN интерфейс закрыт")
        super.onDestroy()
    }
}
