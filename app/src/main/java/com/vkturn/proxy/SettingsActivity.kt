package com.vkturn.proxy

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {

    private val sshManager = SSHManager()
    private lateinit var tvSshLog: TextView
    private lateinit var scrollSshLog: ScrollView
    private lateinit var btnInstallServer: Button
    private lateinit var btnStartProxy: Button
    private lateinit var btnStopProxy: Button

    // Лаунчер для выбора .conf файла WireGuard
    private val wgPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (content != null) parseAndSaveWg(content)
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка чтения файла: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // --- Инициализация SSH полей ---
        val editIp = findViewById<EditText>(R.id.editSshIp)
        val editPort = findViewById<EditText>(R.id.editSshPort)
        val editUser = findViewById<EditText>(R.id.editSshUser)
        val editPass = findViewById<EditText>(R.id.editSshPass)
        val editProxyListen = findViewById<EditText>(R.id.editProxyListen)
        val editProxyConnect = findViewById<EditText>(R.id.editProxyConnect)
        
        // --- Инициализация WireGuard полей ---
        val editWgPriv = findViewById<EditText>(R.id.editWgPriv)
        val editWgPub = findViewById<EditText>(R.id.editWgPub)
        val editWgEnd = findViewById<EditText>(R.id.editWgEnd)

        // --- Терминал и кнопки ---
        val editCustomCmd = findViewById<EditText>(R.id.editCustomCmd)
        val btnSendCmd = findViewById<Button>(R.id.btnSendCmd)
        val btnConnectSsh = findViewById<Button>(R.id.btnConnectSsh)
        val btnCtrlC = findViewById<Button>(R.id.btnCtrlC)
        btnInstallServer = findViewById(R.id.btnInstallServer)
        btnStartProxy = findViewById(R.id.btnStartProxy)
        btnStopProxy = findViewById(R.id.btnStopProxy)
        tvSshLog = findViewById(R.id.tvSshLog)
        scrollSshLog = findViewById(R.id.scrollSshLog)

        // ЗАГРУЗКА НАСТРОЕК (Общий файл ProxyPrefs)
        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        
        editIp.setText(prefs.getString("ip", ""))
        editPort.setText(prefs.getString("port", "22"))
        editUser.setText(prefs.getString("user", "root"))
        editPass.setText(prefs.getString("pass", ""))
        editProxyListen.setText(prefs.getString("proxyListen", "0.0.0.0:56000"))
        editProxyConnect.setText(prefs.getString("proxyConnect", "127.0.0.1:40537"))
        
        // Загрузка WG
        editWgPriv.setText(prefs.getString("wg_priv", ""))
        editWgPub.setText(prefs.getString("wg_pub", ""))
        editWgEnd.setText(prefs.getString("wg_end", ""))

        // Функция сохранения
        val savePrefs = {
            prefs.edit().apply {
                putString("ip", editIp.text.toString())
                putString("port", editPort.text.toString())
                putString("user", editUser.text.toString())
                putString("pass", editPass.text.toString())
                putString("proxyListen", editProxyListen.text.toString())
                putString("proxyConnect", editProxyConnect.text.toString())
                // Сохраняем WG
                putString("wg_priv", editWgPriv.text.toString().trim())
                putString("wg_pub", editWgPub.text.toString().trim())
                putString("wg_end", editWgEnd.text.toString().trim())
            }.apply()
        }

        // Кнопка импорта
        findViewById<Button>(R.id.btnImportWg).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            wgPicker.launch(intent)
        }

        // Навигация и логи
        findViewById<Button>(R.id.btnBack).setOnClickListener { savePrefs(); finish() }
        findViewById<Button>(R.id.btnCopySshLog).setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("ssh_logs", tvSshLog.text))
            Toast.makeText(this, "Логи скопированы!", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnClearSshLog).setOnClickListener { tvSshLog.text = "" }

        // --- SSH Логика ---
        btnConnectSsh.setOnClickListener {
            savePrefs()
            val ip = editIp.text.toString()
            val pass = editPass.text.toString()
            val port = editPort.text.toString().toIntOrNull() ?: 22
            val user = editUser.text.toString()
            
            if (ip.isEmpty() || pass.isEmpty()) return@setOnClickListener

            sshManager.disconnect()
            tvSshLog.text = "[Система]: Подключение к $ip...\n"
            sshManager.startShell(ip, port, user, pass) { output ->
                val clean = output.replace(Regex("\\x1B\\[[0-9;?]*[a-zA-Z]"), "").replace("\r", "")
                runOnUiThread {
                    tvSshLog.append(clean)
                    scrollSshLog.post { scrollSshLog.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
            checkServerState(ip, port, user, pass, "connect")
        }

        // Установка (скрипт)
        btnInstallServer.setOnClickListener {
            val script = """
                mkdir -p /opt/vk-turn && cd /opt/vk-turn && 
                pkill -9 -f "server-linux-" 2>/dev/null;
                ARCH=${'$'}(uname -m); 
                if [ "${'$'}ARCH" = "x86_64" ]; then BIN="server-linux-amd64"; else BIN="server-linux-arm64"; fi; 
                wget -qO ${'$'}BIN https://github.com/cacggghp/vk-turn-proxy/releases/latest/download/${'$'}BIN && 
                chmod +x ${'$'}BIN && echo "Установка завершена!"
            """.trimIndent()
            sshManager.sendShellCommand(script)
            refreshStateAfterDelay(6000)
        }

        btnStartProxy.setOnClickListener {
            val l = editProxyListen.text.toString().ifEmpty { "0.0.0.0:56000" }
            val c = editProxyConnect.text.toString().ifEmpty { "127.0.0.1:40537" }
            val script = """
                cd /opt/vk-turn && 
                ARCH=${'$'}(uname -m); 
                if [ "${'$'}ARCH" = "x86_64" ]; then BIN="server-linux-amd64"; else BIN="server-linux-arm64"; fi; 
                nohup ./${'$'}BIN -listen $l -connect $c > server.log 2>&1 & 
                echo ${'$'}! > proxy.pid && echo "Сервер запущен (PID: ${'$'}(cat proxy.pid))"
            """.trimIndent()
            sshManager.sendShellCommand(script)
            refreshStateAfterDelay(2000)
        }

        btnStopProxy.setOnClickListener {
            val script = "pkill -9 -f 'server-linux-'; rm -f /opt/vk-turn/proxy.pid; echo 'Остановлено.'"
            sshManager.sendShellCommand(script)
            refreshStateAfterDelay(2000)
        }

        btnCtrlC.setOnClickListener { sshManager.sendCtrlC(); tvSshLog.append("^C\n") }
        btnSendCmd.setOnClickListener {
            val cmd = editCustomCmd.text.toString().trim()
            if (cmd.isNotEmpty()) {
                if (cmd.lowercase() == "clear") tvSshLog.text = "" else sshManager.sendShellCommand(cmd)
                editCustomCmd.text.clear()
            }
        }
    }

    private fun refreshStateAfterDelay(ms: Long) {
        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        CoroutineScope(Dispatchers.Main).launch {
            delay(ms)
            checkServerState(
                prefs.getString("ip", "") ?: "",
                prefs.getString("port", "22")?.toIntOrNull() ?: 22,
                prefs.getString("user", "root") ?: "root",
                prefs.getString("pass", "") ?: "",
                "silent"
            )
        }
    }

    private fun parseAndSaveWg(content: String) {
        val priv = Regex("PrivateKey\\s*=\\s*(.+)").find(content)?.groupValues?.get(1)
        val pub = Regex("PublicKey\\s*=\\s*(.+)").find(content)?.groupValues?.get(1)
        val end = Regex("Endpoint\\s*=\\s*(.+)").find(content)?.groupValues?.get(1)
        
        findViewById<EditText>(R.id.editWgPriv).setText(priv?.trim())
        findViewById<EditText>(R.id.editWgPub).setText(pub?.trim())
        findViewById<EditText>(R.id.editWgEnd).setText(end?.trim())
        Toast.makeText(this, "Конфиг WireGuard импортирован!", Toast.LENGTH_SHORT).show()
    }

    private fun checkServerState(ip: String, port: Int, user: String, pass: String, mode: String) {
        if (ip.isEmpty()) return
        val checkCmd = """
            if ls /opt/vk-turn/server-linux-* >/dev/null 2>&1; then echo "INSTALLED:YES"; else echo "INSTALLED:NO"; fi
            if ps aux | grep -v grep | grep -q "server-linux-"; then echo "RUNNING:YES"; else echo "RUNNING:NO"; fi
        """.trimIndent()

        CoroutineScope(Dispatchers.IO).launch {
            val result = sshManager.executeSilentCommand(ip, port, user, pass, checkCmd)
            withContext(Dispatchers.Main) {
                if (result.contains("ERROR")) return@withContext
                val isInst = result.contains("INSTALLED:YES")
                val isRun = result.contains("RUNNING:YES")

                btnInstallServer.isEnabled = true
                btnStartProxy.isEnabled = isInst && !isRun
                btnStopProxy.isEnabled = isRun

                if (mode == "connect") {
                    val s = if (isRun) "РАБОТАЕТ" else "ОСТАНОВЛЕН"
                    val i = if (isInst) "УСТАНОВЛЕН" else "НЕ НАЙДЕН"
                    tvSshLog.append("\n[Система]: vk-turn-proxy $i. Статус: $s.\n")
                }
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); sshManager.disconnect() }
}
