package com.khedr.smartassistant.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.khedr.smartassistant.data.AssistantStore
import com.khedr.smartassistant.ui.AppTheme

class FloatingBubbleService : Service() {
    private var wm: WindowManager? = null
    private var bubble: TextView? = null

    override fun onCreate() {
        super.onCreate()
        // Without the overlay permission addView() would throw and crash the app.
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val store = AssistantStore(this)
        val theme = AppTheme.fromId(store.themeId())
        val density = resources.displayMetrics.density
        val size = (54 * density).toInt()

        val view = TextView(this).apply {
            text = if (store.enabled()) "ON" else "OFF"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(theme.onAccent.toArgb())
            gravity = Gravity.CENTER
            elevation = 8 * density
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(theme.accent.toArgb(), theme.accent2.toArgb())
            ).apply { shape = GradientDrawable.OVAL; setStroke((2 * density).toInt(), 0x88FFFFFF.toInt()) }
            setOnClickListener {
                val s = AssistantStore(this@FloatingBubbleService)
                s.setEnabled(!s.enabled())
                text = if (s.enabled()) "ON" else "OFF"
            }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        params.x = (12 * density).toInt()
        view.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = params.x; oy = params.y; return true }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (ox + (sx - e.rawX)).toInt(); params.y = (oy + (e.rawY - sy)).toInt()
                        manager.updateViewLayout(view, params); return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (kotlin.math.abs(e.rawX - sx) < 12 && kotlin.math.abs(e.rawY - sy) < 12) v.performClick()
                        return true
                    }
                }
                return false
            }
        })
        try { manager.addView(view, params); bubble = view } catch (_: Exception) { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        bubble?.let { try { wm?.removeView(it) } catch (_: Exception) { } }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
