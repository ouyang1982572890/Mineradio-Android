package com.mineradio.app.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.*
import kotlin.random.Random

// 星星粒子数据
private data class Star(
    var x: Float, var y: Float,
    var baseX: Float, var baseY: Float,
    var size: Float,
    var phase: Float, var speed: Float
)

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#010304"))

        val splashView = SplashView(this)
        root.addView(splashView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)
    }

    @SuppressLint("ViewConstructor")
    private inner class SplashView(context: Context) : View(context) {

        private val accentTeal = Color.parseColor("#00F5D4")
        private val accentGold = Color.parseColor("#F4D28A")
        private val accentRose = Color.parseColor("#FF5367")

        private var globalTime = 0f
        private var startTime = 0L

        // 粒子星空
        private val stars = mutableListOf<Star>()
        // 信号线动画阶段
        private var lineProgress = 0f
        private var dotProgress = 0f
        // 品牌文字动画
        private var wordMineProgress = 0f
        private var wordRadioProgress = 0f
        private var subProgress = 0f
        // 点击进入按钮
        private var enterAlpha = 0f
        private var enterPulse = 0f
        private var isSplashReady = false
        private var btnScale = 1f
        private var isBtnAnim = false
        private var btnAnimTime = 0L

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            // 生成随机星星
            for (i in 0 until 60) {
                stars.add(Star(
                    x = 0f, y = 0f,
                    baseX = Random.nextFloat(),
                    baseY = Random.nextFloat(),
                    size = 0.5f + Random.nextFloat() * 2f,
                    phase = Random.nextFloat() * 6.28f,
                    speed = 0.3f + Random.nextFloat() * 1.2f
                ))
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            for (star in stars) {
                star.x = w * star.baseX
                star.y = h * star.baseY
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            startTime = System.currentTimeMillis()
            startAnimation()
        }

        private fun startAnimation() {
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 16L
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                    globalTime = elapsed

                    // 阶段1: 信号线动画 (0-3.2s)
                    lineProgress = ((elapsed - 0.3f) / 2.9f).coerceIn(0f, 1f)
                    dotProgress = ((elapsed - 0.6f) / 2.6f).coerceIn(0f, 1f)

                    // 阶段2: 品牌文字出现 (0.8-3.5s)
                    val t2 = ((elapsed - 0.8f) / 2.7f).coerceIn(0f, 1f)
                    wordMineProgress = easeOutCubic(t2)
                    wordRadioProgress = easeOutCubic(((elapsed - 1.2f) / 2.3f).coerceIn(0f, 1f))

                    // 阶段3: 副标题 (2.5-3.8s)
                    subProgress = ((elapsed - 2.5f) / 1.3f).coerceIn(0f, 1f)

                    // 阶段4: 点击进入按钮 (3.5s+)
                    if (elapsed > 3.5f && !isSplashReady) {
                        isSplashReady = true
                    }
                    if (isSplashReady) {
                        enterAlpha = ((elapsed - 3.5f) / 0.8f).coerceIn(0f, 1f)
                        enterPulse = sin(elapsed * 1.8f) * 0.5f + 0.5f
                    }

                    // 按钮动画
                    if (isBtnAnim) {
                        val bt = (System.currentTimeMillis() - btnAnimTime) / 1000f
                        if (bt > 0.5f) isBtnAnim = false
                        btnScale = if (bt < 0.1f) 1f - 0.05f * easeOutQuad(bt / 0.1f)
                                   else 1f - 0.05f + 0.05f * easeOutQuad(((bt - 0.1f) / 0.4f).coerceIn(0f, 1f))
                    }

                    // 星星动画
                    for (star in stars) {
                        star.x = width * star.baseX + sin(elapsed * star.speed + star.phase) * 8f
                        star.y = height * star.baseY + cos(elapsed * star.speed * 0.7f + star.phase) * 6f
                    }

                    invalidate()
                }
            }
            anim.start()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f

            // 深色背景
            paint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(Color.parseColor("#020606"), Color.parseColor("#050607"), Color.parseColor("#000000")),
                floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // 背景微光
            val glowRadius = w * 0.55f
            paint.shader = RadialGradient(cx, cy - h * 0.08f, glowRadius,
                intArrayOf(
                    Color.argb(14, 255, 83, 103),
                    Color.argb(8, 244, 210, 138),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy - h * 0.08f, glowRadius, paint)
            paint.shader = null

            // 星星
            drawStars(canvas)

            // 信号线
            drawSignalLine(canvas, w, h)

            // 品牌文字
            drawBrandText(canvas, w, h)

            // 副标题
            drawSubtitle(canvas, w, h)

            // 点击进入按钮
            drawEnterButton(canvas, w, h)
        }

        private fun drawStars(canvas: Canvas) {
            for (star in stars) {
                val twinkle = (0.3f + 0.7f * abs(sin(globalTime * 2.5f + star.phase)))
                val alpha = (160 * twinkle).toInt()
                val s = star.size * (0.6f + 0.4f * twinkle)

                paint.style = Paint.Style.FILL
                paint.color = Color.argb(alpha, 255, 255, 255)
                canvas.drawCircle(star.x, star.y, s, paint)

                // 部分星星带微光
                if (star.size > 1.5f && twinkle > 0.7f) {
                    paint.color = Color.argb((40 * twinkle).toInt(), 244, 210, 138)
                    canvas.drawCircle(star.x, star.y, s * 2.5f, paint)
                }
            }
        }

        private fun drawSignalLine(canvas: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val lineY = h / 2f + h * 0.18f
            val lineW = min(w * 0.54f, dip(360))
            val left = cx - lineW / 2f
            val right = cx + lineW / 2f

            if (lineProgress <= 0f) return

            val alpha = ((0.3f + 0.7f * lineProgress) * 255).toInt().coerceIn(0, 255)
            val scaledW = lineW * (0.1f + 0.9f * lineProgress)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.shader = LinearGradient(
                cx - scaledW / 2f, lineY, cx + scaledW / 2f, lineY,
                intArrayOf(
                    Color.argb(0, 0, 0, 0),
                    Color.argb((alpha * 0.22f).toInt(), 122, 215, 194),
                    Color.argb(alpha, 255, 255, 255),
                    Color.argb((alpha * 0.66f).toInt(), 244, 210, 138),
                    Color.argb((alpha * 0.22f).toInt(), 255, 83, 103),
                    Color.argb(0, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.2f, 0.5f, 0.7f, 0.85f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(cx - scaledW / 2f, lineY, cx + scaledW / 2f, lineY, paint)
            paint.shader = null

            // 信号点
            if (dotProgress > 0f) {
                val dotX = cx - scaledW / 2f + scaledW * dotProgress
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(
                    (alpha * (1f - abs(dotProgress - 0.5f) * 2f)).toInt(),
                    244, 210, 138
                )
                val dotR = dip(4) * (0.5f + sin(dotProgress * PI).toFloat() * 0.5f)
                canvas.drawCircle(dotX, lineY, dotR, paint)
            }
        }

        private fun drawBrandText(canvas: Canvas, w: Float, h: Float) {
            if (wordMineProgress <= 0f) return

            val cx = w / 2f
            val baseY = h / 2f - h * 0.06f

            // "Mine" 文字
            val mineAlpha = (255 * wordMineProgress.coerceIn(0f, 1f)).toInt()
            paint.textSize = sp(38f)
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER

            // 偏移量模拟动画
            val mineOffsetX = (1f - wordMineProgress) * dip(10)
            paint.color = Color.argb(mineAlpha, 248, 248, 242)
            paint.setShadowLayer(20f, 0f, 4f, Color.argb((mineAlpha * 0.68f).toInt(), 0, 0, 0))
            canvas.drawText("Mine", cx - dip(42) - mineOffsetX, baseY, paint)

            // "Radio" 渐变文字
            val radioAlpha = (255 * wordRadioProgress.coerceIn(0f, 1f)).toInt()
            if (radioAlpha > 0) {
                paint.clearShadowLayer()
                paint.textSize = sp(38f)
                paint.color = Color.argb(radioAlpha, 255, 255, 255)
                paint.setShadowLayer(20f, 0f, 4f, Color.argb((radioAlpha * 0.68f).toInt(), 0, 0, 0))
                canvas.drawText("Radio", cx + dip(28) + (1f - wordRadioProgress) * dip(10), baseY, paint)
            }
            paint.clearShadowLayer()
        }

        private fun drawSubtitle(canvas: Canvas, w: Float, h: Float) {
            if (subProgress <= 0f) return

            val cx = w / 2f
            val baseY = h / 2f - h * 0.06f
            val alpha = (255 * 0.42f * subProgress).toInt()

            paint.textSize = sp(10f)
            paint.typeface = Typeface.DEFAULT
            paint.textAlign = Paint.Align.CENTER
            paint.color = Color.argb(alpha, 255, 255, 255)
            paint.setShadowLayer(12f, 0f, 0f, Color.argb((alpha * 0.24f).toInt(), 244, 210, 138))
            canvas.drawText("SMART ASSISTANT", cx, baseY + dip(36).toFloat(), paint)
            paint.clearShadowLayer()
        }

        private fun drawEnterButton(canvas: Canvas, w: Float, h: Float) {
            if (enterAlpha <= 0f) return

            val cx = w / 2f
            val btnY = h / 2f + h * 0.2f
            val alpha = (255 * enterAlpha).toInt().coerceIn(0, 255)
            val pulseExtra = if (isSplashReady) enterPulse * 0.2f else 0f
            val finalAlpha = (alpha.toFloat() * (0.46f + pulseExtra)).toInt().coerceIn(0, 255)

            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = sp(13f)
            paint.textAlign = Paint.Align.CENTER
            paint.color = Color.argb(finalAlpha, 255, 255, 255)
            paint.setShadowLayer(18f, 0f, 0f, Color.argb((finalAlpha * 0.24f).toInt(), 244, 210, 138))

            val scale = btnScale
            canvas.save()
            canvas.scale(scale, scale, cx, btnY)
            canvas.drawText("点击进入", cx, btnY, paint)
            canvas.restore()

            paint.clearShadowLayer()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isSplashReady) return true
                    isBtnAnim = true
                    btnAnimTime = System.currentTimeMillis()
                    postDelayed({
                        startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                        this@SplashActivity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        this@SplashActivity.finish()
                    }, 200)
                }
            }
            return true
        }

        private fun easeOutCubic(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
        private fun easeOutQuad(t: Float) = 1f - (1f - t) * (1f - t)
        private fun dip(v: Int): Float = v * resources.displayMetrics.density
        private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
    }
}
