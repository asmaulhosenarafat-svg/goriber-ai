package com.example

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

class AccessibilityController : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive listener: actions are only executed on explicit user-confirmed commands.
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        instanceRef = null
        super.onDestroy()
    }

    fun performUserConfirmedGlobalAction(action: Int): Boolean {
        return performGlobalAction(action)
    }

    companion object {
        @Volatile
        private var instanceRef: WeakReference<AccessibilityController>? = null

        fun getInstance(): AccessibilityController? = instanceRef?.get()

        fun openNotificationShade(): Boolean =
            getInstance()?.performUserConfirmedGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) == true

        fun openQuickSettings(): Boolean =
            getInstance()?.performUserConfirmedGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) == true

        fun openPowerDialog(): Boolean =
            getInstance()?.performUserConfirmedGlobalAction(GLOBAL_ACTION_POWER_DIALOG) == true

        fun lockScreen(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getInstance()?.performUserConfirmedGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
            } else false
    }
}
