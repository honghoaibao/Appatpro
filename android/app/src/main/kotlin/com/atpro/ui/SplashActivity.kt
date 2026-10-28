package com.atpro.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
        window.navigationBarColor = android.graphics.Color.parseColor("#09090B")

        val root = buildSplashView()
        setContentView(root)

        // Chạy animation sau 50ms để view kịp layout
        root.postDelayed({ animateIn(root) }, 50)
    }

    // ── View builder ─────────────────────────────────────────

    private fun buildSplashView(): FrameLayout {
        val ctx = this
        val bg  = FrameLayout(ctx).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#09090B"))
        }

        val center = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = android.view.Gravity.CENTER
        }

        // Logo text "AT PRO"
        val tvLogo = TextView(ctx).apply {
            text      = "AT PRO"
            textSize  = 30f
            typeface  = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#F4F4F5"))
            letterSpacing = 0.28f
            gravity   = android.view.Gravity.CENTER
            alpha     = 0f
            scaleX    = 0.92f
            scaleY    = 0.92f
        }

        // Tagline
        val tvTag = TextView(ctx).apply {
            text     = "Tự động hoá tài khoản"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#71717A"))
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
            // v1.4.0: lấy phiên bản thật thay vì chuỗi cố định (trước đây luôn hiện "v1.2.7")
            text     = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor("#52525B"))
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
                setColor(android.graphics.Color.parseColor("#34D399"))
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
        val logoScaleX = ObjectAnimator.ofFloat(tvLogo, "scaleX", 0.92f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator(1.6f)
        }
        val logoScaleY = ObjectAnimator.ofFloat(tvLogo, "scaleY", 0.92f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator(1.6f)
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
        val dotPulse1 = ObjectAnimator.ofFloat(dot, "scaleX", 1f, 1.5f, 1f).apply {
            duration   = 600
            startDelay = 900
            repeatCount = ObjectAnimator.INFINITE
            repeatMode  = ObjectAnimator.RESTART
        }
        val dotPulse2 = ObjectAnimator.ofFloat(dot, "scaleY", 1f, 1.5f, 1f).apply {
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
