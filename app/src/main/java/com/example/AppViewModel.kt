package com.example

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ActivityLog
import com.example.data.AdminAuditLog
import com.example.data.AutomationRule
import com.example.data.CustomAssistant
import com.example.data.DEFAULT_PREMIUM_PACKAGES
import com.example.data.FirestoreRepository
import com.example.data.PREMIUM_FEATURE_CATALOG
import com.example.data.PaymentRequest
import com.example.data.PremiumAppConfig
import com.example.data.PremiumPackage
import com.example.data.UserProfile
import com.example.data.UserTelemetry
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

class AppViewModel(
    private val repository: FirestoreRepository,
    private val currentUserId: String
) : ViewModel() {

    val isAdmin: Boolean = repository.isCurrentUserAdmin()

    init {
        viewModelScope.launch {
            repository.ensureUserProfileInitialized()
        }
    }

    val userProfile: StateFlow<UiState<UserProfile?>> = repository.observeUserProfile(currentUserId)
        .toUiStateFlow("observeUserProfile")

    val activityLogs: StateFlow<UiState<List<ActivityLog>>> = repository.observeActivityLogs(currentUserId)
        .toUiStateFlow("observeActivityLogs")

    val automations: StateFlow<UiState<List<AutomationRule>>> = repository.observeAutomations(currentUserId)
        .toUiStateFlow("observeAutomations")

    val customAssistants: StateFlow<UiState<List<CustomAssistant>>> = repository.observeCustomAssistants(currentUserId)
        .toUiStateFlow("observeCustomAssistants")

    val myPayments: StateFlow<UiState<List<PaymentRequest>>> = repository.observeMyPayments(currentUserId)
        .toUiStateFlow("observeMyPayments")

    val premiumPackages: StateFlow<UiState<List<PremiumPackage>>> = repository.observePremiumPackages()
        .toUiStateFlow("observePremiumPackages")

    val premiumConfig: StateFlow<UiState<PremiumAppConfig>> = repository.observePremiumConfig()
        .toUiStateFlow("observePremiumConfig")

    val adminTelemetry: StateFlow<UiState<List<UserTelemetry>>> = if (isAdmin) {
        repository.observeAllUserTelemetryForAdmin().toUiStateFlow("observeAllUserTelemetry")
    } else {
        flowOf<UiState<List<UserTelemetry>>>(UiState.Success(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Success(emptyList()))
    }

    val adminPayments: StateFlow<UiState<List<PaymentRequest>>> = if (isAdmin) {
        repository.observeAllPaymentsForAdmin().toUiStateFlow("observeAllPayments")
    } else {
        flowOf<UiState<List<PaymentRequest>>>(UiState.Success(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Success(emptyList()))
    }

    val adminAuditLogs: StateFlow<UiState<List<AdminAuditLog>>> = if (isAdmin) {
        repository.observeAdminAuditLogs().toUiStateFlow("observeAdminAuditLogs")
    } else {
        flowOf<UiState<List<AdminAuditLog>>>(UiState.Success(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Success(emptyList()))
    }

    fun isEffectivePremium(playActiveIds: Set<String>): Boolean {
        val profile = (userProfile.value as? UiState.Success)?.data
        if (profile?.banned == true && !isAdmin) return false
        if (profile?.isEffectivelyPremium() == true) return true
        if (playActiveIds.isNotEmpty()) return true
        return false
    }

    fun isFeatureUnlocked(featureKey: String, playActiveIds: Set<String>): Boolean {
        val config = (premiumConfig.value as? UiState.Success)?.data ?: PremiumAppConfig()
        val globallyEnabled = config.featureFlags[featureKey] ?: true
        if (!globallyEnabled) return false
        return isEffectivePremium(playActiveIds)
    }

    fun checkAndExpireSubscriptionIfDue() {
        val profile = (userProfile.value as? UiState.Success)?.data ?: return
        val nowMs = System.currentTimeMillis()
        if (profile.premiumStatus == "active" && !profile.lifetimePlan && profile.expiresAtMs > 0L && nowMs >= profile.expiresAtMs) {
            viewModelScope.launch {
                repository.expireOwnPremiumIfDue()
            }
        }
    }

    private fun <T> Flow<T>.toUiStateFlow(label: String): StateFlow<UiState<T>> {
        return map<T, UiState<T>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error in $label", rootError)
                emit(UiState.Error(rootError.message ?: "Failed to load $label"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )
    }

    fun buildSyncedStateJson(
        playBillingJson: JSONObject = JSONObject(),
        playActiveIds: Set<String> = emptySet(),
        pickedDocName: String = "",
        pickedDocPreview: String = ""
    ): String {
        val profile = (userProfile.value as? UiState.Success)?.data
        val logs = (activityLogs.value as? UiState.Success)?.data ?: emptyList()
        val rules = (automations.value as? UiState.Success)?.data ?: emptyList()
        val assistants = (customAssistants.value as? UiState.Success)?.data ?: emptyList()
        val payments = (myPayments.value as? UiState.Success)?.data ?: emptyList()
        val packages = (premiumPackages.value as? UiState.Success)?.data ?: DEFAULT_PREMIUM_PACKAGES
        val appConfig = (premiumConfig.value as? UiState.Success)?.data ?: PremiumAppConfig()
        val allUsers = (adminTelemetry.value as? UiState.Success)?.data ?: emptyList()
        val allPayments = (adminPayments.value as? UiState.Success)?.data ?: emptyList()
        val auditLogs = (adminAuditLogs.value as? UiState.Success)?.data ?: emptyList()

        val nowMs = System.currentTimeMillis()
        val effectivePremium = isEffectivePremium(playActiveIds)
        val remainingDays = if (profile?.isEffectivelyPremium(nowMs) == true) {
            profile.remainingDays(nowMs)
        } else if (playActiveIds.isNotEmpty()) {
            30L
        } else {
            0L
        }

        return JSONObject().apply {
            put("isAdmin", isAdmin)
            put("isEffectivePremium", effectivePremium)
            put("pickedDocName", pickedDocName)
            put("pickedDocPreview", pickedDocPreview)
            put("playBilling", playBillingJson)

            put("profile", JSONObject().apply {
                put("userId", profile?.userId ?: currentUserId)
                put("displayName", profile?.displayName ?: "User")
                put("email", profile?.email ?: "")
                put("planTier", if (effectivePremium && (profile?.planTier == "free" || profile == null)) "pro" else (profile?.planTier ?: "free"))
                put("premiumStatus", if (playActiveIds.isNotEmpty() && profile?.premiumStatus != "active") "active" else (profile?.premiumStatus ?: "free"))
                put("packageId", profile?.packageId ?: "")
                put("packageName", profile?.packageName?.ifBlank { if (playActiveIds.isNotEmpty()) "Google Play Premium" else "Free Account" } ?: "Free Account")
                put("subscriptionId", profile?.subscriptionId?.ifBlank { if (playActiveIds.isNotEmpty()) playActiveIds.first() else "NONE" } ?: "NONE")
                put("lifetimePlan", profile?.lifetimePlan ?: false)
                put("giftedByAdmin", profile?.giftedByAdmin ?: false)
                put("purchasedAtMs", profile?.purchasedAtMs ?: 0L)
                put("expiresAtMs", profile?.expiresAtMs ?: 0L)
                put("remainingDays", remainingDays)
                put("banned", profile?.banned ?: false)
                put("banReason", profile?.banReason ?: "")
                put("themeMode", profile?.themeMode ?: "dark")
                put("accentColor", profile?.accentColor ?: "cyan")
                put("totalCommands", profile?.totalCommands ?: 0L)
                put("totalAiQueries", profile?.totalAiQueries ?: 0L)
                put("lastAction", profile?.lastAction ?: "Ready")
            })

            put("packages", JSONArray().apply {
                packages.forEach { pkg ->
                    put(JSONObject().apply {
                        put("packageId", pkg.packageId)
                        put("name", pkg.name)
                        put("nameBn", pkg.nameBn)
                        put("description", pkg.description)
                        put("durationDays", pkg.durationDays)
                        put("lifetimePlan", pkg.lifetimePlan)
                        put("priceBdt", pkg.priceBdt)
                        put("playProductId", pkg.playProductId)
                        put("tier", pkg.tier)
                        put("featuresSummary", pkg.featuresSummary)
                        put("active", pkg.active)
                        put("sortOrder", pkg.sortOrder)
                    })
                }
            })

            put("featureCatalog", JSONArray().apply {
                PREMIUM_FEATURE_CATALOG.forEach { meta ->
                    val globalOn = appConfig.featureFlags[meta.key] ?: true
                    put(JSONObject().apply {
                        put("key", meta.key)
                        put("titleEn", meta.titleEn)
                        put("titleBn", meta.titleBn)
                        put("descriptionBn", meta.descriptionBn)
                        put("targetPage", meta.targetPage)
                        put("globallyEnabled", globalOn)
                        put("unlocked", globalOn && effectivePremium)
                    })
                }
            })

            put("appConfig", JSONObject().apply {
                put("freeDailyAiLimit", appConfig.freeDailyAiLimit)
                put("merchantBkashNumber", appConfig.merchantBkashNumber)
                put("merchantNagadNumber", appConfig.merchantNagadNumber)
                put("merchantRocketNumber", appConfig.merchantRocketNumber)
                put("webhookVerifyUrl", appConfig.webhookVerifyUrl)
                put("promoBanner", appConfig.promoBanner)
                put("featureFlags", JSONObject().apply {
                    appConfig.featureFlags.forEach { (k, v) -> put(k, v) }
                })
            })

            put("logs", JSONArray().apply {
                logs.forEach { l ->
                    put(JSONObject().apply {
                        put("id", l.id)
                        put("controlId", l.controlId)
                        put("actionName", l.actionName)
                        put("category", l.category)
                        put("aiProvider", l.aiProvider)
                        put("permissionStatus", l.permissionStatus)
                        put("userConfirmed", l.userConfirmed)
                        put("success", l.success)
                        put("errorDetails", l.errorDetails)
                        put("timestampMs", (l.createdAt ?: Timestamp.now()).toDate().time)
                    })
                }
            })

            put("automations", JSONArray().apply {
                rules.forEach { r ->
                    put(JSONObject().apply {
                        put("id", r.id)
                        put("title", r.title)
                        put("triggerType", r.triggerType)
                        put("triggerValue", r.triggerValue)
                        put("actionCommand", r.actionCommand)
                        put("enabled", r.enabled)
                        put("lastRunLabel", r.lastRunLabel)
                    })
                }
            })

            put("customAssistants", JSONArray().apply {
                assistants.forEach { a ->
                    put(JSONObject().apply {
                        put("id", a.id)
                        put("name", a.name)
                        put("roleTitle", a.roleTitle)
                        put("systemPrompt", a.systemPrompt)
                        put("providerId", a.providerId)
                    })
                }
            })

            put("myPayments", JSONArray().apply {
                payments.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("planId", p.planId)
                        put("packageName", p.packageName)
                        put("method", p.method)
                        put("senderNumber", p.senderNumber)
                        put("transactionId", p.transactionId)
                        put("amountBdt", p.amountBdt)
                        put("status", p.status)
                        put("refundReason", p.refundReason)
                        put("adminNote", p.adminNote)
                        put("timestampMs", (p.createdAt ?: Timestamp.now()).toDate().time)
                    })
                }
            })

            put("adminUsers", JSONArray().apply {
                allUsers.forEach { u ->
                    put(JSONObject().apply {
                        put("userId", u.userId)
                        put("userEmail", u.userEmail)
                        put("displayName", u.displayName)
                        put("planTier", u.planTier)
                        put("premiumStatus", u.premiumStatus)
                        put("packageName", u.packageName)
                        put("lifetimePlan", u.lifetimePlan)
                        put("expiresAtMs", u.expiresAtMs)
                        put("banned", u.banned)
                        put("commandsCount", u.commandsCount)
                        put("aiQueriesCount", u.aiQueriesCount)
                        put("lastCommand", u.lastCommand)
                        put("deviceModel", u.deviceModel)
                    })
                }
            })

            put("adminPayments", JSONArray().apply {
                allPayments.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("userId", p.userId)
                        put("userEmail", p.userEmail)
                        put("planId", p.planId)
                        put("packageName", p.packageName)
                        put("durationDays", p.durationDays)
                        put("lifetimePlan", p.lifetimePlan)
                        put("method", p.method)
                        put("senderNumber", p.senderNumber)
                        put("transactionId", p.transactionId)
                        put("amountBdt", p.amountBdt)
                        put("status", p.status)
                        put("refundReason", p.refundReason)
                        put("adminNote", p.adminNote)
                        put("timestampMs", (p.createdAt ?: Timestamp.now()).toDate().time)
                    })
                }
            })

            put("adminAuditLogs", JSONArray().apply {
                auditLogs.forEach { a ->
                    put(JSONObject().apply {
                        put("id", a.id)
                        put("adminEmail", a.adminEmail)
                        put("actionType", a.actionType)
                        put("targetUserId", a.targetUserId)
                        put("targetLabel", a.targetLabel)
                        put("details", a.details)
                        put("timestampMs", (a.createdAt ?: Timestamp.now()).toDate().time)
                    })
                }
            })
        }.toString()
    }

    private companion object {
        const val TAG = "AppViewModel"
    }
}
