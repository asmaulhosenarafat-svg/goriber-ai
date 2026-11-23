package com.example

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class FloatingService : Service() {

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var compactBubble: TextView? = null
    private var expandedPanel: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isExpanded = false
    private var bubbleColorHex = "#06B6D4"
    private var bubbleSizeDp = 56

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STYLE -> {
                bubbleColorHex = intent.getStringExtra(EXTRA_COLOR) ?: bubbleColorHex
                bubbleSizeDp = intent.getIntExtra(EXTRA_SIZE_DP, bubbleSizeDp).coerceIn(44, 80)
                applyStyle()
                return START_STICKY
            }
            ACTION_HIDE -> {
                rootView?.visibility = View.GONE
                return START_STICKY
            }
            ACTION_SHOW -> {
                rootView?.visibility = View.VISIBLE
                return START_STICKY
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        bubbleColorHex = intent?.getStringExtra(EXTRA_COLOR) ?: bubbleColorHex
        bubbleSizeDp = (intent?.getIntExtra(EXTRA_SIZE_DP, bubbleSizeDp) ?: bubbleSizeDp).coerceIn(44, 80)

        startAsForeground()
        if (rootView == null) {
            createOverlay()
        } else {
            rootView?.visibility = View.VISIBLE
            applyStyle()
        }
        isRunning = true
        return START_STICKY
    }

    private fun startAsForeground() {
        NotificationService.ensureChannels(this)
        val openIntent = PendingIntent.getActivity(
            this, 100,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 101,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, NotificationService.CHANNEL_FLOATING)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("AI Floating Controller Active")
            .setContentText("Tap to open controller or Stop to close overlay.")
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Emergency Stop", stopIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (160 * density).toInt()
        }

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        compactBubble = TextView(this).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            elevation = 12f
        }

        expandedPanel = buildExpandedPanel()
        expandedPanel?.visibility = View.GONE

        rootView?.addView(compactBubble)
        rootView?.addView(expandedPanel)
        applyStyle()
        setupDragAndClick()
        windowManager?.addView(rootView, layoutParams)
    }

    private fun buildExpandedPanel(): LinearLayout {
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE0E1320"))
                cornerRadius = 20 * density
                setStroke((1.5f * density).toInt(), Color.parseColor("#334155"))
            }
        }

        val header = TextView(this).apply {
            text = "AI Floating Controller"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        panel.addView(header)

        val quickCmdInput = EditText(this).apply {
            hint = "Command (e.g. Wi-Fi, 50%)"
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
        }
        panel.addView(quickCmdInput)

        fun addActionBtn(label: String, pageRoute: String? = null, cmdAction: (() -> Unit)? = null) {
            val btn = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#192132"))
                setOnClickListener {
                    cmdAction?.invoke()
                    pageRoute?.let { openAppRoute(it, quickCmdInput.text?.toString().orEmpty()) }
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
            panel.addView(btn, lp)
        }

        addActionBtn("Open AI Assistant", "ai_assistant")
        addActionBtn("Send Text Command", "ai_assistant")
        addActionBtn("Voice Command", "voice_assistant")
        addActionBtn("Display Control", "display_control")
        addActionBtn("Keyboard Settings", "keyboard_control") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        addActionBtn("App Control", "app_control")
        addActionBtn("Sound Control", "sound_control")
        addActionBtn("Network Control", "network_control")
        addActionBtn("Permission Center", "permission_center")
        addActionBtn("Minimize Bubble") { toggleExpanded(false) }
        addActionBtn("Hide Floating Icon") { rootView?.visibility = View.GONE }
        addActionBtn("Emergency Stop Service") { stopSelf() }

        return panel
    }

    private fun openAppRoute(route: String, commandText: String = "") {
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TARGET_PAGE, route)
            if (commandText.isNotBlank()) putExtra(EXTRA_COMMAND_TEXT, commandText)
        }
        startActivity(launch)
        toggleExpanded(false)
    }

    private fun toggleExpanded(expand: Boolean) {
        isExpanded = expand
        expandedPanel?.visibility = if (expand) View.VISIBLE else View.GONE
        layoutParams?.flags = if (expand) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        rootView?.let { windowManager?.updateViewLayout(it, layoutParams) }
    }

    private fun applyStyle() {
        val density = resources.displayMetrics.density
        val px = (bubbleSizeDp * density).toInt()
        val parsedColor = try { Color.parseColor(bubbleColorHex) } catch (_: Exception) { Color.parseColor("#06B6D4") }
        compactBubble?.apply {
            layoutParams = LinearLayout.LayoutParams(px, px)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(parsedColor)
                setStroke((2 * density).toInt(), Color.WHITE)
            }
        }
    }

    private fun setupDragAndClick() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        compactBubble?.setOnTouchListener { _, event ->
            val lp = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    lp.x = initialX + dx
                    lp.y = initialY + dy
                    rootView?.let { windowManager?.updateViewLayout(it, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleExpanded(!isExpanded)
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        rootView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        rootView = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        const val NOTIF_ID = 4041
        const val ACTION_START = "com.example.FLOATING_START"
        const val ACTION_STOP = "com.example.FLOATING_STOP"
        const val ACTION_HIDE = "com.example.FLOATING_HIDE"
        const val ACTION_SHOW = "com.example.FLOATING_SHOW"
        const val ACTION_UPDATE_STYLE = "com.example.FLOATING_STYLE"
        const val EXTRA_COLOR = "extra_color"
        const val EXTRA_SIZE_DP = "extra_size_dp"
        const val EXTRA_TARGET_PAGE = "extra_target_page"
        const val EXTRA_COMMAND_TEXT = "extra_command_text"
    }
}
