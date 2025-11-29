package com.whispereverywhere.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI Whisper API Service
 * Handles audio transcription using the Whisper-1 model (currently the best available via API)
 */
class WhisperApiService(private var apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun updateApiKey(newKey: String) {
        apiKey = newKey
    }

    /**
     * Transcribe audio file using Whisper API
     * @param audioFile The audio file to transcribe (WAV, MP3, etc.)
     * @param language Optional language code (e.g., "en", "es"). If null, auto-detects.
     * @return TranscriptionResult containing the transcribed text or error
     */
    suspend fun transcribe(
        audioFile: File,
        language: String? = null
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext TranscriptionResult.Error("API key not set")
            }

            if (!audioFile.exists()) {
                return@withContext TranscriptionResult.Error("Audio file not found")
            }

            // Build multipart request
            val requestBodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", WHISPER_MODEL)
                .addFormDataPart("response_format", "json")

            // Add language if specified (otherwise Whisper auto-detects)
            language?.let {
                requestBodyBuilder.addFormDataPart("language", it)
            }

            val requestBody = requestBodyBuilder.build()

            val request = Request.Builder()
                .url(WHISPER_API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext when (response.code) {
                    401 -> TranscriptionResult.Error("Invalid API key")
                    429 -> TranscriptionResult.Error("Rate limit exceeded. Please try again later.")
                    500, 502, 503 -> TranscriptionResult.Error("OpenAI server error. Please try again.")
                    else -> TranscriptionResult.Error("API error (${response.code}): $errorBody")
                }
            }

            val responseBody = response.body?.string()
                ?: return@withContext TranscriptionResult.Error("Empty response")

            try {
                val transcriptionResponse = json.decodeFromString<WhisperResponse>(responseBody)
                TranscriptionResult.Success(transcriptionResponse.text)
            } catch (e: Exception) {
                TranscriptionResult.Error("Failed to parse response: ${e.message}")
            }
        } catch (e: IOException) {
            TranscriptionResult.Error("Network error: ${e.message}")
        } catch (e: Exception) {
            TranscriptionResult.Error("Unexpected error: ${e.message}")
        }
    }

    /**
     * Translate audio file to English using Whisper API
     */
    suspend fun translateToEnglish(audioFile: File): TranscriptionResult = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext TranscriptionResult.Error("API key not set")
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", WHISPER_MODEL)
                .addFormDataPart("response_format", "json")
                .build()

            val request = Request.Builder()
                .url(WHISPER_TRANSLATIONS_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext TranscriptionResult.Error("API error (${response.code}): $errorBody")
            }

            val responseBody = response.body?.string()
                ?: return@withContext TranscriptionResult.Error("Empty response")

            val transcriptionResponse = json.decodeFromString<WhisperResponse>(responseBody)
            TranscriptionResult.Success(transcriptionResponse.text)
        } catch (e: Exception) {
            TranscriptionResult.Error("Translation failed: ${e.message}")
        }
    }

    companion object {
        private const val WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val WHISPER_TRANSLATIONS_URL = "https://api.openai.com/v1/audio/translations"
        private const val WHISPER_MODEL = "whisper-1" // Currently the only/best model available via API
    }
}

@Serializable
data class WhisperResponse(
    val text: String
)

sealed class TranscriptionResult {
    data class Success(val text: String) : TranscriptionResult()
    data class Error(val message: String) : TranscriptionResult()
}
