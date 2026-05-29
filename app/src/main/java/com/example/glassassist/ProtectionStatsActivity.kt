package com.example.glassassist

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProtectionStatsActivity : AppCompatActivity() {

    private lateinit var userPrefs: UserPreferences
    private val client = OkHttpClient()
    private val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_protection_stats)
        userPrefs = UserPreferences(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_refresh).setOnClickListener { loadStats() }

        loadStats()
    }

    private fun loadStats() {
        val wsUrl = userPrefs.dispatchWsUrl ?: run {
            showOfflineStats()
            return
        }
        val httpUrl = wsUrl.replace(Regex("^ws://"), "http://").substringBefore("/ws")
        Thread {
            try {
                val request = Request.Builder().url("$httpUrl/protection/stats").build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@Thread
                    val records = JSONObject(body).optJSONArray("records") ?: return@Thread
                    val list = (0 until records.length()).map { records.getJSONObject(it) }
                    runOnUiThread { renderStats(list) }
                } else {
                    runOnUiThread { showOfflineStats() }
                }
            } catch (e: Exception) {
                runOnUiThread { showOfflineStats() }
            }
        }.start()
    }

    private fun renderStats(records: List<JSONObject>) {
        val total = records.size
        val today = records.count { it.optString("date") == todayStr }

        findViewById<TextView>(R.id.tv_total).text = total.toString()
        findViewById<TextView>(R.id.tv_today).text = today.toString()

        // 키워드별 통계
        val keywordCount = mutableMapOf<String, Int>()
        records.forEach { r ->
            val kw = r.optString("keyword", "알 수 없음")
            keywordCount[kw] = (keywordCount[kw] ?: 0) + 1
        }
        val kwLayout = findViewById<LinearLayout>(R.id.ll_keyword_stats)
        kwLayout.removeAllViews()
        if (keywordCount.isEmpty()) {
            kwLayout.addView(makeLabel("기록 없음"))
        } else {
            keywordCount.entries.sortedByDescending { it.value }.forEach { (kw, cnt) ->
                kwLayout.addView(makeStatRow(kw, cnt, total))
            }
        }

        // 나이대별 통계
        val ageCount = mutableMapOf<String, Int>()
        records.forEach { r ->
            val age = r.optString("age_group", "").takeIf { it.isNotEmpty() } ?: return@forEach
            ageCount[age] = (ageCount[age] ?: 0) + 1
        }
        val ageLayout = findViewById<LinearLayout>(R.id.ll_age_stats)
        ageLayout.removeAllViews()
        val taggedTotal = ageCount.values.sum()
        if (ageCount.isEmpty()) {
            ageLayout.addView(makeLabel("태깅된 기록 없음 (관제실에서 태깅 가능)"))
        } else {
            ageCount.entries.sortedByDescending { it.value }.forEach { (age, cnt) ->
                ageLayout.addView(makeStatRow(age, cnt, taggedTotal))
            }
        }

        // 최근 10건
        val recentLayout = findViewById<LinearLayout>(R.id.ll_recent)
        recentLayout.removeAllViews()
        if (records.isEmpty()) {
            recentLayout.addView(makeLabel("기록 없음"))
        } else {
            records.takeLast(10).reversed().forEach { r ->
                val date = r.optString("date", "")
                val time = r.optString("time", "")
                val kw = r.optString("keyword", "")
                val age = r.optString("age_group", "").takeIf { it.isNotEmpty() } ?: "미태깅"
                val worker = r.optString("worker_id", "")
                recentLayout.addView(makeRecentRow("[$date $time] $kw / $age / $worker"))
            }
        }
    }

    private fun showOfflineStats() {
        findViewById<TextView>(R.id.tv_total).text = "-"
        findViewById<TextView>(R.id.tv_today).text = "-"
        val msg = "관제실 서버 연결 필요 (QR 스캔 후 사용 가능)"
        listOf(R.id.ll_keyword_stats, R.id.ll_age_stats, R.id.ll_recent).forEach { id ->
            val layout = findViewById<LinearLayout>(id)
            layout.removeAllViews()
            layout.addView(makeLabel(msg))
        }
    }

    private fun makeStatRow(label: String, count: Int, total: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
        }
        val pct = if (total > 0) (count * 100 / total) else 0
        val tvLabel = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        val tvCount = TextView(this).apply {
            text = "${count}건 (${pct}%)"
            textSize = 13f
            setTextColor(0xFF1565C0.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(tvLabel)
        row.addView(tvCount)
        return row
    }

    private fun makeLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF999999.toInt())
        setPadding(0, 4, 0, 4)
    }

    private fun makeRecentRow(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF444444.toInt())
        setPadding(0, 6, 0, 6)
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                it.topMargin = 4
            }
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
    }
}
