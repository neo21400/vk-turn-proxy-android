package com.vkturn.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import kotlin.concurrent.thread

class ProxyService : VpnService() {

    private val backend by lazy { GoBackend(this) }
    private var currentTunnel: Tunnel? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var binaryProcess: Process? = null

    // Мост для состояний туннеля
    private class VkWgtunnel(private val name: String) : Tunnel {
        override fun getName() = name
        override fun onStateChange(state: Tunnel.State) {
            addLog("WG Туннель: $state")
        }
    }

    companion object {
        var isRunning = false
        val logBuffer = mutableListOf<String>()
        var onLogReceived: ((String) -> Unit)? = null

        fun addLog(msg: String) {
            if (logBuffer.size > 200) logBuffer.removeAt(0)
            logBuffer.add(msg)
            onLogReceived?.invoke(msg)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY

        startForeground(1, createNotification())
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkTurn::BgLock")
        wakeLock?.acquire()

        // 1. Сначала поднимаем WireGuard (Системный VPN слой)
        startWireGuard()

        // 2. Затем запускаем бинарник vk-turn-proxy (Прикладной слой)
        isRunning = true
        startBinary()

        return START_STICKY
    }

    private fun startWireGuard() {
        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        
        val privKey = prefs.getString("wg_priv", "") ?: ""
        val pubKey = prefs.getString("wg_pub", "") ?: ""
        val endpoint = prefs.getString("wg_end", "") ?: ""
        val localIp = prefs.getString("wg_local", "10.0.0.2/32") ?: "10.0.0.2/32"

        if (privKey.isEmpty() || pubKey.isEmpty() || endpoint.isEmpty()) {
            addLog("ОШИБКА: Конфиг WireGuard пуст! Проверь настройки.")
            return
        }

        thread {
            try {
                val tunnel = VkWgtunnel("vk_wg_tunnel")
                currentTunnel = tunnel

                // Настраиваем интерфейс
                val wgInterfaceBuilder = Interface.Builder()
                    .addAddress(com.wireguard.config.InetNetwork.parse(localIp))
                    .setPrivateKey(Key.fromBase64(privKey))
                    .addDnsServer(InetAddress.getByName("1.1.1.1"))

                // --- ИСКЛЮЧЕНИЕ ПРИЛОЖЕНИЙ ---
                // Обязательно исключаем себя (packageName), чтобы пакеты WG не зациклились
                val excluded = mutableSetOf(packageName)
                
                // Считываем доп. приложения из настроек (если есть, через запятую)
                val extraExcludes = prefs.getString("excluded_apps", "") ?: ""
                if (extraExcludes.isNotEmpty()) {
                    extraExcludes.split(",").forEach { excluded.add(it.trim()) }
                }
                
                wgInterfaceBuilder.excludeApplications(excluded)
                // -----------------------------

                val peer = Peer.Builder()
                    .addAllowedIp(com.wireguard.config.InetNetwork.parse("0.0.0.0/0"))
                    .setEndpoint(com.wireguard.config.InetEndpoint.parse(endpoint))
                    .setPublicKey(Key.fromBase64(pubKey))
                    .build()

                val config = Config.Builder()
                    .setInterface(wgInterfaceBuilder.build())
                    .addPeer(peer)
                    .build()

                // Запуск GoBackend. Он сам создаст VpnService.Builder и установит VPN-соединение.
                backend.setState(tunnel, Tunnel.State.UP, config)
                addLog("WireGuard запущен. Исключено приложений: ${excluded.size}")
                
            } catch (e: Exception) {
                addLog("КРИТИЧЕСКАЯ ОШИБКА WG: ${e.message}")
            }
        }
    }

    private fun startBinary() {
        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        
        // Поиск бинарника (jniLibs или память телефона)
        val customBin = File(filesDir, "custom_vkturn")
        val executable = if (customBin.exists()) customBin.absolutePath 
                         else "${applicationInfo.nativeLibraryDir}/libvkturn.so"

        val peer = prefs.getString("peer", "") ?: ""
        val link = prefs.getString("link", "") ?: ""
        val listen = prefs.getString("listen", "127.0.0.1:9000") ?: "127.0.0.1:9000"

        val cmdArgs = mutableListOf(executable, "-peer", peer, "-listen", listen)
        cmdArgs.add(if (link.contains("yandex")) "-yandex-link" else "-vk-link")
        cmdArgs.add(link)
        
        if (prefs.getBoolean("udp", false)) cmdArgs.add("-udp")
        if (prefs.getBoolean("noDtls", false)) cmdArgs.add("-no-dtls")

        thread {
            try {
                addLog("Запуск ядра прокси: ${cmdArgs.joinToString(" ")}")
                binaryProcess = ProcessBuilder(cmdArgs).redirectErrorStream(true).start()
                val reader = BufferedReader(InputStreamReader(binaryProcess?.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    addLog(line ?: "")
                }
            } catch (e: Exception) {
                addLog("Ошибка бинарника: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("ProxyChannel", "VK VPN Status", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, "ProxyChannel")
        .setContentTitle("VK TURN + WireGuard")
        .setContentText("Туннель и прокси работают")
        .setSmallIcon(android.R.drawable.ic_menu_preferences)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        currentTunnel?.let { backend.setState(it, Tunnel.State.DOWN, null) }
        binaryProcess?.destroy()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        addLog("=== ВСЕ СЛУЖБЫ ОСТАНОВЛЕНЫ ===")
    }
}
