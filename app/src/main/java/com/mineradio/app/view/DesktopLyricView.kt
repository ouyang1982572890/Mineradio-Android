package com.mineradio.app.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/**
 * 桌面歌词悬浮窗 View — 完整动画版
 *
 * 样式与 mineradio .stage-lyric-line 保持一致：
 *   白→青→蓝 渐变文字 + 多层青色发光阴影
 *
 * 动画匹配 CSS @keyframes：
 *   lyr-in   (900ms) → animateEnter()
 *   lyr-out  (700ms) → animateExit()
 *   lyr-bob  (5.6s)  → startBobAnimation()
 */
class DesktopLyricView(context: Context) : TextView(context) {

    // ===== 状态 =====
    private var isShowing = false
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var bobAnimator: AnimatorSet? = null
    private var transitionAnim: AnimatorSet? = null
    private var pendingText: String = ""
    private var gradientApplied = false

    // 普通歌词数据（整行）— 保留兼容旧版
    private var lyricLines: List<Pair<Long, String>> = emptyList()
    private var currentIndex = -1

    // 逐字歌词数据
    data class WordLyric(val startTime: Long, val duration: Long, val word: String)
    private var wordLyrics: List<WordLyric> = emptyList()
    private var currentLineText = ""
    private var currentProgress = 0L

    // 渐变颜色 — stage-lyric-line: #F6FDFF(顶) → #A8F6FF(中) → #7ECDFF(底)
    private val gradientTop = Color.parseColor("#F6FDFF")
    private val gradientMid = Color.parseColor("#A8F6FF")
    private val gradientBottom = Color.parseColor("#7ECDFF")

    // 逐字高亮色
    private val highlightColor = Color.parseColor("#00FFE5")
    private val sungColor = Color.parseColor("#5ED0C8")
    private val normalColor = Color.parseColor("#3A9E98")

