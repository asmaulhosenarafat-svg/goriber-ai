package com.example

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class PermissionManager(private val context: Context) {

    data class PermissionItem(
        val id: String,
        val title: String,
        val titleBn: String,
        val description: String,
        val rationale: String,
        val kind: Kind,
        val runtimePermission: String? = null,
        val granted: Boolean
    ) {
        enum class Kind { RUNTIME, OVERLAY, WRITE_SETTINGS, USAGE_ACCESS, ACCESSIBILITY, NOTIFICATION_LISTENER, BATTERY_OPTIMIZATION, SAF_STORAGE, FOREGROUND_SERVICE }
    }

    fun getAllPermissions(): List<PermissionItem> {
        return listOf(
            PermissionItem(
                id = "camera",
                title = "Camera Permission",
                titleBn = "ক্যামেরা পারমিশন",
                description = "Required only for camera launch checks or visual diagnostics.",
                rationale = "Allow Camera access to verify camera readiness and launch camera controls.",
                kind = PermissionItem.Kind.RUNTIME,
                runtimePermission = Manifest.permission.CAMERA,
                granted = hasRuntimePermission(Manifest.permission.CAMERA)
            ),
            PermissionItem(
                id = "microphone",
                title = "Microphone Permission",
                titleBn = "মাইক্রোফোন পারমিশন",
                description = "Needed for Bengali & English AI Voice Commands.",
                rationale = "Microphone access lets you speak commands to the AI Voice Assistant.",
                kind = PermissionItem.Kind.RUNTIME,
                runtimePermission = Manifest.permission.RECORD_AUDIO,
                granted = hasRuntimePermission(Manifest.permission.RECORD_AUDIO)
            ),
            PermissionItem(
                id = "location",
                title = "Location Permission",
                titleBn = "লোকেশন পারমিশন",
                description = "Foreground location for location-aware network & weather context.",
                rationale = "Foreground Location access enables location-based status checks.",
                kind = PermissionItem.Kind.RUNTIME,
                runtimePermission = Manifest.permission.ACCESS_FINE_LOCATION,
                granted = hasRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                    hasRuntimePermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            ),
            PermissionItem(
                id = "contacts",
                title = "Contacts Permission",
                titleBn = "কন্টাক্টস পারমিশন",
                description = "Used only when you ask AI to look up a contact for calling.",
                rationale = "Contacts permission is only used when you confirm a contact lookup.",
                kind = PermissionItem.Kind.RUNTIME,
                runtimePermission = Manifest.permission.READ_CONTACTS,
                granted = hasRuntimePermission(Manifest.permission.READ_CONTACTS)
            ),
            PermissionItem(
                id = "sms",
                title = "SMS Permission",
                titleBn = "এসএমএস পারমিশন",
                description = "Used only for user-confirmed SMS actions.",
                rationale = "SMS permission lets you compose or send an SMS after explicit confirmation.",
                kind = PermissionItem.Kind.RUNTIME,
                runtimePermission = Manifest.permission.SEND_SMS,
                granted = hasRuntimePermission(Manifest.permission.SEND_SMS)
            ),
            PermissionItem(
                id = "phone",
                title = "Phone Permission",
                titleBn = "ফোন কল পারমিশন",
                description = "Used for SIM/network telemetry and user-confirmed calls.",
                rationale = "Phone permission allows reading SIM state and initiating confirmed calls.",
                kind = PermissionItem.Kind.RUNTIME,
                runtimePermission = Manifest.permission.CALL_PHONE,
                granted = hasRuntimePermission(Manifest.permission.CALL_PHONE)
            ),
            PermissionItem(
                id = "storage",
                title = "Storage Access (SAF)",
                titleBn = "স্টোরেজ পারমিশন (SAF)",
                description = "Zero-permission Storage Access Framework folder/file selection.",
                rationale = "Grant folder access via Android's Storage Access Framework without broad storage permissions.",
                kind = PermissionItem.Kind.SAF_STORAGE,
                granted = context.contentResolver.persistedUriPermissions.isNotEmpty()
            ),
            PermissionItem(
                id = "notification",
                title = "Notification Permission",
                titleBn = "নোটিফিকেশন পারমিশন",
                description = "Required for Floating Controller status & Battery/Storage alerts.",
                rationale = "Allow Notifications so the Floating Controller and Automation alerts stay visible.",
                kind = PermissionItem.Kind.RUNTIME,
                runtimePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.POST_NOTIFICATIONS
                } else null,
                granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                }
            ),
            PermissionItem(
                id = "accessibility",
                title = "Accessibility Permission",
                titleBn = "অ্যাক্সেসিবিলিটি পারমিশন",
                description = "Executes user-confirmed Back, Home, Notifications & Power Menu.",
                rationale = "Enable AI Controller in Accessibility Settings to trigger global navigation actions upon your confirmation.",
                kind = PermissionItem.Kind.ACCESSIBILITY,
                granted = isAccessibilityEnabled()
            ),
            PermissionItem(
                id = "overlay",
                title = "Overlay Permission",
                titleBn = "ফ্লোটিং ওভারলে পারমিশন",
                description = "Mandatory for keeping the Floating Controller over other apps.",
                rationale = "Allow 'Display over other apps' so the Floating Controller can float above your screen.",
                kind = PermissionItem.Kind.OVERLAY,
                granted = Settings.canDrawOverlays(context)
            ),
            PermissionItem(
                id = "usage_access",
                title = "Usage Access",
                titleBn = "অ্যাপ ইউসেজ অ্যাক্সেস",
                description = "Provides daily app usage & screen-time analytics.",
                rationale = "Enable Usage Access in Settings to view real app usage statistics.",
                kind = PermissionItem.Kind.USAGE_ACCESS,
                granted = hasUsageStatsPermission()
            ),
            PermissionItem(
                id = "bluetooth",
                title = "Bluetooth Permission",
                titleBn = "ব্লুটুথ পারমিশন",
                description = "Reads Bluetooth state and opens Bluetooth device controls.",
                rationale = "Allow Bluetooth Connect permission to inspect paired audio devices.",
                kind = PermissionItem.Kind.RUNTIME,
                runtimePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Manifest.permission.BLUETOOTH_CONNECT
                } else null,
                granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    hasRuntimePermission(Manifest.permission.BLUETOOTH_CONNECT)
                } else true
            ),
            PermissionItem(
                id = "foreground_service",
                title = "Foreground Service",
                titleBn = "ফোরগ্রাউন্ড সার্ভিস",
                description = "Authorized persistent execution for Floating Controller.",
                rationale = "Foreground Service permission is declared for user-started Floating Controller sessions.",
                kind = PermissionItem.Kind.FOREGROUND_SERVICE,
                granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
                } else true
            ),
            PermissionItem(
                id = "write_settings",
                title = "Modify System Settings",
                titleBn = "সিস্টেম সেটিংস পরিবর্তন",
                description = "Controls Screen Brightness, Auto Brightness, Timeout & Rotation.",
                rationale = "Allow 'Modify system settings' so confirmed display controls can adjust brightness and timeout directly.",
                kind = PermissionItem.Kind.WRITE_SETTINGS,
                granted = Settings.System.canWrite(context)
            ),
            PermissionItem(
                id = "battery_optimization",
                title = "Battery Optimization Settings",
                titleBn = "ব্যাটারি অপটিমাইজেশন",
                description = "Prevents Android from killing scheduled automations or Floating UI.",
                rationale = "Review Battery Optimization settings if you want continuous Floating Controller reliability.",
                kind = PermissionItem.Kind.BATTERY_OPTIMIZATION,
                granted = isIgnoringBatteryOptimizations()
            )
        )
    }

    fun getPermissionsJson(): String {
        val arr = JSONArray()
        getAllPermissions().forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("titleBn", item.titleBn)
                put("description", item.description)
                put("rationale", item.rationale)
                put("kind", item.kind.name)
                put("runtimePermission", item.runtimePermission ?: "")
                put("granted", item.granted)
            })
        }
        return arr.toString()
    }

    fun isPermissionGranted(id: String): Boolean {
        return getAllPermissions().firstOrNull { it.id == id }?.granted ?: false
    }

    fun hasRuntimePermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun isAccessibilityEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == AccessibilityController::class.java.name
        }
    }

    fun isNotificationListenerEnabled(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun buildSettingsIntent(id: String): Intent {
        val pkgUri = Uri.parse("package:${context.packageName}")
        return when (id) {
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
            "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)
            "usage_access" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "battery_optimization" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
