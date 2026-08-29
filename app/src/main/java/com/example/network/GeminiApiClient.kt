package com.example.network

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiApiClient {
  private val client = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

  suspend fun askCopilot(prompt: String, contextInfo: String): String = withContext(Dispatchers.IO) {
    val apiKey = BuildConfig.GEMINI_API_KEY
    if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
      return@withContext "Gemini API key is not configured. Please add your Gemini API key in the Secrets panel in AI Studio to enable the AI Copilot."
    }

    val systemInstruction = "You are the expert Financial & Policy Copilot for Agent Wallet OS — an AI-agent payment wallet, policy engine, double-entry ledger, and x402/MPP payment protocol gateway. Context:\n$contextInfo"

    val jsonBody = JSONObject().apply {
      put("systemInstruction", JSONObject().apply {
        put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
      })
      put("contents", JSONArray().put(JSONObject().apply {
        put("parts", JSONArray().put(JSONObject().put("text", prompt)))
      }))
    }

    val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
    val request = Request.Builder()
      .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey")
      .post(body)
      .build()

    try {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@withContext "Error: Received HTTP ${response.code}"
        val responseString = response.body?.string() ?: return@withContext "Empty response"
        val root = JSONObject(responseString)
        val candidates = root.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
          val firstCandidate = candidates.getJSONObject(0)
          val content = firstCandidate.optJSONObject("content")
          val parts = content?.optJSONArray("parts")
          if (parts != null && parts.length() > 0) {
            return@withContext parts.getJSONObject(0).optString("text", "No response text")
          }
        }
        return@withContext "No candidate text generated."
      }
    } catch (e: Exception) {
      return@withContext "Error communicating with Gemini AI: ${e.localizedMessage}"
    }
  }
}
