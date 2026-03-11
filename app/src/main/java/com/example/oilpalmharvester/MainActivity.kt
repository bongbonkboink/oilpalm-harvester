package com.example.oilpalmharvester

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.content.res.ColorStateList
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvHarvesterId: TextView
    private lateinit var btnNavLog: Button
    private lateinit var btnNavRecords: Button
    private lateinit var btnNavCalendar: Button
    private lateinit var btnNavSync: Button
    private lateinit var panelLog: LinearLayout
    private lateinit var panelRecords: ScrollView
    private lateinit var panelCalendar: LinearLayout
    private lateinit var panelSync: LinearLayout
    private lateinit var tvTodaySummary: TextView
    private lateinit var btnNewEntry: Button
    private lateinit var spinnerDevices: Spinner
    private lateinit var btnConnect: Button
    private lateinit var tvMyAddress: TextView
    private lateinit var btnSyncNow: Button

    private val btService = BluetoothService()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvHarvesterId   = findViewById(R.id.tvHarvesterId)
        btnNavLog       = findViewById(R.id.btnNavLog)
        btnNavRecords   = findViewById(R.id.btnNavRecords)
        btnNavCalendar  = findViewById(R.id.btnNavCalendar)
        btnNavSync      = findViewById(R.id.btnNavSync)
        panelLog        = findViewById(R.id.panelLog)
        panelRecords    = findViewById(R.id.panelRecords)
        panelCalendar   = findViewById(R.id.panelCalendar)
        panelSync       = findViewById(R.id.panelSync)
        tvTodaySummary  = findViewById(R.id.tvTodaySummary)
        btnNewEntry     = findViewById(R.id.btnNewEntry)
        spinnerDevices  = findViewById(R.id.spinnerDevices)
        btnConnect      = findViewById(R.id.btnConnect)
        tvMyAddress     = findViewById(R.id.tvMyAddress)
        btnSyncNow      = findViewById(R.id.btnSyncNow)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        val prefs = getSharedPreferences("oilpalm", MODE_PRIVATE)
        val hid = prefs.getString("harvester_id", "") ?: ""
        if (hid.isNotEmpty()) tvHarvesterId.text = "ID: $hid"

        btnNavLog.setOnClickListener      { showTab("log") }
        btnNavRecords.setOnClickListener  { showTab("records") }
        btnNavCalendar.setOnClickListener { showTab("calendar") }
        btnNavSync.setOnClickListener     { showTab("sync") }

        btnNewEntry.setOnClickListener {
            Toast.makeText(this, "Harvest logging coming in Stage 2!", Toast.LENGTH_SHORT).show()
        }

        if (hid.isEmpty()) promptForHarvesterId()

        requestBtPermissions()
    }

    private fun promptForHarvesterId() {
        val input = EditText(this).apply {
            hint = "e.g. HRV-001 or your name"
            setPadding(40, 20, 40, 20)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Set Your Harvester ID")
            .setMessage("Saved on device. Appears on all your records.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val id = input.text.toString().trim()
                if (id.isNotEmpty()) {
                    getSharedPreferences("oilpalm", MODE_PRIVATE)
                        .edit().putString("harvester_id", id).apply()
                    tvHarvesterId.text = "ID: $id"
                    Toast.makeText(this, "Harvester ID saved!", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun showTab(tab: String) {
        val cyan = ColorStateList.valueOf(Color.parseColor("#00d4ff"))
        val dark = ColorStateList.valueOf(Color.parseColor("#0f3460"))
        val darkText = Color.parseColor("#1a1a2e")

        panelLog.visibility      = View.GONE
        panelRecords.visibility  = View.GONE
        panelCalendar.visibility = View.GONE
        panelSync.visibility     = View.GONE

        listOf(btnNavLog, btnNavRecords, btnNavCalendar, btnNavSync).forEach {
            it.backgroundTintList = dark
            it.setTextColor(Color.WHITE)
        }

        when (tab) {
            "log" -> {
                panelLog.visibility = View.VISIBLE
                btnNavLog.backgroundTintList = cyan
                btnNavLog.setTextColor(darkText)
            }
            "records" -> {
                panelRecords.visibility = View.VISIBLE
                btnNavRecords.backgroundTintList = cyan
                btnNavRecords.setTextColor(darkText)
            }
            "calendar" -> {
                panelCalendar.visibility = View.VISIBLE
                btnNavCalendar.backgroundTintList = cyan
                btnNavCalendar.setTextColor(darkText)
            }
            "sync" -> {
                panelSync.visibility = View.VISIBLE
                btnNavSync.backgroundTintList = cyan
                btnNavSync.setTextColor(darkText)
            }
        }
    }

    private fun requestBtPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                perms += Manifest.permission.BLUETOOTH_CONNECT
                perms += Manifest.permission.BLUETOOTH_SCAN
            }
        }
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
        else setupBluetooth()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) setupBluetooth()
        else Toast.makeText(this, "Bluetooth permissions denied", Toast.LENGTH_SHORT).show()
    }

    private fun setupBluetooth() {
        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val ba = bm.adapter ?: run {
            Toast.makeText(this, "No Bluetooth!", Toast.LENGTH_SHORT).show()
            return
        }
        val paired = ba.bondedDevices?.toList() ?: emptyList()
        val names = paired.map { "${it.name} (${it.address})" }
        spinnerDevices.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, names
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        btnConnect.setOnClickListener {
            val idx = spinnerDevices.selectedItemPosition
            if (idx < 0 || idx >= paired.size) return@setOnClickListener
            val device = paired[idx]
            btnConnect.isEnabled = false
            Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show()
            scope.launch {
                val connected = withContext(Dispatchers.IO) { btService.connect(device.address) }
                if (!connected) {
                    Toast.makeText(this@MainActivity, "BT connection failed", Toast.LENGTH_SHORT).show()
                    btnConnect.isEnabled = true
                    return@launch
                }
                val addr = withContext(Dispatchers.IO) { RNSBridge.start(btService) }
                if (addr.startsWith("Error")) {
                    Toast.makeText(this@MainActivity, "RNS error: $addr", Toast.LENGTH_SHORT).show()
                    btnConnect.isEnabled = true
                } else {
                    tvMyAddress.text = "Address: $addr"
                    btnSyncNow.isEnabled = true
                    Toast.makeText(this@MainActivity, "RNS Ready!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        btService.disconnect()
    }
}

