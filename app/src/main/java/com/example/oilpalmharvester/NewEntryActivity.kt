package com.example.oilpalmharvester

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
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
    private var locationCallback: LocationCallback? = null

    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    private var photoPath: String = ""
    private var photoUri: Uri? = null

    private val REQ_CAMERA      = 101
    private val REQ_PERMISSIONS = 103

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

        requestAllPermissions()

        btnTakePhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            }
        }
        btnSaveEntry.setOnClickListener { saveEntry() }
        btnCancel.setOnClickListener { finish() }
    }

    private fun requestAllPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
        } else {
            fetchLocation()
        }
    }

    private fun fetchLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        // Try last known location first
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                currentLat = loc.latitude
                currentLng = loc.longitude
                tvGpsStatus.text = "GPS: %.6f, %.6f".format(currentLat, currentLng)
                tvGpsStatus.setTextColor(android.graphics.Color.parseColor("#00d4ff"))
            }
        }

        // Request a fresh fix
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2000L)
            .setMaxUpdates(3)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                currentLat = loc.latitude
                currentLng = loc.longitude
                tvGpsStatus.text = "GPS: %.6f, %.6f".format(currentLat, currentLng)
                tvGpsStatus.setTextColor(android.graphics.Color.parseColor("#00d4ff"))
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request, locationCallback!!, Looper.getMainLooper())
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
        startActivityForResult(intent, REQ_CAMERA)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAMERA && resultCode == RESULT_OK) {
            val bitmap = loadRotatedBitmap(photoPath)
            ivPhotoPreview.setImageBitmap(bitmap)
            ivPhotoPreview.visibility = android.view.View.VISIBLE
            btnTakePhoto.text = "Retake Photo"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_PERMISSIONS -> fetchLocation()
            REQ_CAMERA -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                    launchCamera()
                else
                    toast("Camera permission is required")
            }
        }
    }

    private fun loadRotatedBitmap(path: String): android.graphics.Bitmap {
        val bitmap = BitmapFactory.decodeFile(path)
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        return android.graphics.Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun saveEntry() {
        val blockId  = etBlockId.text.toString().trim()
        val ripeStr  = etRipeBunches.text.toString().trim()
        val emptyStr = etEmptyBunches.text.toString().trim()

        if (blockId.isEmpty())   { toast("Enter a Block ID"); return }
        if (ripeStr.isEmpty())   { toast("Enter ripe bunches count"); return }
        if (emptyStr.isEmpty())  { toast("Enter empty bunches count"); return }
        if (photoPath.isEmpty()) { toast("Photo is required"); return }

        val prefs = getSharedPreferences("oilpalm", MODE_PRIVATE)
        val harvesterId = prefs.getString("harvester_id", "UNKNOWN") ?: "UNKNOWN"

        val record = HarvestRecord(
            harvesterId  = harvesterId,
            blockId      = blockId,
            ripeBunches  = ripeStr.toIntOrNull() ?: 0,
            emptyBunches = emptyStr.toIntOrNull() ?: 0,
            latitude     = currentLat,
            longitude    = currentLng,
            photoPath    = photoPath,
            timestamp    = System.currentTimeMillis()
        )

        lifecycleScope.launch {
            HarvestDatabase.getInstance(this@NewEntryActivity)
                .harvestDao().insert(record)
            toast("Entry saved!")
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
