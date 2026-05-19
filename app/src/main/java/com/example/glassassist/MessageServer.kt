package com.example.glassassist

import android.speech.tts.TextToSpeech
import android.util.Log
import fi.iki.elonen.NanoHTTPD

class MessageServer(
    private val tts: TextToSpeech,
    port: Int = 8080
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.method == Method.POST && session.uri == "/message") {
                val body = mutableMapOf<String, String>()
                session.parseBody(body)
                val message = body["postData"] ?: ""
                Log.d("GlassAssist", "MessageServer 수신: $message")
                tts.speak(message, TextToSpeech.QUEUE_ADD, null, "msg_${System.currentTimeMillis()}")
                newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            Log.e("GlassAssist", "MessageServer 에러: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error")
        }
    }
}