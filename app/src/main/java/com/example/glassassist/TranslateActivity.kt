package com.example.glassassist

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TranslateActivity : AppCompatActivity() {

    private lateinit var logAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translate)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        logAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        findViewById<ListView>(R.id.list_translation_log).adapter = logAdapter

        refreshLog()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
        updateStatus()
    }

    private fun refreshLog() {
        logAdapter.clear()
        MainActivity.translationLog.forEach { logAdapter.add(it) }
        logAdapter.notifyDataSetChanged()
    }

    private fun updateStatus() {
        val dot = findViewById<TextView>(R.id.tv_mode_dot)
        val status = findViewById<TextView>(R.id.tv_mode_status)
        if (MainActivity.translationModeActive) {
            dot.text = "● 번역 중"
            dot.setTextColor(0xFF81C784.toInt())
            status.text = "번역 모드 켜짐 (영어 → 한국어)"
            status.setTextColor(0xFF388E3C.toInt())
        } else {
            dot.text = "● 대기"
            dot.setTextColor(0xFFEF9A9A.toInt())
            status.text = "번역 모드 꺼짐"
            status.setTextColor(0xFF37474F.toInt())
        }
    }
}
