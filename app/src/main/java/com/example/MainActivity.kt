package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.DEFAULT_PREMIUM_PACKAGES
import com.example.data.FirestoreRepository
import com.example.data.PaymentRequest
import com.example.data.PremiumAppConfig
import com.example.data.PremiumPackage
import com.example.data.configureAppCheck
import com.example.data.defaultFeatureFlagsMap
import com.example.ui.AuthScreen
import com.example.ui.BannedAccountScreen
import com.example.ui.signOutUser
import com.example.ui.theme.AIControllerTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth -> trySend(auth.currentUser) }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        val initialRoute = intent?.getStringExtra(FloatingService.EXTRA_TARGET_PAGE)
        val initialCommand = intent?.getStringExtra(FloatingService.EXTRA_COMMAND_TEXT)

        setContent {
            AppContent(initialRoute = initialRoute, initialCommand = initialCommand)
        }
    }
}

@Composable
fun AppContent(initialRoute: String?, initialCommand: String?) {
    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    val user = currentUser
    if (user == null) {
        AIControllerTheme(darkTheme = true, amoled = false, goldRoyal = false, accentKey = "cyan") {
            AuthScreen(onAuthSuccess = {})
        }
    } else {
        val uid = user.uid
        key(uid) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val credentialManager = remember { CredentialManager.create(context) }

            val appViewModel: AppViewModel = viewModel(
                key = uid,
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY])
                        val dbId = app.getString(R.string.firestore_database_id)
                        val db = FirebaseFirestore.getInstance(dbId)
                        AppViewModel(FirestoreRepository(db), uid)
                    }
                }
            )

            val profileState by appViewModel.userProfile.collectAsStateWithLifecycle()
            val profile = (profileState as? UiState.Success)?.data
            val themeMode = profile?.themeMode ?: "dark"
            val accentKey = profile?.accentColor ?: "cyan"
            val isGoldRoyal = themeMode == "gold_royal" && (profile?.isEffectivelyPremium() == true || appViewModel.isAdmin)

            AIControllerTheme(
                darkTheme = themeMode != "light",
                amoled = themeMode == "amoled",
                goldRoyal = isGoldRoyal,
                accentKey = accentKey
            ) {
                if (profile?.banned == true && !appViewModel.isAdmin) {
                    BannedAccountScreen(
                        email = profile.email.ifBlank { user.email.orEmpty() },
                        banReason = profile.banReason,
                        onSignOut = { signOutUser(credentialManager, scope) }
                    )
                } else {
                    AuthenticatedControllerHost(
                        appViewModel = appViewModel,
                        initialRoute = initialRoute,
                        initialCommand = initialCommand
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AuthenticatedControllerHost(
    appViewModel: AppViewModel,
    initialRoute: String?,
    initialCommand: String?
) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val apiSecurity = remember { ApiSecurity(context) }
    val permissionManager = remember { PermissionManager(context) }
    val phoneController = remember { PhoneController(context, permissionManager) }
    val aiOrchestrator = remember { AiOrchestrator(apiSecurity, phoneController) }
    val voiceHelper = remember { VoiceAssistantHelper(context) }
    val repository = remember { FirestoreRepository(context) }
    val credentialManager = remember { CredentialManager.create(context) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pickedDocName by remember { mutableStateOf("") }
    var pickedDocContent by remember { mutableStateOf("") }
    var expiryAlertSent by remember { mutableStateOf(false) }

    val profileState by appViewModel.userProfile.collectAsStateWithLifecycle()
    val logsState by appViewModel.activityLogs.collectAsStateWithLifecycle()
    val automationsState by appViewModel.automations.collectAsStateWithLifecycle()
    val assistantsState by appViewModel.customAssistants.collectAsStateWithLifecycle()
    val paymentsState by appViewModel.myPayments.collectAsStateWithLifecycle()
    val packagesState by appViewModel.premiumPackages.collectAsStateWithLifecycle()
    val configState by appViewModel.premiumConfig.collectAsStateWithLifecycle()
    val adminUsersState by appViewModel.adminTelemetry.collectAsStateWithLifecycle()
    val adminPaymentsState by appViewModel.adminPayments.collectAsStateWithLifecycle()
    val adminAuditState by appViewModel.adminAuditLogs.collectAsStateWithLifecycle()

    fun pushJs(script: String) {
        webViewRef?.post { webViewRef?.evaluateJavascript(script, null) }
    }

    val playBillingManager = remember(appViewModel) {
        PlayBillingManager(
            context = context,
            scope = scope,
            onVerifiedPlayPurchase = { productId, orderId, purchaseToken ->
                scope.launch {
                    val pkgs = (appViewModel.premiumPackages.value as? UiState.Success)?.data ?: DEFAULT_PREMIUM_PACKAGES
                    val matchedPkg = pkgs.firstOrNull { it.playProductId == productId }
                        ?: pkgs.firstOrNull()
                        ?: DEFAULT_PREMIUM_PACKAGES.first()
                    repository.submitPaymentRequest(
                        packageObj = matchedPkg,
                        method = "play_store",
                        senderNumber = "GooglePlay",
                        transactionId = orderId,
                        playPurchaseToken = purchaseToken
                    )
                    pushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote("Google Play purchase verified ($productId)!")});")
                }
            },
            onBillingStatusMessage = { msg ->
                pushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(msg)});")
            }
        )
    }

    val billingSnapshot by playBillingManager.snapshot.collectAsStateWithLifecycle()

    fun pushSyncedState() {
        val json = JSONObject.quote(
            appViewModel.buildSyncedStateJson(
                playBillingJson = playBillingManager.toJson(),
                playActiveIds = billingSnapshot.activeProductIds,
                pickedDocName = pickedDocName,
                pickedDocPreview = pickedDocContent.take(300)
            )
        )
        pushJs("window.onNativeStateSync && window.onNativeStateSync($json);")
    }

    fun pushPermissionsAndTelemetry() {
        val perms = JSONObject.quote(permissionManager.getPermissionsJson())
        val tel = JSONObject.quote(phoneController.getDeviceTelemetryJson())
        val aiSnap = JSONObject.quote(apiSecurity.getProvidersSnapshotJson())
        pushJs("window.onNativeRefresh && window.onNativeRefresh($perms, $tel, $aiSnap);")
    }

    LaunchedEffect(packagesState) {
        val pkgs = (packagesState as? UiState.Success)?.data ?: DEFAULT_PREMIUM_PACKAGES
        val ids = pkgs.mapNotNull { it.playProductId.takeIf(String::isNotBlank) }
        playBillingManager.startConnection(ids)
    }

    LaunchedEffect(profileState) {
        appViewModel.checkAndExpireSubscriptionIfDue()
        val prof = (profileState as? UiState.Success)?.data
        if (prof != null && !expiryAlertSent && prof.isEffectivelyPremium() && !prof.lifetimePlan && prof.remainingDays() in 1L..3L) {
            expiryAlertSent = true
            NotificationService.postSystemAlert(
                context,
                "Goriber AI Premium Expiring Soon",
                "Your ${prof.packageName} expires in ${prof.remainingDays()} day(s). Renew to keep Pro tools active.",
                id = 2010
            )
        }
    }

    LaunchedEffect(
        profileState, logsState, automationsState, assistantsState, paymentsState,
        packagesState, configState, adminUsersState, adminPaymentsState, adminAuditState,
        billingSnapshot, pickedDocName
    ) {
        pushSyncedState()
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pushPermissionsAndTelemetry()
                playBillingManager.startConnection()
                appViewModel.checkAndExpireSubscriptionIfDue()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            voiceHelper.release()
            playBillingManager.endConnection()
        }
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> pushPermissionsAndTelemetry() }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val info = JSONObject().put("type", "media").put("uri", uri.toString()).toString()
            pushJs("window.onNativeFilePicked && window.onNativeFilePicked(${JSONObject.quote(info)});")
        }
    }

    val safTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val filesJson = listSafFilesJson(context, treeUri)
            pushJs("window.onNativeSafFiles && window.onNativeSafFiles(${JSONObject.quote(filesJson)});")
            pushPermissionsAndTelemetry()
        }
    }

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val (name, text) = withContext(Dispatchers.IO) { readDocumentText(context, uri) }
                pickedDocName = name
                pickedDocContent = text
                val info = JSONObject()
                    .put("type", "document")
                    .put("uri", uri.toString())
                    .put("name", name)
                    .put("preview", text.take(240))
                    .toString()
                pushJs("window.onNativeFilePicked && window.onNativeFilePicked(${JSONObject.quote(info)});")
                pushSyncedState()
            }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) {
                pushJs("window.onNativeVoiceResult && window.onNativeVoiceResult(${JSONObject.quote(spoken)});")
            }
        }
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null) {
            wv.evaluateJavascript("window.handleNativeBack && window.handleNativeBack()") { result ->
                if (result != "true") activity.finish()
            }
        } else {
            activity.finish()
        }
    }

    val bridge = remember(appViewModel) {
        AndroidBridge(
            context = context,
            activity = activity,
            scope = scope,
            apiSecurity = apiSecurity,
            permissionManager = permissionManager,
            phoneController = phoneController,
            aiOrchestrator = aiOrchestrator,
            voiceHelper = voiceHelper,
            repository = repository,
            appViewModel = appViewModel,
            playBillingManager = playBillingManager,
            getPickedDocument = { pickedDocName to pickedDocContent },
            onRequestRuntimePermission = { perm -> runtimePermissionLauncher.launch(perm) },
            onLaunchPhotoPicker = { isVideo ->
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(
                        if (isVideo) ActivityResultContracts.PickVisualMedia.VideoOnly
                        else ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            onLaunchSafTree = { safTreeLauncher.launch(null) },
            onLaunchOpenDoc = { mime -> openDocLauncher.launch(arrayOf(mime)) },
            onLaunchVoice = { lang ->
                try {
                    voiceLauncher.launch(voiceHelper.buildVoiceIntent(lang))
                } catch (_: Exception) {
                    voiceHelper.startListening(
                        langTag = lang,
                        onResult = { spoken -> pushJs("window.onNativeVoiceResult && window.onNativeVoiceResult(${JSONObject.quote(spoken)});") },
                        onError = { err -> pushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(err)});") }
                    )
                }
            },
            onSignOut = { signOutUser(credentialManager, scope) },
            onPushJs = { js -> pushJs(js) },
            onPushAll = {
                pushSyncedState()
                pushPermissionsAndTelemetry()
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                pushSyncedState()
                                pushPermissionsAndTelemetry()
                                if (!initialRoute.isNullOrBlank()) {
                                    pushJs("window.navigateTo && window.navigateTo(${JSONObject.quote(initialRoute)});")
                                }
                                if (!initialCommand.isNullOrBlank()) {
                                    pushJs("window.onNativeVoiceResult && window.onNativeVoiceResult(${JSONObject.quote(initialCommand)});")
                                }
                            }
                        }
                        addJavascriptInterface(bridge, "AndroidBridge")
                        loadUrl("file:///android_asset/index.html")
                        webViewRef = this
                    }
                }
            )
        }
    }
}

