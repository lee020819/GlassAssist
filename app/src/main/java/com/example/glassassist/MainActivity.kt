package com.example.glassassist

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioTrack
import android.os.FileObserver
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
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
import android.widget.Toast
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
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

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
    private val sampleRate = 8000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var continuousSpeechRecognizer: SpeechRecognizer? = null
    private var isMonitoring = false
    private val monitorRestartHandler = Handler(Looper.getMainLooper())

    private var isBluetoothScoOn = false
    private var isScoConnecting = false
    private var scoKeepAliveTrack: AudioTrack? = null
    private var isHandlingDanger = false
    private var protectionStreamJob: kotlinx.coroutines.Job? = null
    private var isTranslationMode = false
    private var glassStream: Stream? = null
    private val scoRetryHandler = Handler(Looper.getMainLooper())
    private var glassFileObserver: FileObserver? = null
    private var mediaStoreObserver: ContentObserver? = null
    private val processedGlassFiles = mutableSetOf<String>()

    private var isMeterMode = false
    private var pendingMeterItem: RecordItem? = null
    private var isHandoverMode = false
    private var recognitionListener: RecognitionListener? = null

    private enum class InspectionStep { IDLE, WAITING_TYPE, WAITING_LOCATION }
    private var inspectionStep = InspectionStep.IDLE
    private var inspectionFacility = ""
    private var inspectionType = ""
    private val inspectionTypeMap = mapOf(
        "일상" to "일상점검", "정기" to "정기점검", "전기" to "정기점검",
        "수시" to "수시점검", "특별" to "특별점검", "긴급" to "긴급점검"
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
    private lateinit var glassStatusText: TextView

    companion object {
        private const val NAVER_CLIENT_ID = "YkmvsxAbXEj1AOeuGW0n"
        private const val NAVER_CLIENT_SECRET = "H4fKvtP0Fj"
        private const val TELEGRAM_BOT_TOKEN = "8968419516:AAHfm3bGV9EzAxN8Wda3UPoNXPhRN2igBLQ"
        private const val TELEGRAM_CHAT_ID = "8633866058"
        private const val NEWS_PREVIEW_COUNT = 3
        private val META_AI_DIR = "/storage/emulated/0/Download/Meta AI"
        // 안경 파일명 형식: 20260517_141507_hash.mp4
        private val GLASS_FILE_REGEX = Regex("""^(\d{8})_(\d{6})_[0-9a-f]+\.(mp4|mov|jpg|jpeg|png)$""")
        val dispatchLog = mutableListOf<String>()
        var lastDispatchUrl: String? = null
        val translationLog = mutableListOf<String>()
        var translationModeActive = false
    }

    private val standbyHandler = Handler(Looper.getMainLooper())
    private val standbyRunnable: Runnable = object : Runnable {
        override fun run() {
            if (isRecording) return
            if (tts.isSpeaking) {
                standbyHandler.postDelayed(this, 1000)
                return
            }
            tts.speak("음성인식 대기 중입니다", TextToSpeech.QUEUE_FLUSH, null, "standby")
            runOnUiThread { sttText.text = "음성인식 대기 중...\n[터치하면 음성 녹음]" }
            monitorRestartHandler.postDelayed({ resumeContinuousMonitoring() }, 3500)
        }
    }

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    isScoConnecting = false
                    isBluetoothScoOn = true
                    Log.d("GlassAssist", "안경 SCO 연결 완료")
                    reinitTtsForSco()
                    runOnUiThread {
                        glassStatusText.text = "● 글라스 연결됨"
                        glassStatusText.setTextColor(0xFF4CAF50.toInt())
                    }
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    isScoConnecting = false
                    isBluetoothScoOn = false
                    stopScoKeepAlive()
                    Log.d("GlassAssist", "안경 SCO 끊김, 재연결 시도")
                    startScoRetry()
                    runOnUiThread {
                        glassStatusText.text = "● 글라스 미연결"
                        glassStatusText.setTextColor(0xFFEF9A9A.toInt())
                    }
                }
            }
        }
    }

    private fun reinitTtsForSco() {
        tts.stop()
        tts.shutdown()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.KOREAN
                audioManager.setStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                    0
                )
                startScoKeepAlive()
                messageServer?.stop()
                messageServer = MessageServer(tts)
                try { messageServer?.start() } catch (e: Exception) {
                    Log.e("GlassAssist", "MessageServer 시작 실패: ${e.message}")
                    messageServer = null
                }
            }
            if (!isRecording) resumeContinuousMonitoring()
        }
    }

    private fun startScoKeepAlive() {
        stopScoKeepAlive()
        val sampleRate = 8000
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        scoKeepAliveTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        scoKeepAliveTrack?.play()
        val silence = ShortArray(bufferSize / 2)
        Thread {
            val track = scoKeepAliveTrack
            while (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.write(silence, 0, silence.size)
            }
        }.also { it.isDaemon = true }.start()
        Log.d("GlassAssist", "SCO keep-alive 시작")
    }

    private fun stopScoKeepAlive() {
        scoKeepAliveTrack?.stop()
        scoKeepAliveTrack?.release()
        scoKeepAliveTrack = null
    }

    private fun startScoRetry() {
        scoRetryHandler.removeCallbacksAndMessages(null)
        if (isBluetoothScoOn) return
        tryActivateBluetoothSco()
        // 10초 후 재시도 (연결 성공 시 scoReceiver에서 handler 취소됨)
        scoRetryHandler.postDelayed({
            isScoConnecting = false
            if (!isBluetoothScoOn) startScoRetry()
        }, 10000)
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
        glassStatusText = findViewById(R.id.glass_status_text)
        sttText = findViewById(R.id.stt_text)
        sttText.text = "대기 중...\n[터치하면 음성 녹음]"

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
                try { messageServer?.start() } catch (e: Exception) {
                    Log.e("GlassAssist", "MessageServer 시작 실패: ${e.message}")
                    messageServer = null
                }
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
            monitorRestartHandler.postDelayed({ resumeContinuousMonitoring() }, 3500)
        }

        findViewById<TextView>(R.id.btn_stop).setOnClickListener {
            standbyHandler.removeCallbacks(standbyRunnable)
            tts.stop()
            if (isRecording) stopRecordingAndSend()
            isHandlingDanger = false
            isTranslationMode = false; translationModeActive = false
            inspectionStep = InspectionStep.IDLE
            isMeterMode = false
            stopProtectionStream()
            pauseContinuousMonitoring()
            sttText.text = "음성인식 대기 중...\n[터치하면 음성 녹음]"
            tts.speak("대기중입니다", TextToSpeech.QUEUE_FLUSH, null, "stop_standby")
            monitorRestartHandler.postDelayed({ resumeContinuousMonitoring() }, 3500)
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
        initGlassStream()
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
                pendingMeterItem = meterList.firstOrNull { it.videoUri == null }
                if (pendingMeterItem != null) {
                    Log.d("GlassAssist", "pendingMeterItem 복원: ${pendingMeterItem?.location}")
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
            Log.w("GlassAssist", "Meta AI 폴더 없음, 생성 시도: $META_AI_DIR")
            dir.mkdirs()
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
        val cutoffSec = (System.currentTimeMillis() / 1000) - 300
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
        val bucketCol = "bucket_display_name"
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
                val pending = pendingMeterItem
                if (pending != null && pending.videoUri == null) {
                    pending.videoUri = imageUri
                    pendingMeterItem = null
                    isMeterMode = false
                    isMeterLocation = ""
                    resumeContinuousMonitoring()
                    dbExecutor.execute { db.updateMeterPhoto(userId, pending.date, pending.time, pending.location, imageUri) }
                    runOnUiThread {
                        meterAdapter.clear()
                        meterList.forEach { meterAdapter.add("${it.date} ${it.time} - ${it.location}") }
                        meterAdapter.notifyDataSetChanged()
                        sttText.text = "📊 점검 저장됨\n[터치하면 음성 녹음]"
                        tts.speak("사진이 저장되었습니다", TextToSpeech.QUEUE_FLUSH, null, "insp_saved")
                    }
                } else {
                    // 대기 중인 점검 기록 없음 → 실시간 영상전송 탭에 저장
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
        val pendingForVideo = pendingMeterItem
        if (isMeterMode || (pendingForVideo != null && pendingForVideo.videoUri == null)) {
            isMeterMode = false
            resumeContinuousMonitoring()
            android.media.MediaScannerConnection.scanFile(
                this, arrayOf(file.absolutePath), arrayOf("video/mp4")
            ) { _, uri ->
                val videoUri = uri?.toString() ?: return@scanFile
                if (pendingForVideo != null && pendingForVideo.videoUri == null) {
                    pendingForVideo.videoUri = videoUri
                    pendingMeterItem = null
                    isMeterLocation = ""
                    dbExecutor.execute { db.updateMeterPhoto(userId, pendingForVideo.date, pendingForVideo.time, pendingForVideo.location, videoUri) }
                } else {
                    val now = Date()
                    val item = RecordItem(
                        date = dateFormat.format(now), time = timeFormat.format(now),
                        location = isMeterLocation, videoUri = videoUri
                    )
                    meterList.add(0, item)
                    dbExecutor.execute { db.insertMeter(userId, item.date, item.time, isMeterLocation, videoUri) }
                }
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
                stopProtectionStream()
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
                .setMessage("📋 근무 일지\n\n$report\n\n이메일 발송 후 기록을 삭제하시겠습니까?")
                .setPositiveButton("발송 후 삭제") { _, _ ->
                    saveWorkReport(report)
                    sendWorkReportEmail(report)
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
                .setNeutralButton("이메일만 발송") { _, _ ->
                    sendWorkReportEmail(report)
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
        sendTelegramAlert(keyword, item.date, item.time)
    }

    private fun sendTelegramAlert(keyword: String, date: String, time: String) {
        val message = "🚨 [GlassAssist 위험 감지]\n역무원 ID: $userId\n키워드: $keyword\n시각: $date $time"
        Thread {
            try {
                val body = org.json.JSONObject()
                    .put("chat_id", TELEGRAM_CHAT_ID)
                    .put("text", message)
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                runOnUiThread {
                    if (response.isSuccessful)
                        Toast.makeText(this, "텔레그램 관제실 알림 발송됨", Toast.LENGTH_SHORT).show()
                    else
                        Toast.makeText(this, "텔레그램 발송 실패 (${response.code})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("GlassAssist", "텔레그램 알림 실패: ${e.message}")
                runOnUiThread { Toast.makeText(this, "텔레그램 연결 실패", Toast.LENGTH_SHORT).show() }
            }
        }.start()
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

    private fun fetchDangerKeywordsFromServer() {
        val wsUrl = userPrefs.dispatchWsUrl ?: return
        val httpUrl = wsUrl.replace(Regex("^ws://"), "http://").substringBefore("/ws")
        Thread {
            try {
                val request = Request.Builder().url("$httpUrl/keywords").build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val arr = JSONObject(response.body?.string() ?: "").optJSONArray("keywords") ?: return@Thread
                    val list = (0 until arr.length()).map { arr.getString(it) }
                    KeywordDetector.setServerKeywords(list)
                    Log.d("GlassAssist", "서버 키워드 로드: ${list.size}개")
                    runOnUiThread {
                        Toast.makeText(this, "위험 키워드 ${list.size}개 동기화됨", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("GlassAssist", "키워드 로드 실패: ${e.message}")
            }
        }.start()
    }

    private fun connectDispatchWebSocket() {
        val url = userPrefs.dispatchWsUrl ?: return
        dispatchWebSocket?.close(1000, "재연결")
        val request = Request.Builder().url(url).build()
        dispatchWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastDispatchUrl = url
                fetchDangerKeywordsFromServer()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val now = timeFormat.format(java.util.Date())
                    when (json.optString("type")) {
                        "dispatch" -> {
                            val message = json.getString("message")
                            dispatchLog.add(0, "[$now] 📥 관제실: $message")
                            runOnUiThread {
                                tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "dispatch_${System.currentTimeMillis()}")
                                sttText.text = "관제실: $message\n[터치하면 음성 녹음]"
                            }
                        }
                        "audio_to_worker" -> {
                            val audioData = json.getString("data")
                            val from = json.optString("from", "관제실")
                            dispatchLog.add(0, "[$now] 📥 $from: 음성 수신")
                            playDispatchAudio(audioData)
                        }
                        "keywords_updated" -> {
                            val arr = json.optJSONArray("keywords") ?: return
                            val list = (0 until arr.length()).map { arr.getString(it) }
                            KeywordDetector.setServerKeywords(list)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GlassAssist", "관제 메시지 파싱 오류: ${e.message}")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lastDispatchUrl = null
                Handler(Looper.getMainLooper()).postDelayed({ connectDispatchWebSocket() }, 5000)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                lastDispatchUrl = null
            }
        })
    }

    private fun startRecording() {
        if (isRecording) return
        pauseContinuousMonitoring()

        if (isBluetoothScoOn) {
            startRecordingInternal()
            return
        }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isBluetoothScoOn) {
            val scoInput = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (scoInput != null) {
                audioRecord?.setPreferredDevice(scoInput)
                Log.d("GlassAssist", "안경 마이크 명시 설정: ${scoInput.productName}")
            } else {
                Log.d("GlassAssist", "SCO 입력 장치 없음 → 폰 마이크 사용")
            }
        }

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

        // SCO 유지 — 안경 마이크를 연속 모니터링에서도 계속 사용
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
                        sttText.text = "긴급 상황 감지!\n\n안경 버튼을 눌러 영상을 녹화하세요.\n녹화 후 '대기'라고 말하면 다른 기능 사용 가능.\n영상은 동기화되면 자동 저장됩니다."
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
        recognitionListener = object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                var text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: run { scheduleMonitorRestart(); return }

                text = correctSttText(text)

                runOnUiThread { sttText.text = "[STT] $text" }

                // 전역 리셋: 어떤 상태에서든 "대기" 또는 "중지" 외치면 초기화
                if (text.replace(" ", "").let {
                        it.contains("대기") || it.contains("중지") || it.contains("그만")
                    }) {
                    resetToStandby()
                    return
                }

                // 인수인계 내용 수신
                if (isHandoverMode) {
                    isHandoverMode = false
                    val now = Date()
                    dbExecutor.execute { db.insertHandover(userId, dateFormat.format(now), timeFormat.format(now), text) }
                    handoverList.add(text)
                    runOnUiThread {
                        handoverAdapter.clear()
                        handoverList.forEachIndexed { i, s -> handoverAdapter.add("${i+1}. $s") }
                        handoverAdapter.notifyDataSetChanged()
                        sttText.text = "인수인계 저장됨: $text\n[터치하면 음성 녹음]"
                        tts.speak("인수인계가 저장되었습니다", TextToSpeech.QUEUE_FLUSH, null, "handover_saved")
                    }
                    scheduleMonitorRestart()
                    return
                }

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

                if (isTranslationMode && !isHandlingDanger && text.length >= 2) {
                    val allResults = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: listOf(text)
                    val isEndCommand = allResults.any { r ->
                        val n = r.replace(" ", "").lowercase()
                        n.contains("번역종료") || n.contains("통역종료") ||
                            (n.contains("번역") && n.contains("종료")) ||
                            n.contains("stop") || n.contains("exit") || n.contains("end")
                    }
                    if (isEndCommand) {
                        isTranslationMode = false
                        translationModeActive = false
                        runOnUiThread {
                            sttText.text = "대기 중...\n[터치하면 음성 녹음]"
                            tts.speak("번역 모드를 종료합니다", TextToSpeech.QUEUE_FLUSH, null, "tmode_off")
                        }
                        resumeContinuousMonitoring()
                        return
                    }
                    translateAndSpeak(text)
                    val waitForTts = object : Runnable {
                        override fun run() {
                            if (tts.isSpeaking) {
                                monitorRestartHandler.postDelayed(this, 500)
                            } else {
                                monitorRestartHandler.postDelayed({ startListeningIntent() }, 600)
                            }
                        }
                    }
                    monitorRestartHandler.postDelayed(waitForTts, 1000)
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
                            sttText.text = "긴급 상황 감지!\n\n안경 버튼을 눌러 영상을 녹화하세요.\n녹화 후 '대기'라고 말하면 다른 기능 사용 가능.\n영상은 동기화되면 자동 저장됩니다."
                            tts.speak(detector.getAlertMessage(detection), TextToSpeech.QUEUE_FLUSH, null, "alert_${System.currentTimeMillis()}")
                        }
                    } else if (inspectionTriggerKeywords.any { text.contains(it) }) {
                        inspectionFacility = inspectionTriggerKeywords.first { text.contains(it) }
                        inspectionStep = InspectionStep.WAITING_TYPE
                        runOnUiThread {
                            sttText.text = "점검 유형?\n일상 / 정기 / 수시 / 특별 / 긴급"
                            tts.speak("일상, 정기, 수시, 특별, 긴급 중 어떤 점검인가요?", TextToSpeech.QUEUE_FLUSH, null, "insp_type")
                        }
                    } else if (text.replace(" ", "").let { it.contains("관제호출") || it.contains("관제통화") }) {
                        startDispatchRecording()
                        return
                    } else if (text.replace(" ", "").let { it.contains("통역시작") || it.contains("번역시작") }) {
                        isTranslationMode = true
                        translationModeActive = true
                        runOnUiThread {
                            sttText.text = "번역 모드 시작\n외국어로 말씀해 주세요"
                            tts.speak("번역 모드를 시작합니다", TextToSpeech.QUEUE_FLUSH, null, "tmode_on")
                        }
                    } else if (text.replace(" ", "").contains("인수인계")) {
                        isHandoverMode = true
                        runOnUiThread {
                            sttText.text = "인수인계 내용을 말씀해 주세요"
                            tts.speak("인수인계 내용을 말씀해 주세요", TextToSpeech.QUEUE_FLUSH, null, "handover_prompt")
                        }
                    } else if (text.length >= 5) {
                        sendTextToServer(text)
                        return
                    }
                }
                scheduleMonitorRestart()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                // 전역 리셋: 어떤 상태에서든(접객보호 포함) "대기/중지/그만/취소/멈춰" 감지 시 즉시 초기화
                if (text.replace(" ", "").let {
                        it.contains("대기") || it.contains("중지") || it.contains("그만") ||
                        it.contains("취소") || it.contains("멈춰")
                    }) {
                    resetToStandby()
                    return
                }
                if (isHandlingDanger) return
                val detector = KeywordDetector(this@MainActivity)
                val detection = detector.detect(text)
                if (detection.detected) {
                    isHandlingDanger = true
                    addProtectionRecord(detection.matchedKeyword)
                    startDangerAlert()
                    runOnUiThread {
                        sttText.text = "긴급 상황 감지!\n\n안경 버튼을 눌러 영상을 녹화하세요.\n녹화 후 '대기'라고 말하면 다른 기능 사용 가능.\n영상은 동기화되면 자동 저장됩니다."
                        tts.speak(detector.getAlertMessage(detection), TextToSpeech.QUEUE_FLUSH, null, "alert_${System.currentTimeMillis()}")
                    }
                }
            }

            override fun onError(error: Int) {
                scheduleMonitorRestart()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        continuousSpeechRecognizer?.setRecognitionListener(recognitionListener)
        startListeningIntent()
        startScoRetry()
    }

    private fun initGlassStream() {
        Thread {
            try {
                Wearables.initialize(this).onSuccess {
                    Wearables.createSession(AutoDeviceSelector()).onSuccess { session ->
                        session.addStream(StreamConfiguration()).onSuccess { stream ->
                            stream.start()
                            glassStream = stream
                            Log.d("GlassAssist", "안경 SDK 스트림 연결 완료")
                        }.onFailure { err ->
                            Log.e("GlassAssist", "스트림 생성 실패: $err")
                        }
                    }.onFailure { err ->
                        Log.e("GlassAssist", "세션 생성 실패: $err")
                    }
                }.onFailure { err ->
                    Log.e("GlassAssist", "SDK 초기화 실패: $err")
                }
            } catch (e: Exception) {
                Log.e("GlassAssist", "안경 SDK 초기화 오류: ${e.message}")
            }
        }.start()
    }

    private fun savePhotoAndRecord(photoData: PhotoData, location: String) {
        try {
            val now = Date()
            val fileName = "meter_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)}.jpg"
            val file = File(getExternalFilesDir("glass_media") ?: filesDir, fileName).also {
                it.parentFile?.mkdirs()
            }
            when (photoData) {
                is PhotoData.Bitmap -> file.outputStream().use { out ->
                    photoData.bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                }
                is PhotoData.HEIC -> {
                    val buf = photoData.data
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    file.writeBytes(bytes)
                }
            }
            val imageUri = FileProvider.getUriForFile(this, "${packageName}.provider", file).toString()
            isMeterMode = false
            val pending = pendingMeterItem
            pendingMeterItem = null
            if (pending != null) {
                pending.videoUri = imageUri
                dbExecutor.execute { db.updateMeterPhoto(userId, pending.date, pending.time, pending.location, imageUri) }
            } else {
                val item = RecordItem(date = dateFormat.format(now), time = timeFormat.format(now), location = location, videoUri = imageUri)
                meterList.add(0, item)
                dbExecutor.execute { db.insertMeter(userId, item.date, item.time, location, imageUri) }
            }
            runOnUiThread {
                meterAdapter.clear()
                meterList.forEach { meterAdapter.add("${it.date} ${it.time} - ${it.location}") }
                meterAdapter.notifyDataSetChanged()
                sttText.text = "📊 점검 사진 저장됨\n[터치하면 음성 녹음]"
                tts.speak("사진이 저장되었습니다", TextToSpeech.QUEUE_FLUSH, null, "meter_sdk_saved")
                standbyHandler.removeCallbacks(standbyRunnable)
                standbyHandler.postDelayed(standbyRunnable, 3000)
            }
            resumeContinuousMonitoring()
        } catch (e: Exception) {
            Log.e("GlassAssist", "SDK 사진 저장 실패: ${e.message}")
            runOnUiThread { tts.speak("저장 실패, 직접 촬영해 주세요", TextToSpeech.QUEUE_FLUSH, null, "meter_fail") }
            resumeContinuousMonitoring()
        }
    }

    private fun addMeterRecord(location: String): RecordItem {
        val now = Date()
        val item = RecordItem(date = dateFormat.format(now), time = timeFormat.format(now), location = location)
        pendingMeterItem = item
        meterList.add(0, item)
        dbExecutor.execute { db.insertMeter(userId, item.date, item.time, location, null) }
        runOnUiThread {
            meterAdapter.clear()
            meterList.forEach { meterAdapter.add("${it.date} ${it.time} - ${it.location}") }
            meterAdapter.notifyDataSetChanged()
        }
        return item
    }

    private fun activateInspectionCameraMode(type: String, facility: String, location: String) {
        val loc = "$type / $facility / $location"
        isMeterLocation = loc
        addMeterRecord(loc)
        pauseContinuousMonitoring()

        val stream = glassStream
        if (stream != null) {
            isMeterMode = false
            runOnUiThread {
                sttText.text = "📊 $type - $facility\n위치: $location\n\n안경으로 촬영 중..."
                tts.speak("사진을 촬영합니다", TextToSpeech.QUEUE_FLUSH, null, "insp_sdk")
            }
            lifecycleScope.launch {
                val result = stream.capturePhoto()
                result.onSuccess { photoData -> savePhotoAndRecord(photoData, loc) }
                result.onFailure { _ ->
                    Log.w("GlassAssist", "SDK 촬영 실패 → 수동 모드 전환")
                    isMeterMode = true
                    runOnUiThread {
                        sttText.text = "📊 $type - $facility\n위치: $location\n\n안경 버튼을 누르거나\nMeta AI 앱에서 가져오세요."
                        tts.speak("직접 촬영해 주세요", TextToSpeech.QUEUE_FLUSH, null, "insp_manual")
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isMeterMode) { isMeterMode = false; isMeterLocation = ""; resumeContinuousMonitoring() }
                    }, 60_000L)
                }
            }
        } else {
            isMeterMode = true
            runOnUiThread {
                sttText.text = "📊 $type - $facility\n위치: $location\n\n사진 또는 영상을 촬영하고\nMeta AI 앱에서 가져오세요."
                tts.speak("사진 또는 영상을 촬영하고 Meta AI 앱에서 가져오세요", TextToSpeech.QUEUE_FLUSH, null, "insp_camera")
            }
            Handler(Looper.getMainLooper()).postDelayed({
                if (isMeterMode) { isMeterMode = false; isMeterLocation = ""; resumeContinuousMonitoring()
                    runOnUiThread { sttText.text = "점검 촬영 취소됨.\n[터치하면 음성 녹음]" }
                }
            }, 60_000L)
        }
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
        startProtectionStream()
        Handler(Looper.getMainLooper()).postDelayed({
            if (isHandlingDanger) {
                isHandlingDanger = false
                stopProtectionStream()
                Log.d("GlassAssist", "위험 처리 타임아웃 - 초기화")
            }
        }, 300_000L)
    }

    private fun startProtectionStream() {
        val keyword = protectionList.firstOrNull()?.keyword ?: ""
        dispatchWebSocket?.send(
            org.json.JSONObject()
                .put("type", "protection_alert")
                .put("from", userId)
                .put("keyword", keyword)
                .toString()
        )
        val stream = glassStream ?: return
        protectionStreamJob = lifecycleScope.launch {
            launch {
                stream.videoStream.collect { frame: VideoFrame ->
                    val ws = dispatchWebSocket ?: return@collect
                    val buf = frame.buffer.duplicate()
                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    val frameJson = org.json.JSONObject()
                    frameJson.put("type", "video_frame")
                    frameJson.put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    frameJson.put("isCodecConfig", frame.isCodecConfig)
                    frameJson.put("pts", frame.presentationTimeUs)
                    frameJson.put("from", userId)
                    ws.send(frameJson.toString())
                }
            }
            launch {
                var linkedToRecord = false
                while (true) {
                    kotlinx.coroutines.delay(4000)
                    try {
                        stream.capturePhoto().onSuccess { photoData ->
                            val bytes = when (photoData) {
                                is PhotoData.Bitmap -> {
                                    val baos = java.io.ByteArrayOutputStream()
                                    photoData.bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                                    baos.toByteArray()
                                }
                                is PhotoData.HEIC -> {
                                    val buf = photoData.data.duplicate()
                                    ByteArray(buf.remaining()).also { buf.get(it) }
                                }
                            }
                            // 관제 서버로 전송
                            dispatchWebSocket?.send(
                                org.json.JSONObject()
                                    .put("type", "protection_snapshot")
                                    .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                                    .put("from", userId)
                                    .toString()
                            )
                            // 첫 스냅샷을 로컬 저장 후 접객보호 기록에 자동 연결
                            if (!linkedToRecord) {
                                linkedToRecord = true
                                val now = Date()
                                val fileName = "protection_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)}.jpg"
                                val dir = getExternalFilesDir("protection") ?: filesDir
                                dir.mkdirs()
                                val file = File(dir, fileName)
                                file.writeBytes(bytes)
                                val imageUri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.provider", file).toString()
                                val target = protectionList.firstOrNull { it.videoUri == null }
                                if (target != null) {
                                    target.videoUri = imageUri
                                    dbExecutor.execute { db.linkProtectionVideo(userId, target.timestampMs, imageUri) }
                                    runOnUiThread {
                                        protectionAdapter.clear()
                                        protectionList.forEach { protectionAdapter.add("${it.date} ${it.time} ${it.keyword}") }
                                        protectionAdapter.notifyDataSetChanged()
                                        sttText.text = "📸 접객보호 사진 자동 저장됨\n[터치하면 음성 녹음]"
                                        tts.speak("증거 사진이 자동으로 저장되었습니다", TextToSpeech.QUEUE_FLUSH, null, "protection_saved")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("GlassAssist", "스냅샷 전송 실패: ${e.message}")
                    }
                }
            }
        }
    }

    private fun stopProtectionStream() {
        protectionStreamJob?.cancel()
        protectionStreamJob = null
        dispatchWebSocket?.send(
            org.json.JSONObject().put("type", "protection_end").put("from", userId).toString()
        )
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

    private fun startDispatchRecording() {
        if (userPrefs.dispatchWsUrl == null) {
            tts.speak("관제실이 연결되지 않았습니다. QR 코드를 스캔해 주세요", TextToSpeech.QUEUE_FLUSH, null, "dispatch_no_conn")
            return
        }
        pauseContinuousMonitoring()
        Thread {
            runOnUiThread {
                tts.speak("관제실 연결, 말씀하세요", TextToSpeech.QUEUE_FLUSH, null, "dispatch_start")
                sttText.text = "관제실에 전송 중...\n(말씀 후 잠시 침묵하면 자동 전송)"
            }
            Thread.sleep(1500)

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Handler(Looper.getMainLooper()).post { resumeContinuousMonitoring() }
                return@Thread
            }

            val audioSource = if (isBluetoothScoOn) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                              else MediaRecorder.AudioSource.MIC
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val recorder = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isBluetoothScoOn) {
                val scoInput = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (scoInput != null) recorder.setPreferredDevice(scoInput)
            }

            recorder.startRecording()
            val allData = mutableListOf<Short>()
            val buffer = ShortArray(bufferSize)
            var silentSamples = 0
            val silenceLimit = sampleRate * 2
            val maxSamples = sampleRate * 20

            while (allData.size < maxSamples) {
                val read = recorder.read(buffer, 0, bufferSize)
                if (read <= 0) break
                allData.addAll(buffer.take(read).toList())
                val rms = kotlin.math.sqrt(buffer.take(read).map { it.toDouble() * it }.average())
                silentSamples = if (rms < 700) silentSamples + read else 0
                if (silentSamples >= silenceLimit) break
            }

            recorder.stop()
            recorder.release()

            val tempPath = "${externalCacheDir?.absolutePath}/dispatch_out.wav"
            saveAsWav(allData.toShortArray(), tempPath)
            val base64 = android.util.Base64.encodeToString(java.io.File(tempPath).readBytes(), android.util.Base64.NO_WRAP)
            val now = timeFormat.format(java.util.Date())
            val payload = JSONObject().put("type", "audio_from_worker").put("data", base64).put("from", userId).toString()
            val sent = dispatchWebSocket?.send(payload) ?: false

            runOnUiThread {
                if (sent) {
                    dispatchLog.add(0, "[$now] 📤 나 → 관제실: 음성 전송")
                    sttText.text = "관제실에 전송됐습니다.\n[터치하면 음성 녹음]"
                    tts.speak("관제실에 전송됐습니다", TextToSpeech.QUEUE_FLUSH, null, "dispatch_sent")
                    startActivity(Intent(this@MainActivity, DispatchActivity::class.java))
                } else {
                    tts.speak("관제실 연결을 확인하세요", TextToSpeech.QUEUE_FLUSH, null, "dispatch_fail")
                }
            }
            Handler(Looper.getMainLooper()).postDelayed({ resumeContinuousMonitoring() }, 2000)
        }.start()
    }

    private fun playDispatchAudio(base64Data: String) {
        Thread {
            try {
                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                val tempFile = java.io.File(cacheDir, "dispatch_in.webm")
                tempFile.writeBytes(bytes)
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(tempFile.absolutePath)
                mp.prepare()
                mp.start()
                mp.setOnCompletionListener { mp.release() }
                runOnUiThread { sttText.text = "관제실 음성 수신\n[터치하면 음성 녹음]" }
            } catch (e: Exception) {
                Log.e("GlassAssist", "관제 오디오 재생 실패: ${e.message}")
            }
        }.start()
    }

    private fun getDemoLocationHint(translated: String, langCode: String): Pair<String, java.util.Locale>? {
        val locale = when (langCode) {
            "en" -> java.util.Locale.ENGLISH
            "zh-CN", "zh-TW" -> java.util.Locale.CHINESE
            "ja" -> java.util.Locale.JAPANESE
            "de" -> java.util.Locale.GERMAN
            "fr" -> java.util.Locale.FRENCH
            else -> java.util.Locale.ENGLISH
        }
        val hint = when {
            translated.contains("화장실") -> when (langCode) {
                "en" -> "The nearest restroom is on the 3rd floor, north side."
                "zh-CN", "zh-TW" -> "最近的洗手间在3楼北侧。"
                "ja" -> "最寄りのトイレは3階北側です。"
                "de" -> "Die nächste Toilette ist im 3. Stock auf der Nordseite."
                "fr" -> "Les toilettes les plus proches sont au 3ème étage, côté nord."
                else -> "The nearest restroom is on the 3rd floor."
            }
            translated.contains("출구") -> when (langCode) {
                "en" -> "The nearest exit is Exit number 2."
                "zh-CN", "zh-TW" -> "最近的出口是2号出口。"
                "ja" -> "最寄りの出口は2番出口です。"
                "de" -> "Der nächste Ausgang ist Ausgang Nummer 2."
                "fr" -> "La sortie la plus proche est la sortie numéro 2."
                else -> "The nearest exit is Exit number 2."
            }
            translated.contains("매표소") || translated.contains("승차권") || translated.contains("티켓") -> when (langCode) {
                "en" -> "The ticket office is on the 1st floor, center."
                "zh-CN", "zh-TW" -> "售票处在1楼中央。"
                "ja" -> "切符売り場は1階中央です。"
                else -> "The ticket office is on the 1st floor."
            }
            translated.contains("엘리베이터") -> when (langCode) {
                "en" -> "The elevator is at the east end of the platform."
                "zh-CN", "zh-TW" -> "电梯在站台东端。"
                "ja" -> "エレベーターはホームの東端にあります。"
                else -> "The elevator is at the east end of the platform."
            }
            translated.contains("환승") -> when (langCode) {
                "en" -> "Transfer to Line 2 is in zone B."
                "zh-CN", "zh-TW" -> "换乘2号线请前往B区。"
                "ja" -> "2号線への乗り換えはBゾーンです。"
                else -> "Transfer is in zone B."
            }
            translated.contains("편의점") -> when (langCode) {
                "en" -> "The convenience store is on the 2nd floor, center."
                "zh-CN", "zh-TW" -> "便利店在2楼中央。"
                "ja" -> "コンビニは2階中央にあります。"
                else -> "The convenience store is on the 2nd floor."
            }
            translated.contains("분실") || translated.contains("잃어버") -> when (langCode) {
                "en" -> "Please go to the lost and found at the station office on the 1st floor."
                "zh-CN", "zh-TW" -> "请前往1楼站务室的失物招领处。"
                "ja" -> "1階駅務室の遺失物センターへお越しください。"
                else -> "Please go to the lost and found on the 1st floor."
            }
            translated.contains("응급") || translated.contains("다쳤") || translated.contains("아파") -> when (langCode) {
                "en" -> "Please wait. I will guide you to the station office immediately."
                "zh-CN", "zh-TW" -> "请稍等，我立刻带您去站务室。"
                "ja" -> "少々お待ちください。すぐに駅務室へご案内します。"
                else -> "Please wait. I will help you immediately."
            }
            else -> null
        }
        return hint?.let { Pair(it, locale) }
    }

    private fun detectLanguage(text: String): String {
        var koCount = 0; var jaCount = 0; var zhCount = 0
        for (ch in text) {
            val cp = ch.code
            when {
                cp in 0xAC00..0xD7A3 -> koCount++
                cp in 0x3040..0x30FF -> jaCount++
                cp in 0x4E00..0x9FFF -> zhCount++
            }
        }
        val total = koCount + jaCount + zhCount
        if (total > 0) {
            return when {
                koCount > jaCount && koCount > zhCount -> "ko"
                jaCount >= zhCount -> "ja"
                else -> "zh-CN"
            }
        }
        return try {
            val body = okhttp3.FormBody.Builder().add("query", text).build()
            val request = Request.Builder()
                .url("https://openapi.naver.com/v1/papago/detectLangs")
                .addHeader("X-Naver-Client-Id", NAVER_CLIENT_ID)
                .addHeader("X-Naver-Client-Secret", NAVER_CLIENT_SECRET)
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) JSONObject(response.body?.string() ?: "").optString("langCode", "en")
            else "en"
        } catch (e: Exception) { "en" }
    }

    private fun translateAndSpeak(sourceText: String) {
        Thread {
            try {
                val langCode = detectLanguage(sourceText)
                if (langCode == "ko") {
                    runOnUiThread { sttText.text = "[입력]: $sourceText\n(한국어 감지 — 번역 생략)" }
                    return@Thread
                }
                val langNames = mapOf(
                    "en" to "영어", "zh-CN" to "중국어(간체)", "zh-TW" to "중국어(번체)",
                    "ja" to "일본어", "de" to "독일어", "fr" to "프랑스어",
                    "es" to "스페인어", "ru" to "러시아어", "vi" to "베트남어",
                    "th" to "태국어", "id" to "인도네시아어"
                )
                val encodedText = java.net.URLEncoder.encode(sourceText, "UTF-8")
                val request = Request.Builder()
                    .url("https://api.mymemory.translated.net/get?q=$encodedText&langpair=$langCode|ko")
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val translated = json.getJSONObject("responseData").getString("translatedText")
                    val langLabel = langNames[langCode] ?: langCode
                    val now = timeFormat.format(java.util.Date())
                    translationLog.add(0, "[$now] ($langLabel) $sourceText\n        → $translated")
                    val hintPair = getDemoLocationHint(translated, langCode)
                    val translateId = "translate_${System.currentTimeMillis()}"
                    runOnUiThread {
                        sttText.text = "[외국인 ($langLabel)]: $sourceText\n[번역]: $translated"
                        if (hintPair != null) {
                            val (hintText, hintLocale) = hintPair
                            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) {}
                                override fun onDone(utteranceId: String?) {
                                    if (utteranceId == translateId) {
                                        tts.setLanguage(hintLocale)
                                        tts.speak(hintText, TextToSpeech.QUEUE_FLUSH, null, "hint_${System.currentTimeMillis()}")
                                    } else if (utteranceId?.startsWith("hint_") == true) {
                                        tts.setLanguage(java.util.Locale.KOREAN)
                                        tts.setOnUtteranceProgressListener(null)
                                    }
                                }
                                override fun onError(utteranceId: String?) {
                                    tts.setLanguage(java.util.Locale.KOREAN)
                                    tts.setOnUtteranceProgressListener(null)
                                }
                            })
                        }
                        tts.speak(translated, TextToSpeech.QUEUE_FLUSH, null, translateId)
                    }
                } else {
                    Log.e("GlassAssist", "MyMemory 번역 API 오류: ${response.code}")
                    runOnUiThread {
                        sttText.text = "번역 실패 (오류 ${response.code})\n[터치하면 음성 녹음]"
                        tts.speak("번역에 실패했습니다", TextToSpeech.QUEUE_FLUSH, null, "translate_fail")
                    }
                }
            } catch (e: Exception) {
                Log.e("GlassAssist", "MyMemory 번역 실패: ${e.message}")
                runOnUiThread {
                    sttText.text = "번역 연결 실패\n[터치하면 음성 녹음]"
                    tts.speak("번역에 실패했습니다", TextToSpeech.QUEUE_FLUSH, null, "translate_fail")
                }
            }
        }.start()
    }

    private fun startListeningIntent() {
        if (!isMonitoring || isRecording) return
        if (!isBluetoothScoOn) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            if (isTranslationMode) {
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "zh-CN", "ja-JP"))
            }
            putExtra("android.speech.extra.EXTRA_PREFER_OFFLINE", true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, if (isTranslationMode) 5 else 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (isTranslationMode) 1500L else 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (isTranslationMode) 1200L else 500L)
        }
        continuousSpeechRecognizer?.cancel()
        continuousSpeechRecognizer?.startListening(intent)
    }

    private fun scheduleMonitorRestart() {
        monitorRestartHandler.postDelayed({
            continuousSpeechRecognizer?.cancel()
            continuousSpeechRecognizer?.destroy()
            continuousSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            continuousSpeechRecognizer?.setRecognitionListener(recognitionListener)
            startListeningIntent()
        }, 200)
    }

    private fun pauseContinuousMonitoring() {
        monitorRestartHandler.removeCallbacksAndMessages(null)
        continuousSpeechRecognizer?.stopListening()
    }

    private fun correctSttText(text: String): String {
        val numMap = mapOf(
            "일" to "1", "이" to "2", "삼" to "3", "사" to "4", "오" to "5",
            "육" to "6", "칠" to "7", "팔" to "8", "구" to "9", "십" to "10"
        )
        val locationSuffixes = listOf(
            "번 승강장", "번 출구", "번 게이트", "번 홈", "번 플랫폼",
            "번 출입구", "번 창구", "번 층", "번 계단", "번 엘리베이터", "번 에스컬레이터"
        )
        // "일본 승강장"처럼 숫자+본 형태의 특수 오인식 처리
        var result = text.replace("일본 승강장", "1번 승강장")
        for ((kor, num) in numMap) {
            for (suffix in locationSuffixes) {
                result = result.replace("$kor$suffix", "$num$suffix")
            }
        }
        // "1로" → "1층" 교정
        result = result.replace(Regex("(\\d+)로$"), "$1층")
        result = result.replace(Regex("(\\d+)로 "), "$1층 ")
        // "27" → "2층" 교정 (층을 7로 오인식)
        result = result.replace(Regex("(\\d+)7$"), "$1층")
        result = result.replace(Regex("(\\d+)7 "), "$1층 ")
        return result
    }

    private fun resetToStandby() {
        tts.stop()
        activeCall?.cancel(); activeCall = null
        isHandlingDanger = false
        isTranslationMode = false; translationModeActive = false
        inspectionStep = InspectionStep.IDLE
        isMeterMode = false
        isHandoverMode = false
        stopProtectionStream()
        pauseContinuousMonitoring()
        runOnUiThread {
            sttText.text = "음성인식 대기 중...\n[터치하면 음성 녹음]"
            tts.speak("대기중입니다", TextToSpeech.QUEUE_FLUSH, null, "reset_standby")
        }
        val waitForTts = object : Runnable {
            override fun run() {
                if (tts.isSpeaking) {
                    monitorRestartHandler.postDelayed(this, 300)
                } else {
                    resumeContinuousMonitoring()
                }
            }
        }
        monitorRestartHandler.postDelayed(waitForTts, 300)
    }

    private fun resumeContinuousMonitoring() {
        if (isMonitoring) scheduleMonitorRestart()
    }

    private fun tryActivateBluetoothSco() {
        if (isBluetoothScoOn || isScoConnecting) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scoDevice = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (scoDevice != null) {
                isScoConnecting = true
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.setCommunicationDevice(scoDevice)
                Log.d("GlassAssist", "SCO 연결 요청 (API 31+), 연결 대기 중...")
                // 연결 완료는 scoReceiver SCO_AUDIO_STATE_CONNECTED에서 처리
            }
        } else {
            @Suppress("DEPRECATION")
            if (!audioManager.isBluetoothScoAvailableOffCall) return
            isScoConnecting = true
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
        }
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

    private fun sendWorkReportEmail(report: String) {
        Thread {
            try {
                val json = JSONObject()
                    .put("userId", userId)
                    .put("report", report)
                    .toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$apiUrl/send-report").post(json).build()
                val response = client.newCall(request).execute()
                runOnUiThread {
                    if (response.isSuccessful)
                        android.widget.Toast.makeText(this, "근무 일지가 이메일로 발송되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
                    else
                        android.widget.Toast.makeText(this, "이메일 발송 실패 (${response.code})", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "서버 연결 실패: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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
            FeatureItem(R.drawable.ic_dispatch, "관제실", "dispatch"),
            FeatureItem(R.drawable.ic_translate, "번역", "translate"),
            FeatureItem(R.drawable.ic_schedule, "점검스케줄", "schedule"),
            FeatureItem(R.drawable.ic_stats, "보호통계", "stats"),
        )
        val rvFeatures = findViewById<RecyclerView>(R.id.rv_features)
        rvFeatures.layoutManager = GridLayoutManager(this, 3)
        rvFeatures.isNestedScrollingEnabled = false
        rvFeatures.adapter = FeatureGridAdapter(features) { feature ->
            when (feature.type) {
                "dispatch" -> startActivity(Intent(this, DispatchActivity::class.java))
                "translate" -> startActivity(Intent(this, TranslateActivity::class.java))
                "schedule" -> startActivity(Intent(this, InspectionScheduleActivity::class.java))
                "stats" -> startActivity(Intent(this, ProtectionStatsActivity::class.java))
                else -> startActivity(Intent(this, RecordListActivity::class.java).apply {
                    putExtra("type", feature.type)
                    putExtra("label", feature.label)
                    putExtra("userId", userId)
                })
            }
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
        scoRetryHandler.removeCallbacksAndMessages(null)
        stopScoKeepAlive()
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
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        tts.stop()
        tts.shutdown()
        glassStream?.close()
        messageServer?.stop()
        webSocket?.close(1000, "앱 종료")
        dispatchWebSocket?.close(1000, "앱 종료")
        client.dispatcher.executorService.shutdown()
        audioRecord?.release()
        dbExecutor.shutdown()
    }
}
