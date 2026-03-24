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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.concurrent.thread

class VkWgtunnel(private val name: String) : Tunnel {
    override fun getName() = name
    override fun onStateChange(state: Tunnel.State) {
        ProxyService.addLog("WG ТУННЕЛЬ: $state")
    }
}

class ProxyService : Service() {

    private var currentTunnel: Tunnel? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var binaryProcess: Process? = null
    private lateinit var backend: GoBackend

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
        backend = GoBackend(this)
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
        wakeLock?.acquire(15 * 60 * 1000L) 

        isRunning = true

        thread(name = "ProxyInitThread") {
            startBinary()                    
            Thread.sleep(4500)               
            startWireGuard()
        }

        return START_STICKY
    }

    // ====================== WIREGUARD ======================
private fun startWireGuard() {
    val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)

    val privKey = prefs.getString("wg_priv", "") ?: ""
    val pubKey = prefs.getString("wg_pub", "") ?: ""
    val localIp = prefs.getString("wg_local", "10.0.0.2/32") ?: "10.0.0.2/32"

    if (privKey.isEmpty() || pubKey.isEmpty()) {
        addLog("ОШИБКА: wg_priv или wg_pub не заполнены!")
        return
    }

    try {
        addLog("Подготовка ручного WireGuard...")

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

        val config = Config.parse(configText.byteInputStream())

        WgVpnService.currentConfig = config
        val intent = Intent(this, WgVpnService::class.java)
        startService(intent)

        addLog("Запрос на запуск WireGuard отправлен через VpnService")

    } catch (e: Exception) {
        addLog("Ошибка подготовки WG: ${e.message}")
        e.printStackTrace()
    }
}

    // ====================== VK-TURN BINARY ======================
private fun startBinary() {
    val binaryPath = "${applicationInfo.nativeLibraryDir}/libvkturn.so"
    val binaryFile = File(binaryPath)

    if (!binaryFile.exists()) {
        addLog("ОШИБКА: libvkturn.so не найден в nativeLibraryDir!")
        return
    }

    addLog("Бинарник найден: $binaryPath  (размер: ${binaryFile.length()} байт)")

    val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
    val peer = prefs.getString("peer", "") ?: ""
    val listen = prefs.getString("listen", "127.0.0.1:9000") ?: "127.0.0.1:9000"
    val link = prefs.getString("link", "") ?: ""

    val cmd = mutableListOf(binaryPath, "-peer", peer, "-listen", listen)

    if (link.isNotEmpty()) {
        cmd.add(if (link.contains("yandex")) "-yandex-link" else "-vk-link")
        cmd.add(link)
    }
    if (prefs.getBoolean("udp", true)) cmd.add("-udp")

    val nThreads = prefs.getString("n", "8") ?: "8"
    cmd.addAll(listOf("-n", nThreads))

    try {
        addLog("Запуск ядра... Команда: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd)
            .directory(filesDir)         
            .redirectErrorStream(true)

        binaryProcess = pb.start()

        val reader = BufferedReader(InputStreamReader(binaryProcess?.inputStream))
        thread(name = "CoreLogThread") {
            try {
                var line: String?
                while (isRunning) {
                    line = reader.readLine() ?: break
                    addLog("CORE: $line")
                }
            } catch (e: Exception) {
                if (isRunning) addLog("CORE LOG ERROR: ${e.message}")
            } finally {
                reader.close()
            }
        }

        addLog("Ядро vkturn запущено успешно (из native libs)")

    } catch (e: Exception) {
        addLog("КРИТИЧЕСКАЯ ОШИБКА ЗАПУСКА ЯДРА: ${e.message}")
        e.printStackTrace()
    }
}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ProxyChannel",
                "VK Turn + WireGuard",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Сервис VK-Turn Proxy"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, "ProxyChannel")
        .setContentTitle("VK-Turn + WG")
        .setContentText("Прокси активен")
        .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        isRunning = false
        addLog("Остановка прокси...")

        try {
            currentTunnel?.let {
                backend.setState(it, Tunnel.State.DOWN, null)
            }
        } catch (e: Exception) {
            addLog("Ошибка остановки WG: ${e.message}")
        }

        binaryProcess?.destroyForcibly()
        wakeLock?.takeIf { it.isHeld }?.release()

        super.onDestroy()
    }
}
