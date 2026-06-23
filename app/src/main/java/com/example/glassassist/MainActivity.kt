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
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var sttText: TextView

    private val qaList = mutableListOf<RecordItem>()
    private val protectionList = mutableListOf<RecordItem>()
    private val meterList = mutableListOf<RecordItem>()

    private lateinit var qaAdapter: ArrayAdapter<String>
    private lateinit var protectionAdapter: ArrayAdapter<String>
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private lateinit var qrScanLauncher: ActivityResultLauncher<ScanOptions>

    private var audioRecord: AudioRecord? = null
    private var audioFilePath: String = ""
    private var isRecording = false
    private val sampleRate = 8000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // Whisper 연속 인식 (안경 마이크 → AudioRecord + VAD → 서버 /stt)
    private var whisperThread: Thread? = null
    @Volatile private var whisperActive = false
    @Volatile private var whisperPaused = false
    @Volatile private var llmBusy = false
    @Volatile private var sttInterrupt = false
    private val WHISPER_RMS_THRESHOLD = 600.0
    private val WHISPER_END_SILENCE_MS = 800
    private val WHISPER_MIN_SPEECH_MS = 300
    private val WHISPER_MAX_SPEECH_MS = 12000
    private var isMonitoring = false
    private val monitorRestartHandler = Handler(Looper.getMainLooper())

    private var isBluetoothScoOn = false
    private var isScoConnecting = false
    private var scoKeepAliveTrack: AudioTrack? = null
    private var isHandlingDanger = false
    private var protectionAlertActive = false
    private var isTranslationMode = false
    private var glassStream: Stream? = null
    private val scoRetryHandler = Handler(Looper.getMainLooper())
    private var glassFileObserver: FileObserver? = null
    private var mediaStoreObserver: ContentObserver? = null
    private val processedGlassFiles = mutableSetOf<String>()

    private var isMeterMode = false
    private var pendingMeterItem: RecordItem? = null

    private var inspectionFacility = ""
    private var inspectionType = ""
    private val inspectionTypeMap = mapOf(
        "일상" to "일상점검", "정기" to "정기점검", "전기" to "정기점검",
        "수시" to "수시점검", "특별" to "특별점검", "긴급" to "긴급점검"
    )
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
            runOnUiThread { sttText.text = "음성인식 대기 중... ('코비'라고 부르세요)\n[화면 탭: 대기]" }
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
                    sttText.text = "📊 점검 저장됨\n[화면 탭: 대기]"
                    tts.speak("사진이 저장되었습니다", TextToSpeech.QUEUE_FLUSH, null, "phone_saved")
                }
            }
        }

        setContentView(R.layout.activity_main)

        userPrefs = UserPreferences(this)
        db = DatabaseHelper(this)

        statusText = findViewById(R.id.status_text)
        glassStatusText = findViewById(R.id.glass_status_text)
        sttText = findViewById(R.id.stt_text)
        sttText.text = "대기 중...\n[화면 탭: 대기]"

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
            pauseContinuousMonitoring()
            tts.speak("음성인식 대기 중입니다", TextToSpeech.QUEUE_FLUSH, null, "standby")
            sttText.text = "음성인식 대기 중... ('코비'라고 부르세요)\n[화면 탭: 대기]"
            monitorRestartHandler.postDelayed({ resumeContinuousMonitoring() }, 3500)
        }

        sttText.setOnClickListener {
            resetToStandby()
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
                meterAdapter.notifyDataSetChanged()
                Log.d("GlassAssist", "DB 로드: 보호${protections.size} QA${qas.size} 계량기${meters.size}")
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
                    sttText.text = "계량기 번호: $analysis\n[화면 탭: 대기]"
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
                    sttText.text = "계량기 사진 저장됨 (서버 미연결)\n[화면 탭: 대기]"
                    tts.speak("계량기 사진이 저장되었습니다", TextToSpeech.QUEUE_FLUSH, null, "meter_saved")
                }
            }
        }.start()
    }

    // 파일명(20260517_141507_hash.mp4)에서 촬영 시각 파싱 → 보호기록과 매칭
    private fun tryLinkGlassFile(file: java.io.File, filename: String) {
        Log.d("GlassAssist", "[영상연결] 진입: $filename / exists=${file.exists()}")
        if (!processedGlassFiles.add(filename)) { Log.d("GlassAssist", "[영상연결] 중복 무시: $filename"); return }
        Handler(Looper.getMainLooper()).postDelayed({ processedGlassFiles.remove(filename) }, 10_000)

        val match = GLASS_FILE_REGEX.find(filename)
        if (match == null) { Log.w("GlassAssist", "[영상연결] 정규식 불일치: $filename"); return }
        val ext = match.groupValues[3].lowercase()
        Log.d("GlassAssist", "[영상연결] ext=$ext isMeterMode=$isMeterMode protectionList=${protectionList.size}")

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
                    uploadMediaFile("db/meter-image", mapOf("date" to pending.date, "time" to pending.time), file.absolutePath)
                    runOnUiThread {
                        meterAdapter.clear()
                        meterList.forEach { meterAdapter.add("${it.date} ${it.time} - ${it.location}") }
                        meterAdapter.notifyDataSetChanged()
                        sttText.text = "📊 점검 저장됨\n[화면 탭: 대기]"
                        tts.speak("사진이 저장되었습니다", TextToSpeech.QUEUE_FLUSH, null, "insp_saved")
                    }
                } else {
                    // 점검/보호와 무관한 사진 → 저장하지 않음(대기)
                    runOnUiThread {
                        sttText.text = "[화면 탭: 대기]"
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

        // 점검 모드일 때만 영상을 계량기 기록으로 저장 (접객보호 가로채기 방지)
        val pendingForVideo = pendingMeterItem
        if (isMeterMode) {
            Log.d("GlassAssist", "[영상연결] 점검 모드 → 계량기 영상으로 저장")
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
                    uploadMediaFile("db/meter-image", mapOf("date" to pendingForVideo.date, "time" to pendingForVideo.time), file.absolutePath)
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
                    sttText.text = "📊 점검 영상 저장됨\n[화면 탭: 대기]"
                    tts.speak("영상이 저장되었습니다", TextToSpeech.QUEUE_FLUSH, null, "meter_video")
                }
            }
            return
        }

        // 접객보호 기록 중 촬영 시각과 10분 이내로 가까운 것만 자동 연결 (그 외엔 일반 영상전송)
        val PROTECTION_LINK_WINDOW_MS = 10 * 60 * 1000L
        val bestMatch = protectionList
            .filter { it.timestampMs > 0 && abs(it.timestampMs - recordingTimeMs) <= PROTECTION_LINK_WINDOW_MS }
            .minByOrNull { abs(it.timestampMs - recordingTimeMs) }

        val newDiff = if (bestMatch != null) abs(bestMatch.timestampMs - recordingTimeMs) else Long.MAX_VALUE
        Log.d("GlassAssist", "[영상연결] 영상시각=$recordingTimeMs bestMatch=${bestMatch?.keyword} 시간차=${newDiff}ms")

        // 이미 연결된 영상이 있으면 현재 영상이 더 가까운지 비교
        if (bestMatch != null && bestMatch.videoUri != null) {
            val existingDiff = glassFileRecordingDiff(bestMatch.videoUri!!, bestMatch.timestampMs)
            if (newDiff >= existingDiff) {
                // 기존 보호 영상이 더 가까움 → 새 영상 무시
                Log.d("GlassAssist", "기존 보호 영상이 더 가까움 → 새 영상 무시")
                return
            }
            // 새 영상이 더 가까움 → 보호 영상 교체
            Log.d("GlassAssist", "새 영상이 더 가까움 → 보호 영상 교체")
        }

        if (bestMatch == null) {
            Log.d("GlassAssist", "매칭 보호기록 없음 → 영상 무시")
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
            uploadProtectionVideo(bestMatch.timestampMs, storedUri)
            runOnUiThread {
                bestMatch.videoUri = storedUri
                isHandlingDanger = false
                stopProtectionStream()
                protectionAdapter.clear()
                protectionList.forEach { protectionAdapter.add("${it.date} ${it.time} ${it.keyword}") }
                protectionAdapter.notifyDataSetChanged()
                sttText.text = "안경 영상이 접객보호 기록에 자동 연결되었습니다.\n[화면 탭: 대기]"
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
                    meterList.clear(); meterAdapter.clear()
                    handoverList.clear(); handoverAdapter.clear()
                    sttText.text = "기록이 삭제되었습니다.\n[화면 탭: 대기]"
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
                        val d = dateFormat.format(now); val t = timeFormat.format(now)
                        dbExecutor.execute { db.insertHandover(userId, d, t, memo) }
                        syncToServer("handover", JSONObject()
                            .put("userId", userId).put("date", d).put("time", t).put("content", memo))
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
        syncToServer("qa", JSONObject()
            .put("userId", userId).put("date", item.date).put("time", item.time)
            .put("question", question).put("answer", answer))
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
        syncToServer("protection", JSONObject()
            .put("userId", userId).put("date", item.date).put("time", item.time)
            .put("timestampMs", item.timestampMs).put("keyword", keyword))
        runOnUiThread {
            protectionAdapter.clear()
            protectionList.forEach { protectionAdapter.add("${it.date} ${it.time} ${it.keyword}") }
            protectionAdapter.notifyDataSetChanged()
        }
    }

    // 서버 MySQL DB로 기록 전송 (로컬 SQLite와 이중 저장). 실패해도 앱 동작에 영향 없음
    private fun syncToServer(endpoint: String, payload: JSONObject) {
        Thread {
            try {
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$apiUrl/db/$endpoint").post(body).build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w("GlassAssist", "서버 DB 동기화 실패 ($endpoint): ${e.message}")
            }
        }.start()
    }

    // 접객보호 영상 파일을 서버에 업로드 (대시보드 재생용). 실패해도 앱 동작 무관
    private fun uploadProtectionVideo(timestampMs: Long, videoPath: String) {
        Thread {
            try {
                val file = File(if (videoPath.startsWith("/")) videoPath
                                else Uri.parse(videoPath).path ?: return@Thread)
                if (!file.exists()) { Log.w("GlassAssist", "업로드 영상 없음: $videoPath"); return@Thread }
                val reqBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("userId", userId)
                    .addFormDataPart("timestampMs", timestampMs.toString())
                    .addFormDataPart("file", file.name, file.asRequestBody("video/mp4".toMediaType()))
                    .build()
                val request = Request.Builder().url("$apiUrl/db/protection-video").post(reqBody).build()
                client.newCall(request).execute().close()
                Log.d("GlassAssist", "접객보호 영상 서버 업로드 완료: ${file.name}")
            } catch (e: Exception) {
                Log.w("GlassAssist", "접객보호 영상 업로드 실패: ${e.message}")
            }
        }.start()
    }

    // 사진/영상 파일을 서버로 업로드 (대시보드 재생용). 실패해도 앱 동작에 영향 없음
    private fun uploadMediaFile(endpoint: String, fields: Map<String, String>, filePath: String) {
        Thread {
            try {
                val file = File(if (filePath.startsWith("/")) filePath
                                else Uri.parse(filePath).path ?: return@Thread)
                if (!file.exists()) { Log.w("GlassAssist", "업로드 파일 없음: $filePath"); return@Thread }
                val mime = if (file.extension.lowercase() in listOf("jpg", "jpeg", "png")) "image/jpeg" else "video/mp4"
                val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("userId", userId)
                fields.forEach { (k, v) -> builder.addFormDataPart(k, v) }
                builder.addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
                val request = Request.Builder().url("$apiUrl/$endpoint").post(builder.build()).build()
                client.newCall(request).execute().close()
                Log.d("GlassAssist", "서버 미디어 업로드 완료: $endpoint / ${file.name}")
            } catch (e: Exception) {
                Log.w("GlassAssist", "서버 미디어 업로드 실패($endpoint): ${e.message}")
            }
        }.start()
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
                        sttText.text = "AI 답변: $message\n[화면 탭: 대기]"
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
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastDispatchUrl = url
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
                                sttText.text = "관제실: $message\n[화면 탭: 대기]"
                            }
                        }
                        "audio_to_worker" -> {
                            val audioData = json.getString("data")
                            val from = json.optString("from", "관제실")
                            dispatchLog.add(0, "[$now] 📥 $from: 음성 수신")
                            playDispatchAudio(audioData)
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

    private fun startContinuousMonitoring() {
        isMonitoring = true
        startWhisperLoop()
        startScoRetry()
    }

    private fun startWhisperLoop() {
        if (whisperActive) { whisperPaused = false; return }
        whisperActive = true
        whisperPaused = false
        whisperThread = Thread { whisperCaptureLoop() }.also { it.start() }
    }

    // 안경 마이크를 연속 캡처하며 침묵 기준으로 발화 구간을 잘라 Whisper(/stt)로 전송
    private fun whisperCaptureLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize <= 0) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val buf = ShortArray(bufferSize)
        val chunkMs = (bufferSize * 1000) / sampleRate
        var recorder: AudioRecord? = null
        val segment = ArrayList<Short>()
        var inSpeech = false; var silenceMs = 0; var speechMs = 0

        while (whisperActive) {
            // 일시정지/수동녹음중/SCO 끊김/TTS 재생중 → 마이크 해제 후 대기 (마이크 충돌·에코 방지)
            if (whisperPaused || isRecording || !isBluetoothScoOn) {
                recorder?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
                recorder = null
                if (segment.isNotEmpty()) { segment.clear(); inSpeech = false; silenceMs = 0; speechMs = 0 }
                try { Thread.sleep(60) } catch (_: InterruptedException) {}
                continue
            }
            if (recorder == null) {
                val r = try {
                    AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, channelConfig, audioFormat, bufferSize * 4)
                } catch (e: Exception) { null }
                if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
                    r?.release(); try { Thread.sleep(200) } catch (_: InterruptedException) {}
                    continue
                }
                r.startRecording()
                recorder = r
            }
            val n = recorder.read(buf, 0, bufferSize)
            if (n <= 0) continue
            var sum = 0.0
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            val rms = Math.sqrt(sum / n)
            if (rms > WHISPER_RMS_THRESHOLD) {
                inSpeech = true; silenceMs = 0; speechMs += chunkMs
                for (i in 0 until n) segment.add(buf[i])
            } else if (inSpeech) {
                silenceMs += chunkMs
                for (i in 0 until n) segment.add(buf[i])
                if (silenceMs >= WHISPER_END_SILENCE_MS) {
                    if (speechMs >= WHISPER_MIN_SPEECH_MS) flushSegmentToWhisper(segment.toShortArray())
                    segment.clear(); inSpeech = false; silenceMs = 0; speechMs = 0
                }
            }
            val effMaxMs = if (llmBusy) 1200 else WHISPER_MAX_SPEECH_MS
            if (speechMs >= effMaxMs && segment.isNotEmpty()) {
                flushSegmentToWhisper(segment.toShortArray())
                segment.clear(); inSpeech = false; silenceMs = 0; speechMs = 0
            }
        }
        recorder?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
    }

    // 발화 구간 WAV를 서버 Whisper로 보내고 결과 텍스트 처리 (캡처 스레드에서 동기 호출)
    private fun flushSegmentToWhisper(pcm: ShortArray) {
        whisperPaused = true   // 처리 중 추가 캡처 중단 (에코 방지)
        try {
            val path = "${externalCacheDir?.absolutePath}/whisper_seg.wav"
            saveAsWav(pcm, path)
            val lang = if (isTranslationMode) "en" else "ko"
            val reqBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "seg.wav", File(path).asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("language", lang)
                .build()
            val request = Request.Builder().url("$apiUrl/stt").post(reqBody).build()
            val resp = client.newCall(request).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            val text = JSONObject(body).optString("text", "").trim()
            handleWhisperText(text)
        } catch (e: Exception) {
            Log.w("GlassAssist", "Whisper STT 실패: ${e.message}")
            whisperPaused = false
        }
    }

    private fun handleWhisperText(text: String) {
        if (text.isBlank()) { whisperPaused = false; return }
        Log.d("GlassAssist", "STT인식: '$text'")
        val compact = text.replace(" ", "")
        // 정지 명령은 TTS 재생 중에도 항상 우선 처리 (resetToStandby가 TTS도 중지)
        // 발화가 정지어 자체일 때만 처리 ('운행중지' 같은 내용어 부분일치로 끊기는 것 방지)
        val stopCmds = setOf("대기", "태기", "중지", "그만", "취소", "멈춰", "정지", "중단",
                             "그만해", "중지해", "멈춰줘", "정지해", "대기해", "스톱", "스탑")
        val stopQuery = compact.removePrefix("코비야").removePrefix("코비아").removePrefix("코비")
        if (compact in stopCmds || stopQuery in stopCmds) {
            resetToStandby()
            return
        }
        // 답변 처리 중 정지 외 발화는 무시 (에코·중복 방지)
        if (llmBusy) { whisperPaused = false; return }
        runOnUiThread { sttText.text = "[STT] $text" }
        if (text.length < 2) { whisperPaused = false; return }

        // 통역 모드 중: 모든 발화를 번역 경로로 (외국인 승객 발화 -> 한국어 안내). 종료는 화면 탭 또는 "stop translation".
        if (translationModeActive) {
            // 음성 종료: 영어 STT라, "stop"과 "translation"이 한 발화에 둘 다 있으면 종료 (부분 단어로는 종료 안 됨)
            val low = text.lowercase()
            if (low.contains("stop") && low.contains("translation")) {
                resetToStandby()
                return
            }
            sendToLLM(text, isCall = true)
            return
        }

        // 웨이크워드 게이팅: "코비" 호출 시에만 질의 응답, 호출어 없는 발화는 접객보호(danger)만 검사
        val wakeQuery = extractWakeQuery(text)
        if (wakeQuery != null) {
            // "코비야, 통역" / "코비야, 번역" -> 통역 모드 시작 (시리/빅스비식: 켜는 모드, 종료는 화면 탭)
            if (wakeQuery.replace(" ", "").let { it.contains("통역") || it.contains("번역") }) {
                translationModeActive = true
                isTranslationMode = true
                runOnUiThread {
                    sttText.text = "🌐 통역 모드 시작\n외국어로 말씀하시면 한국어로 안내합니다.\n[화면 탭 또는 'stop translation' 으로 종료]"
                    tts.speak("통역 모드를 시작합니다. 외국어로 말씀해 주세요.", TextToSpeech.QUEUE_FLUSH, null, "trans_on")
                }
                whisperPaused = false
                return
            }
            if (wakeQuery.isBlank()) { whisperPaused = false; return }  // 호출만 하고 질의 없음
            sendToLLM(wakeQuery, isCall = true)
        } else {
            sendToLLM(text, isCall = false)
        }
    }

    // "코비" 호출어 추출: 있으면 호출어 뒤 질의 반환(빈 문자열 가능), 없으면 null
    private val wakeRegex = Regex("코\\s*비(야|아)?")
    private fun extractWakeQuery(text: String): String? {
        val m = wakeRegex.find(text) ?: return null
        return text.substring(m.range.last + 1).trimStart(' ', ',', '.', '!', '?', '~', '·')
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
                uploadMediaFile("db/meter-image", mapOf("date" to pending.date, "time" to pending.time), file.absolutePath)
            } else {
                val item = RecordItem(date = dateFormat.format(now), time = timeFormat.format(now), location = location, videoUri = imageUri)
                meterList.add(0, item)
                dbExecutor.execute { db.insertMeter(userId, item.date, item.time, location, imageUri) }
            }
            runOnUiThread {
                meterAdapter.clear()
                meterList.forEach { meterAdapter.add("${it.date} ${it.time} - ${it.location}") }
                meterAdapter.notifyDataSetChanged()
                sttText.text = "📊 점검 사진 저장됨\n[화면 탭: 대기]"
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
        syncToServer("meter", JSONObject()
            .put("userId", userId).put("date", item.date).put("time", item.time)
            .put("location", location).put("videoUri", ""))
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
                    runOnUiThread { sttText.text = "점검 촬영 취소됨.\n[화면 탭: 대기]" }
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
        // 실시간 스트리밍/4초 스냅샷 전송 제거 - 관제 알림만 발송
        val keyword = protectionList.firstOrNull()?.keyword ?: ""
        dispatchWebSocket?.send(
            org.json.JSONObject()
                .put("type", "protection_alert")
                .put("from", userId)
                .put("keyword", keyword)
                .toString()
        )
        protectionAlertActive = true
    }

    private fun stopProtectionStream() {
        if (!protectionAlertActive) return
        protectionAlertActive = false
        dispatchWebSocket?.send(
            org.json.JSONObject().put("type", "protection_end").put("from", userId).toString()
        )
    }

    private fun sendToLLM(text: String, isCall: Boolean = true) {
        pauseContinuousMonitoring()
        llmBusy = true
        sttInterrupt = false
        Thread {
            try {
                Log.d("GlassAssist", "→서버요청: '$text' (isCall=$isCall)")
                val jsonBody = JSONObject().put("text", text).toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$apiUrl/chat").post(jsonBody).build()
                val call = client.newCall(request)
                activeCall = call
                val response = call.execute()
                activeCall = null
                if (!response.isSuccessful) {
                    runOnUiThread {
                        sttText.text = "서버 오류 (${response.code})\n[화면 탭: 대기]"
                        resumeContinuousMonitoring()
                    }
                    response.close()
                    return@Thread
                }

                val source = response.body?.source() ?: run {
                    runOnUiThread { resumeContinuousMonitoring() }; return@Thread
                }
                val firstLine = source.readUtf8Line() ?: run {
                    runOnUiThread { resumeContinuousMonitoring() }; response.close(); return@Thread
                }
                val json = try { JSONObject(firstLine) } catch (e: Exception) {
                    runOnUiThread { resumeContinuousMonitoring() }; response.close()
                    return@Thread
                }

                val type = json.optString("type", "qa")
                val ttsMsg = json.optString("tts", "")

                // 웨이크워드 게이팅(B): 호출어 없는 발화는 접객보호(danger)만 처리, 나머지는 무시
                if (!isCall && type != "danger") {
                    response.close()
                    runOnUiThread { resumeContinuousMonitoring() }
                    return@Thread
                }
                if (type != "qa") response.close()

                when (type) {
                    "danger" -> {
                        val rawKeyword = json.optString("keyword", "")
                        val keyword = if (rawKeyword.isBlank() || rawKeyword == "위험 발언") text else rawKeyword
                        isHandlingDanger = true
                        addProtectionRecord(keyword)
                        startDangerAlert()
                        runOnUiThread {
                            sttText.text = "긴급 상황 감지!\n\n안경 버튼을 눌러 영상을 녹화하세요.\n녹화 후 '대기'라고 말하면 다른 기능 사용 가능.\n영상은 동기화되면 자동 저장됩니다."
                            if (ttsMsg.isNotEmpty()) tts.speak(ttsMsg, TextToSpeech.QUEUE_FLUSH, null, "danger_${System.currentTimeMillis()}")
                        }
                        runOnUiThread { resumeContinuousMonitoring() }
                    }
                    "translation" -> {
                        val translated = json.optString("translated", "")
                        val lang = json.optString("lang", "en")
                        val langNames = mapOf(
                            "en" to "영어", "zh" to "중국어", "ja" to "일본어",
                            "de" to "독일어", "fr" to "프랑스어", "es" to "스페인어"
                        )
                        val langLabel = langNames[lang] ?: lang
                        val now = timeFormat.format(Date())
                        translationLog.add(0, "[$now] ($langLabel) $text\n        → $translated")
                        runOnUiThread {
                            sttText.text = "[외국인 ($langLabel)]: $text\n[번역]: $translated"
                            if (ttsMsg.isNotEmpty()) tts.speak(ttsMsg, TextToSpeech.QUEUE_FLUSH, null, "translate_${System.currentTimeMillis()}")
                        }
                        monitorRestartHandler.postDelayed({ resumeContinuousMonitoring() }, 2000)
                    }
                    "inspection" -> {
                        val facility = json.optString("facility", "시설물")
                        val inspType = json.optString("inspType", "일상점검")
                        val location = json.optString("location", "위치 미입력")
                        // 카메라/DB 작업은 메인 스레드에서 실행 (네트워크 try-catch와 분리)
                        runOnUiThread {
                            sttText.text = "점검: $inspType / $facility / $location"
                            if (ttsMsg.isNotEmpty()) tts.speak(ttsMsg, TextToSpeech.QUEUE_FLUSH, null, "insp_${System.currentTimeMillis()}")
                            activateInspectionCameraMode(inspType, facility, location)
                        }
                    }
                    "handover" -> {
                        val content = json.optString("content", "")
                        if (content.isNotEmpty()) {
                            val now = Date()
                            val d = dateFormat.format(now); val t = timeFormat.format(now)
                            dbExecutor.execute { db.insertHandover(userId, d, t, content) }
                            syncToServer("handover", JSONObject()
                                .put("userId", userId).put("date", d).put("time", t).put("content", content))
                            handoverList.add(content)
                            runOnUiThread {
                                handoverAdapter.clear()
                                handoverList.forEachIndexed { i, s -> handoverAdapter.add("${i+1}. $s") }
                                handoverAdapter.notifyDataSetChanged()
                                sttText.text = "인수인계 저장됨: $content\n[화면 탭: 대기]"
                                if (ttsMsg.isNotEmpty()) tts.speak(ttsMsg, TextToSpeech.QUEUE_FLUSH, null, "handover_${System.currentTimeMillis()}")
                            }
                        }
                        runOnUiThread { resumeContinuousMonitoring() }
                    }
                    "dispatch" -> {
                        runOnUiThread {
                            if (ttsMsg.isNotEmpty()) tts.speak(ttsMsg, TextToSpeech.QUEUE_FLUSH, null, "dispatch_llm_${System.currentTimeMillis()}")
                        }
                        Handler(Looper.getMainLooper()).postDelayed({ startDispatchRecording() }, 500)
                    }
                    "ignore" -> {
                        runOnUiThread { resumeContinuousMonitoring() }
                    }
                    else -> { // "qa" - 문장 스트리밍 수신, 도착 즉시 TTS
                        var spoken = false
                        val fullAnswer = StringBuilder()
                        runOnUiThread { sttText.text = "Q: $text\n\nA: ..." }
                        while (true) {
                            if (sttInterrupt) break
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            val obj = try { JSONObject(line) } catch (e: Exception) { continue }
                            if (obj.optBoolean("done", false)) {
                                val ans = obj.optString("answer", fullAnswer.toString().trim())
                                Log.d("GlassAssist", "←qa답변: $ans")
                                addQaRecord(text, ans)
                                runOnUiThread { sttText.text = "Q: $text\n\nA: $ans" }
                                break
                            }
                            val sent = obj.optString("tts", "")
                            if (sent.isNotBlank() && !sttInterrupt) {
                                fullAnswer.append(sent).append(" ")
                                val mode = if (!spoken) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                                runOnUiThread { tts.speak(sent, mode, null, "qa_${System.currentTimeMillis()}") }
                                spoken = true
                                whisperPaused = false  // 첫 문장 재생 후 마이크 ON -> 음성 중단 수신
                            }
                        }
                        response.close()
                        runOnUiThread {
                            standbyHandler.removeCallbacks(standbyRunnable)
                            standbyHandler.postDelayed(standbyRunnable, 3000)
                            resumeContinuousMonitoring()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GlassAssist", "LLM 전송 실패: ${e.message}")
                runOnUiThread {
                    sttText.text = "서버 연결 실패\n[화면 탭: 대기]"
                    resumeContinuousMonitoring()
                }
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
                    sttText.text = "관제실에 전송됐습니다.\n[화면 탭: 대기]"
                    tts.speak("관제실에 전송됐습니다", TextToSpeech.QUEUE_FLUSH, null, "dispatch_sent")
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
                runOnUiThread { sttText.text = "관제실 음성 수신\n[화면 탭: 대기]" }
            } catch (e: Exception) {
                Log.e("GlassAssist", "관제 오디오 재생 실패: ${e.message}")
            }
        }.start()
    }

    private fun pauseContinuousMonitoring() {
        monitorRestartHandler.removeCallbacksAndMessages(null)
        whisperPaused = true
    }

    private fun resetToStandby() {
        sttInterrupt = true
        llmBusy = false
        tts.stop()
        activeCall?.cancel(); activeCall = null
        isHandlingDanger = false
        isTranslationMode = false; translationModeActive = false
        isMeterMode = false
        stopProtectionStream()
        pauseContinuousMonitoring()
        runOnUiThread {
            sttText.text = "음성인식 대기 중... ('코비'라고 부르세요)\n[화면 탭: 대기]"
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
        if (!isMonitoring) return
        llmBusy = false
        startWhisperLoop()
        whisperPaused = false
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
- 계량기 점검: ${meterList.size}건

⚠️ 접객보호 상세
${if (protectionList.isEmpty()) "없음" else protectionList.joinToString("\n") { "  [${it.time}] 키워드: ${it.keyword}" }}

💬 질의응답 상세
${if (qaList.isEmpty()) "없음" else qaList.joinToString("\n") { "  [${it.time}] Q: ${it.question.take(30)}" }}

📊 계량기 점검 상세
${if (meterList.isEmpty()) "없음" else meterList.joinToString("\n") { "  [${it.time}] 위치: ${it.location}" }}

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
            // FeatureItem(R.drawable.ic_meter, "계량기", "meter"),   // 계량기는 코레일 외주 담당이라 기능 제외
            // FeatureItem(R.drawable.ic_handover, "인수인계", "handover"),   // 임시 숨김
            FeatureItem(R.drawable.ic_dispatch, "관제실", "dispatch"),
            FeatureItem(R.drawable.ic_translate, "번역", "translate"),
            // FeatureItem(R.drawable.ic_stats, "보호 통계", "stats"),   // 데모 임시 숨김 (나중에 복구)
        )
        val rvFeatures = findViewById<RecyclerView>(R.id.rv_features)
        rvFeatures.layoutManager = GridLayoutManager(this, 3)
        rvFeatures.isNestedScrollingEnabled = false
        rvFeatures.adapter = FeatureGridAdapter(features) { feature ->
            when (feature.type) {
                "dispatch" -> startActivity(Intent(this, DispatchActivity::class.java))
                "translate" -> startActivity(Intent(this, TranslateActivity::class.java))
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
        whisperActive = false
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
