package com.example.glassassist

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity

class AiActivity : AppCompatActivity() {

    private lateinit var logAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        logAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        findViewById<ListView>(R.id.list_ai_log).adapter = logAdapter

        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    private fun refreshLog() {
        logAdapter.clear()
        MainActivity.aiLog.forEach { logAdapter.add(it) }
        logAdapter.notifyDataSetChanged()
    }
}
