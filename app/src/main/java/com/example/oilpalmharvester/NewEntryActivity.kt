package com.example.oilpalmharvester

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class NewEntryActivity : AppCompatActivity() {

    private lateinit var etBlockId: EditText
    private lateinit var etRipeBunches: EditText
    private lateinit var etEmptyBunches: EditText
    private lateinit var tvGpsStatus: TextView
    private lateinit var btnTakePhoto: Button
    private lateinit var ivPhotoPreview: ImageView
    private lateinit var btnSaveEntry: Button
    private lateinit var btnCancel: Button

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    private var photoPath: String = ""
    private var photoUri: Uri? = null

    private val CAMERA_REQUEST = 101
    private val LOCATION_REQUEST = 102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_entry)

        etBlockId      = findViewById(R.id.etBlockId)
        etRipeBunches  = findViewById(R.id.etRipeBunches)
        etEmptyBunches = findViewById(R.id.etEmptyBunches)
        tvGpsStatus    = findViewById(R.id.tvGpsStatus)
        btnTakePhoto   = findViewById(R.id.btnTakePhoto)
        ivPhotoPreview = findViewById(R.id.ivPhotoPreview)
        btnSaveEntry   = findViewById(R.id.btnSaveEntry)
        btnCancel      = findViewById(R.id.btnCancel)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        requestLocationPermission()

        btnTakePhoto.setOnClickListener { launchCamera() }
        btnSaveEntry.setOnClickListener { saveEntry() }
        btnCancel.setOnClickListener { finish() }
    }

    private fun requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_REQUEST)
        } else {
            fetchLocation()
        }
    }

    private fun fetchLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
            if (loc != null) {
                currentLat = loc.latitude
                currentLng = loc.longitude
                tvGpsStatus.text = "GPS: %.6f, %.6f".format(currentLat, currentLng)
                tvGpsStatus.setTextColor(android.graphics.Color.parseColor("#00d4ff"))
            } else {
                tvGpsStatus.text = "GPS: No fix yet (will save 0,0)"
            }
        }
    }

    private fun launchCamera() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val photoFile = File.createTempFile("HARVEST_${timeStamp}_", ".jpg", storageDir)
        photoPath = photoFile.absolutePath
        photoUri = FileProvider.getUriForFile(
            this, "com.example.oilpalmharvester.fileprovider", photoFile)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        startActivityForResult(intent, CAMERA_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAMERA_REQUEST && resultCode == RESULT_OK) {
            val bitmap = BitmapFactory.decodeFile(photoPath)
            ivPhotoPreview.setImageBitmap(bitmap)
            ivPhotoPreview.visibility = android.view.View.VISIBLE
            btnTakePhoto.text = "Retake Photo"
            btnTakePhoto.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#1a3a1a"))
        }
    }

    private fun saveEntry() {
        val blockId = etBlockId.text.toString().trim()
        val ripeStr = etRipeBunches.text.toString().trim()
        val emptyStr = etEmptyBunches.text.toString().trim()

        if (blockId.isEmpty()) { toast("Enter a Block ID"); return }
        if (ripeStr.isEmpty()) { toast("Enter ripe bunches count"); return }
        if (emptyStr.isEmpty()) { toast("Enter empty bunches count"); return }
        if (photoPath.isEmpty()) { toast("Photo is required"); return }

        val prefs = getSharedPreferences("oilpalm", MODE_PRIVATE)
        val harvesterId = prefs.getString("harvester_id", "UNKNOWN") ?: "UNKNOWN"

        val record = HarvestRecord(
            harvesterId   = harvesterId,
            blockId       = blockId,
            ripeBunches   = ripeStr.toIntOrNull() ?: 0,
            emptyBunches  = emptyStr.toIntOrNull() ?: 0,
            latitude      = currentLat,
            longitude     = currentLng,
            photoPath     = photoPath,
            timestamp     = System.currentTimeMillis()
        )

        lifecycleScope.launch {
            HarvestDatabase.getInstance(this@NewEntryActivity)
                .harvestDao().insert(record)
            toast("Entry saved!")
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            fetchLocation()
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
