package com.example.glassassist

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors

class NewsActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var newsAdapter: NewsAdapter

    companion object {
        private const val NAVER_CLIENT_ID = "YkmvsxAbXEj1AOeuGW0n"
        private const val NAVER_CLIENT_SECRET = "H4fKvtP0Fj"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news)

        val source = intent.getStringExtra("source") ?: "naver"
        findViewById<TextView>(R.id.tv_news_title).text =
            if (source == "naver") "📰 네이버 뉴스 - 철도" else "🚆 KORAIL 뉴스"
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        val rv = findViewById<RecyclerView>(R.id.rv_all_news)
        newsAdapter = NewsAdapter(mutableListOf()) { item ->
            if (item.link.isNotEmpty()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link)))
            }
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = newsAdapter

        if (source == "naver") fetchNaverNews() else fetchKorailNews()
    }

    private fun fetchNaverNews() {
        executor.execute {
            try {
                val request = Request.Builder()
                    .url("https://openapi.naver.com/v1/search/news.json?query=철도&display=30&sort=date")
                    .addHeader("X-Naver-Client-Id", NAVER_CLIENT_ID)
                    .addHeader("X-Naver-Client-Secret", NAVER_CLIENT_SECRET)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@execute
                    val body = response.body?.string() ?: return@execute
                    val items = JSONObject(body).getJSONArray("items")
                    val news = (0 until items.length()).map {
                        val obj = items.getJSONObject(it)
                        NewsItem(
                            title = obj.getString("title"),
                            date = obj.getString("pubDate").take(16),
                            link = obj.optString("originallink").ifEmpty { obj.optString("link") }
                        )
                    }
                    handler.post { newsAdapter.update(news) }
                }
            } catch (e: Exception) {
                Log.e("NewsActivity", "네이버 뉴스 실패", e)
            }
        }
    }

    private fun fetchKorailNews() {
        executor.execute {
            try {
                val request = Request.Builder()
                    .url("https://openapi.naver.com/v1/search/news.json?query=코레일&display=30&sort=date")
                    .addHeader("X-Naver-Client-Id", NAVER_CLIENT_ID)
                    .addHeader("X-Naver-Client-Secret", NAVER_CLIENT_SECRET)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@execute
                    val body = response.body?.string() ?: return@execute
                    val items = JSONObject(body).getJSONArray("items")
                    val news = (0 until items.length()).map {
                        val obj = items.getJSONObject(it)
                        NewsItem(
                            title = obj.getString("title"),
                            date = obj.getString("pubDate").take(16),
                            link = obj.optString("originallink").ifEmpty { obj.optString("link") }
                        )
                    }
                    handler.post { newsAdapter.update(news) }
                }
            } catch (e: Exception) {
                Log.e("NewsActivity", "KORAIL 뉴스 실패", e)
            }
        }
    }
}
