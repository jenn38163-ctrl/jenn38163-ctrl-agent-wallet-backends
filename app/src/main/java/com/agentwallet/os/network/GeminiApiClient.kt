package com.agentwallet.os.network

import com.agentwallet.os.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiApiClient {
  private val client = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

  suspend fun askCopilot(prompt: String, contextInfo: String): String = withContext(Dispatchers.IO) {
    val backendUrl = BuildConfig.BACKEND_URL.trim().trimEnd('/')
    if (backendUrl.isBlank() || backendUrl == "http://localhost:8080") {
      return@withContext "Agent Wallet Backend is not configured. Please set BACKEND_URL in your Secrets panel to enable the AI Copilot."
    }

    val idToken = runCatching {
      FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
    }.getOrNull()

    if (idToken.isNullOrBlank()) {
      return@withContext "Please sign in with Firebase to authenticate AI Copilot requests with the backend."
    }

    val systemInstruction =
      "You are the expert Financial & Policy Copilot for Agent Wallet OS — an AI-agent payment wallet, policy engine, double-entry ledger, and x402/MPP payment protocol gateway. Context:\n$contextInfo"
    val fullPrompt = "$systemInstruction\n\nUser Request: $prompt"

    val jsonBody = JSONObject().apply {
      put("prompt", fullPrompt)
    }

    val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    val request = Request.Builder()
      .url("$backendUrl/v1/ai/gemini")
      .header("Authorization", "Bearer $idToken")
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
