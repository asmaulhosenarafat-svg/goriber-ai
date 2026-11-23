package com.example

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "AiOrchestrator"

class AiOrchestrator(
    private val apiSecurity: ApiSecurity,
    private val phoneController: PhoneController
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val commandSchema = Schema.obj(
        properties = mapOf(
            "controlId" to Schema.integer(description = "Matching control ID from 1 to 100, or 0 if conversational only."),
            "actionName" to Schema.string(description = "Human readable action title."),
            "parametersJson" to Schema.string(description = "JSON string of parameters, e.g. {\"value\":50} or {\"query\":\"YouTube\"}."),
            "explanation" to Schema.string(description = "Short explanation of what will change in user's language (Bengali or English)."),
            "replyText" to Schema.string(description = "Helpful assistant reply in Bengali or English."),
            "isDangerous" to Schema.boolean(description = "True if uninstall, lock, restart, or destructive file move.")
        )
    )

    private val codeStudioSchema = Schema.obj(
        properties = mapOf(
            "language" to Schema.string(description = "Primary language or web_bundle."),
            "explanation" to Schema.string(description = "Short summary in user's language."),
            "code" to Schema.string(description = "Complete code snippet when generating single-language code."),
            "html" to Schema.string(description = "HTML body/markup when building web/HTML-CSS-JS apps."),
            "css" to Schema.string(description = "CSS stylesheet when building web/HTML-CSS-JS apps."),
            "js" to Schema.string(description = "JavaScript logic when building web/HTML-CSS-JS apps.")
        )
    )

    private val geminiModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = commandSchema
            }
        )
    }

    private val geminiCodeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = codeStudioSchema
            }
        )
    }

    private val geminiFreeformModel by lazy {
        Firebase.ai.generativeModel(modelName = "gemini-3.8-flash")
    }

    data class AiProposal(
        val providerId: String,
        val providerName: String,
        val controlId: Int,
        val actionName: String,
        val parametersJson: String,
        val explanation: String,
        val replyText: String,
        val isDangerous: Boolean,
        val validated: Boolean,
        val errorMessage: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("providerId", providerId)
            put("providerName", providerName)
            put("controlId", controlId)
            put("actionName", actionName)
            put("parametersJson", parametersJson)
            put("explanation", explanation)
            put("replyText", replyText)
            put("isDangerous", isDangerous)
            put("validated", validated)
            if (errorMessage != null) put("errorMessage", errorMessage)
        }
    }

    suspend fun interpretCommand(userPrompt: String, requestedProvider: String? = null): String = coroutineScope {
        val telemetry = phoneController.getDeviceTelemetryJson()
        val systemInstruction = buildSystemPrompt(telemetry)

        val providersToRun = if (!requestedProvider.isNullOrBlank()) {
            listOf(ApiSecurity.AiProvider.fromId(requestedProvider))
        } else if (apiSecurity.isMultiAiMode()) {
            ApiSecurity.AiProvider.entries.filter { apiSecurity.isProviderEnabled(it.id) }
                .ifEmpty { listOf(apiSecurity.getDefaultProvider()) }
        } else {
            listOf(apiSecurity.getDefaultProvider())
        }

        val results = providersToRun.map { provider ->
            async { queryProvider(provider, systemInstruction, userPrompt) }
        }.awaitAll()

        val primary = results.firstOrNull { it.errorMessage == null } ?: results.first()
        val array = JSONArray()
        results.forEach { array.put(it.toJson()) }

        JSONObject().apply {
            put("primary", primary.toJson())
            put("allProposals", array)
            put("multiAi", results.size > 1)
        }.toString()
    }

    suspend fun generateCode(
        prompt: String,
        mode: String,
        requestedProvider: String? = null
    ): String {
        val provider = if (!requestedProvider.isNullOrBlank()) {
            ApiSecurity.AiProvider.fromId(requestedProvider)
        } else {
            apiSecurity.getDefaultProvider()
        }
        val sysPrompt = """
            You are an expert Senior Software Engineer and Web/App Studio AI inside Goriber AI.
            Mode requested: $mode.
            Return ONLY valid JSON with keys: language (string), explanation (string), code (string), html (string), css (string), js (string).
            If mode is 'web_bundle', put complete HTML body content in 'html', CSS in 'css', and JavaScript in 'js' (and leave 'code' empty or summary).
            If mode is a programming language (kotlin, python, javascript, sql, app_scaffold), put complete runnable code in 'code' and leave html/css/js empty.
        """.trimIndent()

        return try {
            val raw = callProviderJson(provider, sysPrompt, prompt, useCodeSchema = true)
            apiSecurity.recordUsage(provider.id, "Code Studio")
            val cleaned = cleanJsonFence(raw)
            val obj = JSONObject(cleaned)
            JSONObject().apply {
                put("ok", true)
                put("providerName", provider.displayName)
                put("language", obj.optString("language", mode))
                put("explanation", obj.optString("explanation", "Generated successfully."))
                put("code", obj.optString("code", ""))
                put("html", obj.optString("html", ""))
                put("css", obj.optString("css", ""))
                put("js", obj.optString("js", ""))
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "generateCode failed", e)
            val msg = e.localizedMessage ?: "Code generation failed."
            apiSecurity.recordError(provider.id, msg)
            JSONObject().apply {
                put("ok", false)
                put("providerName", provider.displayName)
                put("errorMessage", msg)
            }.toString()
        }
    }

    suspend fun runCustomAssistantPrompt(
        assistantName: String,
        personaSystemPrompt: String,
        userMessage: String,
        providerId: String?
    ): String {
        val provider = ApiSecurity.AiProvider.fromId(providerId ?: apiSecurity.getDefaultProvider().id)
        val combinedSys = "You are '$assistantName'. Persona instructions: $personaSystemPrompt"
        return try {
            val reply = callProviderFreeform(provider, combinedSys, userMessage)
            apiSecurity.recordUsage(provider.id, "Custom Assistant")
            JSONObject().apply {
                put("ok", true)
                put("providerName", provider.displayName)
                put("reply", reply)
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Custom assistant call failed."
            apiSecurity.recordError(provider.id, msg)
            JSONObject().apply {
                put("ok", false)
                put("providerName", provider.displayName)
                put("errorMessage", msg)
            }.toString()
        }
    }

    suspend fun analyzeDocumentContent(
        fileName: String,
        contentText: String,
        question: String,
        providerId: String?
    ): String {
        val provider = if (!providerId.isNullOrBlank()) ApiSecurity.AiProvider.fromId(providerId) else apiSecurity.getDefaultProvider()
        val sysPrompt = "You are Goriber AI File & Document Analyst. Analyze the uploaded file accurately and respond clearly in the user's language (Bengali or English)."
        val safeContent = contentText.take(20_000)
        val userPayload = "File: $fileName\nQuestion: $question\n\n--- FILE CONTENT ---\n$safeContent"
        return try {
            val reply = callProviderFreeform(provider, sysPrompt, userPayload)
            apiSecurity.recordUsage(provider.id, "File Analysis")
            JSONObject().apply {
                put("ok", true)
                put("providerName", provider.displayName)
                put("analysis", reply)
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "File analysis failed."
            apiSecurity.recordError(provider.id, msg)
            JSONObject().apply {
                put("ok", false)
                put("providerName", provider.displayName)
                put("errorMessage", msg)
            }.toString()
        }
    }

    suspend fun testConnection(providerId: String): JSONObject {
        val provider = ApiSecurity.AiProvider.fromId(providerId)
        val result = queryProvider(provider, "Respond strictly with JSON {\"controlId\":0,\"actionName\":\"Ping\",\"parametersJson\":\"{}\",\"explanation\":\"Connection OK\",\"replyText\":\"OK\",\"isDangerous\":false}", "Ping")
        return JSONObject().apply {
            put("providerId", provider.id)
            put("ok", result.errorMessage == null)
            put("message", result.errorMessage ?: "Connected to ${provider.displayName} successfully.")
        }
    }

    private suspend fun callProviderJson(
        provider: ApiSecurity.AiProvider,
        systemPrompt: String,
        userPrompt: String,
        useCodeSchema: Boolean = false
    ): String {
        return when (provider) {
            ApiSecurity.AiProvider.GEMINI -> {
                val customKey = apiSecurity.getDecryptedApiKey(provider.id)
                if (!customKey.isNullOrBlank()) {
                    callGeminiRest(customKey, systemPrompt, userPrompt, jsonMode = true)
                } else {
                    withTimeout(60_000L) {
                        val model = if (useCodeSchema) geminiCodeModel else geminiModel
                        val response = model.generateContent("$systemPrompt\n\nUser request: $userPrompt")
                        response.text ?: throw IllegalStateException("Gemini returned an empty response.")
                    }
                }
            }
            ApiSecurity.AiProvider.CHATGPT -> {
                val key = apiSecurity.getDecryptedApiKey(provider.id)
                    ?: throw IllegalStateException("ChatGPT API Key is not configured.")
                callOpenAiCompatible(apiSecurity.getEndpoint(provider.id), key, provider.defaultModel, systemPrompt, userPrompt, jsonMode = true)
            }
            ApiSecurity.AiProvider.DEEPSEEK -> {
                val key = apiSecurity.getDecryptedApiKey(provider.id)
                    ?: throw IllegalStateException("DeepSeek API Key is not configured.")
                callOpenAiCompatible(apiSecurity.getEndpoint(provider.id), key, provider.defaultModel, systemPrompt, userPrompt, jsonMode = true)
            }
            ApiSecurity.AiProvider.CLAUDE -> {
                val key = apiSecurity.getDecryptedApiKey(provider.id)
                    ?: throw IllegalStateException("Claude API Key is not configured.")
                callClaude(apiSecurity.getEndpoint(provider.id), key, provider.defaultModel, systemPrompt, userPrompt)
            }
        }
    }

    private suspend fun callProviderFreeform(
        provider: ApiSecurity.AiProvider,
        systemPrompt: String,
        userPrompt: String
    ): String {
        return when (provider) {
            ApiSecurity.AiProvider.GEMINI -> {
                val customKey = apiSecurity.getDecryptedApiKey(provider.id)
                if (!customKey.isNullOrBlank()) {
                    callGeminiRest(customKey, systemPrompt, userPrompt, jsonMode = false)
                } else {
                    withTimeout(60_000L) {
                        val response = geminiFreeformModel.generateContent("$systemPrompt\n\nUser request: $userPrompt")
                        response.text ?: throw IllegalStateException("Gemini returned an empty response.")
                    }
                }
            }
            ApiSecurity.AiProvider.CHATGPT -> {
                val key = apiSecurity.getDecryptedApiKey(provider.id)
                    ?: throw IllegalStateException("ChatGPT API Key is not configured.")
                callOpenAiCompatible(apiSecurity.getEndpoint(provider.id), key, provider.defaultModel, systemPrompt, userPrompt, jsonMode = false)
            }
            ApiSecurity.AiProvider.DEEPSEEK -> {
                val key = apiSecurity.getDecryptedApiKey(provider.id)
                    ?: throw IllegalStateException("DeepSeek API Key is not configured.")
                callOpenAiCompatible(apiSecurity.getEndpoint(provider.id), key, provider.defaultModel, systemPrompt, userPrompt, jsonMode = false)
            }
            ApiSecurity.AiProvider.CLAUDE -> {
                val key = apiSecurity.getDecryptedApiKey(provider.id)
                    ?: throw IllegalStateException("Claude API Key is not configured.")
                callClaude(apiSecurity.getEndpoint(provider.id), key, provider.defaultModel, systemPrompt, userPrompt)
            }
        }
    }

    private suspend fun queryProvider(
        provider: ApiSecurity.AiProvider,
        systemPrompt: String,
        userPrompt: String
    ): AiProposal {
        if (apiSecurity.getUsageCount(provider.id) >= apiSecurity.getUsageLimit(provider.id)) {
            val msg = "Usage limit reached for ${provider.displayName}."
            apiSecurity.recordError(provider.id, msg)
            return errorProposal(provider, msg)
        }

        return try {
            val rawJson = callProviderJson(provider, systemPrompt, userPrompt, useCodeSchema = false)
            apiSecurity.recordUsage(provider.id, "Connected")
            parseAndValidateProposal(provider, rawJson)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "${provider.id} timed out", e)
            val msg = "Request timed out after 60 seconds."
            apiSecurity.recordError(provider.id, msg)
            errorProposal(provider, msg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "${provider.id} failed", e)
            val msg = e.localizedMessage ?: "AI provider call failed."
            apiSecurity.recordError(provider.id, msg)
            errorProposal(provider, msg)
        }
    }

    private fun cleanJsonFence(rawText: String): String {
        return rawText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun parseAndValidateProposal(provider: ApiSecurity.AiProvider, rawText: String): AiProposal {
        val obj = JSONObject(cleanJsonFence(rawText))
        val cid = obj.optInt("controlId", 0)
        val valid = cid == 0 || phoneController.isValidControl(cid)
        val meta = if (cid > 0) phoneController.getControlMetadata(cid) else null
        return AiProposal(
            providerId = provider.id,
            providerName = provider.displayName,
            controlId = if (valid) cid else 0,
            actionName = meta?.name ?: obj.optString("actionName", "Assistant Reply"),
            parametersJson = obj.optString("parametersJson", "{}"),
            explanation = obj.optString("explanation", ""),
            replyText = obj.optString("replyText", "Ready."),
            isDangerous = meta?.isDangerous ?: obj.optBoolean("isDangerous", false),
            validated = valid
        )
    }

    private fun callOpenAiCompatible(
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean
    ): String {
        val bodyJson = JSONObject().apply {
            put("model", model)
            if (jsonMode) {
                put("response_format", JSONObject().put("type", "json_object"))
            }
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }
        val req = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${raw.take(180)}")
            val root = JSONObject(raw)
            return root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    }

    private fun callClaude(
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val bodyJson = JSONObject().apply {
            put("model", model)
            put("max_tokens", 2048)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }
        val req = Request.Builder()
            .url(endpoint)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${raw.take(180)}")
            val root = JSONObject(raw)
            return root.getJSONArray("content").getJSONObject(0).getString("text")
        }
    }

    private fun callGeminiRest(apiKey: String, systemPrompt: String, userPrompt: String, jsonMode: Boolean): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val bodyJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().put("parts", JSONArray().apply {
                    put(JSONObject().put("text", "$systemPrompt\n\nUser request: $userPrompt"))
                }))
            })
            if (jsonMode) {
                put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            }
        }
        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${raw.take(180)}")
            val root = JSONObject(raw)
            return root.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        }
    }

    private fun errorProposal(provider: ApiSecurity.AiProvider, message: String) = AiProposal(
        providerId = provider.id,
        providerName = provider.displayName,
        controlId = 0,
        actionName = "Error",
        parametersJson = "{}",
        explanation = message,
        replyText = message,
        isDangerous = false,
        validated = false,
        errorMessage = message
    )

    private fun buildSystemPrompt(telemetryJson: String): String = """
        You are Goriber AI Smart Phone Controller Assistant. Understand Bengali or English requests and map them to ONE of the 100 official control IDs below, or 0 if purely informational.
        Return ONLY valid JSON with keys: controlId (int 0..100), actionName (string), parametersJson (JSON string), explanation (string), replyText (string), isDangerous (boolean).
        Key Control IDs:
        1: Screen Brightness (parametersJson: {"value": 0-100})
        2: Auto Brightness ({"enabled": true/false})
        3: Screen Timeout ({"seconds": 15-1800})
        4: Dark Mode Settings, 5: Light Mode Settings, 6: Screen Rotation ({"enabled": true/false}), 10: Wallpaper Control
        11-20: Keyboard Open(11), Close(12), Language(13), Sound(15), Vibration(16), Voice Typing(19), Settings(20)
        21: Installed Apps, 22: Open App ({"query":"YouTube"}), 23: App Settings, 27: App Usage, 28: Uninstall App (dangerous), 29: Clear App Cache, 30: Default Apps
        31: Media Volume ({"value":0-100}), 32: Call Volume, 33: Alarm Volume, 34: Notification Volume, 35: Ring Volume, 36: Silent Mode, 37: Vibrate Mode, 38: Ringtone, 39: Do Not Disturb, 40: Audio Output
        41: Wi-Fi Settings, 42: Wi-Fi Status, 44: Mobile Data Settings, 45: Bluetooth Settings, 46: Hotspot Settings, 47: Airplane Mode Settings (dangerous), 48: SIM Info, 49: Network Info, 50: Internet Status
        60: Permission Dashboard, 61: File Manager, 62: Image Viewer, 70: Storage Info
        71: Battery Percentage, 72: Battery Health, 73: Charging Status, 74: Battery Saver, 76: RAM Info, 77: CPU Info, 78: Device Temperature, 79: Storage Optimization, 80: Performance Dashboard
        81: Device Info, 82: Android Version, 83: System Settings, 84: Date & Time, 85: Language Settings, 86: Accessibility Settings, 87: Notification Panel, 88: Quick Settings, 89: Screen Lock, 90: Restart Menu (dangerous)
        91: Voice Command, 93: Smart App Launcher ({"query":"..."}), 94: Smart Settings Assistant ({"setting":"..."}), 95: Daily Automation, 96: Scheduled Tasks, 99: Floating Controller.
        Live Device Telemetry: $telemetryJson
    """.trimIndent()
}
