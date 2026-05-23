package com.example.glassassist

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

class QaDetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qa_detail)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<TextView>(R.id.tv_date).text = intent.getStringExtra("date") ?: "-"
        findViewById<TextView>(R.id.tv_time).text = intent.getStringExtra("time") ?: "-"
        findViewById<TextView>(R.id.tv_question).text = intent.getStringExtra("question") ?: "-"
        findViewById<TextView>(R.id.tv_answer).text = intent.getStringExtra("answer") ?: "-"
    }
}

class ProtectionDetailActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_protection_detail)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<TextView>(R.id.tv_date).text = intent.getStringExtra("date") ?: "-"
        findViewById<TextView>(R.id.tv_time).text = intent.getStringExtra("time") ?: "-"
        findViewById<TextView>(R.id.tv_keyword).text = intent.getStringExtra("keyword") ?: "-"

        val videoUri = intent.getStringExtra("videoUri")
        val playerView = findViewById<PlayerView>(R.id.video_view)
        val noVideoText = findViewById<TextView>(R.id.tv_no_video)

        if (videoUri != null) {
            noVideoText.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            val uri = if (videoUri.startsWith("/")) Uri.fromFile(File(videoUri)) else Uri.parse(videoUri)
            player = ExoPlayer.Builder(this).build().also { exo ->
                playerView.player = exo
                exo.setMediaItem(MediaItem.fromUri(uri))
                exo.prepare()
                exo.play()
            }
        } else {
            noVideoText.visibility = View.VISIBLE
            playerView.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}

class VideoDetailActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_detail)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<TextView>(R.id.tv_date).text = intent.getStringExtra("date") ?: "-"
        findViewById<TextView>(R.id.tv_time).text = intent.getStringExtra("time") ?: "-"

        val mediaUri = intent.getStringExtra("videoUri")
        val playerView = findViewById<PlayerView>(R.id.video_view)
        val imageView = findViewById<ImageView>(R.id.image_view)
        val noVideoText = findViewById<TextView>(R.id.tv_no_video)

        if (mediaUri != null) {
            noVideoText.visibility = View.GONE
            val isImage = try {
                val mime = contentResolver.getType(Uri.parse(mediaUri)) ?: ""
                mime.startsWith("image/")
            } catch (e: Exception) {
                mediaUri.substringAfterLast('.').lowercase() in listOf("jpg", "jpeg", "png")
            }
            if (isImage) {
                playerView.visibility = View.GONE
                imageView.visibility = View.VISIBLE
                val uri = if (mediaUri.startsWith("/")) Uri.fromFile(File(mediaUri)) else Uri.parse(mediaUri)
                imageView.setImageURI(uri)
            } else {
                imageView.visibility = View.GONE
                playerView.visibility = View.VISIBLE
                val uri = if (mediaUri.startsWith("/")) Uri.fromFile(File(mediaUri)) else Uri.parse(mediaUri)
                player = ExoPlayer.Builder(this).build().also { exo ->
                    playerView.player = exo
                    exo.setMediaItem(MediaItem.fromUri(uri))
                    exo.prepare()
                    exo.play()
                }
            }
        } else {
            noVideoText.visibility = View.VISIBLE
            playerView.visibility = View.GONE
            imageView.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}

class MeterDetailActivity : AppCompatActivity() {
    private val CAMERA_REQUEST_CODE = 200
    private var photoUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meter_detail)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<TextView>(R.id.tv_date).text = intent.getStringExtra("date") ?: "-"
        findViewById<TextView>(R.id.tv_time).text = intent.getStringExtra("time") ?: "-"
        findViewById<TextView>(R.id.tv_location).text = intent.getStringExtra("location") ?: "-"

        val meterNumber = intent.getStringExtra("meterNumber") ?: "-"
        findViewById<TextView>(R.id.tv_meter_number).text = meterNumber

        val imagePath = intent.getStringExtra("imagePath")
        val imgMeter = findViewById<ImageView>(R.id.img_meter)
        val tvNoImage = findViewById<TextView>(R.id.tv_no_image)

        if (imagePath != null) {
            val uri = if (imagePath.startsWith("/")) Uri.fromFile(File(imagePath)) else Uri.parse(imagePath)
            imgMeter.setImageURI(uri)
            imgMeter.visibility = View.VISIBLE
            tvNoImage.visibility = View.GONE
        } else {
            tvNoImage.visibility = View.VISIBLE
            imgMeter.visibility = View.GONE
        }

        // 카메라 촬영 버튼
        findViewById<android.widget.Button>(R.id.btn_take_photo).setOnClickListener {
            val photoFile = File(getExternalFilesDir(null), "meter_${System.currentTimeMillis()}.jpg")
            photoUri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.provider", photoFile
            )
            val cameraIntent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(cameraIntent, CAMERA_REQUEST_CODE)
        }

        // 수동 추가 버튼
        findViewById<android.widget.Button>(R.id.btn_add_manual).setOnClickListener {
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            val imgMeter = findViewById<ImageView>(R.id.img_meter)
            val tvNoImage = findViewById<TextView>(R.id.tv_no_image)
            imgMeter.setImageURI(photoUri)
            imgMeter.visibility = View.VISIBLE
            tvNoImage.visibility = View.GONE
        }
    }
}

class HandoverDetailActivity : AppCompatActivity() {
    private var position: Int = -1
    private lateinit var tvContent: EditText
    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_handover_detail)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        position = intent.getIntExtra("position", -1)
        val date = intent.getStringExtra("date") ?: "-"
        val time = intent.getStringExtra("time") ?: "-"
        val content = intent.getStringExtra("content") ?: "-"

        findViewById<TextView>(R.id.tv_date).text = date
        findViewById<TextView>(R.id.tv_time).text = time

        tvContent = findViewById(R.id.tv_content)
        tvContent.setText(content)
        tvContent.isEnabled = false

        val btnEdit = findViewById<android.widget.Button>(R.id.btn_edit)

        btnEdit.setOnClickListener {
            if (!isEditMode) {
                isEditMode = true
                tvContent.isEnabled = true
                tvContent.requestFocus()
                btnEdit.text = "✅ 저장하기"
            } else {
                val newContent = tvContent.text.toString().trim()
                if (newContent.isNotEmpty() && position >= 0) {
                    val resultIntent = Intent().apply {
                        putExtra("action", "edit")
                        putExtra("position", position)
                        putExtra("newContent", newContent)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            }
        }

        findViewById<android.widget.Button>(R.id.btn_delete).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("삭제")
                .setMessage("이 인수인계 항목을 삭제하시겠습니까?")
                .setPositiveButton("삭제") { _, _ ->
                    val resultIntent = Intent().apply {
                        putExtra("action", "delete")
                        putExtra("position", position)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }
}
