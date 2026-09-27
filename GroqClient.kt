package com.akshat.edithglasses

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class GroqClient {
    // apiKey comes from ApiKeyManager (encrypted on-device storage) — never
    // from BuildConfig / local.properties, so it's never baked into source.
    suspend fun ask(question: String, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Groq API key is not configured")
            )
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (URL("https://api.groq.com/openai/v1/chat/completions")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val body = JSONObject().apply {
                put("model", "openai/gpt-oss-20b")
                put("stream", false)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put(
                            "content",
                            "You are JARVIS inside EDITH smart glasses. " +
                                "Answer clearly, accurately, and concisely. " +
                                "Plain text only. No markdown tables, no emojis, " +
                                "and no unnecessary preamble. Keep answers suitable " +
                                "for a small 128x64 OLED display."
                        )
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", question)
                    })
                })
            }.toString()

            connection.outputStream.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = BufferedReader(
                InputStreamReader(stream, Charsets.UTF_8)
            ).use { it.readText() }

            if (status !in 200..299) {
                val message = runCatching {
                    val errorObject = JSONObject(responseText).opt("error")
                    when (errorObject) {
                        is JSONObject -> errorObject.optString("message")
                        else -> errorObject?.toString().orEmpty()
                    }
                }.getOrDefault("")

                val suffix = if (message.isNotBlank()) ": $message" else ""
                return@withContext Result.failure(
                    RuntimeException("Groq request failed ($status)$suffix")
                )
            }

            val answer = JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()

            if (answer.isBlank()) {
                Result.failure(RuntimeException("Groq returned an empty answer"))
            } else {
                Result.success(answer)
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}
