package com.prismfiles.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.*

class DonutWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onSliceSelectedListener: ((WheelSliceItem) -> Unit)? = null
    var onFormatSelectedListener: ((String) -> Unit)? = null

    private var slices: List<WheelSliceItem> = emptyList()
    private var categoryTitle: String = "Convert"
    private var categoryCountText: String = ""

    private var currentRotation: Float = 0f
    private var lastTickIndex: Int = -1
    private var flingAnimator: ValueAnimator? = null

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val sectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val sectorBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#262628")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0F0F0")
        textSize = 22f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val sliceSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93")
        textSize = 15f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0F0F0")
        textSize = 34f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val centerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93")
        textSize = 18f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val ringRect = RectF()
    private val innerRect = RectF()

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            flingAnimator?.cancel()
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val cx = width / 2f
            val cy = height / 2f
            val angle1 = atan2(e2.y - cy + distanceY, e2.x - cx + distanceX)
            val angle2 = atan2(e2.y - cy, e2.x - cx)
            val diffDeg = Math.toDegrees((angle2 - angle1).toDouble()).toFloat()

            currentRotation += diffDeg
            checkHapticTick()
            postInvalidateOnAnimation()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val vel = (abs(velocityX) + abs(velocityY)) / 20f
            val dir = if (velocityX + velocityY > 0) 1f else -1f
            flingAnimator?.cancel()
            flingAnimator = ValueAnimator.ofFloat(currentRotation, currentRotation + dir * vel * 2f).apply {
                duration = 650
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    currentRotation = it.animatedValue as Float
                    checkHapticTick()
                    postInvalidateOnAnimation()
                }
            }
            flingAnimator?.start()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val cx = width / 2f
            val cy = height / 2f
            val dx = e.x - cx
            val dy = e.y - cy
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val outerR = min(width, height) * 0.44f
            val innerR = outerR * 0.50f

            if (slices.isEmpty()) return false

            if (dist in innerR..outerR) {
                // Tapped directly on a sector: select that tapped sector
                val touchAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                val sweep = 360f / slices.size
                val normalized = ((touchAngle - currentRotation) % 360f + 360f) % 360f
                val tappedIdx = (normalized / sweep).toInt().coerceIn(0, slices.size - 1)
                val slice = slices[tappedIdx]
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                dispatchSliceSelected(slice)
                return true
            } else if (dist < innerR) {
                // Tapped center circle: confirm the currently active sector
                val slice = getSelectedSlice() ?: slices[0]
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                dispatchSliceSelected(slice)
                return true
            }
            return false
        }
    })

    private fun dispatchSliceSelected(slice: WheelSliceItem) {
        onSliceSelectedListener?.invoke(slice)
        onFormatSelectedListener?.invoke(slice.targetFormat)
    }

    fun setWheelProfile(profile: WheelProfile) {
        flingAnimator?.cancel()
        categoryTitle = profile.name
        categoryCountText = profile.countText
        slices = profile.slices
        currentRotation = 0f
        lastTickIndex = -1
        invalidate()
    }

    fun setTargetFormats(list: List<String>) {
        flingAnimator?.cancel()
        categoryTitle = "Convert"
        categoryCountText = "${list.size} / 12"
        slices = list.map { WheelSliceItem(it.uppercase(), null, it) }
        currentRotation = 0f
        lastTickIndex = -1
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        flingAnimator?.cancel()
        flingAnimator = null
    }

    private fun checkHapticTick() {
        val idx = getSelectedIndex()
        if (idx != -1 && idx != lastTickIndex) {
            lastTickIndex = idx
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            } catch (_: Exception) {}
        }
    }

    private fun getSelectedIndex(): Int {
        if (slices.isEmpty()) return -1
        val sweep = 360f / slices.size
        // 270 degrees is top center (selection pointer)
        val normalized = ((-currentRotation + 270f) % 360f + 360f) % 360f
        return (normalized / sweep).toInt() % slices.size
    }

    fun getSelectedSlice(): WheelSliceItem? {
        val idx = getSelectedIndex()
        return if (idx in slices.indices) slices[idx] else null
    }

    fun getSelectedFormat(): String? {
        return getSelectedSlice()?.targetFormat
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (slices.isEmpty()) return

        val cx = width / 2f
        val cy = height / 2f
        val outerR = min(width, height) * 0.44f
        val innerR = outerR * 0.50f

        ringRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        innerRect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)

        val count = slices.size
        val sweep = 360f / count
        val selectedIdx = getSelectedIndex()

        // Draw sectors
        for (i in 0 until count) {
            val startAngle = currentRotation + i * sweep
            val isSelected = (i == selectedIdx)
            val isEven = (i % 2 == 0)

            sectorPaint.color = if (isSelected) Color.parseColor("#262628")
                else (if (isEven) Color.parseColor("#161616") else Color.parseColor("#101010"))

            val path = Path()
            path.arcTo(ringRect, startAngle, sweep)
            path.arcTo(innerRect, startAngle + sweep, -sweep)
            path.close()
            canvas.drawPath(path, sectorPaint)

            if (isSelected) {
                sectorBorderPaint.color = Color.parseColor("#F0F0F0")
                sectorBorderPaint.strokeWidth = 2f
            } else {
                sectorBorderPaint.color = Color.parseColor("#262628")
                sectorBorderPaint.strokeWidth = 1f
            }
            canvas.drawPath(path, sectorBorderPaint)

            // Text on sector: STRAIGHT, upright, horizontally centered, NEVER upside down
            val midAngleDeg = startAngle + sweep / 2f
            val midAngleRad = Math.toRadians(midAngleDeg.toDouble())
            val textR = (outerR + innerR) / 2f
            val tx = cx + (textR * cos(midAngleRad)).toFloat()
            val ty = cy + (textR * sin(midAngleRad)).toFloat()

            val slice = slices[i]
            val hasSubtitle = !slice.subtitle.isNullOrEmpty()

            textPaint.textSize = when {
                slice.label.length > 12 -> 16f
                slice.label.length > 8 -> 18f
                count > 10 -> 20f
                else -> 22f
            }
            textPaint.color = if (isSelected) Color.parseColor("#F0F0F0") else Color.parseColor("#8E8E93")

            val sub = slice.subtitle
            if (sub != null) {
                // If label has multiple words (e.g. "Trace to SVG"), handle neatly
                val labelLines = if (slice.label.contains(" ") && slice.label.length > 8) {
                    slice.label.split(" ", limit = 2)
                } else {
                    listOf(slice.label)
                }

                if (labelLines.size > 1) {
                    canvas.drawText(labelLines[0], tx, ty - 16f, textPaint)
                    canvas.drawText(labelLines[1], tx, ty + 2f, textPaint)
                    canvas.drawText(sub, tx, ty + 20f, sliceSubPaint)
                } else {
                    canvas.drawText(slice.label, tx, ty - 6f, textPaint)
                    canvas.drawText(sub, tx, ty + 15f, sliceSubPaint)
                }
            } else {
                val words = if (slice.label.contains(" ") && slice.label.length > 9) {
                    slice.label.split(" ", limit = 2)
                } else {
                    listOf(slice.label)
                }

                if (words.size > 1) {
                    val fm = textPaint.fontMetrics
                    val lineH = fm.descent - fm.ascent
                    canvas.drawText(words[0], tx, ty - lineH / 4f, textPaint)
                    canvas.drawText(words[1], tx, ty + lineH * 3f / 4f, textPaint)
                } else {
                    val fm = textPaint.fontMetrics
                    val baseline = ty - (fm.descent + fm.ascent) / 2f
                    canvas.drawText(slice.label, tx, baseline, textPaint)
                }
            }
        }

        // Center circle (quiet matte console surface)
        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#161616")
        }
        canvas.drawCircle(cx, cy, innerR - 4f, centerPaint)

        val centerBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#F0F0F0")
        }
        canvas.drawCircle(cx, cy, innerR - 4f, centerBorder)

        // Center text: Format label on top, action subtitle below
        val selSlice = getSelectedSlice() ?: (if (slices.isNotEmpty()) slices[0] else null)
        val selLabel = selSlice?.label ?: ""
        val selFmt = selSlice?.targetFormat ?: ""
        val isCompress = selFmt.lowercase() in listOf("zip", "7z", "tar.gz", "tar.xz", "tar.bz2", "gz", "xz")
        val isExtract = selFmt.lowercase() == "extract" || selSlice?.actionType == "extract"

        centerTextPaint.textSize = if (selLabel.length > 8) 26f else 34f
        canvas.drawText(selLabel.uppercase(), cx, cy - 6f, centerTextPaint)

        val subText = when {
            isExtract -> "Tap to extract"
            isCompress -> "Tap to compress"
            selSlice?.isAction == true -> "Tap to execute"
            else -> "Tap to convert"
        }
        canvas.drawText(subText, cx, cy + 28f, centerSubPaint)
    }
}