    init {
        textSize = 26f
        gravity = Gravity.CENTER
        setPadding(48, 32, 48, 32)
        setBackgroundColor(Color.TRANSPARENT)
        // glow 近似 CSS: 0 0 14px/36px/80px rgba(cyan)
        setShadowLayer(32f, 0f, 0f, Color.parseColor("#AA00F5D4"))
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = 0.04f  // ~2px at 26sp

        // 3D 透视距离（用于 rotationX / rotationY）
        setCameraDistance(resources.displayMetrics.density * 8000f)

        // 初始隐藏，等 enter 动画
        alpha = 0f
        scaleX = 0.7f
        scaleY = 0.7f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && !gradientApplied) {
            applyGradientShader()
            gradientApplied = true
        }
    }

    private fun applyGradientShader() {
        val shader = LinearGradient(
            0f, 0f, 0f, paint.textSize * 1.2f,
            intArrayOf(gradientTop, gradientMid, gradientBottom),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
    }

    // ========================================================
    //  显示 / 隐藏
    // ========================================================

    fun show() {
        if (isShowing) return
        // ★ 如果有正在播放的退出动画，先取消
        stopAllAnimations()
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        layoutParams?.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        layoutParams?.y = 80

        try {
            // ★ 防御：如果 View 已经有关联的 WindowManager，先移除
            try { windowManager?.removeView(this) } catch (_: Exception) {}
            windowManager?.addView(this, layoutParams)
            isShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hide() {
        if (!isShowing) return
        // ★ 先标记为不显示，防止重复 hide / 快速 show-hide-show 竞态
        isShowing = false
        stopAllAnimations()
        animateExitAndRemove()
    }

    private fun animateExitAndRemove() {
        // 退出 → 从 WindowManager 移除
        val exit = createExitAnimSet()
        exit.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                try { windowManager?.removeView(this@DesktopLyricView) } catch (_: Exception) {}
            }
        })
        transitionAnim = exit
        exit.start()
    }

    private fun stopAllAnimations() {
        bobAnimator?.cancel(); bobAnimator = null
        transitionAnim?.cancel(); transitionAnim = null
    }

    // ========================================================
    //  文本拦截 — 行切换时触发过渡动画
    // ========================================================

    override fun setText(text: CharSequence?, type: BufferType?) {
        val newText = text?.toString() ?: ""
        val oldText = super.getText()?.toString() ?: ""

        // 同文本只更新 Spannable（逐字高亮不触发过渡）
        if (newText == oldText) {
            if (text is Spannable) super.setText(text, type)
            return
        }
        if (newText.isEmpty()) return

        if (!isShowing || oldText.isEmpty()) {
            // 首次 — 直接设文本 + 入场
            super.setText(newText, BufferType.NORMAL)
            gradientApplied = false; applyGradientShader(); gradientApplied = true
            animateEnter()
            return
        }

        // 行切换 — 退出旧行 → 设置新行 → 入场新行
        pendingText = newText
        transitionAnim?.cancel()
        stopBob()

        val exit = createExitAnimSet()
        exit.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                val next = pendingText
                if (next.isNotEmpty()) {
                    super@DesktopLyricView.setText(next, BufferType.NORMAL)
                    gradientApplied = false; applyGradientShader(); gradientApplied = true
                    pendingText = ""
                    animateEnter()
                }
            }
        })
        transitionAnim = exit
        exit.start()
    }

    // ========================================================
    //  入场动画 — 匹配 lyr-in (900ms ease-out)
    // ========================================================

    private fun animateEnter() {
        // 重置到入场起始状态
        translationX = -40f * resources.displayMetrics.density
        translationY = 60f * resources.displayMetrics.density
        rotationX = 28f
        rotationY = -22f
        scaleX = 0.7f
        scaleY = 0.7f
        alpha = 0f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(RenderEffect.createBlurEffect(14f, 14f, Shader.TileMode.CLAMP))
        }

        val enter = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(this@DesktopLyricView, "translationX", translationX, 0f),
                ObjectAnimator.ofFloat(this@DesktopLyricView, "translationY", translationY, 0f),
                ObjectAnimator.ofFloat(this@DesktopLyricView, "rotationX", 28f, 0f),
                ObjectAnimator.ofFloat(this@DesktopLyricView, "rotationY", -22f, 0f),
                ObjectAnimator.ofFloat(this@DesktopLyricView, "scaleX", 0.7f, 1f),
                ObjectAnimator.ofFloat(this@DesktopLyricView, "scaleY", 0.7f, 1f),
                ObjectAnimator.ofFloat(this@DesktopLyricView, "alpha", 0f, 1f)
            )
            duration = 900
            // cubic-bezier(.16,.84,.32,1.02) → DecelerateInterpolator 近似
            interpolator = DecelerateInterpolator(1.6f)

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRenderEffect(null)
                    startBobAnimation()
                }
            })
        }
        transitionAnim = enter
        enter.start()
    }

    // ========================================================
    //  离场动画 — 匹配 lyr-out (700ms ease-in)
    // ========================================================

    private fun createExitAnimSet(): AnimatorSet {
        val density = resources.displayMetrics.density
        return AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(this@DesktopLyricView, "translationX", translationX, 60f * density),
                ObjectAnimator.ofFloat(this@DesktopLyricView, "translationY", translationY, -40f * density),
                ObjectAnimator.ofFloat(this@DesktopLyricView, "rotationX", rotationX, -22f),
                ObjectAnimator.ofFloat(this@DesktopLyricView, "rotationY", rotationY, 18f),
                ObjectAnimator.ofFloat(this@DesktopLyricView, "scaleX", scaleX, 0.78f),
                ObjectAnimator.ofFloat(this@DesktopLyricView, "scaleY", scaleY, 0.78f),
                ObjectAnimator.ofFloat(this@DesktopLyricView, "alpha", alpha, 0f)
            )
            duration = 700
            // cubic-bezier(.55,0,.85,.45) → AccelerateDecelerate 偏向加速
            interpolator = AccelerateDecelerateInterpolator()
        }
    }

    // ========================================================
    //  浮动/抖动动画 — 匹配 lyr-bob (5.6s infinite ease-in-out)
    // ========================================================

    private fun startBobAnimation() {
        stopBob()

        val density = resources.displayMetrics.density

        // CSS @keyframes lyr-bob:
        //   0%   (0,0,0) scale(1)
        //  25%   (6,-7,12) rotateX(2) rotateY(-1.5) scale(1.01)
        //  50%   (0,3,0) rotateX(-1.5) rotateY(0) scale(.99)
        //  75%   (-6,-4,8) rotateX(1.2) rotateY(1.5) scale(1.005)
        // 100%   (0,0,0) scale(1)

        val bob = AnimatorSet().apply {
            playTogether(
                // translateX: 0 → 6px → 0 → -6px → 0
                ObjectAnimator.ofFloat(this@DesktopLyricView, "translationX",
                    0f, 6f * density, 0f, -6f * density, 0f
                ).apply { duration = 5600; repeatCount = ValueAnimator.INFINITE },

                // translateY: 0 → -7px → 3px → -4px → 0
                ObjectAnimator.ofFloat(this@DesktopLyricView, "translationY",
                    0f, -7f * density, 3f * density, -4f * density, 0f
                ).apply { duration = 5600; repeatCount = ValueAnimator.INFINITE },

                // rotationX: 0° → 2° → -1.5° → 1.2° → 0°
                ObjectAnimator.ofFloat(this@DesktopLyricView, "rotationX",
                    0f, 2f, -1.5f, 1.2f, 0f
                ).apply { duration = 5600; repeatCount = ValueAnimator.INFINITE },

                // rotationY: 0° → -1.5° → 0° → 1.5° → 0°
                ObjectAnimator.ofFloat(this@DesktopLyricView, "rotationY",
                    0f, -1.5f, 0f, 1.5f, 0f
                ).apply { duration = 5600; repeatCount = ValueAnimator.INFINITE },

                // scaleX/Y: 1 → 1.01 → 0.99 → 1.005 → 1
                ObjectAnimator.ofFloat(this@DesktopLyricView, "scaleX",
                    1f, 1.01f, 0.99f, 1.005f, 1f
                ).apply { duration = 5600; repeatCount = ValueAnimator.INFINITE },
                ObjectAnimator.ofFloat(this@DesktopLyricView, "scaleY",
                    1f, 1.01f, 0.99f, 1.005f, 1f
                ).apply { duration = 5600; repeatCount = ValueAnimator.INFINITE }
            )
        }
        bobAnimator = bob
        bob.start()
    }

    private fun stopBob() {
        bobAnimator?.cancel()
        bobAnimator = null
    }

    // ========================================================
    //  保留兼容 — updateLyric / updateWordLyric / updateProgress
    // ========================================================

    fun updateLyric(lines: List<Pair<Long, String>>) {
        lyricLines = lines
        currentIndex = -1
    }

    fun updateWordLyric(words: List<Triple<Long, Long, String>>) {
        wordLyrics = words.map { WordLyric(it.first, it.second, it.third) }
        currentLineText = wordLyrics.joinToString("") { it.word }
        text = currentLineText
    }

    fun updateProgress(timeMs: Long) {
        currentProgress = timeMs
        if (wordLyrics.isNotEmpty()) { updateWordHighlight(timeMs); return }
        if (lyricLines.isEmpty()) return
        var newIndex = -1
        for (i in lyricLines.indices) {
            if (timeMs >= lyricLines[i].first) newIndex = i else break
        }
        if (newIndex != currentIndex && newIndex >= 0 && newIndex < lyricLines.size) {
            currentIndex = newIndex
            text = lyricLines[newIndex].second
        }
    }

    private fun updateWordHighlight(timeMs: Long) {
        if (wordLyrics.isEmpty() || currentLineText.isEmpty()) return
        val spannable = SpannableString(currentLineText)
        var position = 0
        var currentWordIndex = -1
        for (i in wordLyrics.indices) {
            val word = wordLyrics[i]
            val wordEnd = word.startTime + word.duration
            if (timeMs >= word.startTime && timeMs < wordEnd) { currentWordIndex = i; break }
            if (timeMs >= wordEnd && (i == wordLyrics.size - 1 || timeMs < wordLyrics[i + 1].startTime))
                currentWordIndex = i
        }
        for (i in wordLyrics.indices) {
            val word = wordLyrics[i]; val wordLen = word.word.length
            when {
                i == currentWordIndex -> {
                    spannable.setSpan(ForegroundColorSpan(highlightColor), position, position + wordLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(RelativeSizeSpan(1.5f), position, position + wordLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(StyleSpan(Typeface.BOLD), position, position + wordLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                i < currentWordIndex -> spannable.setSpan(ForegroundColorSpan(sungColor), position, position + wordLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                else -> spannable.setSpan(ForegroundColorSpan(normalColor), position, position + wordLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            position += wordLen
        }
        setText(spannable, TextView.BufferType.SPANNABLE)
    }

    fun isLyricShowing(): Boolean = isShowing
}
