package com.example.glassassist

import android.content.Context
import android.util.Log
import org.json.JSONObject

class QAMatcher(private val context: Context) {

    companion object {
        private const val TAG = "QAMatcher"
        private const val QA_FILE = "train_data_standardized.jsonl"
        private const val NO_ANSWER = "죄송합니다, 해당 내용에 대한 답변을 찾지 못했습니다. 매뉴얼을 확인해 주세요."
        private const val MIN_SCORE_THRESHOLD = 3

        private val SYNONYM_MAP = mapOf(
            "도어" to listOf("문", "출입문", "게이트"),
            "문" to listOf("도어", "출입문", "게이트"),
            "에러" to listOf("오류", "에러", "고장", "이상"),
            "오류" to listOf("에러", "오류", "고장", "이상"),
            "고장" to listOf("에러", "오류", "이상", "불량"),
            "코드" to listOf("번호", "코드", "넘버"),
            "PSD" to listOf("스크린도어", "안전문", "승강장문"),
            "스크린도어" to listOf("PSD", "안전문", "승강장문"),
            "승객" to listOf("손님", "여객", "탑승객"),
            "역무원" to listOf("직원", "담당자", "관계자"),
            "열차" to listOf("전철", "지하철", "기차"),
            "지하철" to listOf("전철", "열차", "기차"),
            "긴급" to listOf("비상", "위급", "응급"),
            "비상" to listOf("긴급", "위급", "응급")
        )
    }

    private data class QAEntry(
        val id: String,
        val question: String,
        val answer: String,
        val keywords: List<String>
    )

    private var qaList: List<QAEntry> = emptyList()

    init {
        loadQAData()
    }

    private fun loadQAData() {
        try {
            val lines = context.assets.open(QA_FILE)
                .bufferedReader()
                .readLines()
                .filter { it.isNotBlank() }

            val loaded = mutableListOf<QAEntry>()

            for (line in lines) {
                try {
                    val json = JSONObject(line)
                    val question = json.optString("question", "")
                    val answer = json.optString("answer", "")

                    if (question.isNotBlank() && answer.isNotBlank()) {
                        loaded.add(
                            QAEntry(
                                id = "",
                                question = question,
                                answer = answer,
                                keywords = extractKeywords(question)
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "줄 파싱 실패: ${e.message}")
                }
            }

            qaList = loaded
            Log.d(TAG, "Q&A 로드 완료: ${qaList.size}개 항목")

        } catch (e: Exception) {
            Log.e(TAG, "Q&A 파일 로드 실패: ${e.message}")
        }
    }

    private fun extractKeywords(text: String): List<String> {
        val stopWords = setOf(
            "어떻게", "뭐야", "뭐", "이거", "저거", "그거",
            "하면", "해야", "되나", "되는", "있어", "없어",
            "이야", "이에요", "입니다", "합니다", "해줘", "알려줘",
            "좀", "제발", "빨리", "지금", "바로", "즉시",
            "가", "이", "은", "는", "을", "를", "의", "에", "서"
        )
        return text.split(" ", "?", "!", ".", ",")
            .map { it.trim() }
            .filter { it.length >= 2 && it !in stopWords }
    }

    private fun expandWithSynonyms(text: String): String {
        var expanded = text
        SYNONYM_MAP.forEach { (word, synonyms) ->
            if (text.contains(word)) {
                synonyms.forEach { synonym -> expanded += synonym }
            }
        }
        return expanded
    }

    fun findBestAnswer(inputText: String): String {
        if (qaList.isEmpty()) {
            Log.w(TAG, "Q&A 데이터 없음")
            return NO_ANSWER
        }

        val inputKeywords = extractKeywords(inputText)
        val normalizedInput = inputText.replace(" ", "")
        val expandedInput = expandWithSynonyms(normalizedInput)

        var bestScore = 0
        var bestAnswer = ""
        var bestQuestion = ""

        for (entry in qaList) {
            val score = calculateScore(normalizedInput, expandedInput, inputKeywords, entry)
            if (score > bestScore) {
                bestScore = score
                bestAnswer = entry.answer
                bestQuestion = entry.question
            }
        }

        return if (bestScore >= MIN_SCORE_THRESHOLD) {
            Log.d(TAG, "매칭 성공 (점수: $bestScore) 질문: $bestQuestion")
            bestAnswer
        } else {
            Log.d(TAG, "매칭 실패 (최고 점수: $bestScore)")
            NO_ANSWER
        }
    }

    private fun calculateScore(
        normalizedInput: String,
        expandedInput: String,
        inputKeywords: List<String>,
        entry: QAEntry
    ): Int {
        var score = 0
        val normalizedQuestion = entry.question.replace(" ", "")
        val expandedQuestion = expandWithSynonyms(normalizedQuestion)

        if (normalizedQuestion == normalizedInput) return 100

        if (normalizedQuestion.contains(normalizedInput) || normalizedInput.contains(normalizedQuestion)) {
            score += 10
        }

        if (expandedQuestion.contains(normalizedInput) || expandedInput.contains(normalizedQuestion)) {
            score += 5
        }

        val numberRegex = Regex("\\d{3,}")
        val inputNumbers = numberRegex.findAll(normalizedInput).map { it.value }.toSet()
        val questionNumbers = numberRegex.findAll(normalizedQuestion).map { it.value }.toSet()
        score += inputNumbers.intersect(questionNumbers).size * 20
        score -= (questionNumbers - inputNumbers).size * 10

        for (keyword in inputKeywords) {
            if (normalizedQuestion.contains(keyword)) score += 3
            SYNONYM_MAP[keyword]?.forEach { synonym ->
                if (normalizedQuestion.contains(synonym)) score += 2
            }
        }
        for (entryKeyword in entry.keywords) {
            if (normalizedInput.contains(entryKeyword)) score += 3
            SYNONYM_MAP[entryKeyword]?.forEach { synonym ->
                if (normalizedInput.contains(synonym)) score += 2
            }
        }

        for (len in minOf(normalizedInput.length, 6) downTo 2) {
            for (start in 0..normalizedInput.length - len) {
                val sub = normalizedInput.substring(start, start + len)
                if (normalizedQuestion.contains(sub)) {
                    score += len - 1
                    break
                }
            }
        }

        return score
    }
}
