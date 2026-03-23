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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.concurrent.thread

class ProxyService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var process: Process? = null

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

        // 1. Уведомление
        startForeground(1, createNotification())
        
        // 2. WakeLock (чтобы Android не усыпил сервис)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkTurn::BgLock")
        wakeLock?.acquire()

        // 3. Поднимаем системный VPN слой
        setupVpn()

        // 4. Запускаем ядро прокси
        isRunning = true
        addLog("=== ЗАПУСК СИСТЕМЫ (VPN + PROXY) ===")
        startBinary()

        return START_STICKY
    }

    private fun setupVpn() {
        try {
            val builder = Builder()
                .setSession("VK TURN Proxy")
                .setMtu(1280)
                // Эти параметры должны совпадать с настройками твоего WireGuard сервера
                .addAddress("10.0.0.2", 32) 
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
            
            // Важно: исключаем себя, чтобы не было петли трафика
            builder.addDisallowedApplication(packageName)

            vpnInterface = builder.establish()
            addLog("Сетевой интерфейс VPN успешно создан")
        } catch (e: Exception) {
            addLog("ОШИБКА VPN: ${e.message}")
        }
    }

    private fun startBinary() {
        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        val isRaw = prefs.getBoolean("isRaw", false)

        val customBin = File(filesDir, "custom_vkturn")
        val executable = if (customBin.exists()) {
            customBin.absolutePath
        } else {
            "${applicationInfo.nativeLibraryDir}/libvkturn.so"
        }

        val cmdArgs = mutableListOf<String>()
        cmdArgs.add(executable)

        if (isRaw) {
            val rawCmd = prefs.getString("rawCmd", "") ?: ""
            val parts = rawCmd.trim().split("\\s+".toRegex())
            if (parts.size > 1) cmdArgs.addAll(parts.subList(1, parts.size))
        } else {
            // Твои стандартные параметры
            cmdArgs.add("-peer"); cmdArgs.add(prefs.getString("peer", "") ?: "")
            val link = prefs.getString("link", "") ?: ""
            cmdArgs.add(if (link.contains("yandex")) "-yandex-link" else "-vk-link")
            cmdArgs.add(link)
            cmdArgs.add("-listen"); cmdArgs.add(prefs.getString("listen", "127.0.0.1:9000") ?: "")
            
            val n = prefs.getString("n", "") ?: ""
            if (n.isNotEmpty()) { cmdArgs.add("-n"); cmdArgs.add(n) }
            if (prefs.getBoolean("udp", false)) cmdArgs.add("-udp")
            if (prefs.getBoolean("noDtls", false)) cmdArgs.add("-no-dtls")
        }

        thread {
            try {
                addLog("Запуск ядра: ${cmdArgs.joinToString(" ")}")
                process = ProcessBuilder(cmdArgs).redirectErrorStream(true).start()
                val reader = BufferedReader(InputStreamReader(process?.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    addLog(line ?: "")
                }
                val exitCode = process?.waitFor()
                addLog("Ядро остановлено. Код: $exitCode")
            } catch (e: Exception) {
                addLog("Ошибка бинарника: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("ProxyChannel", "VK TURN Connection", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, "ProxyChannel")
        .setContentTitle("VK TURN + WireGuard")
        .setContentText("Защищенное соединение активно")
        .setSmallIcon(android.R.drawable.ic_menu_preferences)
        .setOngoing(true)
        .build()

    override
