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

    // Внутренний класс для отслеживания состояния туннеля библиотекой WireGuard
    private class VkWgtunnel(private val name: String) : Tunnel {
        override fun getName() = name
        override fun onStateChange(state: Tunnel.State) {
            addLog("WG Туннель перешел в состояние: $state")
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

        // 1. Сначала запускаем WireGuard
        startWireGuard()

        // 2. Затем запускаем бинарник vk-turn-proxy
        isRunning = true
        startBinary()

        return START_STICKY
    }

    private fun startWireGuard() {
        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        
        // Получаем данные из настроек (нужно добавить их ввод в SettingsActivity)
        val privKey = prefs.getString("wg_priv", "") ?: ""
        val pubKey = prefs.getString("wg_pub", "") ?: ""
        val endpoint = prefs.getString("wg_end", "") ?: ""
        val localIp = prefs.getString("wg_local", "10.0.0.2/32") ?: "10.0.0.2/32"

        if (privKey.isEmpty() || pubKey.isEmpty() || endpoint.isEmpty()) {
            addLog("ОШИБКА: Данные WireGuard не заполнены в настройках!")
            return
        }

        thread {
            try {
                val tunnel = VkWgtunnel("vk_wg_tunnel")
                currentTunnel = tunnel

                val wgInterface = Interface.Builder()
                    .addAddress(com.wireguard.config.InetNetwork.parse(localIp))
                    .setPrivateKey(Key.fromBase64(privKey))
                    .addDnsServer(InetAddress.getByName("1.1.1.1"))
                    .build()

                val peer = Peer.Builder()
                    .addAllowedIp(com.wireguard.config.InetNetwork.parse("0.0.0.0/0"))
                    .setEndpoint(com.wireguard.config.InetEndpoint.parse(endpoint))
                    .setPublicKey(Key.fromBase64(pubKey))
                    .build()

                val config = Config.Builder()
                    .setInterface(wgInterface)
                    .addPeer(peer)
                    .build()

                // GoBackend сам вызывает VpnService.Builder под капотом, 
                // но нам нужно убедиться, что приложение исключено из туннеля
                // В библиотеке 1.1.0 это часто настраивается через Backend.
                
                backend.setState(tunnel, Tunnel.State.UP, config)
                addLog("WireGuard успешно инициализирован.")
            } catch (e: Exception) {
                addLog("КРИТИЧЕСКАЯ ОШИБКА WG: ${e.message}")
            }
        }
    }

    private fun startBinary() {
        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        
        val customBin = File(filesDir, "custom_vkturn")
        val executable = if (customBin.exists()) customBin.absolutePath 
                         else "${applicationInfo.nativeLibraryDir}/libvkturn.so"

        val cmdArgs = mutableListOf(executable)
        
        // Читаем параметры из настроек для бинарника
        val peer = prefs.getString("peer", "") ?: ""
        val link = prefs.getString("link", "") ?: ""
        val listen = prefs.getString("listen", "127.0.0.1:9000") ?: "127.0.0.1:9000"

        cmdArgs.addAll(listOf("-peer", peer, "-listen", listen))
        cmdArgs.add(if (link.contains("yandex")) "-yandex-link" else "-vk-link")
        cmdArgs.add(link)
        
        if (prefs.getBoolean("udp", false)) cmdArgs.add("-udp")

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
            val channel = NotificationChannel("ProxyChannel", "VK Connect Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, "ProxyChannel")
        .setContentTitle("VK TURN + WireGuard")
        .setContentText("Туннелирование активно")
        .setSmallIcon(android.R.drawable.ic_menu_preferences)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        // Выключаем WireGuard
        currentTunnel?.let { backend.setState(it, Tunnel.State.DOWN, null) }
        // Убиваем процесс прокси
        binaryProcess?.destroy()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        addLog("=== СЕРВИС ПОЛНОСТЬЮ ОСТАНОВЛЕН ===")
    }
}
