package com.vkturn.proxy

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tvLogs: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var btnToggle: Button

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startProxyService()
        } else {
            Toast.makeText(this, "Разрешение VPN отклонено!", Toast.LENGTH_LONG).show()
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val customBin = File(filesDir, "custom_vkturn")
                    FileOutputStream(customBin).use { inputStream?.copyTo(it) }
                    inputStream?.close()
                    customBin.setExecutable(true)
                    WgVpnService.addLog("СИСТЕМА: Кастомный бинарник установлен")
                    Toast.makeText(this, "Ядро обновлено!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    WgVpnService.addLog("ОШИБКА ОБНОВЛЕНИЯ: ${e.message}")
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) Toast.makeText(this, "Разрешите уведомления!", Toast.LENGTH_LONG).show()
        else checkVpnPermissionAndStart()  
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLogs = findViewById(R.id.tvLogs)
        logScrollView = findViewById(R.id.logScrollView)
        btnToggle = findViewById(R.id.btnToggle)
        tvLogs.setTextIsSelectable(true)

        val switchRawMode    = findViewById<Switch>(R.id.switchRawMode)
        val editRawCommand   = findViewById<EditText>(R.id.editRawCommand)
        val layoutGuiSettings = findViewById<LinearLayout>(R.id.layoutGuiSettings)
        val editPeer         = findViewById<EditText>(R.id.editPeer)
        val editLink         = findViewById<EditText>(R.id.editLink)
        val editN            = findViewById<EditText>(R.id.editN)
        val checkUdp         = findViewById<CheckBox>(R.id.checkUdp)
        val checkNoDtls      = findViewById<CheckBox>(R.id.checkNoDtls)
        val editListen       = findViewById<EditText>(R.id.editListen)

        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        switchRawMode.isChecked  = prefs.getBoolean("isRaw", false)
        editRawCommand.setText(prefs.getString("rawCmd", ""))
        editPeer.setText(prefs.getString("peer", ""))
        editLink.setText(prefs.getString("link", ""))
        editN.setText(prefs.getString("n", "8"))
        checkUdp.isChecked    = prefs.getBoolean("udp", true)
        checkNoDtls.isChecked = prefs.getBoolean("noDtls", false)
        editListen.setText(prefs.getString("listen", "127.0.0.1:9000"))

        val updateUiState = {
            editRawCommand.visibility    = if (switchRawMode.isChecked) View.VISIBLE else View.GONE
            layoutGuiSettings.visibility = if (switchRawMode.isChecked) View.GONE else View.VISIBLE
        }
        updateUiState()
        switchRawMode.setOnCheckedChangeListener { _, _ -> updateUiState() }

        findViewById<Button>(R.id.btnUpdateBinary).setOnClickListener {
            filePicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" })
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
        findViewById<Button>(R.id.btnCopyLogs).setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("logs", tvLogs.text))
            Toast.makeText(this, "Логи скопированы!", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnClearLogs).setOnClickListener {
            WgVpnService.logBuffer.clear()
            tvLogs.text = "Консоль очищена."
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnToggle.setOnClickListener {
            Log.d("MainActivity", "btnToggle clicked! isRunning=${WgVpnService.isRunning}")
            if (!WgVpnService.isRunning) {
                savePrefs(prefs, switchRawMode, editRawCommand, editPeer, editLink, editN, checkUdp, checkNoDtls, editListen)
                checkPermissionsAndStart()
            } else {
                stopService(Intent(this, WgVpnService::class.java))
                btnToggle.text = "ЗАПУСТИТЬ ПРОКСИ"
            }
        }
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        checkVpnPermissionAndStart()
    }

    private fun checkVpnPermissionAndStart() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startProxyService()
        }
    }

    private fun startProxyService() {
        WgVpnService.logBuffer.clear()
        startForegroundService(Intent(this, WgVpnService::class.java))
        btnToggle.text = "ОСТАНОВИТЬ ПРОКСИ"
    }


    private fun savePrefs(
        prefs: android.content.SharedPreferences,
        switchRawMode: Switch, editRawCommand: EditText,
        editPeer: EditText, editLink: EditText,
        editN: EditText, checkUdp: CheckBox,
        checkNoDtls: CheckBox, editListen: EditText
    ) {
        prefs.edit().apply {
            putBoolean("isRaw",  switchRawMode.isChecked)
            putString("rawCmd",  editRawCommand.text.toString())
            putString("peer",    editPeer.text.toString())
            putString("link",    editLink.text.toString())
            putString("n",       editN.text.toString())
            putBoolean("udp",    checkUdp.isChecked)
            putBoolean("noDtls", checkNoDtls.isChecked)
            putString("listen",  editListen.text.toString())
        }.apply()
    }


    override fun onResume() {
        super.onResume()
        btnToggle.text = if (WgVpnService.isRunning) "ОСТАНОВИТЬ ПРОКСИ" else "ЗАПУСТИТЬ ПРОКСИ"
        tvLogs.text = WgVpnService.logBuffer.joinToString("\n")
        scrollLogsToEnd()

        WgVpnService.onLogReceived = { msg ->
            runOnUiThread {
                if (tvLogs.text.length > 25000) tvLogs.text = tvLogs.text.substring(10000)
                tvLogs.append("\n$msg")
                scrollLogsToEnd()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        WgVpnService.onLogReceived = null
    }

    private fun scrollLogsToEnd() {
        logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
    }
}
