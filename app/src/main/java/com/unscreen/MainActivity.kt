package com.unscreen

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val gray = Color.parseColor("#9E9E9E")
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var rootInfo: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, top: Int) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun label(res: Int, sp: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            setText(res)
            textSize = sp
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("unscreen", MODE_PRIVATE)
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }

        col.addView(
            ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) },
            lp(dp(96), dp(96), 0)
        )
        col.addView(label(R.string.app_name, 30f, Color.WHITE, true), lp(match, wrap, 16))

        status = label(R.string.status_off, 18f, gray)
        col.addView(status, lp(match, wrap, 24))

        rootInfo = label(R.string.root_checking, 14f, gray)
        col.addView(rootInfo, lp(match, wrap, 8))

        toggle = Button(this).apply {
            isAllCaps = false
            textSize = 18f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#2A2A2A"))
            }
            setOnClickListener {
                if (FlipService.running) FlipService.stop(this@MainActivity)
                else FlipService.start(this@MainActivity)
                ui.postDelayed({ refresh() }, 400)
            }
        }
        col.addView(toggle, lp(match, dp(56), 32))

        val bootSwitch = Switch(this).apply {
            setText(R.string.boot)
            setTextColor(gray)
            isChecked = prefs.getBoolean("boot", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("boot", checked).apply()
            }
        }
        col.addView(bootSwitch, lp(match, wrap, 24))

        col.addView(label(R.string.hint, 14f, gray), lp(match, wrap, 32))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(col)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        Thread {
            val ok = RootShell.checkRoot()
            runOnUiThread {
                rootInfo.setText(if (ok) R.string.root_ok else R.string.root_no)
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = FlipService.running
        status.setText(if (on) R.string.status_on else R.string.status_off)
        status.setTextColor(if (on) Color.parseColor("#A5D6A7") else gray)
        toggle.setText(if (on) R.string.btn_stop else R.string.btn_start)
    }
}
