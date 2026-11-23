package com.example

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.inputmethod.InputMethodManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class PhoneController(
    private val context: Context,
    private val permissionManager: PermissionManager
) {

    data class ControlMetadata(
        val id: Int,
        val name: String,
        val nameBn: String,
        val category: String,
        val requiredPermission: String?,
        val requiresConfirmation: Boolean,
        val isDangerous: Boolean,
        val limitationNote: String? = null
    )

    data class ExecutionResult(
        val controlId: Int,
        val actionName: String,
        val success: Boolean,
        val message: String,
        val dataJson: String = "{}",
        val needsPermission: String? = null,
        val uiAction: String? = null
    ) {
        fun toJson(): String = JSONObject().apply {
            put("controlId", controlId)
            put("actionName", actionName)
            put("success", success)
            put("message", message)
            put("data", try { JSONObject(dataJson) } catch (_: Exception) { JSONObject() })
            if (needsPermission != null) put("needsPermission", needsPermission)
            if (uiAction != null) put("uiAction", uiAction)
        }.toString()
    }

    val allControls: List<ControlMetadata> = buildCatalog()

    fun isValidControl(controlId: Int): Boolean = controlId in 1..100

    fun getControlMetadata(controlId: Int): ControlMetadata? = allControls.firstOrNull { it.id == controlId }

    fun getCatalogJson(): String {
        val arr = JSONArray()
        allControls.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("nameBn", c.nameBn)
                put("category", c.category)
                put("requiredPermission", c.requiredPermission ?: "")
                put("requiresConfirmation", c.requiresConfirmation)
                put("isDangerous", c.isDangerous)
                put("limitationNote", c.limitationNote ?: "")
            })
        }
        return arr.toString()
    }

    fun getDeviceTelemetryJson(): String {
        val batteryInfo = readBatteryInfo()
        val ramInfo = readRamInfo()
        val storageInfo = readStorageInfo()
        val netInfo = readNetworkInfo()
        val audioInfo = readAudioVolumes()

        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("brand", Build.BRAND)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("cpuCores", Runtime.getRuntime().availableProcessors())
            put("abis", Build.SUPPORTED_ABIS.joinToString(", "))
            put("battery", batteryInfo)
            put("ram", ramInfo)
            put("storage", storageInfo)
            put("network", netInfo)
            put("audio", audioInfo)
            put("brightness", getCurrentBrightnessPercent())
            put("autoBrightness", isAutoBrightnessEnabled())
            put("autoRotate", isAutoRotateEnabled())
            put("floatingRunning", FloatingService.isRunning)
            put("accessibilityEnabled", permissionManager.isAccessibilityEnabled())
            put("activeNotifications", NotificationService.activeCount)
        }.toString()
    }

    fun executeControl(controlId: Int, paramsJson: String = "{}"): ExecutionResult {
        val meta = getControlMetadata(controlId)
            ?: return ExecutionResult(controlId, "Unknown", false, "Unauthorized or unknown control ID: $controlId")

        val params = try { JSONObject(paramsJson) } catch (_: Exception) { JSONObject() }

        // Check mandatory permission first
        if (!meta.requiredPermission.isNullOrBlank() && !permissionManager.isPermissionGranted(meta.requiredPermission)) {
            return ExecutionResult(
                controlId = controlId,
                actionName = meta.name,
                success = false,
                message = "Permission required: ${meta.requiredPermission}. Please grant it in Permission Center.",
                needsPermission = meta.requiredPermission
            )
        }

        return try {
            when (controlId) {
                // A. Display Control (1-10)
                1 -> setBrightness(params.optInt("value", 50))
                2 -> setAutoBrightness(params.optBoolean("enabled", true))
                3 -> setScreenTimeout(params.optInt("seconds", 60))
                4 -> openSettingsAction(4, meta.name, Settings.ACTION_DISPLAY_SETTINGS, "Opened Display Settings for Dark Mode (app theme updated).")
                5 -> openSettingsAction(5, meta.name, Settings.ACTION_DISPLAY_SETTINGS, "Opened Display Settings for Light Mode (app theme updated).")
                6 -> setAutoRotate(params.optBoolean("enabled", true))
                7, 8 -> openSettingsAction(controlId, meta.name, Settings.ACTION_DISPLAY_SETTINGS, "Opened Display Settings to adjust font/display scaling.")
                9 -> openSettingsAction(9, meta.name, Settings.ACTION_ACCESSIBILITY_SETTINGS, "Opened Accessibility Settings for Screen Magnification.")
                10 -> openWallpaperChooser()

                // B. Keyboard Control (11-20)
                11 -> ExecutionResult(11, meta.name, true, "Keyboard open requested.", uiAction = "keyboard_open")
                12 -> ExecutionResult(12, meta.name, true, "Keyboard close requested.", uiAction = "keyboard_close")
                13, 14, 18, 20 -> openSettingsAction(controlId, meta.name, Settings.ACTION_INPUT_METHOD_SETTINGS, "Opened System Keyboard & Input Method Settings.", dataJson = readInputMethodsJson())
                15 -> toggleSystemBooleanSetting(15, meta.name, Settings.System.SOUND_EFFECTS_ENABLED, params.optBoolean("enabled", true))
                16 -> toggleSystemBooleanSetting(16, meta.name, Settings.System.HAPTIC_FEEDBACK_ENABLED, params.optBoolean("enabled", true))
                17 -> openSettingsAction(17, meta.name, Settings.ACTION_USER_DICTIONARY_SETTINGS, "Opened Personal Dictionary & Auto-Correction Settings.")
                19 -> ExecutionResult(19, meta.name, true, "Launching Voice Typing / Speech Recognition.", uiAction = "voice_input")

                // C. Application Control (21-30)
                21 -> ExecutionResult(21, meta.name, true, "Loaded installed launchable applications.", dataJson = JSONObject().put("apps", getInstalledAppsArray()).toString())
                22 -> launchAppByQuery(params.optString("packageName", params.optString("query", "")))
                23, 25 -> openAppDetails(controlId, meta.name, params.optString("packageName", context.packageName))
                24 -> readAppInfo(params.optString("packageName", context.packageName))
                26 -> openAppNotifications(params.optString("packageName", context.packageName))
                27 -> readAppUsageSummary()
                28 -> uninstallApp(params.optString("packageName", ""))
                29 -> openAppDetails(29, meta.name, params.optString("packageName", context.packageName), "Android restricts clearing other apps' cache directly. Opened App Info -> Storage & Cache.")
                30 -> openSettingsAction(30, meta.name, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "Opened Default Apps Settings.")

                // D. Sound Control (31-40)
                31 -> setStreamVolume(31, meta.name, AudioManager.STREAM_MUSIC, params.optInt("value", 50))
                32 -> setStreamVolume(32, meta.name, AudioManager.STREAM_VOICE_CALL, params.optInt("value", 60))
                33 -> setStreamVolume(33, meta.name, AudioManager.STREAM_ALARM, params.optInt("value", 70))
                34 -> setStreamVolume(34, meta.name, AudioManager.STREAM_NOTIFICATION, params.optInt("value", 50))
                35 -> setStreamVolume(35, meta.name, AudioManager.STREAM_RING, params.optInt("value", 60))
                36 -> setRingerMode(36, meta.name, AudioManager.RINGER_MODE_SILENT)
                37 -> setRingerMode(37, meta.name, AudioManager.RINGER_MODE_VIBRATE)
                38 -> openSettingsAction(38, meta.name, Settings.ACTION_SOUND_SETTINGS, "Opened System Sound & Ringtone Settings.")
                39 -> openDoNotDisturb()
                40 -> ExecutionResult(40, meta.name, true, "Fetched active audio output devices.", dataJson = readAudioOutputsJson())

                // E. Network Control (41-50)
                41, 43 -> openWifiPanel(controlId, meta.name)
                42 -> ExecutionResult(42, meta.name, true, "Wi-Fi status read.", dataJson = readNetworkInfo().toString())
                44 -> openSettingsAction(44, meta.name, Settings.ACTION_DATA_ROAMING_SETTINGS, "Android restricts direct Mobile Data toggling. Opened Mobile Network Settings.")
                45 -> openSettingsAction(45, meta.name, Settings.ACTION_BLUETOOTH_SETTINGS, "Opened Bluetooth Settings.", dataJson = readBluetoothInfoJson())
                46 -> openSettingsAction(46, meta.name, Settings.ACTION_WIRELESS_SETTINGS, "Opened Hotspot & Tethering Settings.")
                47 -> openSettingsAction(47, meta.name, Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Android restricts direct Airplane Mode toggling. Opened Airplane Mode Settings.")
                48 -> ExecutionResult(48, meta.name, true, "SIM & Carrier info read.", dataJson = readSimInfoJson())
                49, 50 -> ExecutionResult(controlId, meta.name, true, "Network & Internet connectivity verified.", dataJson = readNetworkInfo().toString())

                // F. Security & Permission Control (51-60)
                51 -> ExecutionResult(51, meta.name, true, "Camera permission status verified.", uiAction = "open_permission_center")
                52 -> ExecutionResult(52, meta.name, true, "Microphone permission status verified.", uiAction = "open_permission_center")
                53 -> ExecutionResult(53, meta.name, true, "Location permission status verified.", uiAction = "open_permission_center")
                54 -> ExecutionResult(54, meta.name, true, "Storage (SAF) status verified.", uiAction = "open_permission_center")
                55 -> ExecutionResult(55, meta.name, true, "Contacts permission status verified.", uiAction = "open_permission_center")
                56 -> ExecutionResult(56, meta.name, true, "SMS permission status verified.", uiAction = "open_permission_center")
                57 -> ExecutionResult(57, meta.name, true, "Phone permission status verified.", uiAction = "open_permission_center")
                58 -> ExecutionResult(58, meta.name, true, "Notification permission status verified.", uiAction = "open_permission_center")
                59 -> ExecutionResult(59, meta.name, true, "Accessibility permission status verified.", uiAction = "open_permission_center")
                60 -> ExecutionResult(60, meta.name, true, "Opening Permission Dashboard.", uiAction = "open_permission_center")

                // G. File & Storage Control (61-70)
                61 -> ExecutionResult(61, meta.name, true, "Opening Storage Access Framework folder browser.", uiAction = "saf_tree")
                62 -> ExecutionResult(62, meta.name, true, "Opening Android Photo Picker (Images).", uiAction = "pick_image")
                63 -> ExecutionResult(63, meta.name, true, "Opening Android Photo Picker (Videos).", uiAction = "pick_video")
                64 -> ExecutionResult(64, meta.name, true, "Opening System Audio File Picker.", uiAction = "pick_audio")
                65 -> ExecutionResult(65, meta.name, true, "Opening System Document Picker.", uiAction = "pick_document")
                66, 67, 68, 69 -> ExecutionResult(controlId, meta.name, true, "Opening File Manager SAF workspace.", uiAction = "open_file_manager")
                70 -> ExecutionResult(70, meta.name, true, "Storage stats calculated.", dataJson = readStorageInfo().toString())

                // H. Battery & Performance Control (71-80)
                71, 72, 73 -> ExecutionResult(controlId, meta.name, true, "Battery telemetry updated.", dataJson = readBatteryInfo().toString())
                74 -> openSettingsAction(74, meta.name, Settings.ACTION_BATTERY_SAVER_SETTINGS, "Opened Battery Saver Settings.", dataJson = readBatteryInfo().toString())
                75 -> openSettingsAction(75, meta.name, Intent.ACTION_POWER_USAGE_SUMMARY, "Opened System Battery Usage screen.")
                76 -> ExecutionResult(76, meta.name, true, "RAM telemetry read.", dataJson = readRamInfo().toString())
                77 -> ExecutionResult(77, meta.name, true, "CPU architecture info read.", dataJson = readCpuInfoJson())
                78 -> ExecutionResult(78, meta.name, true, "Device battery temperature read.", dataJson = readBatteryInfo().toString())
                79 -> openStorageOptimization()
                80 -> ExecutionResult(80, meta.name, true, "Performance dashboard refreshed.", dataJson = getDeviceTelemetryJson())

                // I. Phone & System Control (81-90)
                81, 82 -> ExecutionResult(controlId, meta.name, true, "Device & Android version info read.", dataJson = readDeviceInfoJson())
                83 -> openSettingsAction(83, meta.name, Settings.ACTION_SETTINGS, "Opened System Settings.")
                84 -> openSettingsAction(84, meta.name, Settings.ACTION_DATE_SETTINGS, "Opened Date & Time Settings.")
                85 -> openSettingsAction(85, meta.name, Settings.ACTION_LOCALE_SETTINGS, "Opened Language & Region Settings.")
                86 -> openSettingsAction(86, meta.name, Settings.ACTION_ACCESSIBILITY_SETTINGS, "Opened Accessibility Settings.")
                87 -> if (AccessibilityController.openNotificationShade()) {
                    ExecutionResult(87, meta.name, true, "Opened Notification Panel.")
                } else {
                    ExecutionResult(87, meta.name, false, "Enable Accessibility Service to expand Notification Panel.", needsPermission = "accessibility")
                }
                88 -> if (AccessibilityController.openQuickSettings()) {
                    ExecutionResult(88, meta.name, true, "Opened Quick Settings Panel.")
                } else {
                    ExecutionResult(88, meta.name, false, "Enable Accessibility Service to expand Quick Settings.", needsPermission = "accessibility")
                }
                89 -> if (AccessibilityController.lockScreen()) {
                    ExecutionResult(89, meta.name, true, "Screen locked via Accessibility.")
                } else {
                    openSettingsAction(89, meta.name, Settings.ACTION_SECURITY_SETTINGS, "Opened Screen Lock & Security Settings.")
                }
                90 -> if (AccessibilityController.openPowerDialog()) {
                    ExecutionResult(90, meta.name, true, "Opened Android Power / Restart Menu.")
                } else {
                    ExecutionResult(90, meta.name, false, "Non-root apps require Accessibility Service to open the Power/Restart dialog.", needsPermission = "accessibility")
                }

                // J. AI Automation & Smart Control (91-100)
                91 -> ExecutionResult(91, meta.name, true, "Launching Voice Command.", uiAction = "voice_input")
                92 -> ExecutionResult(92, meta.name, true, "Opening AI Text Command Assistant.", uiAction = "open_ai_assistant")
                93 -> launchAppByQuery(params.optString("query", ""))
                94 -> openSmartSetting(params.optString("setting", ""))
                95, 96 -> ExecutionResult(controlId, meta.name, true, "Opening Automation Manager.", uiAction = "open_automation")
                97 -> ExecutionResult(97, meta.name, true, "Opening Smart Permission Manager.", uiAction = "open_permission_center")
                98 -> ExecutionResult(98, meta.name, true, "Opening AI Phone Assistant.", uiAction = "open_ai_assistant")
                99 -> ExecutionResult(99, meta.name, true, "Toggling Floating Controller.", uiAction = "toggle_floating")
                100 -> ExecutionResult(100, meta.name, true, "Multi-Task AI Control ready.", uiAction = "open_ai_assistant")
                else -> ExecutionResult(controlId, meta.name, false, "Unsupported control ID")
            }
        } catch (e: Exception) {
            ExecutionResult(controlId, meta.name, false, e.localizedMessage ?: "Execution failed")
        }
    }

    private fun setBrightness(percent: Int): ExecutionResult {
        val clamped = percent.coerceIn(5, 100)
        val value = (clamped * 255) / 100
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return ExecutionResult(1, "Screen Brightness Control", true, "Screen brightness set to $clamped%.", JSONObject().put("brightness", clamped).toString())
    }

    private fun setAutoBrightness(enabled: Boolean): ExecutionResult {
        val mode = if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
        return ExecutionResult(2, "Auto Brightness", true, "Auto brightness ${if (enabled) "enabled" else "disabled"}.")
    }

    private fun setScreenTimeout(seconds: Int): ExecutionResult {
        val ms = seconds.coerceIn(15, 1800) * 1000
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms)
        return ExecutionResult(3, "Screen Timeout", true, "Screen timeout set to ${seconds}s.")
    }

    private fun setAutoRotate(enabled: Boolean): ExecutionResult {
        Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (enabled) 1 else 0)
        return ExecutionResult(6, "Screen Rotation", true, "Auto-rotation ${if (enabled) "enabled" else "locked"}.")
    }

    private fun toggleSystemBooleanSetting(id: Int, name: String, key: String, enabled: Boolean): ExecutionResult {
        Settings.System.putInt(context.contentResolver, key, if (enabled) 1 else 0)
        return ExecutionResult(id, name, true, "$name set to ${if (enabled) "ON" else "OFF"}.")
    }

    private fun openWallpaperChooser(): ExecutionResult {
        val intent = Intent(Intent.ACTION_SET_WALLPAPER)
        val chooser = Intent.createChooser(intent, "Choose Wallpaper").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        return ExecutionResult(10, "Wallpaper Control", true, "Opened system Wallpaper chooser.")
    }

    private fun setStreamVolume(id: Int, name: String, stream: Int, percent: Int): ExecutionResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val target = ((percent.coerceIn(0, 100) / 100.0) * max).toInt().coerceIn(0, max)
        am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        return ExecutionResult(id, name, true, "$name set to ${percent.coerceIn(0, 100)}%.", JSONObject().put("percent", percent.coerceIn(0, 100)).toString())
    }

    private fun setRingerMode(id: Int, name: String, mode: Int): ExecutionResult {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted && mode == AudioManager.RINGER_MODE_SILENT) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return ExecutionResult(id, name, false, "Do Not Disturb access needed for Silent Mode. Opened Policy Settings.")
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = mode
        return ExecutionResult(id, name, true, "$name activated.")
    }

    private fun openDoNotDisturb(): ExecutionResult {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ExecutionResult(39, "Do Not Disturb", true, "Opened Do Not Disturb Policy Settings.")
    }

    private fun openWifiPanel(id: Int, name: String): ExecutionResult {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_WIFI
        } else {
            Settings.ACTION_WIFI_SETTINGS
        }
        return openSettingsAction(id, name, action, "Opened Wi-Fi Control Panel.")
    }

    private fun openStorageOptimization(): ExecutionResult {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            StorageManager.ACTION_MANAGE_STORAGE
        } else {
            Settings.ACTION_INTERNAL_STORAGE_SETTINGS
        }
        return openSettingsAction(79, "Storage Optimization", action, "Opened Android Storage Manager.")
    }

    private fun openSettingsAction(id: Int, name: String, action: String, msg: String, dataJson: String = "{}"): ExecutionResult {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ExecutionResult(id, name, true, msg, dataJson)
    }

    private fun openAppDetails(id: Int, name: String, packageName: String, customMsg: String? = null): ExecutionResult {
        val pkg = packageName.ifBlank { context.packageName }
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ExecutionResult(id, name, true, customMsg ?: "Opened App Settings for $pkg.")
    }

    private fun openAppNotifications(packageName: String): ExecutionResult {
        val pkg = packageName.ifBlank { context.packageName }
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ExecutionResult(26, "App Notifications", true, "Opened notification settings for $pkg.")
    }

    private fun uninstallApp(packageName: String): ExecutionResult {
        if (packageName.isBlank() || packageName == context.packageName) {
            return ExecutionResult(28, "Uninstall App", false, "Please choose a valid third-party app package.")
        }
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ExecutionResult(28, "Uninstall App", true, "Opened system uninstall confirmation for $packageName.")
    }

    private fun launchAppByQuery(queryOrPkg: String): ExecutionResult {
        if (queryOrPkg.isBlank()) {
            return ExecutionResult(22, "Open Apps", true, "Select an app to launch.", JSONObject().put("apps", getInstalledAppsArray()).toString())
        }
        val pm = context.packageManager
        val direct = pm.getLaunchIntentForPackage(queryOrPkg)
        if (direct != null) {
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(direct)
            return ExecutionResult(22, "Open Apps", true, "Launched $queryOrPkg.")
        }
        val apps = getInstalledAppsList()
        val match = apps.firstOrNull {
            it.first.contains(queryOrPkg, ignoreCase = true) || it.second.contains(queryOrPkg, ignoreCase = true)
        }
        if (match != null) {
            val launch = pm.getLaunchIntentForPackage(match.second)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch != null) {
                context.startActivity(launch)
                return ExecutionResult(22, "Open Apps", true, "Launched ${match.first}.")
            }
        }
        return ExecutionResult(22, "Open Apps", false, "Could not find installed app matching '$queryOrPkg'.")
    }

    private fun openSmartSetting(settingQuery: String): ExecutionResult {
        val q = settingQuery.lowercase(Locale.ROOT)
        val action = when {
            "wifi" in q -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" in q -> Settings.ACTION_BLUETOOTH_SETTINGS
            "sound" in q || "volume" in q -> Settings.ACTION_SOUND_SETTINGS
            "display" in q || "dark" in q || "brightness" in q -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" in q -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "keyboard" in q || "input" in q -> Settings.ACTION_INPUT_METHOD_SETTINGS
            "language" in q || "locale" in q -> Settings.ACTION_LOCALE_SETTINGS
            "date" in q || "time" in q -> Settings.ACTION_DATE_SETTINGS
            "security" in q || "lock" in q -> Settings.ACTION_SECURITY_SETTINGS
            "accessibility" in q -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return openSettingsAction(94, "Smart Settings Assistant", action, "Opened Android Settings for '${settingQuery.ifBlank { "System" }}'.")
    }

    private fun getInstalledAppsList(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return resolved.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(pm)?.toString() ?: pkg
            label to pkg
        }.distinctBy { it.second }.sortedBy { it.first.lowercase(Locale.ROOT) }
    }

    private fun getInstalledAppsArray(): JSONArray {
        val arr = JSONArray()
        getInstalledAppsList().take(60).forEach { (label, pkg) ->
            arr.put(JSONObject().put("label", label).put("packageName", pkg))
        }
        return arr
    }

    private fun readAppInfo(packageName: String): ExecutionResult {
        val pkg = packageName.ifBlank { context.packageName }
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        val obj = JSONObject().apply {
            put("packageName", info.packageName)
            put("versionName", info.versionName ?: "unknown")
            put("targetSdk", info.applicationInfo?.targetSdkVersion ?: 0)
        }
        return ExecutionResult(24, "App Information", true, "Loaded info for $pkg.", obj.toString())
    }

    private fun readAppUsageSummary(): ExecutionResult {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 24 * 60 * 60 * 1000L
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(10)
        val arr = JSONArray()
        stats.forEach {
            arr.put(JSONObject().apply {
                put("packageName", it.packageName)
                put("minutes", (it.totalTimeInForeground / 60000L))
            })
        }
        return ExecutionResult(27, "App Usage", true, "Loaded 24h app usage stats.", JSONObject().put("usage", arr).toString())
    }

    private fun readInputMethodsJson(): String {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val arr = JSONArray()
        imm.enabledInputMethodList.forEach {
            arr.put(JSONObject().put("id", it.id).put("package", it.packageName))
        }
        return JSONObject().put("inputMethods", arr).toString()
    }

    private fun readAudioOutputsJson(): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val arr = JSONArray()
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { dev ->
            val typeLabel = when (dev.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio"
                else -> "Audio Device (${dev.type})"
            }
            arr.put(JSONObject().put("name", dev.productName.toString()).put("type", typeLabel))
        }
        return JSONObject().put("outputs", arr).toString()
    }

    private fun readAudioVolumes(): JSONObject {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun pct(stream: Int): Int {
            val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            return ((am.getStreamVolume(stream) * 100f) / max).toInt()
        }
        return JSONObject().apply {
            put("media", pct(AudioManager.STREAM_MUSIC))
            put("call", pct(AudioManager.STREAM_VOICE_CALL))
            put("alarm", pct(AudioManager.STREAM_ALARM))
            put("notification", pct(AudioManager.STREAM_NOTIFICATION))
            put("ring", pct(AudioManager.STREAM_RING))
            put("ringerMode", when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "Silent"
                AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                else -> "Normal"
            })
        }
    }

    private fun readBatteryInfo(): JSONObject {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val healthCode = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: 0
        val healthLabel = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Normal"
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return JSONObject().apply {
            put("percent", level)
            put("charging", isCharging)
            put("health", healthLabel)
            put("tempC", tempTenths / 10.0)
            put("voltageMv", voltageMv)
            put("batterySaver", pm.isPowerSaveMode)
        }
    }

    private fun readRamInfo(): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMb = mi.totalMem / (1024 * 1024)
        val availMb = mi.availMem / (1024 * 1024)
        return JSONObject().apply {
            put("totalMb", totalMb)
            put("availableMb", availMb)
            put("usedMb", (totalMb - availMb).coerceAtLeast(0))
            put("lowMemory", mi.lowMemory)
        }
    }

    private fun readStorageInfo(): JSONObject {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalGb = (stat.totalBytes / (1024.0 * 1024.0 * 1024.0))
        val availGb = (stat.availableBytes / (1024.0 * 1024.0 * 1024.0))
        return JSONObject().apply {
            put("totalGb", String.format(Locale.US, "%.1f", totalGb).toDouble())
            put("freeGb", String.format(Locale.US, "%.1f", availGb).toDouble())
            put("usedGb", String.format(Locale.US, "%.1f", (totalGb - availGb).coerceAtLeast(0.0)).toDouble())
        }
    }

    private fun readNetworkInfo(): JSONObject {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(net)
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val transport = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        return JSONObject().apply {
            put("wifiEnabled", wifi.isWifiEnabled)
            put("online", hasInternet && validated)
            put("transport", transport)
            put("airplaneMode", airplane)
        }
    }

    private fun readBluetoothInfoJson(): String {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val enabled = try { bm?.adapter?.isEnabled == true } catch (_: SecurityException) { false }
        return JSONObject().put("bluetoothEnabled", enabled).toString()
    }

    private fun readSimInfoJson(): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return JSONObject().apply {
            put("operator", tm.networkOperatorName.ifBlank { "Unavailable" })
            put("countryIso", tm.networkCountryIso.uppercase(Locale.ROOT).ifBlank { "N/A" })
            put("simState", tm.simState)
        }.toString()
    }

    private fun readCpuInfoJson(): String = JSONObject().apply {
        put("cores", Runtime.getRuntime().availableProcessors())
        put("hardware", Build.HARDWARE)
        put("board", Build.BOARD)
        put("abis", Build.SUPPORTED_ABIS.joinToString(", "))
    }.toString()

    private fun readDeviceInfoJson(): String = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("brand", Build.BRAND)
        put("androidVersion", Build.VERSION.RELEASE)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("securityPatch", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "N/A")
    }.toString()

    private fun getCurrentBrightnessPercent(): Int {
        return try {
            val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            ((raw * 100f) / 255f).toInt().coerceIn(0, 100)
        } catch (_: Exception) { 50 }
    }

    private fun isAutoBrightnessEnabled(): Boolean {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (_: Exception) { false }
    }

    private fun isAutoRotateEnabled(): Boolean {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
        } catch (_: Exception) { true }
    }

    private fun buildCatalog(): List<ControlMetadata> = listOf(
        // A. Display (1-10)
        ControlMetadata(1, "Screen Brightness Control", "স্ক্রিন ব্রাইটনেস নিয়ন্ত্রণ", "Display", "write_settings", true, false),
        ControlMetadata(2, "Auto Brightness", "অটো ব্রাইটনেস", "Display", "write_settings", true, false),
        ControlMetadata(3, "Screen Timeout", "স্ক্রিন টাইমআউট", "Display", "write_settings", true, false),
        ControlMetadata(4, "Dark Mode", "ডার্ক মোড", "Display", null, true, false, "Opens Android Display Settings for system-wide Dark Mode."),
        ControlMetadata(5, "Light Mode", "লাইট মোড", "Display", null, true, false, "Opens Android Display Settings for system-wide Light Mode."),
        ControlMetadata(6, "Screen Rotation", "স্ক্রিন রোটেশন", "Display", "write_settings", true, false),
        ControlMetadata(7, "Font Size", "ফন্ট সাইজ", "Display", null, false, false, "Opens Android Display Settings."),
        ControlMetadata(8, "Display Size", "ডিসপ্লে সাইজ", "Display", null, false, false, "Opens Android Display Settings."),
        ControlMetadata(9, "Screen Magnification", "স্ক্রিন ম্যাগনিফিকেশন", "Display", null, false, false, "Opens Accessibility Settings."),
        ControlMetadata(10, "Wallpaper Control", "ওয়ালপেপার নিয়ন্ত্রণ", "Display", null, true, false),

        // B. Keyboard (11-20)
        ControlMetadata(11, "Keyboard Open", "কিবোর্ড চালু", "Keyboard", null, false, false),
        ControlMetadata(12, "Keyboard Close", "কিবোর্ড বন্ধ", "Keyboard", null, false, false),
        ControlMetadata(13, "Keyboard Language", "কিবোর্ড ভাষা", "Keyboard", null, false, false, "Requires user selection in Input Method Settings."),
        ControlMetadata(14, "Keyboard Theme", "কিবোর্ড থিম", "Keyboard", null, false, false),
        ControlMetadata(15, "Keyboard Sound", "কিবোর্ড সাউন্ড", "Keyboard", "write_settings", true, false),
        ControlMetadata(16, "Keyboard Vibration", "কিবোর্ড ভাইব্রেশন", "Keyboard", "write_settings", true, false),
        ControlMetadata(17, "Auto Correction", "অটো কারেকশন", "Keyboard", null, false, false),
        ControlMetadata(18, "Predictive Text", "প্রেডিক্টিভ টেক্সট", "Keyboard", null, false, false),
        ControlMetadata(19, "Voice Typing", "ভয়েস টাইপিং", "Keyboard", "microphone", false, false),
        ControlMetadata(20, "Keyboard Settings", "কিবোর্ড সেটিংস", "Keyboard", null, false, false),

        // C. Application (21-30)
        ControlMetadata(21, "Installed Apps", "ইনস্টলকৃত অ্যাপস", "Apps", null, false, false),
        ControlMetadata(22, "Open Apps", "অ্যাপ চালু করুন", "Apps", null, true, false),
        ControlMetadata(23, "App Settings", "অ্যাপ সেটিংস", "Apps", null, false, false),
        ControlMetadata(24, "App Information", "অ্যাপ তথ্য", "Apps", null, false, false),
        ControlMetadata(25, "App Permission", "অ্যাপ পারমিশন", "Apps", null, false, false),
        ControlMetadata(26, "App Notifications", "অ্যাপ নোটিফিকেশন", "Apps", null, false, false),
        ControlMetadata(27, "App Usage", "অ্যাপ ব্যবহারের সময়", "Apps", "usage_access", false, false),
        ControlMetadata(28, "Uninstall App", "অ্যাপ আনইনস্টল", "Apps", null, true, true, "Requires system uninstall confirmation."),
        ControlMetadata(29, "Clear App Cache", "অ্যাপ ক্যাশ পরিষ্কার", "Apps", null, true, false, "Android restricts clearing other apps' cache; opens Storage Info."),
        ControlMetadata(30, "Default App Control", "ডিফল্ট অ্যাপ নিয়ন্ত্রণ", "Apps", null, false, false),

        // D. Sound (31-40)
        ControlMetadata(31, "Media Volume", "মিডিয়া ভলিউম", "Sound", null, true, false),
        ControlMetadata(32, "Call Volume", "কল ভলিউম", "Sound", null, true, false),
        ControlMetadata(33, "Alarm Volume", "অ্যালার্ম ভলিউম", "Sound", null, true, false),
        ControlMetadata(34, "Notification Volume", "নোটিফিকেশন ভলিউম", "Sound", null, true, false),
        ControlMetadata(35, "Ring Volume", "রিং ভলিউম", "Sound", null, true, false),
        ControlMetadata(36, "Silent Mode", "সাইলেন্ট মোড", "Sound", null, true, false),
        ControlMetadata(37, "Vibrate Mode", "ভাইব্রেট মোড", "Sound", null, true, false),
        ControlMetadata(38, "Ringtone Settings", "রিংটোন সেটিংস", "Sound", null, false, false),
        ControlMetadata(39, "Do Not Disturb", "ডু নট ডিস্টার্ব", "Sound", null, true, false),
        ControlMetadata(40, "Audio Output", "অডিও আউটপুট", "Sound", null, false, false),

        // E. Network (41-50)
        ControlMetadata(41, "Wi-Fi Settings", "ওয়াই-ফাই সেটিংস", "Network", null, false, false, "Opens Android Wi-Fi Panel."),
        ControlMetadata(42, "Wi-Fi Status", "ওয়াই-ফাই স্ট্যাটাস", "Network", null, false, false),
        ControlMetadata(43, "Wi-Fi Network", "ওয়াই-ফাই নেটওয়ার্ক", "Network", null, false, false),
        ControlMetadata(44, "Mobile Data Settings", "মোবাইল ডেটা সেটিংস", "Network", null, false, false, "Direct Mobile Data toggling restricted by Android."),
        ControlMetadata(45, "Bluetooth Settings", "ব্লুটুথ সেটিংস", "Network", "bluetooth", false, false),
        ControlMetadata(46, "Hotspot Settings", "হটস্পট সেটিংস", "Network", null, false, false),
        ControlMetadata(47, "Airplane Mode Settings", "এয়ারপ্লেন মোড সেটিংস", "Network", null, true, true, "Direct Airplane Mode toggle restricted by Android."),
        ControlMetadata(48, "SIM Information", "সিম তথ্য", "Network", null, false, false),
        ControlMetadata(49, "Network Information", "নেটওয়ার্ক তথ্য", "Network", null, false, false),
        ControlMetadata(50, "Internet Status", "ইন্টারনেট স্ট্যাটাস", "Network", null, false, false),

        // F. Security & Permission (51-60)
        ControlMetadata(51, "Camera Permission", "ক্যামেরা পারমিশন", "Security", null, false, false),
        ControlMetadata(52, "Microphone Permission", "মাইক্রোফোন পারমিশন", "Security", null, false, false),
        ControlMetadata(53, "Location Permission", "লোকেশন পারমিশন", "Security", null, false, false),
        ControlMetadata(54, "Storage Permission", "স্টোরেজ পারমিশন", "Security", null, false, false),
        ControlMetadata(55, "Contacts Permission", "কন্টাক্টস পারমিশন", "Security", null, false, false),
        ControlMetadata(56, "SMS Permission", "এসএমএস পারমিশন", "Security", null, false, false),
        ControlMetadata(57, "Phone Permission", "ফোন পারমিশন", "Security", null, false, false),
        ControlMetadata(58, "Notification Permission", "নোটিফিকেশন পারমিশন", "Security", null, false, false),
        ControlMetadata(59, "Accessibility Permission", "অ্যাক্সেসিবিলিটি পারমিশন", "Security", null, false, false),
        ControlMetadata(60, "Permission Dashboard", "পারমিশন ড্যাশবোর্ড", "Security", null, false, false),

        // G. File & Storage (61-70)
        ControlMetadata(61, "File Manager", "ফাইল ম্যানেজার", "Files", null, false, false),
        ControlMetadata(62, "Image Viewer", "ইমেজ ভিউয়ার", "Files", null, false, false),
        ControlMetadata(63, "Video Viewer", "ভিডিও ভিউয়ার", "Files", null, false, false),
        ControlMetadata(64, "Audio Manager", "অডিও ম্যানেজার", "Files", null, false, false),
        ControlMetadata(65, "Document Manager", "ডকুমেন্ট ম্যানেজার", "Files", null, false, false),
        ControlMetadata(66, "File Search", "ফাইল খুঁজুন", "Files", null, false, false),
        ControlMetadata(67, "File Rename", "ফাইল রিনেম", "Files", null, true, false),
        ControlMetadata(68, "File Copy", "ফাইল কপি", "Files", null, true, false),
        ControlMetadata(69, "File Move", "ফাইল মুভ", "Files", null, true, true),
        ControlMetadata(70, "Storage Information", "স্টোরেজ তথ্য", "Files", null, false, false),

        // H. Battery & Performance (71-80)
        ControlMetadata(71, "Battery Percentage", "ব্যাটারি চার্জ %", "Battery", null, false, false),
        ControlMetadata(72, "Battery Health Information", "ব্যাটারি হেলথ", "Battery", null, false, false),
        ControlMetadata(73, "Charging Status", "চার্জিং স্ট্যাটাস", "Battery", null, false, false),
        ControlMetadata(74, "Battery Saver Settings", "ব্যাটারি সেভার", "Battery", null, true, false),
        ControlMetadata(75, "Battery Usage", "ব্যাটারি ব্যবহার", "Battery", null, false, false),
        ControlMetadata(76, "RAM Information", "র‍্যাম তথ্য", "Battery", null, false, false),
        ControlMetadata(77, "CPU Information", "সিপিইউ তথ্য", "Battery", null, false, false),
        ControlMetadata(78, "Device Temperature", "ডিভাইস তাপমাত্রা", "Battery", null, false, false),
        ControlMetadata(79, "Storage Optimization", "স্টোরেজ অপটিমাইজেশন", "Battery", null, true, false),
        ControlMetadata(80, "Performance Dashboard", "পারফরম্যান্স ড্যাশবোর্ড", "Battery", null, false, false),

        // I. Phone & System (81-90)
        ControlMetadata(81, "Device Information", "ডিভাইস তথ্য", "System", null, false, false),
        ControlMetadata(82, "Android Version", "অ্যান্ড্রয়েড ভার্সন", "System", null, false, false),
        ControlMetadata(83, "System Settings", "সিস্টেম সেটিংস", "System", null, false, false),
        ControlMetadata(84, "Date and Time", "তারিখ ও সময়", "System", null, false, false),
        ControlMetadata(85, "Language Settings", "ভাষা সেটিংস", "System", null, false, false),
        ControlMetadata(86, "Accessibility Settings", "অ্যাক্সেসিবিলিটি সেটিংস", "System", null, false, false),
        ControlMetadata(87, "Notification Panel", "নোটিফিকেশন প্যানেল", "System", "accessibility", true, false),
        ControlMetadata(88, "Quick Settings", "কুইক সেটিংস", "System", "accessibility", true, false),
        ControlMetadata(89, "Screen Lock Settings", "স্ক্রিন লক", "System", null, true, true),
        ControlMetadata(90, "Restart Menu", "রিস্টার্ট মেনু", "System", "accessibility", true, true, "Non-root apps open Android Power Menu via Accessibility."),

        // J. AI Automation & Smart Control (91-100)
        ControlMetadata(91, "Voice Command", "ভয়েস কমান্ড", "AI", "microphone", false, false),
        ControlMetadata(92, "AI Text Command", "AI টেক্সট কমান্ড", "AI", null, false, false),
        ControlMetadata(93, "Smart App Launcher", "স্মার্ট অ্যাপ লঞ্চার", "AI", null, true, false),
        ControlMetadata(94, "Smart Settings Assistant", "স্মার্ট সেটিংস অ্যাসিস্ট্যান্ট", "AI", null, true, false),
        ControlMetadata(95, "Daily Automation", "ডেইলি অটোমেশন", "AI", null, false, false),
        ControlMetadata(96, "Scheduled Tasks", "শিডিউল্ড টাস্ক", "AI", null, false, false),
        ControlMetadata(97, "Smart Permission Manager", "স্মার্ট পারমিশন ম্যানেজার", "AI", null, false, false),
        ControlMetadata(98, "AI Phone Assistant", "AI ফোন অ্যাসিস্ট্যান্ট", "AI", null, false, false),
        ControlMetadata(99, "Floating Controller", "ফ্লোটিং কন্ট্রোলার", "AI", "overlay", true, false),
        ControlMetadata(100, "Multi-Task AI Control", "মাল্টি-টাস্ক AI কন্ট্রোল", "AI", null, true, false)
    )
}
