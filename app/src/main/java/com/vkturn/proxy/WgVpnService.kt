package com.vkturn.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

    class WgVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var binaryProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var backend: GoBackend? = null
    private var tunnel: Tunnel? = null

    companion object {
        var isRunning = false
        val logBuffer = mutableListOf<String>()
        var onLogReceived: ((String) -> Unit)? = null

        fun addLog(msg: String) {
            val formatted = "[${System.currentTimeMillis() % 100000}] $msg"
            if (logBuffer.size > 200) logBuffer.removeAt(0)
            logBuffer.add(formatted)
            onLogReceived?.invoke(formatted)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY
        dtlsReady = false

        startForegroundCompat()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkTurn::BgLock")
        wakeLock?.acquire(15 * 60 * 1000L)

        isRunning = true

        thread(name = "CombinedStartThread") {
            val proxyStarted = startBinary()
            if (!proxyStarted) {
                addLog("Не удалось запустить бинарник, останавливаемся")
                stopSelf()
                return@thread
            }

            val ready = waitForProxy(timeoutMs = 15_000)
            if (!ready) {
                addLog("Таймаут ожидания DTLS-соединения")
                stopSelf()
                return@thread
            }

            startVpn()
        }

        return START_STICKY
    }

    private val dtlsReady = AtomicBoolean(false)
    
    private fun waitForProxy(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        dtlsReady.set(false)

        thread(name = "CoreLogThread") {
            try {
                val reader = BufferedReader(InputStreamReader(binaryProcess?.inputStream))
                var line: String?
                while (isRunning) {
                    line = reader.readLine() ?: break
                    addLog("CORE: $line")
                    if (line.contains("Established DTLS connection")) {
                        dtlsReady.set(true)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) addLog("CORE LOG ERROR: ${e.message}")
            }
        }

        while (!dtlsReady.get() && System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }

        return dtlsReady.get()
    }

    private fun startVpn() {
        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        val privKey = prefs.getString("wg_priv", "") ?: ""
        val pubKey  = prefs.getString("wg_pub",  "") ?: ""
        val localIp = prefs.getString("wg_local", "10.0.0.2/32") ?: "10.0.0.2/32"
        val peer    = prefs.getString("peer", "") ?: ""
        val peerIp  = peer.substringBefore(":")

        if (privKey.isEmpty() || pubKey.isEmpty()) {
            addLog("ОШИБКА: wg_priv или wg_pub не заполнены!")
            return
        }

        val allowedIps = buildAllowedIPs(peerIp)
        addLog("AllowedIPs: $allowedIps")

        val configText = """
            [Interface]
            PrivateKey = $privKey
            Address = $localIp
            MTU = 1280

            [Peer]
            PublicKey = $pubKey
            Endpoint = 127.0.0.1:9000
            AllowedIPs = $allowedIps
            PersistentKeepalive = 25
        """.trimIndent()

        try {
            val config = Config.parse(configText.byteInputStream())

            if (backend == null) backend = GoBackend(this)

            tunnel = object : Tunnel {
                override fun getName() = "vk_tunnel"
                override fun onStateChange(state: Tunnel.State) {
                    addLog("WG состояние: $state")
                }
            }

            backend!!.setState(tunnel!!, Tunnel.State.UP, config)
            addLog("WireGuard запущен!")

        } catch (e: Exception) {
            addLog("Ошибка запуска VPN: ${e.message}")
        }
    }

    private fun buildAllowedIPs(excludeIp: String): String {
        if (excludeIp.isEmpty()) return "0.0.0.0/0"
        return try {
            excludeRouteFromDefault(excludeIp)
        } catch (e: Exception) {
            addLog("Ошибка разбора peer IP, используем 0.0.0.0/0")
            "0.0.0.0/0"
        }
    }

    private fun excludeRouteFromDefault(excludeIp: String): String {
        val parts = excludeIp.split(".").map { it.toInt() }
        val target = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    
        val result = mutableListOf<String>()
        var lo = 0L
        val hi = 0xFFFFFFFFL
        val t = target.toLong() and 0xFFFFFFFFL
    
        var prefix = 0
        var base = 0L
    
        while (prefix <= 32) {
            val size = if (prefix == 32) 1L else (1L shl (32 - prefix))
        
            if (base == t && prefix == 32) {
                break
            }
        
            if (t >= base && t < base + size) {
                val half = size / 2
                if (t < base + half) {
                    result.add("${longToIp(base + half)}/${prefix + 1}")
                } else {
                    result.add("${longToIp(base)}/${prefix + 1}")
                    base += half
                }
                prefix++
            } else {
                break
            }
        }
    
        return if (result.isEmpty()) "0.0.0.0/0" else result.joinToString(", ")
    }

    private fun longToIp(ip: Long): String =
        "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"

    private fun calculateExcludedRoutes(a: Int, b: Int, c: Int, d: Int): String {
        val routes = mutableListOf<String>()
        val ip32 = (a shl 24) or (b shl 16) or (c shl 8) or d
        var base = 0
        var prefixLen = 0

        while (prefixLen < 32) {
            val mask = if (prefixLen == 0) 0 else (-1 shl (32 - prefixLen))
            val blockSize = 1 shl (32 - prefixLen)
            val blockBase = ip32 and mask

            if (blockBase == (base and mask)) {
                val half = blockSize / 2
                val leftBase = blockBase
                val rightBase = blockBase or half

                if (ip32 < (leftBase + half)) {
                    routes.add("${intToIp(rightBase)}/${prefixLen + 1}")
                    base = leftBase
                } else {
                    routes.add("${intToIp(leftBase)}/${prefixLen + 1}")
                    base = rightBase
                }
                prefixLen++
            } else {
                break
            }
        }
        return routes.joinToString(", ")
    }

    private fun intToIp(ip: Int): String =
        "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"

    private fun startBinary(): Boolean {
        val binaryPath = "${applicationInfo.nativeLibraryDir}/libvkturn.so"
        val binaryFile = File(binaryPath)

        if (!binaryFile.exists()) {
            addLog("ОШИБКА: libvkturn.so не найден!")
            return false
        }

        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        val peer   = prefs.getString("peer", "") ?: ""
        val listen = prefs.getString("listen", "127.0.0.1:9000") ?: "127.0.0.1:9000"
        val link   = prefs.getString("link", "") ?: ""

        if (peer.isEmpty() || link.isEmpty()) {
            addLog("ОШИБКА: peer или link не заполнены!")
            return false
        }

        val cmd = mutableListOf(binaryPath, "-peer", peer, "-listen", listen)
        cmd += if (link.contains("yandex")) listOf("-yandex-link", link)
               else listOf("-vk-link", link)

        if (prefs.getBoolean("udp", true)) cmd.add("-udp")
        cmd += listOf("-n", prefs.getString("n", "8") ?: "8")

        return try {
            addLog("Запуск: ${cmd.joinToString(" ")}")
            binaryProcess = ProcessBuilder(cmd)
                .directory(filesDir)
                .redirectErrorStream(true)
                .start()
            true
        } catch (e: Exception) {
            addLog("ОШИБКА запуска бинарника: ${e.message}")
            false
        }
    }

    override fun onDestroy() {
        isRunning = false
        addLog("Остановка...")
        dtlsReady.set(false)

        try {
            tunnel?.let { t -> backend?.setState(t, Tunnel.State.DOWN, null) }
        } catch (e: Exception) {
            addLog("Ошибка остановки WG: ${e.message}")
        }

        backend = null
        tunnel = null

        try { vpnInterface?.close() } catch (e: Exception) {}
        binaryProcess?.destroyForcibly()
        wakeLock?.takeIf { it.isHeld }?.release()

        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notif = NotificationCompat.Builder(this, "ProxyChannel")
            .setContentTitle("VK-Turn + WireGuard")
            .setContentText("Прокси активен")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ProxyChannel", "VK Turn + WireGuard",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
