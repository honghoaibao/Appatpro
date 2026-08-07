package com.atpro.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import com.atpro.ui.dashboard.DashboardActivity

/**
 * SplashActivity — v1.2.7.
 *
 * Hiển thị logo "AT PRO" với animation khi app khởi động lạnh.
 * Sau animation hoàn tất → chuyển sang DashboardActivity.
 *
 * Dùng Android View thuần (không Compose) để khởi động nhanh nhất.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor     = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.parseColor("#0D0D14")

        val root = buildSplashView()
        setContentView(root)

        // Chạy animation sau 50ms để view kịp layout
        root.postDelayed({ animateIn(root) }, 50)
    }

    // ── View builder ─────────────────────────────────────────

    private fun buildSplashView(): FrameLayout {
        val ctx = this
        val bg  = FrameLayout(ctx).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0D0D14"))
        }

        val center = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = android.view.Gravity.CENTER
        }

        // Logo text "AT PRO"
        val tvLogo = TextView(ctx).apply {
            text      = "AT PRO"
            textSize  = 38f
            typeface  = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#6C63FF"))
            letterSpacing = 0.12f
            gravity   = android.view.Gravity.CENTER
            alpha     = 0f
            scaleX    = 0.6f
            scaleY    = 0.6f
        }

        // Tagline
        val tvTag = TextView(ctx).apply {
            text     = "Nền tảng nuôi tài khoản tự động"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#6B7280"))
            gravity  = android.view.Gravity.CENTER
            alpha    = 0f
            val lp  = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dpToPx(8) }
            layoutParams = lp
        }

        // Version badge
        val tvVer = TextView(ctx).apply {
            text     = "v1.2.7"
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor("#374151"))
            gravity  = android.view.Gravity.CENTER
            alpha    = 0f
            val lp   = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dpToPx(6) }
            layoutParams = lp
        }

        // Pulse indicator dot
        val dot = View(ctx).apply {
            val dotBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#6C63FF"))
            }
            background = dotBg
            alpha      = 0f
            val lp     = LinearLayout.LayoutParams(dpToPx(8), dpToPx(8)).apply {
                topMargin   = dpToPx(24)
                gravity     = android.view.Gravity.CENTER_HORIZONTAL
            }
            layoutParams = lp
        }

        center.addView(tvLogo)
        center.addView(tvTag)
        center.addView(tvVer)
        center.addView(dot)

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER,
        )
        bg.addView(center, lp)

        // Store refs for animation
        bg.tag = arrayOf(tvLogo, tvTag, tvVer, dot)
        return bg
    }

    // ── Animation ─────────────────────────────────────────────

    private fun animateIn(root: FrameLayout) {
        @Suppress("UNCHECKED_CAST")
        val views = root.tag as Array<View>
        val (tvLogo, tvTag, tvVer, dot) = views

        // Logo: scale up + fade in
        val logoAlpha = ObjectAnimator.ofFloat(tvLogo, "alpha", 0f, 1f).apply {
            duration = 600
            interpolator = AccelerateDecelerateInterpolator()
        }
        val logoScaleX = ObjectAnimator.ofFloat(tvLogo, "scaleX", 0.6f, 1f).apply {
            duration = 700
            interpolator = OvershootInterpolator(1.5f)
        }
        val logoScaleY = ObjectAnimator.ofFloat(tvLogo, "scaleY", 0.6f, 1f).apply {
            duration = 700
            interpolator = OvershootInterpolator(1.5f)
        }

        // Tagline: fade in với delay
        val tagAlpha = ObjectAnimator.ofFloat(tvTag, "alpha", 0f, 1f).apply {
            duration  = 400
            startDelay = 400
        }
        // Version: fade in
        val verAlpha = ObjectAnimator.ofFloat(tvVer, "alpha", 0f, 0.6f).apply {
            duration   = 300
            startDelay = 550
        }
        // Dot: pulse
        val dotAlpha = ObjectAnimator.ofFloat(dot, "alpha", 0f, 1f).apply {
            duration   = 400
            startDelay = 700
        }
        val dotPulse1 = ObjectAnimator.ofFloat(dot, "scaleX", 1f, 1.8f, 1f).apply {
            duration   = 600
            startDelay = 900
            repeatCount = ObjectAnimator.INFINITE
            repeatMode  = ObjectAnimator.RESTART
        }
        val dotPulse2 = ObjectAnimator.ofFloat(dot, "scaleY", 1f, 1.8f, 1f).apply {
            duration   = 600
            startDelay = 900
            repeatCount = ObjectAnimator.INFINITE
            repeatMode  = ObjectAnimator.RESTART
        }

        val set = AnimatorSet()
        set.playTogether(logoAlpha, logoScaleX, logoScaleY, tagAlpha, verAlpha, dotAlpha, dotPulse1, dotPulse2)
        set.doOnEnd {
            // Giữ splash thêm 400ms sau animation hoàn tất rồi chuyển màn hình
        }
        set.start()

        // Chuyển sang Dashboard sau 1300ms tổng
        root.postDelayed({
            startActivity(Intent(this, DashboardActivity::class.java))
            // Slide-up transition khi chuyển sang Dashboard
            overridePendingTransition(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            )
            finish()
        }, 1_300)
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
