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
import android.text.Editable
import android.text.TextWatcher
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
    private lateinit var btnRipePad: Button
    private lateinit var btnEmptyPad: Button
    private lateinit var seekRipe: SeekBar
    private lateinit var etEmptyBunches: EditText
    private lateinit var seekEmpty: SeekBar
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
    private var editRecordId: Long = -1L

    private val REQ_CAMERA      = 101
    private val REQ_PERMISSIONS = 103

    private var syncingSeekBar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_entry)

        etBlockId      = findViewById(R.id.etBlockId)
        etRipeBunches  = findViewById(R.id.etRipeBunches)
        seekRipe       = findViewById(R.id.seekRipe)
        btnRipePad  = findViewById(R.id.btnRipePad)
        btnEmptyPad = findViewById(R.id.btnEmptyPad)
        etEmptyBunches = findViewById(R.id.etEmptyBunches)
        seekEmpty      = findViewById(R.id.seekEmpty)
        tvGpsStatus    = findViewById(R.id.tvGpsStatus)
        btnTakePhoto   = findViewById(R.id.btnTakePhoto)
        ivPhotoPreview = findViewById(R.id.ivPhotoPreview)
        btnSaveEntry   = findViewById(R.id.btnSaveEntry)
        btnCancel      = findViewById(R.id.btnCancel)

        seekRipe.max  = 200
        seekEmpty.max = 200

        // Numpad dialog for Ripe
        btnRipePad.setOnClickListener { showNumpad("Ripe Bunches") { v ->
            etRipeBunches.setText(v.toString())
            seekRipe.progress = minOf(v, seekRipe.max)
            btnRipePad.text = v.toString()
        }}

        // Numpad dialog for Empty
        btnEmptyPad.setOnClickListener { showNumpad("Empty Bunches") { v ->
            etEmptyBunches.setText(v.toString())
            seekEmpty.progress = minOf(v, seekEmpty.max)
            btnEmptyPad.text = v.toString()
        }}

        // Restore last used Block ID
        val prefs = getSharedPreferences("oilpalm", MODE_PRIVATE)
        val lastBlock = prefs.getString("last_block_id", "") ?: ""
        if (lastBlock.isNotEmpty()) etBlockId.setText(lastBlock)

        // Check if editing existing record
        editRecordId = intent.getLongExtra("edit_record_id", -1L)
        if (editRecordId != -1L) {
            btnSaveEntry.text = "Update Entry"
            loadRecordForEdit(editRecordId)
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        requestAllPermissions()

        // Sync slider <-> text for Ripe
        seekRipe.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                if (fromUser && !syncingSeekBar) {
                    syncingSeekBar = true
                    etRipeBunches.setText(v.toString())
                    etRipeBunches.setSelection(etRipeBunches.text.length)
                    btnRipePad.text = v.toString()
                    syncingSeekBar = false
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        etRipeBunches.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                if (!syncingSeekBar) {
                    syncingSeekBar = true
                    val v = s.toString().toIntOrNull() ?: 0
                    seekRipe.progress = minOf(v, seekRipe.max)
                    btnRipePad.text = s.toString().ifEmpty { "0" }
                    syncingSeekBar = false
                }
            }
            override fun beforeTextChanged(s: CharSequence, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence, st: Int, b: Int, c: Int) {}
        })

        // Sync slider <-> text for Empty
        seekEmpty.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                if (fromUser && !syncingSeekBar) {
                    syncingSeekBar = true
                    etEmptyBunches.setText(v.toString())
                    etEmptyBunches.setSelection(etEmptyBunches.text.length)
                    btnEmptyPad.text = v.toString()
                    syncingSeekBar = false
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        etEmptyBunches.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                if (!syncingSeekBar) {
                    syncingSeekBar = true
                    val v = s.toString().toIntOrNull() ?: 0
                    seekEmpty.progress = minOf(v, seekEmpty.max)
                    btnEmptyPad.text = s.toString().ifEmpty { "0" }
                    syncingSeekBar = false
                }
            }
            override fun beforeTextChanged(s: CharSequence, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence, st: Int, b: Int, c: Int) {}
        })

        btnTakePhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) launchCamera()
            else ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
        btnSaveEntry.setOnClickListener { saveEntry() }
        btnCancel.setOnClickListener { finish() }
    }

    private fun loadRecordForEdit(id: Long) {
        lifecycleScope.launch {
            val record = HarvestDatabase.getInstance(this@NewEntryActivity)
                .harvestDao().getById(id) ?: return@launch
            etBlockId.setText(record.blockId)
            etRipeBunches.setText(record.ripeBunches.toString())
            etEmptyBunches.setText(record.emptyBunches.toString())
            seekRipe.progress  = minOf(record.ripeBunches, seekRipe.max)
            btnRipePad.text  = record.ripeBunches.toString()
            seekEmpty.progress = minOf(record.emptyBunches, seekEmpty.max)
            btnEmptyPad.text = record.emptyBunches.toString()
            photoPath = record.photoPath
            currentLat = record.latitude
            currentLng = record.longitude
            if (photoPath.isNotEmpty()) {
                try {
                    ivPhotoPreview.setImageBitmap(loadRotatedBitmap(photoPath))
                    ivPhotoPreview.visibility = android.view.View.VISIBLE
                    btnTakePhoto.text = "Retake Photo"
                } catch (_: Exception) {}
            }
            tvGpsStatus.text = "GPS: %.6f, %.6f".format(record.latitude, record.longitude)
            tvGpsStatus.setTextColor(android.graphics.Color.parseColor("#00d4ff"))
        }
    }

    private fun requestAllPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
        else fetchLocation()
    }

    private fun fetchLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                currentLat = loc.latitude
                currentLng = loc.longitude
                tvGpsStatus.text = "GPS: %.6f, %.6f".format(currentLat, currentLng)
                tvGpsStatus.setTextColor(android.graphics.Color.parseColor("#00d4ff"))
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2000L).setMaxUpdates(3).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                currentLat = loc.latitude
                currentLng = loc.longitude
                tvGpsStatus.text = "GPS: %.6f, %.6f".format(currentLat, currentLng)
                tvGpsStatus.setTextColor(android.graphics.Color.parseColor("#00d4ff"))
            }
        }
        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    private fun launchCamera() {
        val timeStamp  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val photoFile  = File.createTempFile("HARVEST_${timeStamp}_", ".jpg", storageDir)
        photoPath = photoFile.absolutePath
        photoUri  = FileProvider.getUriForFile(
            this, "com.example.oilpalmharvester.fileprovider", photoFile)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        startActivityForResult(intent, REQ_CAMERA)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAMERA && resultCode == RESULT_OK) {
            compressPhoto(photoPath)
            ivPhotoPreview.setImageBitmap(loadRotatedBitmap(photoPath))
            ivPhotoPreview.visibility = android.view.View.VISIBLE
            btnTakePhoto.text = "Retake Photo"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_PERMISSIONS -> fetchLocation()
            REQ_CAMERA -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                launchCamera() else toast("Camera permission required")
        }
    }

    private fun loadRotatedBitmap(path: String): android.graphics.Bitmap {
        val bitmap = BitmapFactory.decodeFile(path)
        val exif   = ExifInterface(path)
        val orient = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orient) {
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

        // Persist last block ID
        getSharedPreferences("oilpalm", MODE_PRIVATE)
            .edit().putString("last_block_id", blockId).apply()

        val prefs       = getSharedPreferences("oilpalm", MODE_PRIVATE)
        val harvesterId = prefs.getString("harvester_id", "UNKNOWN") ?: "UNKNOWN"

        lifecycleScope.launch {
            val dao = HarvestDatabase.getInstance(this@NewEntryActivity).harvestDao()
            if (editRecordId != -1L) {
                val existing = dao.getById(editRecordId) ?: return@launch
                dao.update(existing.copy(
                    blockId      = blockId,
                    ripeBunches  = ripeStr.toIntOrNull() ?: 0,
                    emptyBunches = emptyStr.toIntOrNull() ?: 0,
                    latitude     = currentLat,
                    longitude    = currentLng,
                    photoPath    = photoPath,
                    synced       = false,
                    photoSynced  = false
                ))
                toast("Entry updated!")
            } else {
                dao.insert(HarvestRecord(
                    harvesterId  = harvesterId,
                    blockId      = blockId,
                    ripeBunches  = ripeStr.toIntOrNull() ?: 0,
                    emptyBunches = emptyStr.toIntOrNull() ?: 0,
                    latitude     = currentLat,
                    longitude    = currentLng,
                    photoPath    = photoPath,
                    timestamp    = System.currentTimeMillis()
                ))
                toast("Entry saved!")
            }
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }

    private fun compressPhoto(path: String) {
        try {
            val original = loadRotatedBitmap(path)
            val maxDim = 1024
            val scale = minOf(maxDim.toFloat() / original.width, maxDim.toFloat() / original.height, 1f)
            val w = (original.width  * scale).toInt()
            val h = (original.height * scale).toInt()
            val scaled = android.graphics.Bitmap.createScaledBitmap(original, w, h, true)
            java.io.FileOutputStream(path).use { out ->
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
            }
        } catch (e: Exception) {
            // keep original if compression fails
        }
    }

        private fun showNumpad(title: String, onValue: (Int) -> Unit) {
        val display = android.widget.TextView(this).apply {
            text = "0"
            textSize = 48f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 24, 0, 24)
            setBackgroundColor(android.graphics.Color.parseColor("#0a1628"))
        }
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 3
            setPadding(16, 8, 16, 8)
            setBackgroundColor(android.graphics.Color.parseColor("#0f1f3d"))
        }
        val keys = listOf("1","2","3","4","5","6","7","8","9","CLR","0","OK")
        var current = ""
        fun updateDisplay() { display.text = if (current.isEmpty()) "0" else current }

        for (key in keys) {
            val btn = android.widget.Button(this).apply {
                text = key
                textSize = 22f
                setTextColor(android.graphics.Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    when(key) {
                        "OK"  -> android.graphics.Color.parseColor("#00d4ff")
                        "CLR" -> android.graphics.Color.parseColor("#8b0000")
                        else  -> android.graphics.Color.parseColor("#0f3460")
                    })
                val lp = android.widget.GridLayout.LayoutParams().apply {
                    width  = 0
                    height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(6, 6, 6, 6)
                }
                layoutParams = lp
                minimumHeight = 140
            }
            btn.setOnClickListener {
                when (key) {
                    "CLR" -> { current = ""; updateDisplay() }
                    "OK"  -> { /* handled below */ }
                    else  -> {
                        if (current.length < 3) { current += key; updateDisplay() }
                    }
                }
            }
            grid.addView(btn)
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        container.addView(display)
        container.addView(grid)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setCancelable(true)
            .create()

        // Wire OK button separately so we can dismiss
        val okBtn = grid.getChildAt(11) as android.widget.Button
        okBtn.setOnClickListener {
            val v = current.toIntOrNull() ?: 0
            onValue(v)
            dialog.dismiss()
        }
        dialog.show()
    }

        private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}





