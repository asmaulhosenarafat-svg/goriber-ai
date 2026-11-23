package com.example.data

import android.util.Log
import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

const val ADMIN_EMAIL = "arafatislam989834@gmail.com"
const val DAY_MS = 86_400_000L

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(exception: Throwable, operationType: OperationType, path: String?): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

data class PremiumFeatureMeta(
    val key: String,
    val titleEn: String,
    val titleBn: String,
    val descriptionBn: String,
    val targetPage: String
)

val PREMIUM_FEATURE_CATALOG: List<PremiumFeatureMeta> = listOf(
    PremiumFeatureMeta("adv_ai_models", "Advanced AI Models", "উন্নত AI মডেল", "উন্নত Gemini, GPT-4o, Claude Sonnet ও DeepSeek মডেল ব্যবহার।", "ai_manager"),
    PremiumFeatureMeta("multi_ai", "Multi-AI Parallel Engine", "একাধিক AI মডেল যুক্ত করা", "একসাথে ৪টি AI-কে প্রশ্ন করে সেরা উত্তর ও কমান্ড তুলনা।", "ai_manager"),
    PremiumFeatureMeta("custom_api_keys", "Custom AI API Keys", "নিজস্ব AI API Key Vault", "Hardware Keystore-এ ChatGPT, Claude, DeepSeek নিজস্ব API Key যুক্ত করা।", "api_manager"),
    PremiumFeatureMeta("adv_ai_chat", "Advanced AI Chat System", "উন্নত AI Chat System", "দীর্ঘ কনটেক্সট, স্মার্ট টুল কলিং ও ভয়েস অ্যাসিস্ট্যান্ট।", "ai_assistant"),
    PremiumFeatureMeta("ai_code_gen", "AI Code Generator", "AI দিয়ে কোড তৈরি", "Kotlin, Python, JS, SQL ও স্ক্রিপ্ট জেনারেশন।", "code_studio"),
    PremiumFeatureMeta("html_css_js_studio", "HTML/CSS/JS Live Studio", "HTML, CSS, JS তৈরি ও প্রিভিউ", "ইন-অ্যাপ কোড জেনারেটর এবং তাৎক্ষণিক লাইভ ওয়েব প্রিভিউ।", "code_studio"),
    PremiumFeatureMeta("app_builder_tools", "App Builder & Manager Tools", "অ্যাপ তৈরি ও ম্যানেজমেন্ট টুলস", "অ্যাপ টেমপ্লেট, প্রজেক্ট স্কেফল্ড ও অ্যাডভান্সড অ্যাপ ম্যানেজার।", "code_studio"),
    PremiumFeatureMeta("adv_phone_controls", "Advanced Phone Controls", "অ্যাডভান্সড ফোন কন্ট্রোল", "উচ্চ-সক্ষমতার কন্ট্রোল ম্যাক্রো ও দ্রুত ব্যাচ অ্যাকশন।", "phone_control"),
    PremiumFeatureMeta("custom_ai_assistant", "Custom AI Assistant Builder", "Custom AI Assistant তৈরি", "নিজের মতো করে কাস্টম সিস্টেম প্রম্পট ও পার্সোনা অ্যাসিস্ট্যান্ট।", "custom_assistants"),
    PremiumFeatureMeta("adv_automation", "Advanced Automation", "অ্যাডভান্সড অটোমেশন", "আনলিমিটেড অটোমেশন রুলস ও স্মার্ট ট্রিগার।", "automation_manager"),
    PremiumFeatureMeta("higher_limits", "Higher AI Usage Limits", "বেশি ব্যবহারের লিমিট", "ফ্রি ইউজার দৈনিক ১৫টি, প্রিমিয়াম ইউজার ১০০০+ AI কোয়েরি।", "ai_assistant"),
    PremiumFeatureMeta("file_ai_analysis", "File Upload & AI Analysis", "ফাইল আপলোড ও AI বিশ্লেষণ", "যেকোনো কোড, ডকুমেন্ট বা লগ ফাইল SAF দিয়ে দিয়ে AI বিশ্লেষণ।", "custom_assistants"),
    PremiumFeatureMeta("gold_themes", "Black & Gold Premium Themes", "Black & Gold প্রিমিয়াম থিম", "রাজকীয় Obsidian Black ও Royal Gold প্রিমিয়াম থিম আনলক।", "app_settings"),
    PremiumFeatureMeta("fast_ai_processing", "Fast AI Processing", "দ্রুত AI প্রসেসিং", "প্রায়োরিটি রেসপন্স ও কম লেটেন্সি রিকোয়েস্ট।", "ai_assistant"),
    PremiumFeatureMeta("enhanced_security", "Enhanced Security", "উন্নত সিকিউরিটি সিস্টেম", "Admin/User Vault PIN, অডিট লগ এবং সুরক্ষিত কী এনক্রিপশন।", "security_center")
)

