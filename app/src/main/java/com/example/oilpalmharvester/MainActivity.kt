package com.example.oilpalmharvester

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.view.View
import android.content.res.ColorStateList
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvHarvesterId: TextView
    private lateinit var btnSettings: Button
    private lateinit var btnNavLog: Button
    private lateinit var btnNavRecords: Button
    private lateinit var btnNavCalendar: Button
    private lateinit var btnNavSync: Button
    private lateinit var panelLog: LinearLayout
    private lateinit var panelRecords: ScrollView
    private lateinit var panelCalendar: LinearLayout
    private lateinit var panelSync: ScrollView
    private lateinit var tvTodaySummary: TextView
    private lateinit var btnNewEntry: Button
    private lateinit var recordsContainer: LinearLayout
    private lateinit var calendarGrid: android.widget.GridLayout
    private lateinit var tvCalendarMonth: TextView
    private lateinit var spinnerDevices: Spinner
    private lateinit var btnConnect: Button
    private lateinit var tvMyAddress: TextView
    private lateinit var tvBaseStationAddr: TextView
    private lateinit var tvUnsyncedCount: TextView
    private lateinit var btnSyncNow: Button
    private lateinit var btnSendPhotos: Button
    private lateinit var btnAnnounce: Button

    private val btService = BluetoothService()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var calendarYear  = 0
    private var calendarMonth = 0
    private var rnsConnected  = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvHarvesterId    = findViewById(R.id.tvHarvesterId)
        btnSettings      = findViewById(R.id.btnSettings)
        btnNavLog        = findViewById(R.id.btnNavLog)
        btnNavRecords    = findViewById(R.id.btnNavRecords)
        btnNavCalendar   = findViewById(R.id.btnNavCalendar)
        btnNavSync       = findViewById(R.id.btnNavSync)
        panelLog         = findViewById(R.id.panelLog)
        panelRecords     = findViewById(R.id.panelRecords)
        panelCalendar    = findViewById(R.id.panelCalendar)
        panelSync        = findViewById(R.id.panelSync)
        tvTodaySummary   = findViewById(R.id.tvTodaySummary)
        btnNewEntry      = findViewById(R.id.btnNewEntry)
        recordsContainer = findViewById(R.id.recordsContainer)
        calendarGrid     = findViewById(R.id.calendarGrid)
        tvCalendarMonth  = findViewById(R.id.tvCalendarMonth)
        spinnerDevices   = findViewById(R.id.spinnerDevices)
        spinnerDevices.setPopupBackgroundResource(android.R.color.black)
        btnConnect       = findViewById(R.id.btnConnect)
        tvMyAddress      = findViewById(R.id.tvMyAddress)
        tvBaseStationAddr = findViewById(R.id.tvBaseStationAddr)
        tvUnsyncedCount  = findViewById(R.id.tvUnsyncedCount)
        btnSyncNow       = findViewById(R.id.btnSyncNow)
        btnAnnounce      = findViewById(R.id.btnAnnounce)
        btnSendPhotos    = findViewById(R.id.btnSendPhotos)

        if (!Python.isStarted()) Python.start(AndroidPlatform(this))

        val prefs = getSharedPreferences("oilpalm", MODE_PRIVATE)
        val hid = prefs.getString("harvester_id", "") ?: ""
        if (hid.isNotEmpty()) tvHarvesterId.text = "ID: $hid"

        val cal = Calendar.getInstance()
        calendarYear  = cal.get(Calendar.YEAR)
        calendarMonth = cal.get(Calendar.MONTH)

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnNavLog.setOnClickListener      { showTab("log") }
        btnNavRecords.setOnClickListener  { showTab("records") }
        btnNavCalendar.setOnClickListener { showTab("calendar") }
        btnNavSync.setOnClickListener     { showTab("sync") }

        btnNewEntry.setOnClickListener {
            startActivityForResult(Intent(this, NewEntryActivity::class.java), 200)
        }

        btnSyncNow.setOnClickListener    { startCsvSync() }
        btnSendPhotos.setOnClickListener { sendPendingPhotos() }
        btnAnnounce.setOnClickListener {
            if (!rnsConnected) { toast("Connect to RNode first"); return@setOnClickListener }
            scope.launch {
                val result = withContext(Dispatchers.IO) { RNSBridge.announce() }
                toast(result)
            }
        }

        if (hid.isEmpty()) promptForHarvesterId()
        requestBtPermissions()
        refreshTodaySummary()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("oilpalm", MODE_PRIVATE)
        val hid  = prefs.getString("harvester_id", "") ?: ""
        if (hid.isNotEmpty()) tvHarvesterId.text = "ID: $hid"
        val base = prefs.getString("base_station_address", "") ?: ""
        tvBaseStationAddr.text = if (base.isEmpty()) "Not set - go to Settings" else base
        tvBaseStationAddr.setTextColor(
            if (base.isEmpty()) Color.parseColor("#555555")
            else Color.parseColor("#00d4ff"))
        refreshUnsyncedCount()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == RESULT_OK) refreshTodaySummary()
    }

    private fun refreshTodaySummary() {
        lifecycleScope.launch {
            val dao = HarvestDatabase.getInstance(this@MainActivity).harvestDao()
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0);      cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            val end   = start + 86400000L
            val records = dao.getForDay(start, end)
            val totalRipe  = records.sumOf { it.ripeBunches }
            val totalEmpty = records.sumOf { it.emptyBunches }
            tvTodaySummary.text = if (records.isEmpty()) "No entries yet"
            else "${records.size} block(s)  |  Ripe: $totalRipe  |  Empty: $totalEmpty"
        }
    }

    private fun refreshUnsyncedCount() {
        lifecycleScope.launch {
            val dao      = HarvestDatabase.getInstance(this@MainActivity).harvestDao()
            val unsynced = dao.getUnsynced()
            val noPhoto  = dao.getUnsyncedPhotos()
            tvUnsyncedCount.text =
                "${unsynced.size} CSV record(s) pending  |  ${noPhoto.size} photo(s) pending"
        }
    }

    // -- CSV sync via RNS ------------------------------------------------------

    private fun startCsvSync() {
        val prefs    = getSharedPreferences("oilpalm", MODE_PRIVATE)
        val baseAddr = prefs.getString("base_station_address", "") ?: ""
        if (baseAddr.isEmpty()) { toast("Set base station address in Settings first"); return }
        if (!rnsConnected)      { toast("Connect to RNode first"); return }

        btnSyncNow.isEnabled = false
        btnSyncNow.text = "Syncing..."

        scope.launch {
            val dao      = HarvestDatabase.getInstance(this@MainActivity).harvestDao()
            val unsynced = withContext(Dispatchers.IO) { dao.getUnsynced() }

            if (unsynced.isEmpty()) {
                btnSyncNow.isEnabled = true
                btnSyncNow.text = "Sync CSV via RNS"
                return@launch
            }

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val sb  = StringBuilder()
            sb.appendLine("id,harvester_id,block_id,ripe_bunches,empty_bunches,latitude,longitude,timestamp,photo_file")
            for (r in unsynced) {
                sb.appendLine("${r.id},${r.harvesterId},${r.blockId},${r.ripeBunches}," +
                    "${r.emptyBunches},${r.latitude},${r.longitude}," +
                    "${sdf.format(Date(r.timestamp))},${File(r.photoPath).name}")
            }
            val filename = "harvest_${SimpleDateFormat("yyyyMMdd_HHmmss",
                Locale.US).format(Date())}.csv"
            val result = withContext(Dispatchers.IO) {
                RNSBridge.sendCsv(baseAddr, sb.toString(), filename)
            }

            if (result == "OK") {
                for (r in unsynced) {
                    withContext(Dispatchers.IO) { dao.markSynced(r.id) }
                }
                refreshUnsyncedCount()
            } else {
            }

            btnSyncNow.isEnabled = true
            btnSyncNow.text = "Sync CSV via RNS"
        }
    }

    // -- Photo transfer via Bluetooth OBEX ------------------------------------

    private fun sendPendingPhotos() {
        lifecycleScope.launch {
            val dao     = HarvestDatabase.getInstance(this@MainActivity).harvestDao()
            val pending = dao.getUnsyncedPhotos()

            if (pending.isEmpty()) {
                toast("No pending photos")
                return@launch
            }

            for (record in pending) {
                val file = File(record.photoPath)
                if (!file.exists()) {
                    dao.markPhotoSynced(record.id)
                    continue
                }

                // Use Android's built-in Bluetooth file share (OBEX)
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "com.example.oilpalmharvester.fileprovider",
                    file)

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT,
                        "Harvest photo - Block ${record.blockId}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                // Launch the BT share chooser for this photo
                startActivity(Intent.createChooser(
                    shareIntent,
                    "Send photo for block ${record.blockId}"))

                // Mark as photo-synced — user is responsible for completing the transfer
                dao.markPhotoSynced(record.id)
            }
            refreshUnsyncedCount()
        }
    }

    

    // -- Tab navigation --------------------------------------------------------

    private fun showTab(tab: String) {
        val cyan     = ColorStateList.valueOf(Color.parseColor("#00d4ff"))
        val dark     = ColorStateList.valueOf(Color.parseColor("#0f3460"))
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
                refreshTodaySummary()
            }
            "records" -> {
                panelRecords.visibility = View.VISIBLE
                btnNavRecords.backgroundTintList = cyan
                btnNavRecords.setTextColor(darkText)
                loadRecords()
            }
            "calendar" -> {
                panelCalendar.visibility = View.VISIBLE
                btnNavCalendar.backgroundTintList = cyan
                btnNavCalendar.setTextColor(darkText)
                loadCalendar()
            }
            "sync" -> {
                panelSync.visibility = View.VISIBLE
                btnNavSync.backgroundTintList = cyan
                btnNavSync.setTextColor(darkText)
                refreshUnsyncedCount()
            }
        }
    }

    // -- Records list ---------------------------------------------------------

        private fun loadRecords() {
        lifecycleScope.launch {
            val records = HarvestDatabase.getInstance(this@MainActivity)
                .harvestDao().getAll()
            recordsContainer.removeAllViews()
            if (records.isEmpty()) {
                recordsContainer.addView(TextView(this@MainActivity).apply {
                    text = "No records yet"
                    setTextColor(Color.parseColor("#aaaaaa"))
                    textSize = 15f
                    setPadding(16, 32, 16, 32)
                })
                return@launch
            }
            val sdf = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault())
            for (record in records) {
                val card = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(12, 12, 12, 12)
                    setBackgroundColor(Color.parseColor("#0f3460"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).also {
                        it.setMargins(0, 0, 0, 8) }
                }

                // Thumbnail — tap to enlarge
                val thumb = ImageView(this@MainActivity).apply {
                    val lp = LinearLayout.LayoutParams(120, 120)
                    lp.setMargins(0, 0, 12, 0)
                    layoutParams = lp
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    try { setImageBitmap(loadRotatedBitmap(record.photoPath)) }
                    catch (e: Exception) { setBackgroundColor(Color.DKGRAY) }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (record.photoPath.isEmpty()) return@setOnClickListener
                        try {
                            val bmp = loadRotatedBitmap(record.photoPath)
                            val iv = ImageView(this@MainActivity).apply {
                                setImageBitmap(bmp)
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.MATCH_PARENT)
                            }
                            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("Block: ${record.blockId}")
                                .setView(iv)
                                .setPositiveButton("Close", null)
                                .show()
                        } catch (e: Exception) { toast("Cannot load photo") }
                    }
                }
                card.addView(thumb)

                // Info column
                val info = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                info.addView(TextView(this@MainActivity).apply {
                    text = "Block: ${record.blockId}"
                    setTextColor(Color.parseColor("#00d4ff"))
                    textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                info.addView(TextView(this@MainActivity).apply {
                    text = "Ripe: ${record.ripeBunches}   Empty: ${record.emptyBunches}"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                })
                info.addView(TextView(this@MainActivity).apply {
                    text = "GPS: ${"%.4f".format(record.latitude)}, ${"%.4f".format(record.longitude)}"
                    setTextColor(Color.parseColor("#aaaaaa"))
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                })
                info.addView(TextView(this@MainActivity).apply {
                    val csvStatus   = if (record.synced) "CSV sent" else "CSV pending"
                    val photoStatus = if (record.photoSynced) "Photo sent" else "Photo pending"
                    text = "${sdf.format(Date(record.timestamp))}  |  $csvStatus  |  $photoStatus"
                    setTextColor(Color.GRAY)
                    textSize = 10f
                })
                card.addView(info)

                // Bin icon button
                val btnDel = ImageButton(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    setBackgroundColor(Color.TRANSPARENT)
                    imageTintList = ColorStateList.valueOf(Color.parseColor("#cc3333"))
                    layoutParams = LinearLayout.LayoutParams(96, 96).also {
                        it.gravity = android.view.Gravity.CENTER_VERTICAL }
                    setOnClickListener {
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Delete Record")
                            .setMessage("Delete block ${record.blockId} entry?")
                            .setPositiveButton("Delete") { _, _ ->
                                lifecycleScope.launch {
                                    HarvestDatabase.getInstance(this@MainActivity)
                                        .harvestDao().deleteById(record.id)
                                    try { java.io.File(record.photoPath).delete() } catch (_: Exception) {}
                                    loadRecords()
                                    refreshTodaySummary()
                                }
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                }
                card.addView(btnDel)
                recordsContainer.addView(card)
            }
        }
    }

    // -- Calendar -------------------------------------------------------------

        private fun loadCalendar() {
        lifecycleScope.launch {
            val cal = Calendar.getInstance()
            cal.set(calendarYear, calendarMonth, 1, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startOfMonth = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            val endOfMonth = cal.timeInMillis

            val records = HarvestDatabase.getInstance(this@MainActivity)
                .harvestDao().getForMonth(startOfMonth, endOfMonth)

            // Map day -> {ripe, empty}
            data class DayData(var ripe: Int = 0, var empty: Int = 0)
            val dailyData = mutableMapOf<Int, DayData>()
            for (r in records) {
                val dc = Calendar.getInstance()
                dc.timeInMillis = r.timestamp
                val day = dc.get(Calendar.DAY_OF_MONTH)
                val d = dailyData.getOrPut(day) { DayData() }
                d.ripe  += r.ripeBunches
                d.empty += r.emptyBunches
            }

            tvCalendarMonth.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                .format(Date(startOfMonth))
            calendarGrid.removeAllViews()
            calendarGrid.columnCount = 7

            for (name in listOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat")) {
                calendarGrid.addView(TextView(this@MainActivity).apply {
                    text = name
                    setTextColor(Color.parseColor("#00d4ff"))
                    textSize = 11f
                    gravity = android.view.Gravity.CENTER
                    setPadding(4, 6, 4, 6)
                    layoutParams = android.widget.GridLayout.LayoutParams().apply {
                        width  = 0
                        height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                        columnSpec = android.widget.GridLayout.spec(
                            android.widget.GridLayout.UNDEFINED, 1f)
                    }
                })
            }

            val startDow = Calendar.getInstance().also {
                it.timeInMillis = startOfMonth }.get(Calendar.DAY_OF_WEEK) - 1
            val daysInMonth = Calendar.getInstance().also {
                it.timeInMillis = startOfMonth
                it.add(Calendar.MONTH, 1)
                it.add(Calendar.DAY_OF_MONTH, -1)
            }.get(Calendar.DAY_OF_MONTH)
            val todayCal = Calendar.getInstance()
            val isCurrentMonth = todayCal.get(Calendar.YEAR)  == calendarYear &&
                                 todayCal.get(Calendar.MONTH) == calendarMonth

            for (i in 0 until startDow) {
                calendarGrid.addView(android.view.View(this@MainActivity).apply {
                    layoutParams = android.widget.GridLayout.LayoutParams().apply {
                        width  = 0
                        height = 80
                        columnSpec = android.widget.GridLayout.spec(
                            android.widget.GridLayout.UNDEFINED, 1f)
                    }
                })
            }

            for (day in 1..daysInMonth) {
                val isToday = isCurrentMonth && day == todayCal.get(Calendar.DAY_OF_MONTH)
                val data    = dailyData[day]
                val hasData = data != null

                val cell = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(2, 4, 2, 4)
                    setBackgroundColor(when {
                        isToday  -> Color.parseColor("#1a3a5c")
                        hasData  -> Color.parseColor("#0a2a0a")
                        else     -> Color.TRANSPARENT
                    })
                    layoutParams = android.widget.GridLayout.LayoutParams().apply {
                        width  = 0
                        height = 80
                        columnSpec = android.widget.GridLayout.spec(
                            android.widget.GridLayout.UNDEFINED, 1f)
                    }
                    isClickable = true
                    isFocusable = true
                }

                cell.addView(TextView(this@MainActivity).apply {
                    text = "$day"
                    setTextColor(if (isToday) Color.parseColor("#00d4ff") else Color.WHITE)
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                })
                if (hasData) {
                    cell.addView(TextView(this@MainActivity).apply {
                        text = "${(data!!.ripe + data.empty)}"
                        setTextColor(Color.parseColor("#00ff88"))
                        textSize = 9f
                        gravity = android.view.Gravity.CENTER
                    })
                }

                // Tap cell to show day summary popup
                cell.setOnClickListener {
                    val d = dailyData[day]
                    val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(
                        Calendar.getInstance().also {
                            it.set(calendarYear, calendarMonth, day) }.time)
                    val msg = if (d != null)
                        "Ripe bunches:   ${d.ripe}\nEmpty bunches: ${d.empty}\nTotal:               ${d.ripe + d.empty}"
                    else
                        "No harvest recorded."
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(dateStr)
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }

                calendarGrid.addView(cell)
            }

            // ── Analytics section ─────────────────────────────────────────────
            buildAnalytics(records)
        }
    }

    private fun buildAnalytics(records: List<HarvestRecord>) {
        val container = findViewById<LinearLayout>(R.id.analyticsContainer)
        container.removeAllViews()

        fun header(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#00d4ff"))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 20, 0, 6)
        }
        fun row(label: String, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
            addView(TextView(this@MainActivity).apply {
                text = label
                setTextColor(Color.parseColor("#aaaaaa"))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                this.text = value
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = android.view.Gravity.END
            })
        }

        // Today stats
        val todayCal = Calendar.getInstance()
        todayCal.set(Calendar.HOUR_OF_DAY, 0); todayCal.set(Calendar.MINUTE, 0)
        todayCal.set(Calendar.SECOND, 0);      todayCal.set(Calendar.MILLISECOND, 0)
        val startOfToday = todayCal.timeInMillis
        val endOfToday   = startOfToday + 86_400_000L

        val todayRecords = records.filter { it.timestamp in startOfToday until endOfToday }
        val todayRipe    = todayRecords.sumOf { it.ripeBunches }
        val todayEmpty   = todayRecords.sumOf { it.emptyBunches }

        container.addView(header("Today"))
        container.addView(row("Ripe bunches",  "$todayRipe"))
        container.addView(row("Empty bunches", "$todayEmpty"))
        container.addView(row("Total bunches", "${todayRipe + todayEmpty}"))
        container.addView(row("Entries",       "${todayRecords.size}"))

        // Month to date
        val monthRipe  = records.sumOf { it.ripeBunches }
        val monthEmpty = records.sumOf { it.emptyBunches }
        container.addView(header("This Month"))
        container.addView(row("Ripe bunches",  "$monthRipe"))
        container.addView(row("Empty bunches", "$monthEmpty"))
        container.addView(row("Total bunches", "${monthRipe + monthEmpty}"))
        container.addView(row("Entries",       "${records.size}"))

        // By Block ID - month
        val byBlock = records.groupBy { it.blockId }
        if (byBlock.isNotEmpty()) {
            container.addView(header("By Block (This Month)"))
            byBlock.entries.sortedByDescending {
                it.value.sumOf { r -> r.ripeBunches + r.emptyBunches } }
            .forEach { (blockId, recs) ->
                val ripe  = recs.sumOf { it.ripeBunches }
                val empty = recs.sumOf { it.emptyBunches }
                container.addView(row("Block $blockId", "R:$ripe  E:$empty  T:${ripe+empty}"))
            }
        }

        // Divider
        container.addView(android.view.View(this).apply {
            setBackgroundColor(Color.parseColor("#0f3460"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                it.setMargins(0, 16, 0, 0) }
        })
    }

    // -- Helpers ---------------------------------------------------------------

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
                    toast("Harvester ID saved!")
                }
            }.show()
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
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) setupBluetooth()
        else toast("Bluetooth permissions denied")
    }

    private fun setupBluetooth() {
        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val ba = bm.adapter ?: run { toast("No Bluetooth!"); return }
        val paired = ba.bondedDevices?.toList() ?: emptyList()
        spinnerDevices.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            paired.map { "${it.name} (${it.address})" }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        btnConnect.setOnClickListener {
            val idx = spinnerDevices.selectedItemPosition
            if (idx < 0 || idx >= paired.size) return@setOnClickListener
            val device = paired[idx]
            btnConnect.isEnabled = false
            toast("Connecting...")
            scope.launch {
                val connected = withContext(Dispatchers.IO) { btService.connect(device.address) }
                if (!connected) {
                    toast("BT connection failed")
                    btnConnect.isEnabled = true
                    return@launch
                }
                val addr = withContext(Dispatchers.IO) { RNSBridge.start(btService) }
                if (addr.startsWith("Error")) {
                    toast("RNS error: $addr")
                    btnConnect.isEnabled = true
                } else {
                    tvMyAddress.text = "My address: $addr"
                    rnsConnected = true
                    btnSyncNow.isEnabled = true
                    toast("RNS Ready!")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        btService.disconnect()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}












