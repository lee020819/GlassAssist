package com.example.glassassist

import android.content.Context
import android.util.Log
import org.json.JSONObject

class KeywordDetector(private val context: Context) {

    companion object {
        private const val TAG = "KeywordDetector"
        private const val KEYWORDS_FILE = "danger_keywords.json"

        private val POSITIVE_CONTEXT_WORDS = listOf(
            "맛있", "재밌", "웃기", "좋아", "행복", "즐거", "신나",
            "너무 좋", "완전 좋", "대박", "쩐다", "노래", "영화", "게임", "음식", "맛집"
        )

        private val NORMALIZATION_MAP = mapOf(
            "ㅅㅂ" to "씨발",
            "ㅂㅅ" to "병신",
            "ㅈㄹ" to "지랄",
            "ㄲㅈ" to "꺼져",
            "ㅁㅊ" to "미쳐"
        )
    }

    data class DetectionResult(
        val detected: Boolean,
        val severity: String,
        val categoryName: String,
        val matchedKeyword: String,
        val alertLevel: String,
        val contextSafe: Boolean = false
    )

    private data class KeywordCategory(
        val id: String,
        val name: String,
        val severity: String,
        val keywords: List<String>
    )

    private var categories: List<KeywordCategory> = emptyList()
    private var singleTriggerSeverities: List<String> = listOf("critical", "high")
    private var alertLevels: Map<String, String> = emptyMap()

    init {
        loadKeywords()
    }

    private fun loadKeywords() {
        try {
            val json = context.assets.open(KEYWORDS_FILE)
                .bufferedReader()
                .use { it.readText() }

            val root = JSONObject(json)
            val categoriesArray = root.getJSONArray("categories")
            val loadedCategories = mutableListOf<KeywordCategory>()

            for (i in 0 until categoriesArray.length()) {
                val cat = categoriesArray.getJSONObject(i)
                val keywordsArray = cat.getJSONArray("keywords")
                val keywordList = mutableListOf<String>()
                for (j in 0 until keywordsArray.length()) {
                    keywordList.add(keywordsArray.getString(j))
                }
                loadedCategories.add(
                    KeywordCategory(
                        id = cat.getString("id"),
                        name = cat.getString("name"),
                        severity = cat.getString("severity"),
                        keywords = keywordList
                    )
                )
            }

            categories = loadedCategories

            val rules = root.optJSONObject("detection_rules")
            if (rules != null) {
                val triggerArray = rules.optJSONArray("single_keyword_trigger")
                if (triggerArray != null) {
                    val triggers = mutableListOf<String>()
                    for (i in 0 until triggerArray.length()) {
                        triggers.add(triggerArray.getString(i))
                    }
                    singleTriggerSeverities = triggers
                }

                val alertObj = rules.optJSONObject("alert_levels")
                if (alertObj != null) {
                    val alerts = mutableMapOf<String, String>()
                    alertObj.keys().forEach { key -> alerts[key] = alertObj.getString(key) }
                    alertLevels = alerts
                }
            }

            Log.d(TAG, "키워드 로드 완료: ${categories.size}개 카테고리")

        } catch (e: Exception) {
            Log.e(TAG, "키워드 파일 로드 실패: ${e.message}")
        }
    }

    private fun normalizeText(text: String): String {
        var normalized = text.replace(" ", "").lowercase()
        NORMALIZATION_MAP.forEach { (from, to) ->
            normalized = normalized.replace(from, to)
        }
        return normalized
    }

    private fun isPositiveContext(text: String): Boolean {
        return POSITIVE_CONTEXT_WORDS.any { text.contains(it) }
    }

    private fun isCriticalCategory(categoryId: String): Boolean {
        return categoryId in listOf("threat", "physical_threat", "extreme_danger", "suicide_threat")
    }

    fun detect(text: String): DetectionResult {
        val normalizedText = normalizeText(text)
        val isPositive = isPositiveContext(text)

        for (category in categories) {
            for (keyword in category.keywords) {
                val normalizedKeyword = normalizeText(keyword)
                if (normalizedText.contains(normalizedKeyword)) {

                    // critical 카테고리는 문맥 무시하고 항상 감지
                    if (isCriticalCategory(category.id)) {
                        val alertLevel = alertLevels[category.severity] ?: "local_alert"
                        Log.d(TAG, "긴급 키워드 감지! 카테고리: ${category.name}, 키워드: $keyword")
                        return DetectionResult(
                            detected = true,
                            severity = category.severity,
                            categoryName = category.name,
                            matchedKeyword = keyword,
                            alertLevel = alertLevel,
                            contextSafe = false
                        )
                    }

                    // 긍정적 문맥이면 위협 아님으로 처리
                    if (isPositive) {
                        Log.d(TAG, "긍정적 문맥 감지 - 위협 아님: $keyword")
                        return DetectionResult(
                            detected = false,
                            severity = "none",
                            categoryName = "",
                            matchedKeyword = keyword,
                            alertLevel = "",
                            contextSafe = true
                        )
                    }

                    val alertLevel = alertLevels[category.severity] ?: "local_alert"
                    Log.d(TAG, "위험 키워드 감지! 카테고리: ${category.name}, 키워드: $keyword")
                    return DetectionResult(
                        detected = true,
                        severity = category.severity,
                        categoryName = category.name,
                        matchedKeyword = keyword,
                        alertLevel = alertLevel,
                        contextSafe = false
                    )
                }
            }
        }

        return DetectionResult(
            detected = false,
            severity = "none",
            categoryName = "",
            matchedKeyword = "",
            alertLevel = ""
        )
    }

    fun getAlertMessage(result: DetectionResult): String {
        return when (result.severity) {
            "critical" -> "⚠️ 긴급 상황 감지! ${result.categoryName} 발언이 감지되었습니다. 즉시 철도경찰에 연락하고 영상 녹화를 시작하세요."
            "high" -> "⚠️ 위험 발언 감지! ${result.categoryName}이 감지되었습니다. 상급자에게 보고하고 영상 녹화를 시작하세요."
            "medium" -> "주의! ${result.categoryName}이 감지되었습니다. 상황을 주시하고 영상 녹화를 시작하세요."
            else -> ""
        }
    }
}
