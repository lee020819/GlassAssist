package com.example.glassassist

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class InspectionScheduleActivity : AppCompatActivity() {

    private lateinit var db: DatabaseHelper
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var userId: String
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val displayFormat = SimpleDateFormat("M월 d일", Locale.KOREAN)
    private val displayDateTimeFormat = SimpleDateFormat("M월 d일 HH:mm", Locale.US)

    private val demoData = listOf(
        Triple("소화기 점검", 30, -35),
        Triple("비상구 점검", 7, -5),
        Triple("엘리베이터 점검", 90, -60),
        Triple("스크린도어 점검", 30, -2),
        Triple("CCTV 점검", 14, -10),
        Triple("소방시설 점검", 90, -100)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection_schedule)
        db = DatabaseHelper(this)
        userId = UserPreferences(this).userId ?: "미설정"
        rv = findViewById(R.id.rv_schedule)
        tvEmpty = findViewById(R.id.tv_empty)
        rv.layoutManager = LinearLayoutManager(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_add).setOnClickListener { showAddDialog() }

        executor.execute {
            if (db.isScheduleEmpty()) insertDemoData()
            loadSchedules()
        }
    }

    private fun insertDemoData() {
        val cal = Calendar.getInstance()
        demoData.forEach { (facility, interval, daysOffset) ->
            cal.time = Date()
            cal.add(Calendar.DAY_OF_YEAR, daysOffset)
            db.insertSchedule(facility, interval, dateFormat.format(cal.time))
        }
    }

    private fun loadSchedules() {
        val schedules = db.getSchedules()
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.time
        runOnUiThread {
            tvEmpty.visibility = if (schedules.isEmpty()) View.VISIBLE else View.GONE
            rv.adapter = ScheduleAdapter(schedules, today)
        }
    }

    private fun showAddDialog() {
        val view = layoutInflater.inflate(android.R.layout.simple_list_item_2, null)
        val facilityInput = EditText(this).apply { hint = "시설물명 (예: 소화기)" }
        val intervalInput = EditText(this).apply {
            hint = "점검 주기 (일, 예: 30)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(facilityInput)
            addView(intervalInput)
        }
        AlertDialog.Builder(this)
            .setTitle("점검 항목 추가")
            .setView(container)
            .setPositiveButton("추가") { _, _ ->
                val facility = facilityInput.text.toString().trim()
                val interval = intervalInput.text.toString().toIntOrNull()
                if (facility.isNotEmpty() && interval != null && interval > 0) {
                    executor.execute {
                        db.insertSchedule(facility, interval)
                        loadSchedules()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    inner class ScheduleAdapter(
        private var items: List<DatabaseHelper.ScheduleData>,
        private val today: Date
    ) : RecyclerView.Adapter<ScheduleAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvFacility: TextView = view.findViewById(R.id.tv_facility)
            val tvInterval: TextView = view.findViewById(R.id.tv_interval)
            val tvNextDue: TextView = view.findViewById(R.id.tv_next_due)
            val tvLastChecked: TextView = view.findViewById(R.id.tv_last_checked)
            val tvLocation: TextView = view.findViewById(R.id.tv_location)
            val viewStatus: View = view.findViewById(R.id.view_status)
            val btnDone: TextView = view.findViewById(R.id.btn_done)
            val btnDelete: TextView = view.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_schedule, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvFacility.text = item.facility
            holder.tvInterval.text = "주기: ${item.intervalDays}일"

            // lastChecked는 "yyyy-MM-dd HH:mm" 또는 "yyyy-MM-dd" 형식 모두 허용
            val lastCheckedDate = item.lastChecked?.take(10)
            val nextDue = lastCheckedDate?.let {
                try {
                    val last = dateFormat.parse(it)!!
                    val cal = Calendar.getInstance().apply { time = last }
                    cal.add(Calendar.DAY_OF_YEAR, item.intervalDays)
                    cal.time
                } catch (e: Exception) { null }
            }

            val daysLeft = nextDue?.let { ((it.time - today.time) / 86400000).toInt() }

            val statusColor = when {
                nextDue == null -> 0xFFF44336.toInt()
                daysLeft!! < 0 -> 0xFFF44336.toInt()
                daysLeft <= 3 -> 0xFFFFEB3B.toInt()
                else -> 0xFF4CAF50.toInt()
            }
            holder.viewStatus.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(statusColor)
            }

            when {
                nextDue == null -> holder.tvNextDue.text = "미점검"
                daysLeft!! < 0 -> holder.tvNextDue.text = "지연: ${-daysLeft}일 초과 (${displayFormat.format(nextDue)})"
                daysLeft <= 3 -> holder.tvNextDue.text = "${daysLeft}일 후 점검 (${displayFormat.format(nextDue)})"
                else -> holder.tvNextDue.text = "${daysLeft}일 후 점검 (${displayFormat.format(nextDue)})"
            }

            // 최근 점검일시 표시
            if (item.lastChecked != null) {
                val displayChecked = try {
                    if (item.lastChecked.length > 10)
                        displayDateTimeFormat.format(dateTimeFormat.parse(item.lastChecked)!!)
                    else
                        displayFormat.format(dateFormat.parse(item.lastChecked)!!) + " 점검"
                } catch (e: Exception) { item.lastChecked }
                holder.tvLastChecked.text = "최근 점검: $displayChecked"
                holder.tvLastChecked.visibility = View.VISIBLE
            } else {
                holder.tvLastChecked.visibility = View.GONE
            }

            // 위치/층 표시 (note 필드)
            if (!item.note.isNullOrBlank()) {
                holder.tvLocation.text = "위치: ${item.note}"
                holder.tvLocation.visibility = View.VISIBLE
            } else {
                holder.tvLocation.visibility = View.GONE
            }

            holder.btnDone.setOnClickListener {
                val locationInput = EditText(this@InspectionScheduleActivity).apply {
                    hint = "위치/층 입력 (예: 3층, 2번 승강장)"
                    setPadding(48, 24, 48, 24)
                    setText(item.note ?: "")
                }
                AlertDialog.Builder(this@InspectionScheduleActivity)
                    .setTitle("점검 완료 - ${item.facility}")
                    .setMessage("위치/층을 입력하면 계량기 기록과 연동됩니다.")
                    .setView(locationInput)
                    .setPositiveButton("완료 처리") { _, _ ->
                        val location = locationInput.text.toString().trim().ifEmpty { null }
                        val now = dateTimeFormat.format(Date())
                        executor.execute {
                            db.updateScheduleCheckedWithNote(item.id, now, location)
                            // 계량기 기록 연동 탐색
                            val linked = db.getMeterRecordsByFacility(userId, item.facility)
                            runOnUiThread {
                                if (linked.isNotEmpty()) {
                                    val latest = linked.first()
                                    android.widget.Toast.makeText(
                                        this@InspectionScheduleActivity,
                                        "계량기 기록 연동: ${latest.date} ${latest.time} - ${latest.location}",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            loadSchedules()
                        }
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            holder.btnDelete.setOnClickListener {
                AlertDialog.Builder(this@InspectionScheduleActivity)
                    .setMessage("'${item.facility}' 항목을 삭제하시겠습니까?")
                    .setPositiveButton("삭제") { _, _ ->
                        executor.execute {
                            db.deleteSchedule(item.id)
                            loadSchedules()
                        }
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
    }
}
