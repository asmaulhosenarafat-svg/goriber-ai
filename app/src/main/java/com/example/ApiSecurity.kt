package com.example

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Hardware-backed Android Keystore encryption manager for user-supplied AI API keys
 * and salted PBKDF2 Admin Security Vault passcodes.
 * Plaintext API keys NEVER leave native memory into HTML/JS.
 */
class ApiSecurity(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class AiProvider(val id: String, val displayName: String, val defaultEndpoint: String, val defaultModel: String) {
        GEMINI("gemini", "Google Gemini", "firebase-ai-logic://gemini-3.8-flash", "gemini-3.8-flash"),
        CHATGPT("chatgpt", "ChatGPT (OpenAI)", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
        CLAUDE("claude", "Claude (Anthropic)", "https://api.anthropic.com/v1/messages", "claude-3-5-sonnet-20241022"),
        DEEPSEEK("deepseek", "DeepSeek AI", "https://api.deepseek.com/chat/completions", "deepseek-chat");

        companion object {
            fun fromId(id: String): AiProvider = entries.firstOrNull { it.id == id } ?: GEMINI
        }
    }

    init {
        ensureDefaults()
    }

    private fun ensureDefaults() {
        if (!prefs.contains(KEY_DEFAULT_PROVIDER)) {
            prefs.edit()
                .putString(KEY_DEFAULT_PROVIDER, AiProvider.GEMINI.id)
                .putBoolean(enabledKey(AiProvider.GEMINI.id), true)
                .putBoolean(KEY_MULTI_AI_MODE, false)
                .apply()
        }
    }

    private fun getOrCreateAesKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? {
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= 12) return null
            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun saveApiKey(
        providerId: String,
        apiKey: String,
        customEndpoint: String? = null,
        usageLimit: Int = 100
    ) {
        val cleanKey = apiKey.trim()
        val editor = prefs.edit()
        if (cleanKey.isNotEmpty()) {
            editor.putString(apiKeyPref(providerId), encrypt(cleanKey))
            editor.putString(maskPref(providerId), maskKey(cleanKey))
            editor.putBoolean(enabledKey(providerId), true)
        }
        if (customEndpoint != null) {
            editor.putString(endpointPref(providerId), customEndpoint.trim())
        }
        editor.putInt(limitPref(providerId), usageLimit.coerceIn(1, 100000))
        editor.putString(statusPref(providerId), "Configured")
        editor.apply()
    }

    fun getDecryptedApiKey(providerId: String): String? {
        val enc = prefs.getString(apiKeyPref(providerId), null) ?: return null
        return decrypt(enc)
    }

    fun deleteApiKey(providerId: String) {
        prefs.edit()
            .remove(apiKeyPref(providerId))
            .remove(maskPref(providerId))
            .putString(statusPref(providerId), if (providerId == AiProvider.GEMINI.id) "Built-in Ready" else "Not Configured")
            .apply()
        if (providerId != AiProvider.GEMINI.id) {
            prefs.edit().putBoolean(enabledKey(providerId), false).apply()
        }
    }

    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        prefs.edit().putBoolean(enabledKey(providerId), enabled).apply()
    }

    fun isProviderEnabled(providerId: String): Boolean {
        return prefs.getBoolean(enabledKey(providerId), providerId == AiProvider.GEMINI.id)
    }

    fun setDefaultProvider(providerId: String) {
        prefs.edit().putString(KEY_DEFAULT_PROVIDER, AiProvider.fromId(providerId).id).apply()
    }

    fun getDefaultProvider(): AiProvider {
        return AiProvider.fromId(prefs.getString(KEY_DEFAULT_PROVIDER, AiProvider.GEMINI.id) ?: AiProvider.GEMINI.id)
    }

    fun setMultiAiMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MULTI_AI_MODE, enabled).apply()
    }

    fun isMultiAiMode(): Boolean = prefs.getBoolean(KEY_MULTI_AI_MODE, false)

    fun getEndpoint(providerId: String): String {
        val custom = prefs.getString(endpointPref(providerId), "")?.trim().orEmpty()
        return custom.ifEmpty { AiProvider.fromId(providerId).defaultEndpoint }
    }

    fun getUsageCount(providerId: String): Int = prefs.getInt(usagePref(providerId), 0)

    fun getUsageLimit(providerId: String): Int = prefs.getInt(limitPref(providerId), 500)

    fun recordUsage(providerId: String, status: String) {
        val current = getUsageCount(providerId)
        prefs.edit()
            .putInt(usagePref(providerId), current + 1)
            .putString(statusPref(providerId), status)
            .apply()
    }

    fun recordError(providerId: String, message: String) {
        prefs.edit().putString(statusPref(providerId), "Error").apply()
        val arr = JSONArray(prefs.getString(KEY_ERROR_HISTORY, "[]") ?: "[]")
        val entry = JSONObject().apply {
            put("provider", providerId)
            put("message", message.take(240))
            put("timestamp", System.currentTimeMillis())
        }
        val next = JSONArray()
        next.put(entry)
        for (i in 0 until minOf(arr.length(), 24)) {
            next.put(arr.get(i))
        }
        prefs.edit().putString(KEY_ERROR_HISTORY, next.toString()).apply()
    }

    fun clearErrorHistory() {
        prefs.edit().putString(KEY_ERROR_HISTORY, "[]").apply()
    }

    fun getProvidersSnapshotJson(): String {
        val list = JSONArray()
        AiProvider.entries.forEach { p ->
            val hasCustomKey = prefs.contains(apiKeyPref(p.id))
            val isBuiltInGemini = p == AiProvider.GEMINI && !hasCustomKey
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.displayName)
                put("enabled", isProviderEnabled(p.id))
                put("isDefault", getDefaultProvider() == p)
                put("hasKey", hasCustomKey || isBuiltInGemini)
                put("hasCustomKey", hasCustomKey)
                put("maskedKey", when {
                    hasCustomKey -> prefs.getString(maskPref(p.id), "••••") ?: "••••"
                    isBuiltInGemini -> "AppCheck-Keyless"
                    else -> ""
                })
                put("endpoint", getEndpoint(p.id))
                put("model", p.defaultModel)
                put("usageCount", getUsageCount(p.id))
                put("usageLimit", getUsageLimit(p.id))
                put("status", prefs.getString(statusPref(p.id), if (isBuiltInGemini) "Built-in Ready" else "Not Configured"))
            }
            list.put(obj)
        }
        val root = JSONObject().apply {
            put("defaultProvider", getDefaultProvider().id)
            put("multiAiMode", isMultiAiMode())
            put("providers", list)
            put("errors", JSONArray(prefs.getString(KEY_ERROR_HISTORY, "[]") ?: "[]"))
            put("adminPinSet", hasAdminPasscode())
        }
        return root.toString()
    }

    // --- Admin Security Vault Passcode (PBKDF2WithHmacSHA256) ---
    fun hasAdminPasscode(): Boolean = prefs.contains(KEY_ADMIN_HASH) && prefs.contains(KEY_ADMIN_SALT)

    fun setAdminPasscode(passcode: String): Boolean {
        if (passcode.length < 4) return false
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hash = pbkdf2(passcode, salt)
        prefs.edit()
            .putString(KEY_ADMIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_ADMIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
        return true
    }

    fun verifyAdminPasscode(passcode: String): Boolean {
        val saltStr = prefs.getString(KEY_ADMIN_SALT, null) ?: return false
        val hashStr = prefs.getString(KEY_ADMIN_HASH, null) ?: return false
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        val expected = Base64.decode(hashStr, Base64.NO_WRAP)
        val actual = pbkdf2(passcode, salt)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun pbkdf2(passcode: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passcode.toCharArray(), salt, 65536, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun maskKey(key: String): String {
        if (key.length <= 8) return "••••••••"
        return "${key.take(4)}••••••${key.takeLast(4)}"
    }

    private fun apiKeyPref(id: String) = "enc_key_$id"
    private fun maskPref(id: String) = "mask_key_$id"
    private fun enabledKey(id: String) = "enabled_$id"
    private fun endpointPref(id: String) = "endpoint_$id"
    private fun limitPref(id: String) = "limit_$id"
    private fun usagePref(id: String) = "usage_$id"
    private fun statusPref(id: String) = "status_$id"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ai_controller_api_aes_key"
        private const val PREFS_NAME = "ai_controller_secure_vault"
        private const val KEY_DEFAULT_PROVIDER = "default_provider"
        private const val KEY_MULTI_AI_MODE = "multi_ai_mode"
        private const val KEY_ERROR_HISTORY = "api_error_history"
        private const val KEY_ADMIN_SALT = "admin_vault_salt"
        private const val KEY_ADMIN_HASH = "admin_vault_hash"
    }
}
