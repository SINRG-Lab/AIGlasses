package com.example.aiglasses

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class OpenAIService(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val CHAT_URL = "https://api.openai.com/v1/chat/completions"
        private const val SYSTEM_PROMPT =
            "You are a helpful voice assistant for smart glasses. " +
            "Keep answers short (1-2 sentences). " +
            "If the user asks for current facts (like who is president), say you may be outdated " +
            "unless you were provided updated info."
        private const val VISION_SYSTEM_PROMPT =
            "You are a helpful vision assistant for smart glasses. " +
            "The user is wearing smart glasses with a camera. " +
            "Describe what you see concisely (1-3 sentences) and answer any questions about the image."
    }

    /**
     * Transcribe audio using OpenAI Whisper API.
     * @param wavFile A WAV file (16kHz, 16-bit, mono)
     * @return Transcribed text
     */
    fun transcribe(wavFile: File): String {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "en")
            .addFormDataPart(
                "file",
                wavFile.name,
                wavFile.asRequestBody("audio/wav".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(WHISPER_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty Whisper response")

        if (!response.isSuccessful) {
            throw Exception("Whisper API error ${response.code}: $body")
        }

        return JSONObject(body).getString("text").trim()
    }

    /**
     * Get AI chat response using OpenAI Responses API.
     * @param userText The user's transcribed speech
     * @return AI response text
     */
    fun chat(userText: String): String {
        val input = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userText)
            })
        }

        val json = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", input)
        }

        val request = Request.Builder()
            .url(CHAT_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty chat response")

        if (!response.isSuccessful) {
            throw Exception("Chat API error ${response.code}: $body")
        }

        return JSONObject(body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    /**
     * Vision chat: send user text + JPEG image to GPT-4o for visual understanding.
     * @param userText The user's transcribed question (or a default prompt)
     * @param jpegBytes Raw JPEG image bytes from the ESP32 camera
     * @return AI response text describing/answering about the image
     */
    fun visionChat(userText: String, jpegBytes: ByteArray): String {
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val imageUrl = "data:image/jpeg;base64,$base64Image"

        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", userText)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", imageUrl)
                })
            })
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", VISION_SYSTEM_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            })
        }

        val json = JSONObject().apply {
            put("model", "gpt-4o")
            put("messages", messages)
            put("max_tokens", 300)
        }

        val request = Request.Builder()
            .url(CHAT_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty vision response")

        if (!response.isSuccessful) {
            throw Exception("Vision API error ${response.code}: $body")
        }

        return JSONObject(body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }
}