fun defaultFeatureFlagsMap(): Map<String, Boolean> =
    PREMIUM_FEATURE_CATALOG.associate { it.key to true }

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "User",
    val email: String = "",
    val planTier: String = "free",
    val premiumStatus: String = "free",
    val packageId: String = "",
    val packageName: String = "",
    val subscriptionId: String = "",
    val lifetimePlan: Boolean = false,
    val giftedByAdmin: Boolean = false,
    val purchasedAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    val banned: Boolean = false,
    val banReason: String = "",
    val themeMode: String = "dark",
    val accentColor: String = "cyan",
    val totalCommands: Long = 0L,
    val totalAiQueries: Long = 0L,
    val lastAction: String = "Signed in",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun isEffectivelyPremium(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (banned) return false
        if (premiumStatus != "active") return false
        if (lifetimePlan || expiresAtMs == 0L) return true
        return expiresAtMs > nowMs
    }

    fun remainingDays(nowMs: Long = System.currentTimeMillis()): Long {
        if (!isEffectivelyPremium(nowMs)) return 0L
        if (lifetimePlan || expiresAtMs == 0L) return 99999L
        val diff = (expiresAtMs - nowMs).coerceAtLeast(0L)
        return ((diff + DAY_MS - 1L) / DAY_MS)
    }
}

