package com.example.glassassist

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.provider.MediaStore
import android.util.Log
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.meta.wearable.dat.core.types.DeviceIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GlassVideoRecorder(private val context: Context) {

    companion object {
        private const val TAG = "GlassVideoRecorder"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordingJob: Job? = null
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var muxerStarted = false
    private var outputFile: File? = null

    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null

    var isRecording = false
        private set

    var onStarted: (() -> Unit)? = null
    var onSaved: ((String?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null

    fun start(deviceId: DeviceIdentifier) {
        if (isRecording) return

        val config = StreamConfiguration(VideoQuality.HIGH, 30, true)

        val name = "danger_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREAN).format(Date())}.mp4"
        outputFile = File(context.getExternalFilesDir(null), name)
        muxer = try {
            MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            Log.e(TAG, "MediaMuxer 초기화 실패: ${e.message}")
            onError?.invoke("녹화 초기화 실패")
            return
        }

        videoTrack = -1
        muxerStarted = false
        cachedSps = null
        cachedPps = null
        isRecording = true
        onStarted?.invoke()

        recordingJob = scope.launch {
            try {
                // 전달받은 deviceId로 SpecificDeviceSelector 먼저 시도
                onStatus?.invoke("세션 생성 중 (deviceId: $deviceId)...")
                Log.d(TAG, "세션 생성 시도: deviceId=$deviceId")
                var sessionResult = Wearables.createSession(SpecificDeviceSelector(deviceId))
                Log.d(TAG, "SpecificDevice 결과: $sessionResult")

                // 실패하면 AutoDeviceSelector로 재시도
                if (sessionResult.getOrNull() == null) {
                    onStatus?.invoke("SpecificDevice 실패 → Auto 재시도...")
                    Log.d(TAG, "AutoDeviceSelector 재시도")
                    sessionResult = Wearables.createSession(AutoDeviceSelector())
                    Log.d(TAG, "Auto 결과: $sessionResult")
                }

                // 세션 생성
                val session: DeviceSession = sessionResult.getOrNull() ?: run {
                    val errMsg = sessionResult.exceptionOrNull()?.let {
                        "${it.javaClass.simpleName}: ${it.message}"
                    } ?: sessionResult.toString()
                    Log.e(TAG, "세션 생성 최종 실패: $errMsg")
                    onError?.invoke("세션 생성 실패: $errMsg")
                    return@launch
                }

                // 세션 상태 모니터링
                launch {
                    session.state.collect { state ->
                        Log.d(TAG, "기기 세션 상태: $state")
                        onStatus?.invoke("기기 세션: $state")
                    }
                }

                // 세션 에러를 변수에 저장 (나중에 실패 메시지에 포함)
                var lastSessionError: String? = null
                launch {
                    session.errors.collect { error ->
                        Log.e(TAG, "세션 에러: $error")
                        lastSessionError = error.toString()
                        onStatus?.invoke("세션 에러: $error")
                    }
                }

                session.start()
                Log.d(TAG, "세션 start() 호출 완료")
                onStatus?.invoke("세션 연결 대기 중...")

                // start()는 비동기(fire-and-forget) → STARTED 상태까지 최대 15초 대기
                val reached = withTimeoutOrNull(15_000) {
                    session.state.first { state ->
                        val s = state.toString()
                        s == "STARTED" || s == "STOPPED"
                    }
                }
                if (reached?.toString() != "STARTED") {
                    val reason = lastSessionError ?: (reached?.toString() ?: "시간 초과")
                    Log.e(TAG, "세션 시작 실패: $reason")
                    onError?.invoke("세션 실패 원인: $reason")
                    session.stop()
                    return@launch
                }
                onStatus?.invoke("세션 시작됨 - 스트림 추가 중")

                // 스트림 추가
                val streamResult = session.addStream(config)
                val stream: Stream = streamResult.getOrNull() ?: run {
                    Log.e(TAG, "스트림 추가 실패: $streamResult")
                    onError?.invoke("스트림 추가 실패")
                    return@launch
                }

                // 스트림 상태 모니터링
                launch {
                    stream.state.collect { state ->
                        Log.d(TAG, "스트림 상태: $state")
                        onStatus?.invoke("스트림 상태: $state")
                    }
                }

                // 스트림 에러 모니터링 (HINGE_CLOSED, PERMISSIONS_DENIED, STREAM_ERROR)
                launch {
                    stream.errorStream.collect { error ->
                        Log.e(TAG, "스트림 에러: $error")
                        onStatus?.invoke("스트림 에러: $error")
                        onError?.invoke("스트림 에러: $error")
                    }
                }

                stream.start()

                var frameCount = 0
                stream.videoStream.collect { frame ->
                    frameCount++
                    if (frameCount == 1) {
                        Log.d(TAG, "첫 프레임! compressed=${frame.isCompressed} size=${frame.buffer.remaining()}")
                        onStatus?.invoke("첫 프레임! ${frame.width}x${frame.height}")
                    }
                    if (!isRecording || !frame.isCompressed) return@collect

                    val data = ByteArray(frame.buffer.remaining())
                    frame.buffer.get(data)

                    if (!muxerStarted) {
                        extractNalUnit(data, 7)?.let { cachedSps = it }
                        extractNalUnit(data, 8)?.let { cachedPps = it }

                        val sps = cachedSps ?: return@collect
                        val pps = cachedPps ?: return@collect

                        val format = MediaFormat.createVideoFormat("video/avc", frame.width, frame.height).apply {
                            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                            setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                        }
                        videoTrack = muxer!!.addTrack(format)
                        muxer!!.start()
                        muxerStarted = true
                        Log.d(TAG, "녹화 시작: ${frame.width}x${frame.height}")
                        onStatus?.invoke("녹화 중: ${frame.width}x${frame.height}")
                    }

                    if (muxerStarted) {
                        val bufferInfo = MediaCodec.BufferInfo().apply {
                            presentationTimeUs = frame.presentationTimeUs
                            size = data.size
                            offset = 0
                            flags = if (isKeyFrame(data)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        }
                        muxer?.writeSampleData(videoTrack, ByteBuffer.wrap(data), bufferInfo)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "스트림 예외: ${e.javaClass.simpleName} - ${e.message}")
                onError?.invoke("스트림 오류: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                finalizeMuxer()
            }
        }
    }

    fun stop() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
    }

    private fun finalizeMuxer() {
        try {
            if (muxerStarted) {
                muxer?.stop()
                muxer?.release()
                muxer = null
                muxerStarted = false
                saveToMediaStore()
            } else {
                muxer?.release()
                muxer = null
                outputFile?.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "종료 오류: ${e.message}")
        }
    }

    private fun saveToMediaStore() {
        val file = outputFile ?: return
        if (!file.exists()) return

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GlassAssist")
        }

        context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            context.contentResolver.openOutputStream(uri)?.use { os ->
                file.inputStream().use { it.copyTo(os) }
            }
            onSaved?.invoke(uri.toString())
            Log.d(TAG, "갤러리 저장 완료: $uri")
        }
        file.delete()
    }

    private fun extractNalUnit(data: ByteArray, nalType: Int): ByteArray? {
        var i = 0
        while (i < data.size - 3) {
            val scLen = when {
                i + 3 < data.size && data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                        data[i+2] == 0.toByte() && data[i+3] == 1.toByte() -> 4
                data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                        data[i+2] == 1.toByte() -> 3
                else -> { i++; continue }
            }
            val nalStart = i + scLen
            if (nalStart >= data.size) break
            if (data[nalStart].toInt() and 0x1F == nalType) {
                var j = nalStart + 1
                while (j < data.size - 2) {
                    if (data[j] == 0.toByte() && data[j+1] == 0.toByte() &&
                        (data[j+2] == 1.toByte() ||
                                (j + 3 < data.size && data[j+2] == 0.toByte() && data[j+3] == 1.toByte()))) break
                    j++
                }
                return data.copyOfRange(i, j)
            }
            i += scLen + 1
        }
        return null
    }

    private fun isKeyFrame(data: ByteArray): Boolean {
        var i = 0
        while (i < data.size - 3) {
            val scLen = when {
                i + 3 < data.size && data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                        data[i+2] == 0.toByte() && data[i+3] == 1.toByte() -> 4
                data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                        data[i+2] == 1.toByte() -> 3
                else -> { i++; continue }
            }
            val nalStart = i + scLen
            if (nalStart >= data.size) break
            if (data[nalStart].toInt() and 0x1F == 5) return true
            i += scLen + 1
        }
        return false
    }
}
