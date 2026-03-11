package com.example.oilpalmharvester

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etHarvesterId = findViewById<EditText>(R.id.etHarvesterId)
        val etBaseStation = findViewById<EditText>(R.id.etBaseStation)
        val btnSave       = findViewById<Button>(R.id.btnSaveSettings)

        val prefs = getSharedPreferences("oilpalm", MODE_PRIVATE)
        etHarvesterId.setText(prefs.getString("harvester_id", ""))
        etBaseStation.setText(prefs.getString("base_station_address", ""))

        btnSave.setOnClickListener {
            val hid  = etHarvesterId.text.toString().trim()
            val addr = etBaseStation.text.toString().trim()
            if (hid.isEmpty()) {
                Toast.makeText(this, "Harvester ID cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString("harvester_id", hid)
                .putString("base_station_address", addr)
                .apply()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
