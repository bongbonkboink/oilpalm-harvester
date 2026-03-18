package com.example.oilpalmharvester

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etHarvesterId   = findViewById<EditText>(R.id.etHarvesterId)
        val etBaseStation   = findViewById<EditText>(R.id.etBaseStation)
        val btnSave         = findViewById<Button>(R.id.btnSaveSettings)

        // RNode fields
        val etFrequency     = findViewById<EditText>(R.id.etFrequency)
        val spinnerBw       = findViewById<Spinner>(R.id.spinnerBandwidth)
        val etTxPower       = findViewById<EditText>(R.id.etTxPower)
        val spinnerSf       = findViewById<Spinner>(R.id.spinnerSf)
        val spinnerCr       = findViewById<Spinner>(R.id.spinnerCr)
        val btnSaveRNode    = findViewById<Button>(R.id.btnSaveRNode)
        val tvRNodeStatus   = findViewById<TextView>(R.id.tvRNodeStatus)

        val prefs = getSharedPreferences("oilpalm", MODE_PRIVATE)
        etHarvesterId.setText(prefs.getString("harvester_id", ""))
        etBaseStation.setText(prefs.getString("base_station_address", ""))

        btnSave.setOnClickListener {
            val hid  = etHarvesterId.text.toString().trim()
            val base = etBaseStation.text.toString().trim()
            prefs.edit()
                .putString("harvester_id", hid)
                .putString("base_station_address", base)
                .apply()
            toast("Settings saved!")
        }

        // Bandwidth options
        val bwValues = intArrayOf(7800,10400,15600,20800,31250,41700,62500,125000,250000,500000)
        val bwLabels = bwValues.map {
            when {
                it >= 1000 -> "${it/1000} kHz"
                else       -> "$it Hz"
            }
        }
        val bwAdapter = object : ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, bwLabels) {
            override fun getView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup) =
                super.getView(p, cv, parent).also { (it as? TextView)?.setTextColor(android.graphics.Color.WHITE) }
            override fun getDropDownView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup) =
                super.getDropDownView(p, cv, parent).also {
                    (it as? TextView)?.apply {
                        setTextColor(android.graphics.Color.WHITE)
                        setBackgroundColor(android.graphics.Color.parseColor("#0f3460"))
                        setPadding(24,20,24,20)
                    }
                }
        }
        bwAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBw.adapter = bwAdapter

        // SF options 6-12
        val sfValues = (6..12).toList()
        val sfAdapter = object : ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, sfValues.map { "SF$it" }) {
            override fun getView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup) =
                super.getView(p, cv, parent).also { (it as? TextView)?.setTextColor(android.graphics.Color.WHITE) }
            override fun getDropDownView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup) =
                super.getDropDownView(p, cv, parent).also {
                    (it as? TextView)?.apply {
                        setTextColor(android.graphics.Color.WHITE)
                        setBackgroundColor(android.graphics.Color.parseColor("#0f3460"))
                        setPadding(24,20,24,20)
                    }
                }
        }
        sfAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSf.adapter = sfAdapter

        // CR options 5-8
        val crValues = (5..8).toList()
        val crAdapter = object : ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, crValues.map { "4/$it" }) {
            override fun getView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup) =
                super.getView(p, cv, parent).also { (it as? TextView)?.setTextColor(android.graphics.Color.WHITE) }
            override fun getDropDownView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup) =
                super.getDropDownView(p, cv, parent).also {
                    (it as? TextView)?.apply {
                        setTextColor(android.graphics.Color.WHITE)
                        setBackgroundColor(android.graphics.Color.parseColor("#0f3460"))
                        setPadding(24,20,24,20)
                    }
                }
        }
        crAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCr.adapter = crAdapter

        // Load current RNode config
        try {
            val py = Python.getInstance()
            val cfg = py.getModule("rns_worker").callAttr("get_rnode_config")
            val cfgMap = cfg.asMap()
            val freq = cfgMap[py.builtins.callAttr("str", "frequency")]?.toString()?.toLongOrNull() ?: 433025000L
            val bw   = cfgMap[py.builtins.callAttr("str", "bandwidth")]?.toString()?.toIntOrNull() ?: 31250
            val tx   = cfgMap[py.builtins.callAttr("str", "txpower")]?.toString()?.toIntOrNull()   ?: 17
            val sf   = cfgMap[py.builtins.callAttr("str", "sf")]?.toString()?.toIntOrNull()        ?: 8
            val cr   = cfgMap[py.builtins.callAttr("str", "cr")]?.toString()?.toIntOrNull()        ?: 6

            etFrequency.setText((freq / 1_000_000.0).toString())
            etTxPower.setText(tx.toString())
            spinnerBw.setSelection(bwValues.indexOf(bw).coerceAtLeast(0))
            spinnerSf.setSelection(sfValues.indexOf(sf).coerceAtLeast(0))
            spinnerCr.setSelection(crValues.indexOf(cr).coerceAtLeast(0))
        } catch (e: Exception) {
            etFrequency.setText("433.025")
            etTxPower.setText("17")
        }

        btnSaveRNode.setOnClickListener {
            try {
                val freqMhz = etFrequency.text.toString().toDoubleOrNull()
                if (freqMhz == null) { toast("Invalid frequency"); return@setOnClickListener }
                val freqHz  = (freqMhz * 1_000_000).toLong().toInt()
                val bw      = bwValues[spinnerBw.selectedItemPosition]
                val tx      = etTxPower.text.toString().toIntOrNull() ?: 17
                val sf      = sfValues[spinnerSf.selectedItemPosition]
                val cr      = crValues[spinnerCr.selectedItemPosition]

                val py = Python.getInstance()
                val result = py.getModule("rns_worker")
                    .callAttr("save_rnode_config", freqHz, bw, tx, sf, cr).toString()

                tvRNodeStatus.text = if (result == "OK") "Saved! Reconnect RNode to apply." else result
                tvRNodeStatus.setTextColor(
                    if (result == "OK") android.graphics.Color.parseColor("#00d4ff")
                    else android.graphics.Color.parseColor("#ff4444"))
            } catch (e: Exception) {
                toast("Error: ${e.message}")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
