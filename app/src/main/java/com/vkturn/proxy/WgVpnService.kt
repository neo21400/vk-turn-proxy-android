package com.vkturn.proxy

import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.config.Config

class WgVpnService : VpnService() {

    companion object {
        var backend: GoBackend? = null
        var currentConfig: Config? = null
    }

    override fun onCreate() {
        super.onCreate()
        backend = GoBackend(this)
    }

    // Этот метод будет вызываться из ProxyService
    fun startTunnel(config: Config) {
        currentConfig = config
        try {
            val tunnel = ProxyService.VkWgtunnel("vk_tunnel")  // используем тот же класс
            backend?.setState(tunnel, com.wireguard.android.backend.Tunnel.State.UP, config)
            ProxyService.addLog("WireGuard запущен через VpnService!")
        } catch (e: Exception) {
            ProxyService.addLog("Ошибка в VpnService: ${e.message}")
            e.printStackTrace()
        }
    }
}
