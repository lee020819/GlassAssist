package com.example.glassassist

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class RecordListActivity : AppCompatActivity() {

    private lateinit var type: String
    private lateinit var userId: String
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private var currentRows = listOf<RecordRow>()

    private lateinit var handoverLauncher: ActivityResultLauncher<Intent>
    private lateinit var phoneCameraLauncher: ActivityResultLauncher<Uri>
    private var phoneCameraUri: Uri? = null
    private var pendingMeterLocation = ""

    private val dateFormat = SimpleDateFormat("yyyy.M.d", Locale.KOREAN)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.KOREAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handoverLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val action = data.getStringExtra("action")
                val position = data.getIntExtra("position", -1)
                if (position >= 0) {
                    val row = currentRows.getOrNull(position) ?: return@registerForActivityResult
                    val db = DatabaseHelper(this)
                    when (action) {
                        "edit" -> {
                            val newContent = data.getStringExtra("newContent") ?: return@registerForActivityResult
                            Executors.newSingleThreadExecutor().execute {
                                db.updateHandover(userId, row.extra1 ?: "", newContent)
                                loadAndShow()
                            }
                        }
                        "delete" -> {
                            Executors.newSingleThreadExecutor().execute {
                                db.deleteHandover(userId, row.extra1 ?: "")
                                loadAndShow()
                            }
                        }
                    }
                }
            }
        }

        phoneCameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                val imageUri = phoneCameraUri?.toString() ?: return@registerForActivityResult
                val now = Date()
                val date = dateFormat.format(now)
                val time = timeFormat.format(now)
                Executors.newSingleThreadExecutor().execute {
                    DatabaseHelper(this).insertMeter(userId, date, time, pendingMeterLocation, imageUri)
                    loadAndShow()
                }
            }
        }

        setContentView(R.layout.activity_record_list)

        type = intent.getStringExtra("type") ?: return
        val label = intent.getStringExtra("label") ?: ""
        userId = intent.getStringExtra("userId") ?: ""

        findViewById<TextView>(R.id.tv_title).text = label
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        rv = findViewById(R.id.rv_records)
        tvEmpty = findViewById(R.id.tv_empty)
        rv.layoutManager = LinearLayoutManager(this)

        val btnAdd = findViewById<TextView>(R.id.btn_add)
        if (type == "meter" || type == "handover") {
            btnAdd.visibility = View.VISIBLE
            btnAdd.setOnClickListener {
                if (type == "meter") showMeterAddDialog()
                else showHandoverAddDialog()
            }
        }

        loadAndShow()
    }

    override fun onResume() {
        super.onResume()
        loadAndShow()
    }

    private fun showMeterAddDialog() {
        val facilityInput = EditText(this).apply {
            hint = "시설물 입력 (예: 소화기, 에스컬레이터)"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("점검 시설물")
            .setView(facilityInput)
            .setPositiveButton("다음") { _, _ ->
                val facility = facilityInput.text.toString().trim().ifEmpty { "시설물 미입력" }
                val types = arrayOf("일상점검", "정기점검", "수시점검", "특별점검", "긴급점검", "직접 입력")
                var selectedIndex = 0
                AlertDialog.Builder(this)
                    .setTitle("점검 유형 선택")
                    .setSingleChoiceItems(types, 0) { _, which -> selectedIndex = which }
                    .setPositiveButton("다음") { _, _ ->
                        val selectedType = if (selectedIndex == types.lastIndex) null else types[selectedIndex]
                        fun proceedWithType(inspType: String) {
                            val locationInput = EditText(this).apply {
                                hint = "위치 입력 (예: 3번 승강장)"
                                setPadding(48, 24, 48, 24)
                            }
                            AlertDialog.Builder(this)
                                .setTitle("위치 입력")
                                .setView(locationInput)
                                .setPositiveButton("촬영하기") { _, _ ->
                                    val loc = locationInput.text.toString().trim().ifEmpty { "위치 미입력" }
                                    pendingMeterLocation = "$inspType / $facility / $loc"
                                    launchPhoneCamera()
                                }
                                .setNegativeButton("취소", null)
                                .show()
                        }
                        if (selectedType == null) {
                            val typeInput = EditText(this).apply {
                                hint = "점검 유형 입력"
                                setPadding(48, 24, 48, 24)
                            }
                            AlertDialog.Builder(this)
                                .setTitle("점검 유형 직접 입력")
                                .setView(typeInput)
                                .setPositiveButton("다음") { _, _ ->
                                    proceedWithType(typeInput.text.toString().trim().ifEmpty { "기타점검" })
                                }
                                .setNegativeButton("취소", null)
                                .show()
                        } else {
                            proceedWithType(selectedType)
                        }
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun launchPhoneCamera() {
        try {
            val photoFile = File(filesDir, "meter_photo_${System.currentTimeMillis()}.jpg")
            phoneCameraUri = FileProvider.getUriForFile(this, "${packageName}.provider", photoFile)
            phoneCameraLauncher.launch(phoneCameraUri!!)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "카메라 실행 실패: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun showHandoverAddDialog() {
        val input = EditText(this).apply {
            hint = "인수인계 내용 입력"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("인수인계 추가")
            .setView(input)
            .setPositiveButton("추가") { _, _ ->
                val memo = input.text.toString().trim()
                if (memo.isNotEmpty()) {
                    val now = Date()
                    Executors.newSingleThreadExecutor().execute {
                        DatabaseHelper(this).insertHandover(userId, dateFormat.format(now), timeFormat.format(now), memo)
                        loadAndShow()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun loadAndShow() {
        Executors.newSingleThreadExecutor().execute {
            val rows = loadRecords(type, userId)
            runOnUiThread {
                currentRows = rows
                if (rows.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    rv.visibility = View.GONE
                } else {
                    tvEmpty.visibility = View.GONE
                    rv.visibility = View.VISIBLE
                    rv.adapter = RecordAdapter(rows) { row, position ->
                        navigateToDetail(row, position)
                    }
                }
            }
        }
    }

    private fun navigateToDetail(row: RecordRow, position: Int) {
        val intent = when (type) {
            "protection" -> Intent(this, ProtectionDetailActivity::class.java).apply {
                putExtra("date", row.date)
                putExtra("time", row.time)
                putExtra("keyword", row.main)
                putExtra("videoUri", row.extra1)
            }
            "qa" -> Intent(this, QaDetailActivity::class.java).apply {
                putExtra("date", row.date)
                putExtra("time", row.time)
                putExtra("question", row.extra1 ?: "")
                putExtra("answer", row.extra2 ?: "")
            }
            "video" -> Intent(this, VideoDetailActivity::class.java).apply {
                putExtra("date", row.date)
                putExtra("time", row.time)
                putExtra("videoUri", row.extra1)
            }
            "meter" -> {
                val loc = row.extra1 ?: ""
                Intent(this, MeterDetailActivity::class.java).apply {
                    putExtra("date", row.date)
                    putExtra("time", row.time)
                    putExtra("location", loc.substringBefore(" / 계량기 번호:").trim())
                    putExtra("meterNumber", loc.substringAfter("계량기 번호: ", "-"))
                    putExtra("imagePath", row.extra2)
                }
            }
            "handover" -> Intent(this, HandoverDetailActivity::class.java).apply {
                putExtra("date", row.date)
                putExtra("time", row.time)
                putExtra("content", row.extra1 ?: "")
                putExtra("position", position)
            }
            else -> null
        } ?: return

        if (type == "handover") handoverLauncher.launch(intent)
        else startActivity(intent)
    }

    private fun loadRecords(type: String, userId: String): List<RecordRow> {
        val db = DatabaseHelper(this)
        return when (type) {
            "qa" -> db.getQaRecords(userId).map {
                RecordRow(it.date, it.time, it.question, it.answer, extra1 = it.question, extra2 = it.answer)
            }
            "protection" -> db.getProtectionRecords(userId).map {
                RecordRow(it.date, it.time, it.keyword, "", extra1 = it.videoUri)
            }
            "video" -> db.getVideoRecords(userId).map {
                RecordRow(it.date, it.time, "영상 기록", it.videoUri ?: "-", extra1 = it.videoUri)
            }
            "meter" -> db.getMeterRecords(userId).map {
                RecordRow(it.date, it.time, it.location, "", extra1 = it.location, extra2 = it.videoUri)
            }
            "handover" -> db.getHandoverRecords(userId).map {
                RecordRow(it.date, it.time, it.content, "", extra1 = it.content)
            }
            else -> emptyList()
        }
    }

    data class RecordRow(
        val date: String,
        val time: String,
        val main: String,
        val sub: String,
        val extra1: String? = null,
        val extra2: String? = null
    )

    class RecordAdapter(
        private val items: List<RecordRow>,
        private val onItemClick: (RecordRow, Int) -> Unit
    ) : RecyclerView.Adapter<RecordAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val date: TextView = view.findViewById(R.id.tv_record_date)
            val time: TextView = view.findViewById(R.id.tv_record_time)
            val main: TextView = view.findViewById(R.id.tv_record_main)
            val sub: TextView = view.findViewById(R.id.tv_record_sub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.date.text = item.date
            holder.time.text = item.time
            holder.main.text = item.main
            if (item.sub.isNotEmpty()) {
                holder.sub.visibility = View.VISIBLE
                holder.sub.text = item.sub
            } else {
                holder.sub.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onItemClick(item, holder.adapterPosition) }
        }
    }
}
