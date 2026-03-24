package com.vkturn.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import kotlin.concurrent.thread

class ProxyService : Service() {

    private val backend by lazy { GoBackend(this) }
    private var currentTunnel: Tunnel? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var binaryProcess: Process? = null

    private class VkWgtunnel(private val name: String) : Tunnel {
        override fun getName() = name
        override fun onStateChange(state: Tunnel.State) {
            addLog("WG ТУННЕЛЬ: $state")
        }
    }

    companion object {
        var isRunning = false
        val logBuffer = mutableListOf<String>()
        var onLogReceived: ((String) -> Unit)? = null

        fun addLog(msg: String) {
            val formattedMsg = "[${System.currentTimeMillis() % 100000}] $msg"
            if (logBuffer.size > 200) logBuffer.removeAt(0)
            logBuffer.add(formattedMsg)
            onLogReceived?.invoke(formattedMsg)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, createNotification())
        }
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkTurn::BgLock")
        wakeLock?.acquire(10 * 60 * 1000L)

        isRunning = true

        thread {
            startWireGuard()
            Thread.sleep(1500)
            startBinary()
        }

        return START_STICKY
    }

private fun startWireGuard() {
        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        val privKey = prefs.getString("wg_priv", "") ?: ""
        val pubKey = prefs.getString("wg_pub", "") ?: ""
        val endpoint = prefs.getString("wg_end", "") ?: ""
        val localIp = prefs.getString("wg_local", "10.0.0.2/32") ?: "10.0.0.2/32"

        if (privKey.isEmpty() || pubKey.isEmpty() || endpoint.isEmpty()) {
            addLog("ОШИБКА: Конфиг WireGuard пуст!")
            return
        }

        try {
            val tunnel = VkWgtunnel("vk_tunnel")
            currentTunnel = tunnel

            val configText = """
                [Interface]
                PrivateKey = $privKey
                Address = $localIp
                DNS = 1.1.1.1
                
                [Peer]
                PublicKey = $pubKey
                Endpoint = $endpoint
                AllowedIPs = 0.0.0.0/0
                PersistentKeepalive = 25
            """.trimIndent()

            val config = com.wireguard.config.Config.parse(configText.byteInputStream())

            val finalConfig = com.wireguard.config.Config.Builder()
                .setInterface(config.interface)
                .addPeers(config.peers)
                .build()

            backend.setState(tunnel, com.wireguard.android.backend.Tunnel.State.UP, finalConfig)
            addLog("WireGuard успешно запущен.")
            
        } catch (e: Exception) {
            addLog("КРИТИЧЕСКАЯ ОШИБКА WG: ${e.message}")
        }
    }
    
private fun startBinary() {
        val internalBin = File(filesDir, "libvkturn.so")

        try {
            if (!internalBin.exists()) {
                val abi = Build.SUPPORTED_ABIS[0] 
                addLog("Извлечение бинарника для $abi...")
                
                val libraryFile = File(applicationInfo.nativeLibraryDir, "libvkturn.so")
                
                if (libraryFile.exists()) {
                    libraryFile.inputStream().use { input ->
                        internalBin.outputStream().use { output -> input.copyTo(output) }
                    }
                } else {
                    addLog("ОШИБКА: libvkturn.so не найден в nativeLibraryDir")
                    return
                }
            }
            
            internalBin.setExecutable(true, false)
        } catch (e: Exception) {
            addLog("Ошибка подготовки: ${e.message}")
        }

        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        val peer = prefs.getString("peer", "") ?: ""
        val listen = prefs.getString("listen", "127.0.0.1:9000") ?: "127.0.0.1:9000"
        val link = prefs.getString("link", "") ?: ""

        val cmd = mutableListOf(internalBin.absolutePath, "-peer", peer, "-listen", listen)
        if (link.isNotEmpty()) {
            cmd.add(if (link.contains("yandex")) "-yandex-link" else "-vk-link")
            cmd.add(link)
        }
        if (prefs.getBoolean("udp", true)) cmd.add("-udp")
        cmd.addAll(listOf("-n", prefs.getString("n", "8") ?: "8"))

        try {
            addLog("Запуск ядра...")
            val pb = ProcessBuilder(cmd).directory(filesDir).redirectErrorStream(true)
            binaryProcess = pb.start()
            
            val reader = BufferedReader(InputStreamReader(binaryProcess?.inputStream))
            thread {
                var line: String?
                while (isRunning && reader.readLine().also { line = it } != null) {
                    addLog("CORE: $line")
                }
            }
        } catch (e: Exception) {
            addLog("ОШИБКА ЯДРА: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ProxyChannel", 
                "VPN Service", 
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, "ProxyChannel")
        .setContentTitle("VK-Turn + WG")
        .setContentText("Сервис работает")
        .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        isRunning = false
        addLog("Остановка прокси...")
        currentTunnel?.let { 
            thread { backend.setState(it, Tunnel.State.DOWN, null) }
        }
        binaryProcess?.destroy()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
}