private fun readDocumentText(context: Context, uri: Uri): Pair<String, String> {
    var displayName = "Document"
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) displayName = c.getString(0) ?: displayName
        }
    } catch (_: Exception) {}

    val text = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buf = ByteArray(24_576)
            val read = stream.read(buf)
            if (read > 0) String(buf, 0, read, Charsets.UTF_8) else ""
        }.orEmpty()
    } catch (_: Exception) {
        ""
    }
    return displayName to text
}

private fun listSafFilesJson(context: Context, treeUri: Uri): String {
    val arr = JSONArray()
    try {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext() && arr.length() < 60) {
                val id = cursor.getString(0) ?: ""
                val name = cursor.getString(1) ?: "Unnamed"
                val mime = cursor.getString(2) ?: ""
                val size = cursor.getLong(3)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                arr.put(JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("mime", mime)
                    put("size", size)
                    put("uri", childUri.toString())
                    put("isDir", mime == DocumentsContract.Document.MIME_TYPE_DIR)
                })
            }
        }
    } catch (_: Exception) {}
    return JSONObject().put("treeUri", treeUri.toString()).put("files", arr).toString()
}

class AndroidBridge(
    private val context: Context,
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val apiSecurity: ApiSecurity,
    private val permissionManager: PermissionManager,
    private val phoneController: PhoneController,
    private val aiOrchestrator: AiOrchestrator,
    private val voiceHelper: VoiceAssistantHelper,
    private val repository: FirestoreRepository,
    private val appViewModel: AppViewModel,
    private val playBillingManager: PlayBillingManager,
    private val getPickedDocument: () -> Pair<String, String>,
    private val onRequestRuntimePermission: (String) -> Unit,
    private val onLaunchPhotoPicker: (Boolean) -> Unit,
    private val onLaunchSafTree: () -> Unit,
    private val onLaunchOpenDoc: (String) -> Unit,
    private val onLaunchVoice: (String) -> Unit,
    private val onSignOut: () -> Unit,
    private val onPushJs: (String) -> Unit,
    private val onPushAll: () -> Unit
) {

    private fun activePlayIds(): Set<String> = playBillingManager.snapshot.value.activeProductIds

    private fun hasFeature(key: String): Boolean = appViewModel.isFeatureUnlocked(key, activePlayIds())

    private fun currentPackageList(): List<PremiumPackage> =
        (appViewModel.premiumPackages.value as? UiState.Success)?.data ?: DEFAULT_PREMIUM_PACKAGES

    @JavascriptInterface
    fun getCatalogJson(): String = phoneController.getCatalogJson()

    @JavascriptInterface
    fun getTelemetryJson(): String = phoneController.getDeviceTelemetryJson()

    @JavascriptInterface
    fun getPermissionsJson(): String = permissionManager.getPermissionsJson()

    @JavascriptInterface
    fun getAiProvidersJson(): String = apiSecurity.getProvidersSnapshotJson()

    @JavascriptInterface
    fun getSyncedStateJson(): String {
        val doc = getPickedDocument()
        return appViewModel.buildSyncedStateJson(
            playBillingJson = playBillingManager.toJson(),
            playActiveIds = activePlayIds(),
            pickedDocName = doc.first,
            pickedDocPreview = doc.second.take(300)
        )
    }

    @JavascriptInterface
    fun requestPermission(permId: String) {
        val item = permissionManager.getAllPermissions().firstOrNull { it.id == permId } ?: return
        activity.runOnUiThread {
            when (item.kind) {
                PermissionManager.PermissionItem.Kind.RUNTIME -> {
                    val rp = item.runtimePermission
                    if (rp != null) onRequestRuntimePermission(rp)
                    else context.startActivity(permissionManager.buildSettingsIntent(permId))
                }
                PermissionManager.PermissionItem.Kind.SAF_STORAGE -> onLaunchSafTree()
                else -> context.startActivity(permissionManager.buildSettingsIntent(permId))
            }
        }
    }

    @JavascriptInterface
    fun openPermissionSettings(permId: String) {
        activity.runOnUiThread {
            context.startActivity(permissionManager.buildSettingsIntent(permId))
        }
    }

    @JavascriptInterface
    fun executeControl(controlId: Int, paramsJson: String, aiProvider: String, userConfirmed: Boolean): String {
        val profile = (appViewModel.userProfile.value as? UiState.Success)?.data
        if (profile?.banned == true && !appViewModel.isAdmin) {
            return JSONObject().put("success", false).put("message", "Account is suspended.").toString()
        }
        val meta = phoneController.getControlMetadata(controlId)
        if (meta != null && meta.requiresConfirmation && !userConfirmed) {
            return JSONObject()
                .put("success", false)
                .put("message", "User confirmation is required before executing ${meta.name}.")
                .toString()
        }

        val result = phoneController.executeControl(controlId, paramsJson)
        if (result.success && result.uiAction != null) {
            activity.runOnUiThread {
                when (result.uiAction) {
                    "keyboard_open" -> {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        activity.currentFocus?.let { imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
                    }
                    "keyboard_close" -> {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        activity.currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
                    }
                    "pick_image" -> onLaunchPhotoPicker(false)
                    "pick_video" -> onLaunchPhotoPicker(true)
                    "pick_audio" -> onLaunchOpenDoc("audio/*")
                    "pick_document" -> onLaunchOpenDoc("*/*")
                    "saf_tree" -> onLaunchSafTree()
                    "voice_input" -> onLaunchVoice("bn-BD")
                }
            }
        }

        scope.launch {
            repository.logActivity(
                controlId = controlId,
                actionName = result.actionName,
                category = meta?.category ?: "System",
                aiProvider = aiProvider.ifBlank { "Manual" },
                permissionStatus = if (result.needsPermission != null) "Missing (${result.needsPermission})" else "Granted",
                userConfirmed = userConfirmed,
                success = result.success,
                errorDetails = if (result.success) "" else result.message,
                isAiQuery = aiProvider != "Manual"
            )
        }
        return result.toJson()
    }

    @JavascriptInterface
    fun sendAiCommand(prompt: String, providerId: String, callbackId: String) {
        val profile = (appViewModel.userProfile.value as? UiState.Success)?.data
        val cfg = (appViewModel.premiumConfig.value as? UiState.Success)?.data ?: PremiumAppConfig()
        val isPremium = appViewModel.isEffectivePremium(activePlayIds())
        if (!isPremium && !appViewModel.isAdmin && (profile?.totalAiQueries ?: 0L) >= cfg.freeDailyAiLimit) {
            val err = JSONObject().apply {
                put("primary", JSONObject().apply {
                    put("providerName", "System")
                    put("controlId", 0)
                    put("replyText", "ফ্রি লিমিট শেষ (${cfg.freeDailyAiLimit} queries)। আনলিমিটেড ব্যবহারের জন্য Premium নিন।")
                    put("errorMessage", "Free AI limit reached")
                })
                put("allProposals", JSONArray())
            }.toString()
            onPushJs("window.onNativeAiResponse && window.onNativeAiResponse(${JSONObject.quote(callbackId)}, ${JSONObject.quote(err)});")
            return
        }

        scope.launch {
            val resultJson = withContext(Dispatchers.IO) {
                aiOrchestrator.interpretCommand(prompt, providerId.ifBlank { null })
            }
            onPushJs("window.onNativeAiResponse && window.onNativeAiResponse(${JSONObject.quote(callbackId)}, ${JSONObject.quote(resultJson)});")
        }
    }

    // --- Premium Tool 1: AI Code Generator & HTML/CSS/JS Studio ---
    @JavascriptInterface
    fun generateAiCode(prompt: String, mode: String, providerId: String, callbackId: String) {
        val requiredKey = if (mode == "web_bundle") "html_css_js_studio" else "ai_code_gen"
        if (!hasFeature(requiredKey) && !appViewModel.isAdmin) {
            val blocked = JSONObject()
                .put("ok", false)
                .put("errorMessage", "Premium Required: AI Code Studio ব্যবহার করতে Premium প্যাকেজ নিন।")
                .toString()
            onPushJs("window.onNativeCodeGenResponse && window.onNativeCodeGenResponse(${JSONObject.quote(callbackId)}, ${JSONObject.quote(blocked)});")
            return
        }
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                aiOrchestrator.generateCode(prompt, mode, providerId.ifBlank { null })
            }
            onPushJs("window.onNativeCodeGenResponse && window.onNativeCodeGenResponse(${JSONObject.quote(callbackId)}, ${JSONObject.quote(res)});")
        }
    }

    // --- Premium Tool 2: Custom AI Assistants ---
    @JavascriptInterface
    fun saveCustomAssistant(assistantId: String, name: String, roleTitle: String, systemPrompt: String, providerId: String) {
        if (!hasFeature("custom_ai_assistant") && !appViewModel.isAdmin) {
            onPushJs("window.onNativeToast && window.onNativeToast('Premium Required for Custom AI Assistant Builder.');")
            return
        }
        scope.launch {
            val res = repository.saveCustomAssistant(assistantId.ifBlank { null }, name, roleTitle, systemPrompt, providerId)
            val msg = if (res.isSuccess) "Custom AI Assistant saved!" else (res.exceptionOrNull()?.localizedMessage ?: "Save failed")
            onPushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(msg)});")
        }
    }

    @JavascriptInterface
    fun deleteCustomAssistant(assistantId: String) {
        scope.launch { repository.deleteCustomAssistant(assistantId) }
    }

    @JavascriptInterface
    fun runCustomAssistantChat(assistantId: String, userMessage: String, callbackId: String) {
        if (!hasFeature("custom_ai_assistant") && !appViewModel.isAdmin) {
            val blocked = JSONObject().put("ok", false).put("errorMessage", "Premium Required for Custom AI Assistants.").toString()
            onPushJs("window.onNativeCustomAssistantReply && window.onNativeCustomAssistantReply(${JSONObject.quote(callbackId)}, ${JSONObject.quote(blocked)});")
            return
        }
        val assistants = (appViewModel.customAssistants.value as? UiState.Success)?.data ?: emptyList()
        val target = assistants.firstOrNull { it.id == assistantId }
        if (target == null) {
            val missing = JSONObject().put("ok", false).put("errorMessage", "Assistant not found.").toString()
            onPushJs("window.onNativeCustomAssistantReply && window.onNativeCustomAssistantReply(${JSONObject.quote(callbackId)}, ${JSONObject.quote(missing)});")
            return
        }
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                aiOrchestrator.runCustomAssistantPrompt(target.name, target.systemPrompt, userMessage, target.providerId)
            }
            onPushJs("window.onNativeCustomAssistantReply && window.onNativeCustomAssistantReply(${JSONObject.quote(callbackId)}, ${JSONObject.quote(res)});")
        }
    }

    // --- Premium Tool 3: File Upload & AI Analysis ---
    @JavascriptInterface
    fun pickDocumentForAi() {
        if (!hasFeature("file_ai_analysis") && !appViewModel.isAdmin) {
            onPushJs("window.onNativeToast && window.onNativeToast('Premium Required: File Upload & AI Analysis.');")
            return
        }
        activity.runOnUiThread { onLaunchOpenDoc("*/*") }
    }

    @JavascriptInterface
    fun analyzePickedDocument(question: String, providerId: String, callbackId: String) {
        if (!hasFeature("file_ai_analysis") && !appViewModel.isAdmin) {
            val blocked = JSONObject().put("ok", false).put("errorMessage", "Premium Required for File AI Analysis.").toString()
            onPushJs("window.onNativeFileAnalysis && window.onNativeFileAnalysis(${JSONObject.quote(callbackId)}, ${JSONObject.quote(blocked)});")
            return
        }
        val (name, content) = getPickedDocument()
        if (content.isBlank()) {
            val empty = JSONObject().put("ok", false).put("errorMessage", "Please pick a readable text/code/log file first.").toString()
            onPushJs("window.onNativeFileAnalysis && window.onNativeFileAnalysis(${JSONObject.quote(callbackId)}, ${JSONObject.quote(empty)});")
            return
        }
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                aiOrchestrator.analyzeDocumentContent(name, content, question.ifBlank { "Summarize and explain key points." }, providerId.ifBlank { null })
            }
            onPushJs("window.onNativeFileAnalysis && window.onNativeFileAnalysis(${JSONObject.quote(callbackId)}, ${JSONObject.quote(res)});")
        }
    }

    @JavascriptInterface
    fun saveApiKey(providerId: String, apiKey: String, customEndpoint: String, usageLimit: Int): String {
        if (providerId != "gemini" && !hasFeature("custom_api_keys") && !appViewModel.isAdmin) {
            onPushJs("window.onNativeToast && window.onNativeToast('Premium Required to link ChatGPT/Claude/DeepSeek API keys.');")
            return apiSecurity.getProvidersSnapshotJson()
        }
        apiSecurity.saveApiKey(providerId, apiKey, customEndpoint, usageLimit)
        onPushAll()
        return apiSecurity.getProvidersSnapshotJson()
    }

    @JavascriptInterface
    fun deleteApiKey(providerId: String): String {
        apiSecurity.deleteApiKey(providerId)
        onPushAll()
        return apiSecurity.getProvidersSnapshotJson()
    }

    @JavascriptInterface
    fun setProviderConfig(providerId: String, enabled: Boolean, isDefault: Boolean, multiAi: Boolean): String {
        if (multiAi && !hasFeature("multi_ai") && !appViewModel.isAdmin) {
            onPushJs("window.onNativeToast && window.onNativeToast('Premium Required for Multi-AI Parallel Mode.');")
            apiSecurity.setMultiAiMode(false)
        } else {
            apiSecurity.setMultiAiMode(multiAi)
        }
        apiSecurity.setProviderEnabled(providerId, enabled)
        if (isDefault) apiSecurity.setDefaultProvider(providerId)
        onPushAll()
        return apiSecurity.getProvidersSnapshotJson()
    }

    @JavascriptInterface
    fun testApiConnection(providerId: String, callbackId: String) {
        scope.launch {
            val res = withContext(Dispatchers.IO) { aiOrchestrator.testConnection(providerId) }
            onPushJs("window.onNativeApiTest && window.onNativeApiTest(${JSONObject.quote(callbackId)}, ${JSONObject.quote(res.toString())});")
            onPushAll()
        }
    }

    @JavascriptInterface
    fun clearApiErrors(): String {
        apiSecurity.clearErrorHistory()
        onPushAll()
        return apiSecurity.getProvidersSnapshotJson()
    }

    @JavascriptInterface
    fun controlFloating(action: String, colorHex: String, sizeDp: Int): String {
        if (action == "start" && !permissionManager.isPermissionGranted("overlay")) {
            return JSONObject().put("success", false).put("needsPermission", "overlay").put("message", "Overlay Permission is required first.").toString()
        }
        val intent = Intent(context, FloatingService::class.java).apply {
            this.action = when (action) {
                "start" -> FloatingService.ACTION_START
                "stop" -> FloatingService.ACTION_STOP
                "hide" -> FloatingService.ACTION_HIDE
                "show" -> FloatingService.ACTION_SHOW
                "style" -> FloatingService.ACTION_UPDATE_STYLE
                else -> FloatingService.ACTION_START
            }
            putExtra(FloatingService.EXTRA_COLOR, colorHex)
            putExtra(FloatingService.EXTRA_SIZE_DP, sizeDp)
        }
        activity.runOnUiThread {
            if (action == "start" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
        return JSONObject().put("success", true).put("message", "Floating controller updated ($action).").toString()
    }

    @JavascriptInterface
    fun startVoiceRecognition(langTag: String) {
        activity.runOnUiThread { onLaunchVoice(langTag.ifBlank { "bn-BD" }) }
    }

    @JavascriptInterface
    fun speakText(text: String, langTag: String) {
        voiceHelper.setLanguage(langTag.ifBlank { "bn-BD" })
        voiceHelper.speak(text)
    }

    @JavascriptInterface
    fun stopSpeaking() {
        voiceHelper.stopSpeaking()
    }

    @JavascriptInterface
    fun updateTheme(themeMode: String, accentColor: String) {
        if (themeMode == "gold_royal" && !hasFeature("gold_themes") && !appViewModel.isAdmin) {
            onPushJs("window.onNativeToast && window.onNativeToast('Black & Gold Royal Theme requires Premium!');")
            return
        }
        scope.launch {
            repository.updateThemePreferences(themeMode, accentColor)
        }
    }

    @JavascriptInterface
    fun saveAutomationRule(ruleId: String, title: String, triggerType: String, triggerValue: String, actionCommand: String, enabled: Boolean) {
        val currentRules = (appViewModel.automations.value as? UiState.Success)?.data ?: emptyList()
        if (ruleId.isBlank() && currentRules.size >= 2 && !hasFeature("adv_automation") && !appViewModel.isAdmin) {
            onPushJs("window.onNativeToast && window.onNativeToast('Free limit: max 2 automations. Upgrade to Premium for unlimited!');")
            return
        }
        scope.launch {
            repository.saveAutomation(ruleId.ifBlank { null }, title, triggerType, triggerValue, actionCommand, enabled)
        }
    }

    @JavascriptInterface
    fun deleteAutomationRule(ruleId: String) {
        scope.launch { repository.deleteAutomation(ruleId) }
    }

    @JavascriptInterface
    fun triggerTestAlert(title: String, message: String) {
        NotificationService.postSystemAlert(context, title, message)
    }

    @JavascriptInterface
    fun deleteActivityLog(logId: String) {
        scope.launch { repository.deleteActivityLog(logId) }
    }

    // --- Google Play Store & Play Billing Bridge ---
    @JavascriptInterface
    fun launchPlayPurchase(productId: String): String {
        val res = playBillingManager.launchPurchase(activity, productId)
        return res.toString()
    }

    @JavascriptInterface
    fun restorePlayPurchases() {
        playBillingManager.restorePurchases()
    }

    @JavascriptInterface
    fun openPlayStoreListing() {
        activity.runOnUiThread { playBillingManager.openPlayStoreListing(activity) }
    }

    @JavascriptInterface
    fun openPlaySubscriptions() {
        activity.runOnUiThread { playBillingManager.openPlaySubscriptions(activity) }
    }

    @JavascriptInterface
    fun openPlayStoreHome() {
        activity.runOnUiThread { playBillingManager.openPlayStoreHome(activity) }
    }

    // --- Payments, Refunds & Subscription Self-Cancel ---
    @JavascriptInterface
    fun submitPackagePayment(packageId: String, method: String, senderNumber: String, transactionId: String) {
        val pkg = currentPackageList().firstOrNull { it.packageId == packageId } ?: currentPackageList().first()
        scope.launch {
            val res = repository.submitPaymentRequest(pkg, method, senderNumber, transactionId)
            val msg = if (res.isSuccess) {
                "Payment submitted (TrxID: $transactionId). Waiting for Admin verification."
            } else {
                res.exceptionOrNull()?.localizedMessage ?: "Payment failed"
            }
            onPushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(msg)});")
        }
    }

    @JavascriptInterface
    fun requestPaymentRefund(requestId: String, reason: String) {
        scope.launch {
            val res = repository.requestRefund(requestId, reason)
            val msg = if (res.isSuccess) "Refund request sent to Admin." else (res.exceptionOrNull()?.localizedMessage ?: "Refund request failed")
            onPushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(msg)});")
        }
    }

    @JavascriptInterface
    fun cancelMySubscription() {
        scope.launch {
            val res = repository.cancelOwnSubscription()
            val msg = if (res.isSuccess) "Subscription cancelled. Switched to Free account." else (res.exceptionOrNull()?.localizedMessage ?: "Cancel failed")
            onPushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(msg)});")
        }
    }

    // --- Admin Panel Controls (Double-checked in Kotlin + Firestore Security Rules) ---
    @JavascriptInterface
    fun adminGiftOrGrantPackage(targetUserId: String, targetEmail: String, packageId: String, gifted: Boolean) {
        val pkg = currentPackageList().firstOrNull { it.packageId == packageId } ?: currentPackageList().first()
        scope.launch {
            val res = repository.adminGrantOrGiftPackage(targetUserId, targetEmail, pkg, gifted)
            val msg = if (res.isSuccess) "Granted ${pkg.name} to $targetEmail" else (res.exceptionOrNull()?.localizedMessage ?: "Action failed")
            onPushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(msg)});")
        }
    }

    @JavascriptInterface
    fun adminAdjustPremiumDays(targetUserId: String, targetEmail: String, deltaDays: Long) {
        scope.launch {
            val res = repository.adminAdjustDays(targetUserId, targetEmail, deltaDays)
            val msg = if (res.isSuccess) "Adjusted $deltaDays day(s) for $targetEmail" else (res.exceptionOrNull()?.localizedMessage ?: "Action failed")
            onPushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(msg)});")
        }
    }

    @JavascriptInterface
    fun adminSetUserPremiumStatus(targetUserId: String, targetEmail: String, status: String) {
        scope.launch {
            val res = repository.adminSetPremiumStatus(targetUserId, targetEmail, status)
            val msg = if (res.isSuccess) "Updated status -> $status" else (res.exceptionOrNull()?.localizedMessage ?: "Action failed")
            onPushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(msg)});")
        }
    }

    @JavascriptInterface
    fun adminBanUser(targetUserId: String, targetEmail: String, ban: Boolean, reason: String) {
        scope.launch {
            val res = repository.adminBanUser(targetUserId, targetEmail, ban, reason)
            val msg = if (res.isSuccess) {
                if (ban) "User $targetEmail banned." else "User $targetEmail unbanned."
            } else {
                res.exceptionOrNull()?.localizedMessage ?: "Ban action failed"
            }
            onPushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(msg)});")
        }
    }

    @JavascriptInterface
    fun adminInspectUser(targetUserId: String, callbackId: String) {
        scope.launch {
            val res = repository.adminInspectUserDetails(targetUserId)
            val out = JSONObject()
            if (res.isSuccess) {
                val (prof, logs) = res.getOrThrow()
                out.put("ok", true)
                out.put("profile", JSONObject().apply {
                    put("displayName", prof?.displayName ?: "")
                    put("email", prof?.email ?: "")
                    put("premiumStatus", prof?.premiumStatus ?: "free")
                    put("packageName", prof?.packageName ?: "")
                    put("subscriptionId", prof?.subscriptionId ?: "")
                    put("remainingDays", prof?.remainingDays() ?: 0L)
                    put("banned", prof?.banned ?: false)
                    put("banReason", prof?.banReason ?: "")
                })
                out.put("logs", JSONArray().apply {
                    logs.forEach { l ->
                        put(JSONObject().apply {
                            put("controlId", l.controlId)
                            put("actionName", l.actionName)
                            put("aiProvider", l.aiProvider)
                            put("success", l.success)
                        })
                    }
                })
            } else {
                out.put("ok", false).put("error", res.exceptionOrNull()?.localizedMessage ?: "Inspection failed")
            }
            onPushJs("window.onNativeAdminInspect && window.onNativeAdminInspect(${JSONObject.quote(callbackId)}, ${JSONObject.quote(out.toString())});")
        }
    }

    @JavascriptInterface
    fun adminSavePackage(jsonStr: String) {
        scope.launch {
            val res = try {
                val o = JSONObject(jsonStr)
                val pkg = PremiumPackage(
                    packageId = o.getString("packageId"),
                    name = o.getString("name"),
                    nameBn = o.optString("nameBn", ""),
                    description = o.optString("description", ""),
                    durationDays = o.optLong("durationDays", 30L),
                    lifetimePlan = o.optBoolean("lifetimePlan", false),
                    priceBdt = o.optLong("priceBdt", 299L),
                    playProductId = o.optString("playProductId", ""),
                    tier = o.optString("tier", "pro"),
                    featuresSummary = o.optString("featuresSummary", "All Premium Features"),
                    active = o.optBoolean("active", true),
                    sortOrder = o.optLong("sortOrder", 10L)
                )
                repository.adminSavePackage(pkg)
            } catch (e: Exception) {
                Result.failure(e)
            }
            val msg = if (res.isSuccess) "Package saved!" else (res.exceptionOrNull()?.localizedMessage ?: "Package save failed")
            onPushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(msg)});")
        }
    }

    @JavascriptInterface
    fun adminDeletePackage(packageId: String) {
        scope.launch {
            val res = repository.adminDeletePackage(packageId)
            val msg = if (res.isSuccess) "Package deleted." else (res.exceptionOrNull()?.localizedMessage ?: "Delete failed")
            onPushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(msg)});")
        }
    }

    @JavascriptInterface
    fun adminSeedDefaultPackages() {
        scope.launch {
            val res = repository.seedDefaultPackagesAndConfigIfNeeded()
            val msg = if (res.isSuccess) "Default 6 packages & feature flags synced!" else (res.exceptionOrNull()?.localizedMessage ?: "Seed failed")
            onPushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(msg)});")
        }
    }

    @JavascriptInterface
    fun adminSaveAppConfig(jsonStr: String) {
        scope.launch {
            val res = try {
                val o = JSONObject(jsonStr)
                val flagsJson = o.optJSONObject("featureFlags")
                val flags = defaultFeatureFlagsMap().toMutableMap()
                if (flagsJson != null) {
                    flagsJson.keys().forEach { k -> flags[k] = flagsJson.optBoolean(k, true) }
                }
                val cfg = PremiumAppConfig(
                    freeDailyAiLimit = o.optLong("freeDailyAiLimit", 15L),
                    merchantBkashNumber = o.optString("merchantBkashNumber", ""),
                    merchantNagadNumber = o.optString("merchantNagadNumber", ""),
                    merchantRocketNumber = o.optString("merchantRocketNumber", ""),
                    webhookVerifyUrl = o.optString("webhookVerifyUrl", ""),
                    promoBanner = o.optString("promoBanner", ""),
                    featureFlags = flags
                )
                repository.adminUpdateAppConfig(cfg)
            } catch (e: Exception) {
                Result.failure(e)
            }
            val msg = if (res.isSuccess) "Feature flags & config updated!" else (res.exceptionOrNull()?.localizedMessage ?: "Update failed")
            onPushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(msg)});")
        }
    }

    @JavascriptInterface
    fun adminReviewPaymentV2(paymentId: String, newStatus: String, adminNote: String) {
        val allPays = (appViewModel.adminPayments.value as? UiState.Success)?.data ?: emptyList()
        val pay = allPays.firstOrNull { it.id == paymentId } ?: PaymentRequest(id = paymentId)
        scope.launch {
            val res = repository.adminReviewPayment(pay, newStatus, adminNote)
            val msg = if (res.isSuccess) "Payment $newStatus!" else (res.exceptionOrNull()?.localizedMessage ?: "Review failed")
            onPushJs("window.onNativeToast && window.onNativeToast(${JSONObject.quote(msg)});")
        }
    }

    @JavascriptInterface
    fun setAdminPasscode(passcode: String): Boolean = apiSecurity.setAdminPasscode(passcode)

    @JavascriptInterface
    fun verifyAdminPasscode(passcode: String): Boolean = apiSecurity.verifyAdminPasscode(passcode)

    @JavascriptInterface
    fun renameSafFile(uriString: String, newName: String): String {
        return try {
            val uri = Uri.parse(uriString)
            DocumentsContract.renameDocument(context.contentResolver, uri, newName)
            JSONObject().put("success", true).put("message", "Renamed to $newName").toString()
        } catch (e: Exception) {
            JSONObject().put("success", false).put("message", e.localizedMessage ?: "Rename failed").toString()
        }
    }

    @JavascriptInterface
    fun signOut() {
        activity.runOnUiThread { onSignOut() }
    }

    @JavascriptInterface
    fun showToast(msg: String) {
        activity.runOnUiThread { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }
}
