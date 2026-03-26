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
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.concurrent.thread
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel

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

    private fun waitForProxy(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val reader = BufferedReader(InputStreamReader(binaryProcess?.inputStream))

        var dtlsReady = false
        val logThread = thread(name = "CoreLogThread") {
            try {
                var line: String?
                while (isRunning) {
                    line = reader.readLine() ?: break
                    addLog("CORE: $line")
                    if (line.contains("Established DTLS connection")) {
                        dtlsReady = true
                    }
                }
            } catch (e: Exception) {
                if (isRunning) addLog("CORE LOG ERROR: ${e.message}")
            }
        }

        while (!dtlsReady && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }

        return dtlsReady
    }

    private fun startVpn() {
        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        val privKey = prefs.getString("wg_priv", "") ?: ""
        val pubKey  = prefs.getString("wg_pub",  "") ?: ""
        val localIp = prefs.getString("wg_local", "10.0.0.2/32") ?: "10.0.0.2/32"

        if (privKey.isEmpty() || pubKey.isEmpty()) {
            addLog("ОШИБКА: wg_priv или wg_pub не заполнены!")
            return
        }

        val configText = """
            [Interface]
            PrivateKey = $privKey
            Address = $localIp
            DNS = 1.1.1.1, 1.0.0.1
            MTU = 1280

            [Peer]
            PublicKey = $pubKey
            Endpoint = 127.0.0.1:9000
            AllowedIPs = 0.0.0.0/0
            PersistentKeepalive = 25
        """.trimIndent()

         try {
            val config = Config.parse(configText.byteInputStream())

            backend = GoBackend(this)

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

        try {
            tunnel?.let { t ->
                backend?.setState(t, Tunnel.State.DOWN, null)
            }
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
