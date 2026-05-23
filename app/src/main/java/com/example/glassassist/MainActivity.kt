package com.example.glassassist

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.FileObserver
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.provider.MediaStore
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import android.app.AlertDialog
import android.database.ContentObserver
import android.net.Uri
import android.widget.EditText
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import android.view.LayoutInflater
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var sttText: TextView

    private val qaList = mutableListOf<RecordItem>()
    private val protectionList = mutableListOf<RecordItem>()
    private val videoList = mutableListOf<RecordItem>()
    private val meterList = mutableListOf<RecordItem>()

    private lateinit var qaAdapter: ArrayAdapter<String>
    private lateinit var protectionAdapter: ArrayAdapter<String>
    private lateinit var videoAdapter: ArrayAdapter<String>
    private lateinit var meterAdapter: ArrayAdapter<String>

    private val dateFormat = SimpleDateFormat("yyyy.M.d", Locale.KOREAN)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.KOREAN)

    private var isMeterLocation = ""

    data class RecordItem(
        val date: String,
        val time: String,
        val timestampMs: Long = 0,
        val question: String = "",
        val answer: String = "",
        val keyword: String = "",
        val location: String = "",
        var videoUri: String? = null
    )

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var audioManager: AudioManager
    private lateinit var tts: TextToSpeech
    private var messageServer: MessageServer? = null

    private lateinit var userPrefs: UserPreferences
    private lateinit var db: DatabaseHelper
    private val dbExecutor = Executors.newSingleThreadExecutor()
    private val userId get() = userPrefs.userId ?: "미설정"
    private val apiUrl get() = userPrefs.apiUrl
    private val wsUrl get() = userPrefs.wsUrl

    private var webSocket: WebSocket? = null
    private var dispatchWebSocket: WebSocket? = null
    private val client = OkHttpClient()
    private lateinit var qrScanLauncher: ActivityResultLauncher<ScanOptions>

    private var audioRecord: AudioRecord? = null
    private var audioFilePath: String = ""
    private var isRecording = false
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var continuousSpeechRecognizer: SpeechRecognizer? = null
    private var isMonitoring = false
    private val monitorRestartHandler = Handler(Looper.getMainLooper())

    private var isBluetoothScoOn = false
    private var isHandlingDanger = false
    private var glassFileObserver: FileObserver? = null
    private var mediaStoreObserver: ContentObserver? = null
    private val processedGlassFiles = mutableSetOf<String>()

    private var isMeterMode = false
    private var isAwake = false
    private val wakeTimeoutHandler = Handler(Looper.getMainLooper())

    private enum class InspectionStep { IDLE, WAITING_TYPE, WAITING_LOCATION }
    private var inspectionStep = InspectionStep.IDLE
    private var inspectionFacility = ""
    private var inspectionType = ""
    private val inspectionTypeMap = mapOf(
        "일상" to "일상점검", "정기" to "정기점검", "수시" to "수시점검",
        "특별" to "특별점검", "긴급" to "긴급점검"
    )
    private var inspectionTriggerKeywords: List<String> = emptyList()
    @Volatile private var activeCall: okhttp3.Call? = null

    private lateinit var phoneCameraLauncher: ActivityResultLauncher<Uri>
    private var phoneCameraUri: Uri? = null
    private var pendingInspectionType = ""
    private var pendingInspectionLocation = ""

    private val handoverList = mutableListOf<String>()
    private lateinit var handoverAdapter: ArrayAdapter<String>
    private lateinit var newsAdapter: NewsAdapter

    companion object {
        private const val NAVER_CLIENT_ID = "YkmvsxAbXEj1AOeuGW0n"
        private const val NAVER_CLIENT_SECRET = "H4fKvtP0Fj"
        private const val NEWS_PREVIEW_COUNT = 3
        private val META_AI_DIR = "/storage/emulated/0/Download/Meta AI"
        private val WAKE_WORDS = listOf("안녕글래스", "안녕 글래스", "안녕글레스", "안녕 글레스")
        // 안경 파일명 형식: 20260517_141507_hash.mp4
        private val GLASS_FILE_REGEX = Regex("""^(\d{8})_(\d{6})_[0-9a-f]+\.(mp4|mov|jpg|jpeg|png)$""")
    }

    private val standbyHandler = Handler(Looper.getMainLooper())
    private val standbyRunnable: Runnable = object : Runnable {
        override fun run() {
            if (isRecording) return
            if (tts.isSpeaking) {
                standbyHandler.postDelayed(this, 1000)
                return
            }
            tts.speak("안녕 글래스라고 불러주세요", TextToSpeech.QUEUE_FLUSH, null, "standby")
            runOnUiThread { sttText.text = "'안녕 글래스'라고 불러주세요\n[터치하면 음성 녹음]" }
            monitorRestartHandler.postDelayed({ resumeContinuousMonitoring() }, 2000)
        }
    }

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
            val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    isBluetoothScoOn = true
                    reinitTtsAndRecord()
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    isBluetoothScoOn = false
                }
            }
        }
    }

    private fun reinitTtsAndRecord() {
        tts.stop()
        tts.shutdown()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.KOREAN
                messageServer?.stop()
                messageServer = MessageServer(tts)
                messageServer?.start()
            }
            Handler(Looper.getMainLooper()).postDelayed({ startRecordingInternal() }, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
            val url = result.contents ?: return@registerForActivityResult
            userPrefs.dispatchWsUrl = url
            connectDispatchWebSocket()
            android.widget.Toast.makeText(this, "관제 서버 연결: $url", android.widget.Toast.LENGTH_SHORT).show()
        }

        phoneCameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                val location = pendingInspectionType
                val now = Date()
                val imageUri = phoneCameraUri?.toString() ?: return@registerForActivityResult
                val item = RecordItem(date = dateFormat.format(now), time = timeFormat.format(now), location = location, videoUri = imageUri)
                meterList.add(0, item)
                dbExecutor.execute { db.insertMeter(userId, item.date, item.time, location, imageUri) }
                runOnUiThread {
                    meterAdapter.clear()
                    meterList.forEach { meterAdapter.add("${it.date} ${it.time} - ${it.location}") }
                    meterAdapter.notifyDataSetChanged()
                    sttText.text = "📊 점검 저장됨\n[터치하면 음성 녹음]"
                    tts.speak("사진이 저장되었습니다", TextToSpeech.QUEUE_FLUSH, null, "phone_saved")
                }
            }
        }

        setContentView(R.layout.activity_main)

        userPrefs = UserPreferences(this)
        db = DatabaseHelper(this)
        loadInspectionKeywords()

        statusText = findViewById(R.id.status_text)
        sttText = findViewById(R.id.stt_text)
        sttText.text = "'안녕 글래스'라고 불러주세요\n[터치하면 음성 녹음]"

        if (userPrefs.userId == null) showUserIdDialog()

        setupLists()
        loadAllRecordsFromDb()
        setupButtons()
        setupDashboard()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter

        registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.KOREAN
                messageServer = MessageServer(tts)
                messageServer?.start()
                connectWebSocket()
                connectDispatchWebSocket()
            }
        }

        findViewById<TextView>(R.id.btn_dispatch_qr).setOnClickListener {
            val opts = ScanOptions().apply {
                setPrompt("관제 서버 QR 코드를 스캔하세요")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
            qrScanLauncher.launch(opts)
        }

        findViewById<TextView>(R.id.btn_standby).setOnClickListener {
            standbyHandler.removeCallbacks(standbyRunnable)
            tts.stop()
            if (isRecording) stopRecordingAndSend()
            pauseContinuousMonitoring()
            tts.speak("음성인식 대기 중입니다", TextToSpeech.QUEUE_FLUSH, null, "standby")
            sttText.text = "음성인식 대기 중...\n[터치하면 음성 녹음]"
            monitorRestartHandler.postDelayed({ resumeContinuousMonitoring() }, 2000)
        }

        findViewById<TextView>(R.id.btn_stop).setOnClickListener {
            standbyHandler.removeCallbacks(standbyRunnable)
            tts.stop()
            if (isRecording) stopRecordingAndSend()
            isHandlingDanger = false
            pauseContinuousMonitoring()
            sttText.text = "중지됨.\n[터치하면 음성 녹음]"
            standbyHandler.postDelayed(standbyRunnable, 3_000)
        }

        sttText.setOnClickListener {
            when {
                tts.isSpeaking -> {
                    tts.stop()
                    sttText.text = "응답 중지됨.\n[터치하면 음성 녹음]"
                }
                isRecording -> stopRecordingAndSend()
                else -> startRecording()
            }
        }

        if (checkPermissions()) {
            startContinuousMonitoring()
            registerGlassMediaObserver()
            registerMediaStoreObserver()
        } else {
            requestPermissions()
        }
        startForegroundService(Intent(this, GlassAssistService::class.java))
    }

    private fun showUserIdDialog() {
        val input = EditText(this).apply {
            hint = "예: 010-1234-5678"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("사용자 등록")
            .setMessage("전화번호를 입력하세요.\n앱 재설치 전까지 이 번호로 기록됩니다.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("확인") { _, _ ->
                userPrefs.userId = input.text.toString().trim().ifEmpty { "미설정" }
            }
            .show()
    }

    private fun loadAllRecordsFromDb() {
        dbExecutor.execute {
            val protections = db.getProtectionRecords(userId)
            val qas = db.getQaRecords(userId)
            val videos = db.getVideoRecords(userId)
            val meters = db.getMeterRecords(userId)

            runOnUiThread {
                protections.forEach { r ->
                    protectionList.add(RecordItem(r.date, r.time, r.timestampMs, keyword = r.keyword, videoUri = r.videoUri))
                    protectionAdapter.add("${r.date} ${r.time} ${r.keyword}")
                }
                qas.forEach { r ->
                    qaList.add(RecordItem(r.date, r.time, question = r.question, answer = r.answer))
                    qaAdapter.add("${r.time} Q: ${r.question.take(10)}...")
                }
                videos.forEach { r ->
                    videoList.add(RecordItem(r.date, r.time, videoUri = r.videoUri))
                    videoAdapter.add("${r.date} ${r.time} 촬영")
                }
                meters.forEach { r ->
                    meterList.add(RecordItem(r.date, r.time, location = r.location, videoUri = r.videoUri))
                    meterAdapter.add("${r.date} ${r.time} - ${r.location}")
                }
                protectionAdapter.notifyDataSetChanged()
                qaAdapter.notifyDataSetChanged()
                videoAdapter.notifyDataSetChanged()
                meterAdapter.notifyDataSetChanged()
                Log.d("GlassAssist", "DB 로드: 보호${protections.size} QA${qas.size} 영상${videos.size} 계량기${meters.size}")
            }
        }
    }

    // Meta AI 폴더 직접 감시 (MediaStore 등록 안 하므로 FileObserver 사용)
    private fun registerGlassMediaObserver() {
        val dir = java.io.File(META_AI_DIR)
        if (!dir.exists()) {
            Log.w("GlassAssist", "Meta AI 폴더 없음: $META_AI_DIR")
            return
        }

        glassFileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && GLASS_FILE_REGEX.matches(path)) {
                        Log.d("GlassAssist", "안경 파일 감지: $path")
                        tryLinkGlassFile(java.io.File(META_AI_DIR, path), path)
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(META_AI_DIR, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && GLASS_FILE_REGEX.matches(path)) {
                        Log.d("GlassAssist", "안경 파일 감지: $path")
                        tryLinkGlassFile(java.io.File(META_AI_DIR, path), path)
                    }
                }
            }
        }
        glassFileObserver?.startWatching()
        Log.d("GlassAssist", "Meta AI 폴더 감시 시작: $META_AI_DIR")
    }

    private fun registerMediaStoreObserver() {
        mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Thread { checkNewGlassMedia() }.start()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver!!
        )
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver!!
        )
        Log.d("GlassAssist", "MediaStore 전체 감시 시작 (자동 전송 감지용)")
    }

    @Suppress("DEPRECATION")
    private fun checkNewGlassMedia() {
        val cutoffSec = (System.currentTimeMillis() / 1000) - 30
        queryGlassFiles(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            cutoffSec
        )
        queryGlassFiles(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            cutoffSec
        )
    }

    @Suppress("DEPRECATION")
    private fun queryGlassFiles(contentUri: Uri, nameCol: String, dataCol: String, cutoffSec: Long) {
        val bucketCol = MediaStore.MediaColumns.BUCKET_NAME
        contentResolver.query(
            contentUri, arrayOf(nameCol, dataCol, bucketCol),
            "${MediaStore.MediaColumns.DATE_ADDED} >= ?",
            arrayOf(cutoffSec.toString()),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(nameCol)
            val dataIdx = cursor.getColumnIndexOrThrow(dataCol)
            val bucketIdx = cursor.getColumnIndexOrThrow(bucketCol)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx).takeIf { it.isNotBlank() } ?: continue
                val path = cursor.getString(dataIdx).takeIf { it.isNotBlank() } ?: continue
                val bucket = cursor.getString(bucketIdx) ?: ""
                val isMetaAlbum = bucket.equals("Meta View", ignoreCase = true) ||
                                  bucket.equals("Meta AI", ignoreCase = true)
                val isGlassFile = GLASS_FILE_REGEX.matches(name) || isMetaAlbum
                if (isGlassFile) {
                    val file = java.io.File(path)
                    if (file.exists()) tryLinkGlassFile(file, name)
                }
            }
        }
    }

    private fun analyzeMeterImage(file: java.io.File, imageUri: String) {
        Thread {
            try {
                val requestBody = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart(
                        "file", "meter.jpg",
                        file.asRequestBody("image/jpeg".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url("$apiUrl/analyze-frame")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                val analysis = if (response.isSuccessful) {
                    try {
                        JSONObject(responseBody).optString("analysis", "인식 실패")
                    } catch (e: Exception) { "인식 실패" }
                } else "서버 오류 (${response.code})"

                val now = Date()
                val item = RecordItem(
                    date = dateFormat.format(now),
                    time = timeFormat.format(now),
                    location = "$isMeterLocation / 계량기 번호: $analysis",
                    videoUri = imageUri
                )
                meterList.add(0, item)
                dbExecutor.execute {
                    db.insertMeter(userId, item.date, item.time, item.location, imageUri)
                }

                runOnUiThread {
                    meterAdapter.clear()
                    meterList.forEach { meterAdapter.add("${it.date} ${it.time} - ${it.location}") }
                    meterAdapter.notifyDataSetChanged()
                    sttText.text = "계량기 번호: $analysis\n[터치하면 음성 녹음]"
                    tts.speak("계량기 번호 $analysis 입니다", TextToSpeech.QUEUE_FLUSH, null, "meter_result")
                    standbyHandler.removeCallbacks(standbyRunnable)
                    standbyHandler.postDelayed(standbyRunnable, 3000)
                }
            } catch (e: Exception) {
                // 서버 미연결 시 사진만 저장
                val now = Date()
                val item = RecordItem(
                    date = dateFormat.format(now),
                    time = timeFormat.format(now),
                    location = isMeterLocation,
                    videoUri = imageUri
                )
                meterList.add(0, item)
                dbExecutor.execute {
                    db.insertMeter(userId, item.date, item.time, item.location, imageUri)
                }
                runOnUiThread {
                    meterAdapter.clear()
                    meterList.forEach { meterAdapter.add("${it.date} ${it.time} - ${it.location}") }
                    meterAdapter.notifyDataSetChanged()
                    sttText.text = "계량기 사진 저장됨 (서버 미연결)\n[터치하면 음성 녹음]"
                    tts.speak("계량기 사진이 저장되었습니다", TextToSpeech.QUEUE_FLUSH, null, "meter_saved")
                }
            }
        }.start()
    }

    // 파일명(20260517_141507_hash.mp4)에서 촬영 시각 파싱 → 보호기록과 매칭
    private fun tryLinkGlassFile(file: java.io.File, filename: String) {
        if (!processedGlassFiles.add(filename)) return  // 중복 이벤트 무시
        Handler(Looper.getMainLooper()).postDelayed({ processedGlassFiles.remove(filename) }, 10_000)

        val match = GLASS_FILE_REGEX.find(filename) ?: return
        val ext = match.groupValues[3].lowercase()

        // 안경 사진 처리
        if (ext in listOf("jpg", "jpeg", "png")) {
            android.media.MediaScannerConnection.scanFile(
                this, arrayOf(file.absolutePath), arrayOf("image/jpeg")
            ) { _, uri ->
                val imageUri = uri?.toString() ?: return@scanFile
                if (isMeterMode) {
                    val savedLocation = isMeterLocation
                    isMeterMode = false
                    resumeContinuousMonitoring()
                    val now = Date()
                    val item = RecordItem(date = dateFormat.format(now), time = timeFormat.format(now), location = savedLocation, videoUri = imageUri)
                    meterList.add(0, item)
                    dbExecutor.execute { db.insertMeter(userId, item.date, item.time, savedLocation, imageUri) }
                    runOnUiThread {
                        meterAdapter.clear()
                        meterList.forEach { meterAdapter.add("${it.date} ${it.time} - ${it.location}") }
                        meterAdapter.notifyDataSetChanged()
                        sttText.text = "📊 점검 저장됨\n[터치하면 음성 녹음]"
                        tts.speak("사진이 저장되었습니다", TextToSpeech.QUEUE_FLUSH, null, "insp_saved")
                    }
                } else {
                    // 일반 모드 → 실시간 영상전송 탭에 저장
                    runOnUiThread {
                        sttText.text = "안경 사진이 저장되었습니다.\n[터치하면 음성 녹음]"
                        tts.speak("안경 사진이 저장되었습니다", TextToSpeech.QUEUE_FLUSH, null, "photo_${System.currentTimeMillis()}")
                        addVideoRecord(imageUri)
                        standbyHandler.removeCallbacks(standbyRunnable)
                        standbyHandler.postDelayed(standbyRunnable, 3000)
                    }
                }
            }
            return
        }

        val dateStr = match.groupValues[1]
        val timeStr = match.groupValues[2]
        val recordingTimeMs = try {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse("${dateStr}_${timeStr}")?.time ?: return
        } catch (e: Exception) {
            Log.e("GlassAssist", "파일명 시각 파싱 실패: $filename")
            return
        }

        // 점검 모드 → 영상도 점검 기록으로 저장
        if (isMeterMode) {
            isMeterMode = false
            resumeContinuousMonitoring()
            android.media.MediaScannerConnection.scanFile(
                this, arrayOf(file.absolutePath), arrayOf("video/mp4")
            ) { _, uri ->
                val videoUri = uri?.toString() ?: return@scanFile
                val now = Date()
                val item = RecordItem(
                    date = dateFormat.format(now), time = timeFormat.format(now),
                    location = isMeterLocation, videoUri = videoUri
                )
                meterList.add(0, item)
                dbExecutor.execute { db.insertMeter(userId, item.date, item.time, isMeterLocation, videoUri) }
                runOnUiThread {
                    meterAdapter.clear()
                    meterList.forEach { meterAdapter.add("${it.date} ${it.time} - ${it.location}") }
                    meterAdapter.notifyDataSetChanged()
                    sttText.text = "📊 점검 영상 저장됨\n[터치하면 음성 녹음]"
                    tts.speak("영상이 저장되었습니다", TextToSpeech.QUEUE_FLUSH, null, "meter_video")
                }
            }
            return
        }

        // 연결 여부 관계없이 전체 접객보호 기록 중 가장 가까운 것 탐색
        val bestMatch = protectionList
            .filter { it.timestampMs > 0 }
            .minByOrNull { abs(it.timestampMs - recordingTimeMs) }
            ?.takeIf { abs(it.timestampMs - recordingTimeMs) <= 15_000 }

        val newDiff = if (bestMatch != null) abs(bestMatch.timestampMs - recordingTimeMs) else Long.MAX_VALUE

        // 이미 연결된 영상이 있으면 현재 영상이 더 가까운지 비교
        if (bestMatch != null && bestMatch.videoUri != null) {
            val existingDiff = glassFileRecordingDiff(bestMatch.videoUri!!, bestMatch.timestampMs)
            if (newDiff >= existingDiff) {
                // 기존 영상이 더 가깝거나 같음 → 새 영상은 실시간 영상전송으로
                Log.d("GlassAssist", "기존 영상이 더 가까움 → 실시간 영상전송으로 저장")
                android.media.MediaScannerConnection.scanFile(
                    this, arrayOf(file.absolutePath), arrayOf("video/mp4")
                ) { _, uri ->
                    val videoUri = uri?.toString() ?: return@scanFile
                    runOnUiThread { addVideoRecord(videoUri) }
                }
                return
            }
            // 새 영상이 더 가까움 → 기존 영상을 실시간 영상전송으로 내리고 교체
            Log.d("GlassAssist", "새 영상이 더 가까움 → 기존 영상 실시간 탭으로 이동 후 교체")
        }

        if (bestMatch == null) {
            Log.d("GlassAssist", "매칭 보호기록 없음 → 실시간 영상전송으로 저장")
            android.media.MediaScannerConnection.scanFile(
                this, arrayOf(file.absolutePath), arrayOf("video/mp4")
            ) { _, uri ->
                val videoUri = uri?.toString() ?: return@scanFile
                runOnUiThread { addVideoRecord(videoUri) }
            }
            return
        }

        Log.d("GlassAssist", "매칭 성공 (시간차: ${newDiff}ms) - MediaScanner 등록 중...")

        android.media.MediaScannerConnection.scanFile(
            this, arrayOf(file.absolutePath), arrayOf("video/mp4")
        ) { _, uri ->
            val contentUri = uri?.toString()
            if (contentUri == null) {
                Log.e("GlassAssist", "MediaScanner URI 획득 실패: ${file.absolutePath}")
                return@scanFile
            }

            val destDir = (getExternalFilesDir("glass_media") ?: filesDir).also { it.mkdirs() }
            val destFile = java.io.File(destDir, filename)
            val storedUri = try {
                if (!destFile.exists()) file.copyTo(destFile)
                destFile.absolutePath
            } catch (e: Exception) {
                Log.e("GlassAssist", "파일 복사 실패, content URI 사용: ${e.message}")
                contentUri
            }

            Log.d("GlassAssist", "안경 영상 자동 연결: $storedUri")
            dbExecutor.execute { db.linkProtectionVideo(userId, bestMatch.timestampMs, storedUri) }
            runOnUiThread {
                val oldUri = bestMatch.videoUri  // 교체되는 기존 영상
                if (oldUri != null) addVideoRecord(oldUri)  // 기존 영상 → 실시간 영상전송
                bestMatch.videoUri = storedUri
                isHandlingDanger = false
                protectionAdapter.clear()
                protectionList.forEach { protectionAdapter.add("${it.date} ${it.time} ${it.keyword}") }
                protectionAdapter.notifyDataSetChanged()
                sttText.text = "안경 영상이 접객보호 기록에 자동 연결되었습니다.\n[터치하면 음성 녹음]"
                tts.speak("안경 영상이 자동으로 연결되었습니다", TextToSpeech.QUEUE_FLUSH, null, "linked_${System.currentTimeMillis()}")
                standbyHandler.removeCallbacks(standbyRunnable)
                standbyHandler.postDelayed(standbyRunnable, 3000)
            }
        }
    }

    // 저장된 URI에서 촬영 시각과 접객보호 타임스탬프 간 차이 계산
    private fun glassFileRecordingDiff(uri: String, protectionTimestampMs: Long): Long {
        val filename = uri.substringAfterLast('/')
        val match = GLASS_FILE_REGEX.find(filename) ?: return Long.MAX_VALUE
        return try {
            val t = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .parse("${match.groupValues[1]}_${match.groupValues[2]}")?.time ?: return Long.MAX_VALUE
            abs(protectionTimestampMs - t)
        } catch (e: Exception) { Long.MAX_VALUE }
    }

    private fun setupLists() {
        qaAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val qaListView = findViewById<ListView>(R.id.list_qa)
        qaListView.adapter = qaAdapter
        qaListView.setOnItemClickListener { _, _, position, _ ->
            val item = qaList[position]
            startActivity(Intent(this, QaDetailActivity::class.java).apply {
                putExtra("date", item.date)
                putExtra("time", item.time)
                putExtra("question", item.question)
                putExtra("answer", item.answer)
            })
        }

        protectionAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val protectionListView = findViewById<ListView>(R.id.list_protection)
        protectionListView.adapter = protectionAdapter
        protectionListView.setOnItemClickListener { _, _, position, _ ->
            val item = protectionList[position]
            startActivity(Intent(this, ProtectionDetailActivity::class.java).apply {
                putExtra("date", item.date)
                putExtra("time", item.time)
                putExtra("keyword", item.keyword)
                putExtra("videoUri", item.videoUri)
            })
        }

        videoAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val videoListView = findViewById<ListView>(R.id.list_video)
        videoListView.adapter = videoAdapter
        videoListView.setOnItemClickListener { _, _, position, _ ->
            val item = videoList[position]
            startActivity(Intent(this, VideoDetailActivity::class.java).apply {
                putExtra("date", item.date)
                putExtra("time", item.time)
                putExtra("videoUri", item.videoUri)
            })
        }

        meterAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val meterListView = findViewById<ListView>(R.id.list_meter)
        meterListView.adapter = meterAdapter
        meterListView.setOnItemClickListener { _, _, position, _ ->
            val item = meterList[position]
            startActivity(Intent(this, MeterDetailActivity::class.java).apply {
                putExtra("date", item.date)
                putExtra("time", item.time)
                putExtra("location", item.location.substringBefore(" / 계량기 번호:").trim())
                putExtra("meterNumber", item.location.substringAfter("계량기 번호: ", "-"))
                putExtra("imagePath", item.videoUri)
            })
        }

        handoverAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val handoverListView = findViewById<ListView>(R.id.list_handover)
        handoverListView.adapter = handoverAdapter
        handoverListView.setOnItemClickListener { _, _, position, _ ->
            val content = handoverList[position]
            startActivityForResult(Intent(this, HandoverDetailActivity::class.java).apply {
                putExtra("date", "")
                putExtra("time", "")
                putExtra("content", content)
                putExtra("position", position)
            }, 200)
        }
    }

    private fun setupButtons() {
        findViewById<TextView>(R.id.btn_clear_records).setOnClickListener {
            val report = generateWorkReport()

            AlertDialog.Builder(this)
                .setTitle("근무 종료")
                .setMessage("📋 근무 일지\n\n$report\n\n기록을 삭제하시겠습니까?")
                .setPositiveButton("삭제") { _, _ ->
                    saveWorkReport(report)
                    dbExecutor.execute { db.deleteAllRecords(userId) }
                    protectionList.clear(); protectionAdapter.clear()
                    qaList.clear(); qaAdapter.clear()
                    videoList.clear(); videoAdapter.clear()
                    meterList.clear(); meterAdapter.clear()
                    handoverList.clear(); handoverAdapter.clear()
                    sttText.text = "기록이 삭제되었습니다.\n[터치하면 음성 녹음]"
                    standbyHandler.removeCallbacks(standbyRunnable)
                    standbyHandler.postDelayed(standbyRunnable, 3000)
                }
                .setNegativeButton("취소", null)
                .show()
        }

        findViewById<TextView>(R.id.btn_add_meter).setOnClickListener {
            // 1단계: 시설물 직접 입력
            val facilityInput = EditText(this).apply {
                hint = "시설물 입력 (예: 소화기, 에스컬레이터)"
                setPadding(48, 24, 48, 24)
            }
            AlertDialog.Builder(this)
                .setTitle("📊 점검 시설물")
                .setView(facilityInput)
                .setPositiveButton("다음") { _, _ ->
                    val facility = facilityInput.text.toString().trim().ifEmpty { "시설물 미입력" }
                    // 2단계: 점검 유형 선택 (직접 입력 포함)
                    val types = arrayOf("일상점검", "정기점검", "수시점검", "특별점검", "긴급점검", "직접 입력")
                    var selectedIndex = 0
                    AlertDialog.Builder(this)
                        .setTitle("점검 유형 선택")
                        .setSingleChoiceItems(types, 0) { _, which -> selectedIndex = which }
                        .setPositiveButton("다음") { _, _ ->
                            val selectedType = if (selectedIndex == types.lastIndex) null else types[selectedIndex]
                            fun proceedWithType(type: String) {
                                val locationInput = EditText(this).apply {
                                    hint = "위치 입력 (예: 3번 승강장)"
                                    setPadding(48, 24, 48, 24)
                                }
                                AlertDialog.Builder(this)
                                    .setTitle("📍 위치 입력")
                                    .setView(locationInput)
                                    .setPositiveButton("촬영하기") { _, _ ->
                                        launchPhoneCamera(facility, type, locationInput.text.toString().trim().ifEmpty { "위치 미입력" })
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

        findViewById<TextView>(R.id.btn_add_handover).setOnClickListener {
            val input = EditText(this).apply {
                hint = "인수인계 내용 입력"
                setPadding(48, 24, 48, 24)
            }
            AlertDialog.Builder(this)
                .setTitle("📝 인수인계 추가")
                .setView(input)
                .setPositiveButton("추가") { _, _ ->
                    val memo = input.text.toString().trim()
                    if (memo.isNotEmpty()) {
                        val now = Date()
                        dbExecutor.execute { db.insertHandover(userId, dateFormat.format(now), timeFormat.format(now), memo) }
                        handoverList.add(memo)
                        handoverAdapter.clear()
                        handoverList.forEachIndexed { i, s -> handoverAdapter.add("${i+1}. $s") }
                        handoverAdapter.notifyDataSetChanged()
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun addQaRecord(question: String, answer: String) {
        val now = Date()
        val item = RecordItem(date = dateFormat.format(now), time = timeFormat.format(now), question = question, answer = answer)
        qaList.add(0, item)
        dbExecutor.execute {
            db.insertQa(userId, item.date, item.time, question, answer)
        }
        runOnUiThread {
            qaAdapter.clear()
            qaList.forEach { qaAdapter.add("${it.time} Q: ${it.question.take(10)}...") }
            qaAdapter.notifyDataSetChanged()
        }
    }

    private fun addProtectionRecord(keyword: String) {
        val now = Date()
        val item = RecordItem(
            date = dateFormat.format(now),
            time = timeFormat.format(now),
            timestampMs = System.currentTimeMillis(),
            keyword = keyword
        )
        protectionList.add(0, item)
        dbExecutor.execute {
            db.insertProtection(userId, item.date, item.time, item.timestampMs, keyword)
        }
        runOnUiThread {
            protectionAdapter.clear()
            protectionList.forEach { protectionAdapter.add("${it.date} ${it.time} ${it.keyword}") }
            protectionAdapter.notifyDataSetChanged()
        }
    }

    private fun addVideoRecord(uri: String? = null) {
        val now = Date()
        val item = RecordItem(date = dateFormat.format(now), time = timeFormat.format(now), videoUri = uri)
        videoList.add(0, item)
        dbExecutor.execute {
            db.insertVideo(userId, item.date, item.time, uri)
        }
        runOnUiThread {
            videoAdapter.clear()
            videoList.forEach { videoAdapter.add("${it.date} ${it.time} 촬영") }
            videoAdapter.notifyDataSetChanged()
        }
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    statusText.text = "● 연결됨"
                    statusText.setTextColor(0xFF4CAF50.toInt())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val message = json.getString("message")
                    runOnUiThread {
                        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "msg_${System.currentTimeMillis()}")
                        sttText.text = "AI 답변: $message\n[터치하면 음성 녹음]"
                    }
                } catch (e: Exception) {
                    Log.e("GlassAssist", "메시지 파싱 오류: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    statusText.text = "● 연결 실패"
                    statusText.setTextColor(0xFFF44336.toInt())
                }
                Handler(Looper.getMainLooper()).postDelayed({ connectWebSocket() }, 3000)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {}
        })
    }

    private fun connectDispatchWebSocket() {
        val url = userPrefs.dispatchWsUrl ?: return
        dispatchWebSocket?.close(1000, "재연결")
        val request = Request.Builder().url(url).build()
        dispatchWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "dispatch") {
                        val message = json.getString("message")
                        runOnUiThread {
                            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "dispatch_${System.currentTimeMillis()}")
                            sttText.text = "관제실: $message\n[터치하면 음성 녹음]"
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GlassAssist", "관제 메시지 파싱 오류: ${e.message}")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Handler(Looper.getMainLooper()).postDelayed({ connectDispatchWebSocket() }, 5000)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {}
        })
    }

    private fun startRecording() {
        if (isRecording) return
        pauseContinuousMonitoring()
        runOnUiThread { sttText.text = "안경 마이크 연결 중..." }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scoDevice = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (scoDevice != null) {
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.setCommunicationDevice(scoDevice)
                isBluetoothScoOn = true
                Handler(Looper.getMainLooper()).postDelayed({ startRecordingInternal() }, 300)
            } else {
                isBluetoothScoOn = false
                startRecordingInternal()
            }
            return
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isRecording) {
                isBluetoothScoOn = false
                startRecordingInternal()
            }
        }, 3000)
    }

    private fun startRecordingInternal() {
        if (isRecording) return

        audioFilePath = "${externalCacheDir?.absolutePath}/recorded_audio.wav"
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        if (bufferSize <= 0) {
            runOnUiThread { sttText.text = "마이크 초기화 실패" }
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread { sttText.text = "마이크 권한이 없습니다." }
            return
        }

        val audioSource = if (isBluetoothScoOn) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                          else MediaRecorder.AudioSource.MIC

        audioRecord?.release()
        audioRecord = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            runOnUiThread { sttText.text = "마이크 초기화 실패" }
            audioRecord?.release()
            audioRecord = null
            return
        }

        audioRecord?.startRecording()
        isRecording = true
        runOnUiThread {
            sttText.text = if (isBluetoothScoOn) "안경 마이크로 녹음 중...\n(다시 터치하면 전송)"
                           else "폰 마이크로 녹음 중...\n(다시 터치하면 전송)"
        }

        Thread {
            val audioData = mutableListOf<Short>()
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (read > 0) audioData.addAll(buffer.take(read).toList())
            }
            saveAsWav(audioData.toShortArray(), audioFilePath)
            sendAudioToServer(audioFilePath)
        }.start()
    }

    private fun stopRecordingAndSend() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        if (isBluetoothScoOn) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                audioManager.mode = AudioManager.MODE_NORMAL
            }
            isBluetoothScoOn = false
        }
        runOnUiThread { sttText.text = "서버로 오디오 전송 중..." }
    }

    private fun saveAsWav(audioData: ShortArray, filePath: String) {
        val byteData = ByteArray(audioData.size * 2)
        for (i in audioData.indices) {
            byteData[i * 2] = (audioData[i].toInt() and 0xFF).toByte()
            byteData[i * 2 + 1] = (audioData[i].toInt() shr 8 and 0xFF).toByte()
        }
        val totalDataLen = byteData.size + 36
        val header = ByteArray(44)
        header[0]='R'.code.toByte(); header[1]='I'.code.toByte(); header[2]='F'.code.toByte(); header[3]='F'.code.toByte()
        header[4]=(totalDataLen and 0xFF).toByte(); header[5]=(totalDataLen shr 8 and 0xFF).toByte()
        header[6]=(totalDataLen shr 16 and 0xFF).toByte(); header[7]=(totalDataLen shr 24 and 0xFF).toByte()
        header[8]='W'.code.toByte(); header[9]='A'.code.toByte(); header[10]='V'.code.toByte(); header[11]='E'.code.toByte()
        header[12]='f'.code.toByte(); header[13]='m'.code.toByte(); header[14]='t'.code.toByte(); header[15]=' '.code.toByte()
        header[16]=16; header[17]=0; header[18]=0; header[19]=0
        header[20]=1; header[21]=0; header[22]=1; header[23]=0
        header[24]=(sampleRate and 0xFF).toByte(); header[25]=(sampleRate shr 8 and 0xFF).toByte()
        header[26]=(sampleRate shr 16 and 0xFF).toByte(); header[27]=(sampleRate shr 24 and 0xFF).toByte()
        val byteRate = sampleRate * 2
        header[28]=(byteRate and 0xFF).toByte(); header[29]=(byteRate shr 8 and 0xFF).toByte()
        header[30]=(byteRate shr 16 and 0xFF).toByte(); header[31]=(byteRate shr 24 and 0xFF).toByte()
        header[32]=2; header[33]=0; header[34]=16; header[35]=0
        header[36]='d'.code.toByte(); header[37]='a'.code.toByte(); header[38]='t'.code.toByte(); header[39]='a'.code.toByte()
        header[40]=(byteData.size and 0xFF).toByte(); header[41]=(byteData.size shr 8 and 0xFF).toByte()
        header[42]=(byteData.size shr 16 and 0xFF).toByte(); header[43]=(byteData.size shr 24 and 0xFF).toByte()
        File(filePath).outputStream().use { out -> out.write(header); out.write(byteData) }
    }

    private fun sendAudioToServer(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            runOnUiThread { sttText.text = "오디오 파일 없음" }
            return
        }

        Thread {
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio.wav", file.asRequestBody("audio/wav".toMediaType()))
                    .build()
                val request = Request.Builder().url("$apiUrl/conversation").post(requestBody).build()
                val call = client.newCall(request)
                activeCall = call
                val response = call.execute()
                activeCall = null
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    runOnUiThread { sttText.text = "서버 에러 (${response.code})" }
                    return@Thread
                }

                val json = try { JSONObject(responseBody) } catch (e: Exception) {
                    runOnUiThread { sttText.text = "응답 파싱 실패" }
                    return@Thread
                }

                val answer = json.optString("llm_answer", "응답 없음")
                val sttTextResult = json.optString("stt_text", "")

                val detector = KeywordDetector(this@MainActivity)
                val detection = detector.detect(sttTextResult)
                if (detection.detected) {
                    val alertMsg = detector.getAlertMessage(detection)
                    addProtectionRecord(detection.matchedKeyword)
                    startDangerAlert()
                    runOnUiThread {
                        sttText.text = "긴급 상황 감지!\n\n안경 버튼을 눌러 영상을 녹화하세요.\nMeta AI 앱에서 '가져오기'를 누르면 자동 연결됩니다."
                        tts.speak(alertMsg, TextToSpeech.QUEUE_FLUSH, null, "alert_${System.currentTimeMillis()}")
                    }
                } else {
                    addQaRecord(sttTextResult, answer)
                    runOnUiThread {
                        sttText.text = "Q: $sttTextResult\n\nA: $answer"
                        tts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "msg_${System.currentTimeMillis()}")
                        standbyHandler.removeCallbacks(standbyRunnable)
                        standbyHandler.postDelayed(standbyRunnable, 3_000)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { sttText.text = "전송 실패: ${e.message}" }
            } finally {
                resumeContinuousMonitoring()
            }
        }.start()
    }

    private fun startContinuousMonitoring() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        isMonitoring = true
        continuousSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        continuousSpeechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: run { scheduleMonitorRestart(); return }

                // 점검 유형 음성 응답 처리
                if (inspectionStep == InspectionStep.WAITING_TYPE) {
                    inspectionType = inspectionTypeMap.entries.firstOrNull { text.contains(it.key) }?.value ?: text
                    inspectionStep = InspectionStep.WAITING_LOCATION
                    runOnUiThread {
                        sttText.text = "점검 유형: $inspectionType\n위치를 말씀해주세요"
                        tts.speak("위치를 말씀해주세요", TextToSpeech.QUEUE_FLUSH, null, "insp_location")
                    }
                    scheduleMonitorRestart()
                    return
                }
                // 점검 위치 음성 응답 처리
                if (inspectionStep == InspectionStep.WAITING_LOCATION) {
                    inspectionStep = InspectionStep.IDLE
                    activateInspectionCameraMode(inspectionType, inspectionFacility, text)
                    return
                }

                if (!isHandlingDanger) {
                    val detector = KeywordDetector(this@MainActivity)
                    val detection = detector.detect(text)
                    if (detection.detected) {
                        isHandlingDanger = true
                        addProtectionRecord(detection.matchedKeyword)
                        startDangerAlert()
                        runOnUiThread {
                            sttText.text = "긴급 상황 감지!\n\n안경 버튼을 눌러 영상을 녹화하세요.\nMeta AI 앱에서 '가져오기'를 누르면 자동 연결됩니다."
                            tts.speak(detector.getAlertMessage(detection), TextToSpeech.QUEUE_FLUSH, null, "alert_${System.currentTimeMillis()}")
                        }
                    } else if (tts.isSpeaking) {
                        scheduleMonitorRestart()
                        return
                    } else if (inspectionTriggerKeywords.any { text.contains(it) }) {
                        inspectionFacility = inspectionTriggerKeywords.first { text.contains(it) }
                        inspectionStep = InspectionStep.WAITING_TYPE
                        runOnUiThread {
                            sttText.text = "점검 유형?\n일상 / 정기 / 수시 / 특별 / 긴급"
                            tts.speak("일상, 정기, 수시, 특별, 긴급 중 어떤 점검인가요?", TextToSpeech.QUEUE_FLUSH, null, "insp_type")
                        }
                    } else if (WAKE_WORDS.any { text.replace(" ", "").contains(it.replace(" ", "")) }) {
                        isAwake = true
                        wakeTimeoutHandler.removeCallbacksAndMessages(null)
                        wakeTimeoutHandler.postDelayed({
                            isAwake = false
                            runOnUiThread { sttText.text = "'안녕 글래스'라고 불러주세요\n[터치하면 음성 녹음]" }
                        }, 10_000)
                        runOnUiThread {
                            sttText.text = "네, 말씀하세요\n[터치하면 음성 녹음]"
                            tts.speak("네, 말씀하세요", TextToSpeech.QUEUE_FLUSH, null, "wake_${System.currentTimeMillis()}")
                        }
                    } else if (isAwake && text.length >= 2) {
                        isAwake = false
                        wakeTimeoutHandler.removeCallbacksAndMessages(null)
                        sendTextToServer(text)
                        return
                    }
                }
                scheduleMonitorRestart()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (isHandlingDanger) return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                // TTS 응답 중 음성 중지 명령
                if (tts.isSpeaking && (text.contains("중지") || text.contains("대기") || text.contains("취소") || text.contains("그만") || text.contains("멈춰"))) {
                    tts.stop()
                    runOnUiThread { sttText.text = "응답 중지됨.\n[터치하면 음성 녹음]" }
                    scheduleMonitorRestart()
                    return
                }
                // 음성 Q&A 중지/대기
                if (activeCall != null && (text.contains("중지") || text.contains("대기") || text.contains("취소"))) {
                    activeCall?.cancel()
                    activeCall = null
                    runOnUiThread {
                        sttText.text = "Q&A 중단됨.\n[터치하면 음성 녹음]"
                        tts.speak("중단했습니다", TextToSpeech.QUEUE_FLUSH, null, "qa_cancel")
                    }
                    resumeContinuousMonitoring()
                    return
                }
                val detector = KeywordDetector(this@MainActivity)
                val detection = detector.detect(text)
                if (detection.detected) {
                    isHandlingDanger = true
                    addProtectionRecord(detection.matchedKeyword)
                    startDangerAlert()
                    runOnUiThread {
                        sttText.text = "긴급 상황 감지!\n\n안경 버튼을 눌러 영상을 녹화하세요.\nMeta AI 앱에서 '가져오기'를 누르면 자동 연결됩니다."
                        tts.speak(detector.getAlertMessage(detection), TextToSpeech.QUEUE_FLUSH, null, "alert_${System.currentTimeMillis()}")
                    }
                }
            }

            override fun onError(error: Int) { if (!isHandlingDanger) scheduleMonitorRestart() }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        startListeningIntent()
    }

    private fun activateInspectionCameraMode(type: String, facility: String, location: String) {
        isMeterMode = true
        isMeterLocation = "$type / $facility / $location"
        pauseContinuousMonitoring()
        runOnUiThread {
            sttText.text = "📊 $type - $facility\n위치: $location\n\n사진 또는 영상을 촬영하고\nMeta AI 앱에서 가져오세요."
            tts.speak("사진 또는 영상을 촬영하고 Meta AI 앱에서 가져오세요", TextToSpeech.QUEUE_FLUSH, null, "insp_camera")
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (isMeterMode) {
                isMeterMode = false
                isMeterLocation = ""
                resumeContinuousMonitoring()
                runOnUiThread { sttText.text = "점검 촬영 취소됨.\n[터치하면 음성 녹음]" }
            }
        }, 60_000L)
    }

    private fun launchPhoneCamera(facility: String, inspectionType: String, location: String) {
        pendingInspectionType = "$inspectionType / $facility / $location"
        pendingInspectionLocation = location
        try {
            val photoFile = File(filesDir, "meter_photo.jpg").also { if (it.exists()) it.delete() }
            phoneCameraUri = FileProvider.getUriForFile(this, "${packageName}.provider", photoFile)
            phoneCameraLauncher.launch(phoneCameraUri!!)
        } catch (e: Exception) {
            Log.e("GlassAssist", "카메라 실행 실패: ${e.message}")
            android.widget.Toast.makeText(this, "카메라 실행 실패: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun loadInspectionKeywords() {
        try {
            val json = assets.open("inspection_keywords.json").bufferedReader().use { it.readText() }
            val root = org.json.JSONObject(json)
            val arr = root.getJSONArray("trigger_keywords")
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) list.add(arr.getString(i))
            inspectionTriggerKeywords = list
        } catch (e: Exception) {
            android.util.Log.e("GlassAssist", "inspection_keywords.json 로드 실패: ${e.message}")
        }
    }

    // 위험 감지 시 TTS 알림만 (영상은 안경 버튼으로 직접 촬영)
    private fun startDangerAlert() {
        // 5분 후 isHandlingDanger 자동 초기화 (안경 영상 연결 안 된 경우 대비)
        Handler(Looper.getMainLooper()).postDelayed({
            if (isHandlingDanger) {
                isHandlingDanger = false
                Log.d("GlassAssist", "위험 처리 타임아웃 - 초기화")
            }
        }, 300_000L)
    }

    private fun sendTextToServer(question: String) {
        pauseContinuousMonitoring()
        Thread {
            try {
                val jsonBody = JSONObject().put("text", question).toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$apiUrl/chat").post(jsonBody).build()
                val call = client.newCall(request)
                activeCall = call
                val response = call.execute()
                activeCall = null
                val responseBody = response.body?.string() ?: ""
                val answer = if (response.isSuccessful) {
                    try { JSONObject(responseBody).optString("answer", "응답 없음") } catch (e: Exception) { "응답 파싱 실패" }
                } else "서버 오류 (${response.code})"
                addQaRecord(question, answer)
                runOnUiThread {
                    sttText.text = "Q: $question\n\nA: $answer"
                    tts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "qa_${System.currentTimeMillis()}")
                    standbyHandler.removeCallbacks(standbyRunnable)
                    standbyHandler.postDelayed(standbyRunnable, 3000)
                    resumeContinuousMonitoring()
                }
            } catch (e: Exception) {
                Log.e("GlassAssist", "텍스트 전송 실패: ${e.message}")
                runOnUiThread { resumeContinuousMonitoring() }
            }
        }.start()
    }

    private fun startListeningIntent() {
        if (!isMonitoring || isRecording) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
        }
        continuousSpeechRecognizer?.startListening(intent)
    }

    private fun scheduleMonitorRestart() {
        monitorRestartHandler.postDelayed({ startListeningIntent() }, 200)
    }

    private fun pauseContinuousMonitoring() {
        monitorRestartHandler.removeCallbacksAndMessages(null)
        continuousSpeechRecognizer?.stopListening()
    }

    private fun resumeContinuousMonitoring() {
        if (isMonitoring) scheduleMonitorRestart()
    }

    private fun checkPermissions(): Boolean {
        val base = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
        else
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return (base + storage).all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val base = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        ActivityCompat.requestPermissions(this, base + storage, 1001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startContinuousMonitoring()
            registerGlassMediaObserver()
            registerMediaStoreObserver()
        } else {
            runOnUiThread { sttText.text = "권한이 필요합니다. 앱 설정에서 허용해주세요." }
        }
    }

    private fun generateWorkReport(): String {
        val now = SimpleDateFormat("yyyy년 M월 d일 HH:mm", Locale.KOREAN).format(Date())
        val handoverText = if (handoverList.isEmpty()) "없음"
        else handoverList.mapIndexed { index, memo -> "  ${index + 1}. $memo" }.joinToString("\n")

        return """
근무자: $userId
근무 종료: $now

📊 업무 통계
- 질의응답: ${qaList.size}건
- 접객보호 감지: ${protectionList.size}건
- 영상 전송: ${videoList.size}건
- 계량기 점검: ${meterList.size}건

⚠️ 접객보호 상세
${if (protectionList.isEmpty()) "없음" else protectionList.joinToString("\n") { "  [${it.time}] 키워드: ${it.keyword}" }}

💬 질의응답 상세
${if (qaList.isEmpty()) "없음" else qaList.joinToString("\n") { "  [${it.time}] Q: ${it.question.take(30)}" }}

📊 계량기 점검 상세
${if (meterList.isEmpty()) "없음" else meterList.joinToString("\n") { "  [${it.time}] 위치: ${it.location}" }}

📹 영상 전송
${if (videoList.isEmpty()) "없음" else videoList.joinToString("\n") { "  [${it.time}] 촬영 기록" }}

📝 인수인계 사항
$handoverText
        """.trimIndent()
    }

    private fun saveWorkReport(report: String) {
        try {
            val fileName = "근무일지_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREAN).format(Date())}.txt"
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val file = File(downloadsDir, fileName)
            file.writeText(report, Charsets.UTF_8)
            Log.d("GlassAssist", "근무 일지 저장: ${file.absolutePath}")
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    "근무 일지가 다운로드 폴더에 저장되었습니다.\n$fileName",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e("GlassAssist", "근무 일지 저장 실패: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == RESULT_OK && data != null) {
            val action = data.getStringExtra("action")
            val position = data.getIntExtra("position", -1)
            if (position >= 0) {
                when (action) {
                    "edit" -> {
                        val newContent = data.getStringExtra("newContent") ?: return
                        val oldContent = handoverList[position]
                        dbExecutor.execute { db.updateHandover(userId, oldContent, newContent) }
                        handoverList[position] = newContent
                        handoverAdapter.clear()
                        handoverList.forEachIndexed { i, s -> handoverAdapter.add("${i+1}. $s") }
                        handoverAdapter.notifyDataSetChanged()
                    }
                    "delete" -> {
                        val oldContent = handoverList[position]
                        dbExecutor.execute { db.deleteHandover(userId, oldContent) }
                        handoverList.removeAt(position)
                        handoverAdapter.clear()
                        handoverList.forEachIndexed { i, s -> handoverAdapter.add("${i+1}. $s") }
                        handoverAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun setupDashboard() {
        val rvNews = findViewById<RecyclerView>(R.id.rv_news)
        val tabNews = findViewById<TabLayout>(R.id.tab_news)

        newsAdapter = NewsAdapter(mutableListOf()) { item ->
            if (item.link.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link)))
        }
        rvNews.layoutManager = LinearLayoutManager(this)
        rvNews.adapter = newsAdapter

        val tab0View = LayoutInflater.from(this).inflate(R.layout.tab_news_header, null)
        tab0View.findViewById<TextView>(R.id.tv_tab_label).text = "네이버 뉴스"
        tab0View.findViewById<TextView>(R.id.tv_tab_more).setOnClickListener {
            startActivity(Intent(this, NewsActivity::class.java).apply { putExtra("source", "naver") })
        }

        val tab1View = LayoutInflater.from(this).inflate(R.layout.tab_news_header, null)
        tab1View.findViewById<TextView>(R.id.tv_tab_label).text = "KORAIL 뉴스"
        tab1View.findViewById<TextView>(R.id.tv_tab_more).setOnClickListener {
            startActivity(Intent(this, NewsActivity::class.java).apply { putExtra("source", "korail") })
        }

        tabNews.addTab(tabNews.newTab().setCustomView(tab0View))
        tabNews.addTab(tabNews.newTab().setCustomView(tab1View))

        tabNews.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (tab.position == 0) fetchNaverNews() else fetchKorailNews()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        fetchNaverNews()

        val features = listOf(
            FeatureItem(R.drawable.ic_qa, "질의응답", "qa"),
            FeatureItem(R.drawable.ic_protection, "접객보호", "protection"),
            FeatureItem(R.drawable.ic_video, "영상전송", "video"),
            FeatureItem(R.drawable.ic_meter, "계량기", "meter"),
            FeatureItem(R.drawable.ic_handover, "인수인계", "handover"),
            FeatureItem(0, "", "empty"),
            FeatureItem(0, "", "empty"),
            FeatureItem(0, "", "empty"),
            FeatureItem(0, "", "empty"),
        )
        val rvFeatures = findViewById<RecyclerView>(R.id.rv_features)
        rvFeatures.layoutManager = GridLayoutManager(this, 3)
        rvFeatures.isNestedScrollingEnabled = false
        rvFeatures.adapter = FeatureGridAdapter(features) { feature ->
            startActivity(Intent(this, RecordListActivity::class.java).apply {
                putExtra("type", feature.type)
                putExtra("label", feature.label)
                putExtra("userId", userId)
            })
        }
    }

    private fun fetchNaverNews() {
        Thread {
            try {
                val request = Request.Builder()
                    .url("https://openapi.naver.com/v1/search/news.json?query=철도&display=10&sort=date")
                    .addHeader("X-Naver-Client-Id", NAVER_CLIENT_ID)
                    .addHeader("X-Naver-Client-Secret", NAVER_CLIENT_SECRET)
                    .build()
                val response = client.newCall(request).execute()
                val body = if (response.isSuccessful) response.body?.string() else null
                response.close()
                if (body != null) {
                    val items = JSONObject(body).getJSONArray("items")
                    val news = (0 until minOf(items.length(), NEWS_PREVIEW_COUNT)).map {
                        val obj = items.getJSONObject(it)
                        NewsItem(
                            title = obj.getString("title"),
                            date = obj.getString("pubDate").take(16),
                            link = obj.optString("originallink").ifEmpty { obj.optString("link") }
                        )
                    }
                    runOnUiThread { newsAdapter.update(news) }
                }
            } catch (e: Exception) {
                Log.e("GlassAssist", "네이버 뉴스 실패", e)
            }
        }.start()
    }

    private fun fetchKorailNews() {
        Thread {
            try {
                val request = Request.Builder()
                    .url("https://openapi.naver.com/v1/search/news.json?query=코레일&display=10&sort=date")
                    .addHeader("X-Naver-Client-Id", NAVER_CLIENT_ID)
                    .addHeader("X-Naver-Client-Secret", NAVER_CLIENT_SECRET)
                    .build()
                val response = client.newCall(request).execute()
                val body = if (response.isSuccessful) response.body?.string() else null
                response.close()
                if (body != null) {
                    val items = JSONObject(body).getJSONArray("items")
                    val news = (0 until minOf(items.length(), NEWS_PREVIEW_COUNT)).map {
                        val obj = items.getJSONObject(it)
                        NewsItem(
                            title = obj.getString("title"),
                            date = obj.getString("pubDate").take(16),
                            link = obj.optString("originallink").ifEmpty { obj.optString("link") }
                        )
                    }
                    runOnUiThread { newsAdapter.update(news) }
                }
            } catch (e: Exception) {
                Log.e("GlassAssist", "KORAIL 뉴스 실패", e)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, GlassAssistService::class.java))
        isMonitoring = false
        monitorRestartHandler.removeCallbacksAndMessages(null)
        standbyHandler.removeCallbacksAndMessages(null)
        wakeTimeoutHandler.removeCallbacksAndMessages(null)
        continuousSpeechRecognizer?.destroy()
        glassFileObserver?.stopWatching()
        mediaStoreObserver?.let { contentResolver.unregisterContentObserver(it) }
        unregisterReceiver(scoReceiver)
        if (isBluetoothScoOn) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }
        tts.stop()
        tts.shutdown()
        messageServer?.stop()
        webSocket?.close(1000, "앱 종료")
        dispatchWebSocket?.close(1000, "앱 종료")
        client.dispatcher.executorService.shutdown()
        audioRecord?.release()
        dbExecutor.shutdown()
    }
}