@Keep
@IgnoreExtraProperties
data class PremiumPackage(
    @DocumentId val id: String = "",
    val packageId: String = "",
    val name: String = "",
    val nameBn: String = "",
    val description: String = "",
    val durationDays: Long = 30L,
    val lifetimePlan: Boolean = false,
    val priceBdt: Long = 299L,
    val playProductId: String = "",
    val tier: String = "pro",
    val featuresSummary: String = "",
    val active: Boolean = true,
    val sortOrder: Long = 1L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

val DEFAULT_PREMIUM_PACKAGES: List<PremiumPackage> = listOf(
    PremiumPackage(
        id = "pkg_7d",
        packageId = "pkg_7d",
        name = "7 Days Premium",
        nameBn = "৭ দিনের প্রিমিয়াম",
        description = "সাপ্তাহিক ট্রায়াল প্রো প্যাক — সব ১৫টি প্রিমিয়াম ফিচার ৭ দিনের জন্য।",
        durationDays = 7L,
        lifetimePlan = false,
        priceBdt = 99L,
        playProductId = "goriber_ai_7d",
        tier = "pro",
        featuresSummary = "All 15 Premium Features • 7 Days Access • Black & Gold Theme",
        active = true,
        sortOrder = 1L
    ),
    PremiumPackage(
        id = "pkg_30d",
        packageId = "pkg_30d",
        name = "30 Days Premium",
        nameBn = "৩০ দিনের প্রিমিয়াম",
        description = "সবচেয়ে জনপ্রিয় মাসিক প্রো প্যাক — Multi-AI, Code Studio এবং কাস্টম অ্যাসিস্ট্যান্ট।",
        durationDays = 30L,
        lifetimePlan = false,
        priceBdt = 299L,
        playProductId = "goriber_ai_30d",
        tier = "pro",
        featuresSummary = "All 15 Premium Features • 30 Days • Priority AI • Code Studio",
        active = true,
        sortOrder = 2L
    ),
    PremiumPackage(
        id = "pkg_90d",
        packageId = "pkg_90d",
        name = "90 Days Premium",
        nameBn = "৯০ দিনের প্রিমিয়াম",
        description = "৩ মাসের সাশ্রয়ী প্যাক — অ্যাডভান্সড অটোমেশন ও ফাইল এনালাইসিস সহ।",
        durationDays = 90L,
        lifetimePlan = false,
        priceBdt = 749L,
        playProductId = "goriber_ai_90d",
        tier = "pro",
        featuresSummary = "All 15 Premium Features • 90 Days • Save 16% • Fast AI",
        active = true,
        sortOrder = 3L
    ),
    PremiumPackage(
        id = "pkg_180d",
        packageId = "pkg_180d",
        name = "180 Days Premium",
        nameBn = "১৮০ দিনের প্রিমিয়াম",
        description = "৬ মাসের প্রো প্যাক — নির্বিঘ্ন প্রোডাক্টিভিটি ও অ্যাডভান্সড কন্ট্রোল।",
        durationDays = 180L,
        lifetimePlan = false,
        priceBdt = 1349L,
        playProductId = "goriber_ai_180d",
        tier = "pro",
        featuresSummary = "All 15 Premium Features • 180 Days • Save 25%",
        active = true,
        sortOrder = 4L
    ),
    PremiumPackage(
        id = "pkg_365d",
        packageId = "pkg_365d",
        name = "365 Days Premium",
        nameBn = "৩৬৫ দিনের প্রিমিয়াম",
        description = "১ বছরের বাৎসরিক প্যাক — সর্বোচ্চ সাশ্রয় ও প্রায়োরিটি সাপোর্ট।",
        durationDays = 365L,
        lifetimePlan = false,
        priceBdt = 2499L,
        playProductId = "goriber_ai_365d",
        tier = "pro",
        featuresSummary = "All 15 Premium Features • 365 Days • Save 30%",
        active = true,
        sortOrder = 5L
    ),
    PremiumPackage(
        id = "pkg_lifetime",
        packageId = "pkg_lifetime",
        name = "Lifetime Premium",
        nameBn = "আজীবন প্রিমিয়াম (Lifetime)",
        description = "একবার পেমেন্টে আজীবন সব প্রিমিয়াম ফিচার, এন্টারপ্রাইজ টুলস ও গোল্ডেন থিম।",
        durationDays = 0L,
        lifetimePlan = true,
        priceBdt = 5999L,
        playProductId = "goriber_ai_lifetime",
        tier = "enterprise",
        featuresSummary = "Lifetime Access • Enterprise Tier • All Future Pro Updates",
        active = true,
        sortOrder = 6L
    )
)

@Keep
@IgnoreExtraProperties
data class PremiumAppConfig(
    @DocumentId val id: String = "premium_features",
    val freeDailyAiLimit: Long = 15L,
    val merchantBkashNumber: String = "01700-000000 (Configure in Admin)",
    val merchantNagadNumber: String = "01700-000000 (Configure in Admin)",
    val merchantRocketNumber: String = "01700-000000 (Configure in Admin)",
    val webhookVerifyUrl: String = "",
    val promoBanner: String = "Upgrade to Black & Gold Premium for AI Code Studio & Multi-AI!",
    val featureFlags: Map<String, Boolean> = defaultFeatureFlagsMap(),
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ActivityLog(
    @DocumentId val id: String = "",
    val userId: String = "",
    val controlId: Long = 0L,
    val actionName: String = "",
    val category: String = "System",
    val aiProvider: String = "Manual",
    val permissionStatus: String = "Granted",
    val userConfirmed: Boolean = true,
    val success: Boolean = true,
    val errorDetails: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AutomationRule(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val triggerType: String = "daily",
    val triggerValue: String = "08:00",
    val actionCommand: String = "",
    val enabled: Boolean = true,
    val lastRunLabel: String = "Never",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CustomAssistant(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val roleTitle: String = "",
    val systemPrompt: String = "",
    val providerId: String = "gemini",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PaymentRequest(
    @DocumentId val id: String = "",
    val userId: String = "",
    val userEmail: String = "",
    val planId: String = "pkg_30d",
    val packageName: String = "30 Days Premium",
    val durationDays: Long = 30L,
    val lifetimePlan: Boolean = false,
    val method: String = "bkash",
    val senderNumber: String = "",
    val transactionId: String = "",
    val playPurchaseToken: String = "",
    val amountBdt: Long = 299L,
    val status: String = "pending",
    val refundReason: String = "",
    val adminNote: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class UserTelemetry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val userEmail: String = "",
    val displayName: String = "User",
    val planTier: String = "free",
    val premiumStatus: String = "free",
    val packageName: String = "",
    val lifetimePlan: Boolean = false,
    val expiresAtMs: Long = 0L,
    val banned: Boolean = false,
    val commandsCount: Long = 0L,
    val aiQueriesCount: Long = 0L,
    val lastCommand: String = "",
    val deviceModel: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AdminAuditLog(
    @DocumentId val id: String = "",
    val adminUid: String = "",
    val adminEmail: String = "",
    val actionType: String = "",
    val targetUserId: String = "",
    val targetLabel: String = "",
    val details: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)
