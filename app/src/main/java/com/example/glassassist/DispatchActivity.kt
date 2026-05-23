package com.example.glassassist

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DispatchActivity : AppCompatActivity() {

    private lateinit var logAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dispatch)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        logAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        findViewById<ListView>(R.id.list_dispatch_log).adapter = logAdapter

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
        MainActivity.dispatchLog.forEach { logAdapter.add(it) }
        logAdapter.notifyDataSetChanged()
    }

    private fun updateStatus() {
        val dot = findViewById<TextView>(R.id.tv_status_dot)
        val status = findViewById<TextView>(R.id.tv_status)
        val wsUrl = MainActivity.lastDispatchUrl
        if (wsUrl != null) {
            dot.text = "● 연결됨"
            dot.setTextColor(0xFF81C784.toInt())
            status.text = "연결: $wsUrl"
        } else {
            dot.text = "● 미연결"
            dot.setTextColor(0xFFEF9A9A.toInt())
            status.text = "관제실 미연결\n메인 화면에서 QR 버튼으로 연결하세요"
        }
    }
}
