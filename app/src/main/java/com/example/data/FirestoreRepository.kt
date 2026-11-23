package com.example.data

import android.content.Context
import android.os.Build
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FirestoreRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth: FirebaseAuth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun isCurrentUserAdmin(): Boolean {
        val user = auth.currentUser ?: return false
        return user.isEmailVerified && user.email.equals(ADMIN_EMAIL, ignoreCase = true)
    }

    suspend fun ensureUserProfileInitialized(): Result<UserProfile> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "users/$uid"
        return try {
            val user = auth.currentUser!!
            val docRef = db.collection("users").document(uid)
            val snap = docRef.get().await()
            val profile = if (snap.exists()) {
                snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    ?: UserProfile(userId = uid)
            } else {
                val safeName = (user.displayName ?: user.email?.substringBefore("@") ?: "User").take(80).ifBlank { "User" }
                val safeEmail = (user.email ?: "").take(120)
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to safeName,
                    "email" to safeEmail,
                    "planTier" to "free",
                    "premiumStatus" to "free",
                    "packageId" to "",
                    "packageName" to "",
                    "subscriptionId" to "",
                    "lifetimePlan" to false,
                    "giftedByAdmin" to false,
                    "purchasedAtMs" to 0L,
                    "expiresAtMs" to 0L,
                    "banned" to false,
                    "banReason" to "",
                    "themeMode" to "dark",
                    "accentColor" to "cyan",
                    "totalCommands" to 0L,
                    "totalAiQueries" to 0L,
                    "lastAction" to "Signed in",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
                UserProfile(
                    id = uid,
                    userId = uid,
                    displayName = safeName,
                    email = safeEmail,
                    planTier = "free",
                    premiumStatus = "free"
                )
            }

            // Ensure owner telemetry document exists
            val telemetryRef = db.collection("user_telemetry").document(uid)
            val telSnap = telemetryRef.get().await()
            if (!telSnap.exists()) {
                telemetryRef.set(
                    mapOf(
                        "userId" to uid,
                        "userEmail" to (user.email ?: "").take(120),
                        "displayName" to profile.displayName.take(80).ifBlank { "User" },
                        "planTier" to "free",
                        "premiumStatus" to "free",
                        "packageName" to "",
                        "lifetimePlan" to false,
                        "expiresAtMs" to 0L,
                        "banned" to false,
                        "commandsCount" to profile.totalCommands,
                        "aiQueriesCount" to profile.totalAiQueries,
                        "lastCommand" to profile.lastAction.take(160),
                        "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}".take(80),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            // If Admin signs in and packages/config haven't been initialized yet, seed defaults
            if (isCurrentUserAdmin()) {
                seedDefaultPackagesAndConfigIfNeeded()
            }
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun seedDefaultPackagesAndConfigIfNeeded(): Result<Unit> {
        if (!isCurrentUserAdmin()) return Result.failure(SecurityException("Admin required."))
        return try {
            val existingPkgs = db.collection("premium_packages").limit(1).get().await()
            if (existingPkgs.isEmpty) {
                DEFAULT_PREMIUM_PACKAGES.forEach { pkg ->
                    db.collection("premium_packages").document(pkg.packageId).set(
                        mapOf(
                            "packageId" to pkg.packageId,
                            "name" to pkg.name,
                            "nameBn" to pkg.nameBn,
                            "description" to pkg.description,
                            "durationDays" to pkg.durationDays,
                            "lifetimePlan" to pkg.lifetimePlan,
                            "priceBdt" to pkg.priceBdt,
                            "playProductId" to pkg.playProductId,
                            "tier" to pkg.tier,
                            "featuresSummary" to pkg.featuresSummary,
                            "active" to pkg.active,
                            "sortOrder" to pkg.sortOrder,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }

            val cfgRef = db.collection("app_config").document("premium_features")
            if (!cfgRef.get().await().exists()) {
                cfgRef.set(
                    mapOf(
                        "freeDailyAiLimit" to 15L,
                        "merchantBkashNumber" to "01700-000000",
                        "merchantNagadNumber" to "01700-000000",
                        "merchantRocketNumber" to "01700-000000",
                        "webhookVerifyUrl" to "",
                        "promoBanner" to "Unlock Black & Gold Premium for AI Code Studio, Multi-AI & Unlimited Automation!",
                        "featureFlags" to defaultFeatureFlagsMap(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "premium_packages")
            Result.failure(e)
        }
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        val uid = requireUserId()
        if (uid != userId) throw IllegalStateException("Mismatched user")
        val path = "users/$uid"
        emitAll(
            db.collection("users").document(uid)
                .snapshots()
                .map { it.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    fun observePremiumPackages(): Flow<List<PremiumPackage>> = flow {
        requireUserId()
        val path = "premium_packages"
        emitAll(
            db.collection("premium_packages")
                .orderBy("sortOrder", Query.Direction.ASCENDING)
                .snapshots()
                .map { snap ->
                    val items = snap.toObjects(PremiumPackage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    items.ifEmpty { DEFAULT_PREMIUM_PACKAGES }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    if (firestoreError != null) handleFirestoreError(firestoreError, OperationType.LIST, path)
                    emit(DEFAULT_PREMIUM_PACKAGES)
                }
        )
    }

    fun observePremiumConfig(): Flow<PremiumAppConfig> = flow {
        requireUserId()
        val path = "app_config/premium_features"
        emitAll(
            db.collection("app_config").document("premium_features")
                .snapshots()
                .map { snap ->
                    snap.toObject(PremiumAppConfig::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        ?: PremiumAppConfig()
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    if (firestoreError != null) handleFirestoreError(firestoreError, OperationType.GET, path)
                    emit(PremiumAppConfig())
                }
        )
    }

    suspend fun expireOwnPremiumIfDue(): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "users/$uid"
        return try {
            db.collection("users").document(uid).update(
                mapOf(
                    "premiumStatus" to "expired",
                    "planTier" to "free",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            db.collection("user_telemetry").document(uid).update(
                mapOf(
                    "premiumStatus" to "expired",
                    "planTier" to "free",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun cancelOwnSubscription(): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "users/$uid"
        return try {
            db.collection("users").document(uid).update(
                mapOf(
                    "premiumStatus" to "cancelled",
                    "planTier" to "free",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            db.collection("user_telemetry").document(uid).update(
                mapOf(
                    "premiumStatus" to "cancelled",
                    "planTier" to "free",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateThemePreferences(themeMode: String, accentColor: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val validTheme = if (themeMode in listOf("dark", "light", "amoled", "gold_royal")) themeMode else "dark"
        val validAccent = if (accentColor in listOf("cyan", "indigo", "emerald", "amber", "rose", "gold")) accentColor else "cyan"
        val path = "users/$uid"
        return try {
            db.collection("users").document(uid).update(
                mapOf(
                    "themeMode" to validTheme,
                    "accentColor" to validAccent,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    fun observeActivityLogs(userId: String): Flow<List<ActivityLog>> = flow {
        val uid = requireUserId()
        if (uid != userId) throw IllegalStateException("Mismatched user")
        val path = "users/$uid/activity_logs"
        emitAll(
            db.collection("users").document(uid).collection("activity_logs")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(60)
                .snapshots()
                .map { snap -> snap.toObjects(ActivityLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun logActivity(
        controlId: Int,
        actionName: String,
        category: String,
        aiProvider: String,
        permissionStatus: String,
        userConfirmed: Boolean,
        success: Boolean,
        errorDetails: String = "",
        isAiQuery: Boolean = false
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val docId = "log_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val path = "users/$uid/activity_logs/$docId"
        return try {
            val payload = mapOf(
                "userId" to uid,
                "controlId" to controlId.coerceIn(0, 200).toLong(),
                "actionName" to actionName.take(120).ifBlank { "Action" },
                "category" to category.take(50).ifBlank { "System" },
                "aiProvider" to aiProvider.take(50).ifBlank { "Manual" },
                "permissionStatus" to permissionStatus.take(40).ifBlank { "Granted" },
                "userConfirmed" to userConfirmed,
                "success" to success,
                "errorDetails" to errorDetails.take(300),
                "createdAt" to FieldValue.serverTimestamp()
            )
            db.collection("users").document(uid).collection("activity_logs").document(docId).set(payload).await()

            val userUpdates = mutableMapOf<String, Any>(
                "totalCommands" to FieldValue.increment(1L),
                "lastAction" to actionName.take(160),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (isAiQuery) {
                userUpdates["totalAiQueries"] = FieldValue.increment(1L)
            }
            db.collection("users").document(uid).update(userUpdates).await()

            val telUpdates = mutableMapOf<String, Any>(
                "commandsCount" to FieldValue.increment(1L),
                "lastCommand" to actionName.take(160),
                "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}".take(80),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (isAiQuery) {
                telUpdates["aiQueriesCount"] = FieldValue.increment(1L)
            }
            db.collection("user_telemetry").document(uid).update(telUpdates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteActivityLog(logId: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "users/$uid/activity_logs/$logId"
        return try {
            require(logId.isNotBlank()) { "logId must not be blank" }
            db.collection("users").document(uid).collection("activity_logs").document(logId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    fun observeAutomations(userId: String): Flow<List<AutomationRule>> = flow {
        val uid = requireUserId()
        if (uid != userId) throw IllegalStateException("Mismatched user")
        val path = "users/$uid/automations"
        emitAll(
            db.collection("users").document(uid).collection("automations")
                .snapshots()
                .map { snap -> snap.toObjects(AutomationRule::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveAutomation(
        ruleId: String?,
        title: String,
        triggerType: String,
        triggerValue: String,
        actionCommand: String,
        enabled: Boolean
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val validType = if (triggerType in listOf("daily", "scheduled", "battery_low", "storage_low", "network_change", "app_launch", "custom")) triggerType else "daily"
        val cleanTitle = title.trim().take(100).ifBlank { "Automation Rule" }
        val cleanCmd = actionCommand.trim().take(200).ifBlank { "Check Battery" }
        val id = ruleId?.takeIf { it.isNotBlank() } ?: "auto_${System.currentTimeMillis()}"
        val path = "users/$uid/automations/$id"
        return try {
            val docRef = db.collection("users").document(uid).collection("automations").document(id)
            if (ruleId.isNullOrBlank()) {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "title" to cleanTitle,
                        "triggerType" to validType,
                        "triggerValue" to triggerValue.trim().take(80),
                        "actionCommand" to cleanCmd,
                        "enabled" to enabled,
                        "lastRunLabel" to "Never",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.update(
                    mapOf(
                        "title" to cleanTitle,
                        "triggerType" to validType,
                        "triggerValue" to triggerValue.trim().take(80),
                        "actionCommand" to cleanCmd,
                        "enabled" to enabled,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteAutomation(ruleId: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "users/$uid/automations/$ruleId"
        return try {
            require(ruleId.isNotBlank())
            db.collection("users").document(uid).collection("automations").document(ruleId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    fun observeCustomAssistants(userId: String): Flow<List<CustomAssistant>> = flow {
        val uid = requireUserId()
        if (uid != userId) throw IllegalStateException("Mismatched user")
        val path = "users/$uid/custom_assistants"
        emitAll(
            db.collection("users").document(uid).collection("custom_assistants")
                .snapshots()
                .map { snap -> snap.toObjects(CustomAssistant::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    if (firestoreError != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(firestoreError, OperationType.LIST, path)
                    }
                    emit(emptyList())
                }
        )
    }

    suspend fun saveCustomAssistant(
        assistantId: String?,
        name: String,
        roleTitle: String,
        systemPrompt: String,
        providerId: String
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val cleanName = name.trim().take(60).ifBlank { return Result.failure(IllegalArgumentException("Name required")) }
        val cleanPrompt = systemPrompt.trim().take(1200).ifBlank { return Result.failure(IllegalArgumentException("Prompt required")) }
        val validProv = if (providerId in listOf("gemini", "chatgpt", "claude", "deepseek")) providerId else "gemini"
        val id = assistantId?.takeIf { it.isNotBlank() } ?: "ast_${System.currentTimeMillis()}"
        val path = "users/$uid/custom_assistants/$id"
        return try {
            val docRef = db.collection("users").document(uid).collection("custom_assistants").document(id)
            if (assistantId.isNullOrBlank()) {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "name" to cleanName,
                        "roleTitle" to roleTitle.trim().take(80),
                        "systemPrompt" to cleanPrompt,
                        "providerId" to validProv,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.update(
                    mapOf(
                        "name" to cleanName,
                        "roleTitle" to roleTitle.trim().take(80),
                        "systemPrompt" to cleanPrompt,
                        "providerId" to validProv,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteCustomAssistant(assistantId: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        return try {
            db.collection("users").document(uid).collection("custom_assistants").document(assistantId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/$uid/custom_assistants/$assistantId")
            Result.failure(e)
        }
    }

    fun observeMyPayments(userId: String): Flow<List<PaymentRequest>> = flow {
        val uid = requireUserId()
        if (uid != userId) throw IllegalStateException("Mismatched user")
        val path = "payment_requests"
        emitAll(
            db.collection("payment_requests")
                .whereEqualTo("userId", uid)
                .snapshots()
                .map { snap -> snap.toObjects(PaymentRequest::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun submitPaymentRequest(
        packageObj: PremiumPackage,
        method: String,
        senderNumber: String,
        transactionId: String,
        playPurchaseToken: String = ""
    ): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("Not signed in"))
        val uid = user.uid
        val validMethod = if (method in listOf("bkash", "nagad", "rocket", "play_store")) method else "bkash"
        val cleanPhone = senderNumber.trim().take(64)
        val cleanTrx = transactionId.trim().take(80)
        if (cleanPhone.length < 4 || cleanTrx.length < 4) {
            return Result.failure(IllegalArgumentException("Enter valid sender number and Transaction ID."))
        }
        val reqId = "pay_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val path = "payment_requests/$reqId"
        return try {
            db.collection("payment_requests").document(reqId).set(
                mapOf(
                    "userId" to uid,
                    "userEmail" to (user.email ?: "user@example.com").take(120),
                    "planId" to packageObj.packageId.take(64).ifBlank { "pkg_30d" },
                    "packageName" to packageObj.name.take(100).ifBlank { "Premium Package" },
                    "durationDays" to packageObj.durationDays.coerceIn(0L, 36500L),
                    "lifetimePlan" to packageObj.lifetimePlan,
                    "method" to validMethod,
                    "senderNumber" to cleanPhone,
                    "transactionId" to cleanTrx,
                    "playPurchaseToken" to playPurchaseToken.take(400),
                    "amountBdt" to packageObj.priceBdt.coerceIn(0L, 500000L),
                    "status" to "pending",
                    "refundReason" to "",
                    "adminNote" to "",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun requestRefund(requestId: String, reason: String): Result<Unit> {
        auth.currentUser ?: return Result.failure(IllegalStateException("Not signed in"))
        val cleanReason = reason.trim().take(240).ifBlank { "User requested refund" }
        val path = "payment_requests/$requestId"
        return try {
            db.collection("payment_requests").document(requestId).update(
                mapOf(
                    "status" to "refund_requested",
                    "refundReason" to cleanReason,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- Admin Panel Queries & Actions (Strictly verified Admin only) ---
    fun observeAllUserTelemetryForAdmin(): Flow<List<UserTelemetry>> = flow {
        requireUserId()
        if (!isCurrentUserAdmin()) {
            emit(emptyList())
            return@flow
        }
        val path = "user_telemetry"
        emitAll(
            db.collection("user_telemetry")
                .snapshots()
                .map { snap -> snap.toObjects(UserTelemetry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    if (firestoreError != null) handleFirestoreError(firestoreError, OperationType.LIST, path)
                    throw error
                }
        )
    }

    fun observeAllPaymentsForAdmin(): Flow<List<PaymentRequest>> = flow {
        requireUserId()
        if (!isCurrentUserAdmin()) {
            emit(emptyList())
            return@flow
        }
        val path = "payment_requests"
        emitAll(
            db.collection("payment_requests")
                .snapshots()
                .map { snap -> snap.toObjects(PaymentRequest::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    if (firestoreError != null) handleFirestoreError(firestoreError, OperationType.LIST, path)
                    throw error
                }
        )
    }

    fun observeAdminAuditLogs(): Flow<List<AdminAuditLog>> = flow {
        requireUserId()
        if (!isCurrentUserAdmin()) {
            emit(emptyList())
            return@flow
        }
        val path = "admin_audit_logs"
        emitAll(
            db.collection("admin_audit_logs")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(80)
                .snapshots()
                .map { snap -> snap.toObjects(AdminAuditLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    if (firestoreError != null) handleFirestoreError(firestoreError, OperationType.LIST, path)
                    emit(emptyList())
                }
        )
    }

    private suspend fun writeAdminAuditLog(
        actionType: String,
        targetUserId: String,
        targetLabel: String,
        details: String
    ) {
        val admin = auth.currentUser ?: return
        val logId = "aud_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        db.collection("admin_audit_logs").document(logId).set(
            mapOf(
                "adminUid" to admin.uid,
                "adminEmail" to (admin.email ?: ADMIN_EMAIL).take(120),
                "actionType" to actionType.take(50),
                "targetUserId" to targetUserId.take(128),
                "targetLabel" to targetLabel.take(120),
                "details" to details.take(400),
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun adminGrantOrGiftPackage(
        targetUserId: String,
        targetEmail: String,
        pkg: PremiumPackage,
        gifted: Boolean
    ): Result<Unit> {
        if (!isCurrentUserAdmin()) return Result.failure(SecurityException("Admin verification required."))
        return try {
            val nowMs = System.currentTimeMillis()
            val userSnap = db.collection("users").document(targetUserId).get().await()
            val existingUser = userSnap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)

            val baseStartMs = if (existingUser != null && existingUser.isEffectivelyPremium(nowMs) && !existingUser.lifetimePlan && existingUser.expiresAtMs > nowMs) {
                existingUser.expiresAtMs
            } else {
                nowMs
            }
            val newExpiryMs = if (pkg.lifetimePlan || pkg.durationDays <= 0L) {
                0L
            } else {
                baseStartMs + (pkg.durationDays * DAY_MS)
            }
            val subId = "SUB-${pkg.packageId.uppercase()}-${UUID.randomUUID().toString().take(6).uppercase()}"
            val tier = if (pkg.tier == "enterprise" || pkg.lifetimePlan) "enterprise" else "pro"

            db.collection("users").document(targetUserId).update(
                mapOf(
                    "planTier" to tier,
                    "premiumStatus" to "active",
                    "packageId" to pkg.packageId.take(64),
                    "packageName" to pkg.name.take(100),
                    "subscriptionId" to subId,
                    "lifetimePlan" to pkg.lifetimePlan,
                    "giftedByAdmin" to gifted,
                    "purchasedAtMs" to nowMs,
                    "expiresAtMs" to newExpiryMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            db.collection("user_telemetry").document(targetUserId).update(
                mapOf(
                    "planTier" to tier,
                    "premiumStatus" to "active",
                    "packageName" to pkg.name.take(100),
                    "lifetimePlan" to pkg.lifetimePlan,
                    "expiresAtMs" to newExpiryMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            writeAdminAuditLog(
                actionType = if (gifted) "GIFT_PREMIUM" else "ACTIVATE_PREMIUM",
                targetUserId = targetUserId,
                targetLabel = targetEmail.ifBlank { targetUserId },
                details = "${pkg.name} (${if (pkg.lifetimePlan) "Lifetime" else "${pkg.durationDays}d"}) • Sub: $subId"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$targetUserId")
            Result.failure(e)
        }
    }

    suspend fun adminAdjustDays(
        targetUserId: String,
        targetEmail: String,
        deltaDays: Long
    ): Result<Unit> {
        if (!isCurrentUserAdmin()) return Result.failure(SecurityException("Admin verification required."))
        return try {
            val nowMs = System.currentTimeMillis()
            val snap = db.collection("users").document(targetUserId).get().await()
            val profile = snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(IllegalStateException("User profile not found"))

            val currentBase = if (profile.expiresAtMs > nowMs) profile.expiresAtMs else nowMs
            val updatedExpiry = (currentBase + deltaDays * DAY_MS).coerceAtLeast(nowMs)
            val stillActive = updatedExpiry > nowMs
            val newStatus = if (stillActive) "active" else "expired"
            val newTier = if (stillActive) profile.planTier.takeIf { it != "free" } ?: "pro" else "free"
            val pkgName = profile.packageName.ifBlank { "Custom Admin Plan" }
            val subId = profile.subscriptionId.ifBlank { "SUB-ADJ-${UUID.randomUUID().toString().take(6).uppercase()}" }

            db.collection("users").document(targetUserId).update(
                mapOf(
                    "planTier" to newTier,
                    "premiumStatus" to newStatus,
                    "packageId" to profile.packageId.ifBlank { "pkg_custom" },
                    "packageName" to pkgName,
                    "subscriptionId" to subId,
                    "lifetimePlan" to false,
                    "expiresAtMs" to updatedExpiry,
                    "purchasedAtMs" to if (profile.purchasedAtMs > 0L) profile.purchasedAtMs else nowMs,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            db.collection("user_telemetry").document(targetUserId).update(
                mapOf(
                    "planTier" to newTier,
                    "premiumStatus" to newStatus,
                    "packageName" to pkgName,
                    "lifetimePlan" to false,
                    "expiresAtMs" to updatedExpiry,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            writeAdminAuditLog(
                actionType = if (deltaDays >= 0) "EXTEND_DAYS" else "REDUCE_DAYS",
                targetUserId = targetUserId,
                targetLabel = targetEmail.ifBlank { targetUserId },
                details = "Adjusted by $deltaDays day(s) -> status=$newStatus"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$targetUserId")
            Result.failure(e)
        }
    }

    suspend fun adminSetPremiumStatus(
        targetUserId: String,
        targetEmail: String,
        newStatus: String
    ): Result<Unit> {
        if (!isCurrentUserAdmin()) return Result.failure(SecurityException("Admin verification required."))
        val valid = if (newStatus in listOf("active", "suspended", "cancelled", "free", "expired")) newStatus else "suspended"
        return try {
            val tier = if (valid == "active") "pro" else "free"
            db.collection("users").document(targetUserId).update(
                mapOf(
                    "premiumStatus" to valid,
                    "planTier" to tier,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            db.collection("user_telemetry").document(targetUserId).update(
                mapOf(
                    "premiumStatus" to valid,
                    "planTier" to tier,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            writeAdminAuditLog(
                actionType = "SET_PREMIUM_${valid.uppercase()}",
                targetUserId = targetUserId,
                targetLabel = targetEmail.ifBlank { targetUserId },
                details = "Changed premiumStatus to $valid"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$targetUserId")
            Result.failure(e)
        }
    }

    suspend fun adminBanUser(
        targetUserId: String,
        targetEmail: String,
        ban: Boolean,
        reason: String
    ): Result<Unit> {
        if (!isCurrentUserAdmin()) return Result.failure(SecurityException("Admin verification required."))
        val cleanReason = if (ban) reason.trim().take(240).ifBlank { "Suspended by Admin" } else ""
        return try {
            db.collection("users").document(targetUserId).update(
                mapOf(
                    "banned" to ban,
                    "banReason" to cleanReason,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            db.collection("user_telemetry").document(targetUserId).update(
                mapOf(
                    "banned" to ban,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            writeAdminAuditLog(
                actionType = if (ban) "BAN_USER" else "UNBAN_USER",
                targetUserId = targetUserId,
                targetLabel = targetEmail.ifBlank { targetUserId },
                details = if (ban) "Banned: $cleanReason" else "Unbanned user account"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$targetUserId")
            Result.failure(e)
        }
    }

    suspend fun adminInspectUserDetails(targetUserId: String): Result<Pair<UserProfile?, List<ActivityLog>>> {
        if (!isCurrentUserAdmin()) return Result.failure(SecurityException("Admin verification required."))
        return try {
            val userSnap = db.collection("users").document(targetUserId).get().await()
            val profile = userSnap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            val logsSnap = db.collection("users").document(targetUserId).collection("activity_logs")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(20)
                .get()
                .await()
            val logs = logsSnap.toObjects(ActivityLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            Result.success(profile to logs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "users/$targetUserId")
            Result.failure(e)
        }
    }

    suspend fun adminSavePackage(pkg: PremiumPackage): Result<Unit> {
        if (!isCurrentUserAdmin()) return Result.failure(SecurityException("Admin verification required."))
        val id = pkg.packageId.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(64)
        if (id.isBlank()) return Result.failure(IllegalArgumentException("Invalid packageId"))
        return try {
            val docRef = db.collection("premium_packages").document(id)
            val exists = docRef.get().await().exists()
            if (!exists) {
                docRef.set(
                    mapOf(
                        "packageId" to id,
                        "name" to pkg.name.trim().take(80).ifBlank { "Package" },
                        "nameBn" to pkg.nameBn.trim().take(80),
                        "description" to pkg.description.trim().take(300),
                        "durationDays" to pkg.durationDays.coerceIn(0L, 36500L),
                        "lifetimePlan" to pkg.lifetimePlan,
                        "priceBdt" to pkg.priceBdt.coerceIn(1L, 500000L),
                        "playProductId" to pkg.playProductId.trim().take(100),
                        "tier" to (if (pkg.tier == "enterprise" || pkg.lifetimePlan) "enterprise" else "pro"),
                        "featuresSummary" to pkg.featuresSummary.trim().take(500),
                        "active" to pkg.active,
                        "sortOrder" to pkg.sortOrder.coerceIn(0L, 999L),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.update(
                    mapOf(
                        "name" to pkg.name.trim().take(80).ifBlank { "Package" },
                        "nameBn" to pkg.nameBn.trim().take(80),
                        "description" to pkg.description.trim().take(300),
                        "durationDays" to pkg.durationDays.coerceIn(0L, 36500L),
                        "lifetimePlan" to pkg.lifetimePlan,
                        "priceBdt" to pkg.priceBdt.coerceIn(1L, 500000L),
                        "playProductId" to pkg.playProductId.trim().take(100),
                        "tier" to (if (pkg.tier == "enterprise" || pkg.lifetimePlan) "enterprise" else "pro"),
                        "featuresSummary" to pkg.featuresSummary.trim().take(500),
                        "active" to pkg.active,
                        "sortOrder" to pkg.sortOrder.coerceIn(0L, 999L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            writeAdminAuditLog("EDIT_PACKAGE", "", id, "Saved ${pkg.name} • ৳${pkg.priceBdt} (${pkg.durationDays}d)")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "premium_packages/$id")
            Result.failure(e)
        }
    }

    suspend fun adminDeletePackage(packageId: String): Result<Unit> {
        if (!isCurrentUserAdmin()) return Result.failure(SecurityException("Admin verification required."))
        return try {
            db.collection("premium_packages").document(packageId).delete().await()
            writeAdminAuditLog("DELETE_PACKAGE", "", packageId, "Deleted package $packageId")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "premium_packages/$packageId")
            Result.failure(e)
        }
    }

    suspend fun adminUpdateAppConfig(config: PremiumAppConfig): Result<Unit> {
        if (!isCurrentUserAdmin()) return Result.failure(SecurityException("Admin verification required."))
        return try {
            db.collection("app_config").document("premium_features").set(
                mapOf(
                    "freeDailyAiLimit" to config.freeDailyAiLimit.coerceIn(1L, 1000L),
                    "merchantBkashNumber" to config.merchantBkashNumber.trim().take(30),
                    "merchantNagadNumber" to config.merchantNagadNumber.trim().take(30),
                    "merchantRocketNumber" to config.merchantRocketNumber.trim().take(30),
                    "webhookVerifyUrl" to config.webhookVerifyUrl.trim().take(300),
                    "promoBanner" to config.promoBanner.trim().take(200),
                    "featureFlags" to config.featureFlags,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            writeAdminAuditLog("UPDATE_FEATURES_CONFIG", "", "app_config", "Updated feature toggles & free limit=${config.freeDailyAiLimit}")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "app_config/premium_features")
            Result.failure(e)
        }
    }

    suspend fun adminReviewPayment(
        payment: PaymentRequest,
        newStatus: String,
        adminNote: String = ""
    ): Result<Unit> {
        if (!isCurrentUserAdmin()) return Result.failure(SecurityException("Admin verification required."))
        val validStatus = if (newStatus in listOf("approved", "rejected", "refunded")) newStatus else "rejected"
        val path = "payment_requests/${payment.id}"
        return try {
            db.collection("payment_requests").document(payment.id).update(
                mapOf(
                    "status" to validStatus,
                    "adminNote" to adminNote.trim().take(240),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            if (validStatus == "approved") {
                val pkg = PremiumPackage(
                    packageId = payment.planId,
                    name = payment.packageName.ifBlank { payment.planId },
                    durationDays = payment.durationDays,
                    lifetimePlan = payment.lifetimePlan,
                    priceBdt = payment.amountBdt,
                    tier = if (payment.lifetimePlan || payment.planId.contains("lifetime") || payment.planId.contains("enterprise")) "enterprise" else "pro"
                )
                adminGrantOrGiftPackage(payment.userId, payment.userEmail, pkg, gifted = false)
            } else if (validStatus == "refunded") {
                adminSetPremiumStatus(payment.userId, payment.userEmail, "cancelled")
            }

            writeAdminAuditLog(
                actionType = "PAYMENT_${validStatus.uppercase()}",
                targetUserId = payment.userId,
                targetLabel = payment.userEmail,
                details = "${payment.method.uppercase()} Trx:${payment.transactionId} • ৳${payment.amountBdt} -> $validStatus"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }
}
