package com.prismfiles.app

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

data class TreemapNodeUi(
    val name: String,
    val path: String,
    val size: Long,
    val isDir: Boolean,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val depth: Int
)

class CushionTreemapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var nodes: List<TreemapNodeUi> = emptyList()
    private var selectedNode: TreemapNodeUi? = null
    var onNodeSelected: ((TreemapNodeUi) -> Unit)? = null

    // Calibrated instrument-grade category palette (quiet precision, zero rainbow pastel slop)
    private val palette = intArrayOf(
        Color.parseColor("#2C4251"), // Slate Steel
        Color.parseColor("#1C3F3A"), // Quiet Pine
        Color.parseColor("#38324F"), // Deep Indigo Plum
        Color.parseColor("#4D3B2F"), // Muted Amber Umber
        Color.parseColor("#26384C"), // Deep Cobalt
        Color.parseColor("#2F433E"), // Sage Graphite
        Color.parseColor("#42323D"), // Muted Wine Slate
        Color.parseColor("#3B3B42"), // Titanium Neutral
        Color.parseColor("#2A3C38"), // Jade Dark
        Color.parseColor("#3D352E"), // Dark Sand
        Color.parseColor("#303846"), // Navy Grey
        Color.parseColor("#453036")  // Deep Crimson Charcoal
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#0A0A0A")
    }
    private val selectStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#F0F0F0")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0F0F0")
        textSize = 24f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93")
        textSize = 18f
        typeface = Typeface.MONOSPACE
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    private var baseWidth = 1000f
    private var baseHeight = 1000f

    fun setNodes(newNodes: List<TreemapNodeUi>, refW: Float = 1000f, refH: Float = 1000f) {
        nodes = newNodes
        baseWidth = if (refW > 0) refW else 1000f
        baseHeight = if (refH > 0) refH else 1000f
        selectedNode = newNodes.firstOrNull()
        selectedNode?.let { onNodeSelected?.invoke(it) }
        invalidate()
    }

    fun setSelected(node: TreemapNodeUi?) {
        selectedNode = node
        invalidate()
    }

    fun getSelected(): TreemapNodeUi? = selectedNode

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.isEmpty() || width <= 0 || height <= 0) return

        val scaleX = width.toFloat() / baseWidth
        val scaleY = height.toFloat() / baseHeight

        for (i in nodes.indices) {
            val n = nodes[i]
            val left = n.x * scaleX
            val top = n.y * scaleY
            val right = (n.x + n.w) * scaleX
            val bottom = (n.y + n.h) * scaleY

            val rectW = right - left
            val rectH = bottom - top
            if (rectW < 1.5f || rectH < 1.5f) continue

            val baseColor = palette[(n.name.hashCode() and 0x7FFFFFFF) % palette.size]

            // 3D Cushion ridge surface gradient
            val shader = LinearGradient(
                left, top, right, bottom,
                intArrayOf(lighten(baseColor, 0.30f), baseColor, darken(baseColor, 0.40f)),
                floatArrayOf(0.0f, 0.45f, 1.0f),
                Shader.TileMode.CLAMP
            )
            fillPaint.shader = shader
            val rectF = RectF(left + 1f, top + 1f, right - 1f, bottom - 1f)
            canvas.drawRoundRect(rectF, 4f, 4f, fillPaint)
            fillPaint.shader = null

            // Outer gap divider border
            canvas.drawRoundRect(rectF, 4f, 4f, strokePaint)

            // Highlight border if selected
            if (selectedNode?.path == n.path) {
                canvas.drawRoundRect(rectF, 4f, 4f, selectStrokePaint)
            }

            // Text labels (fits within rectangle)
            if (rectW > 60f && rectH > 35f) {
                val maxChars = (rectW / 18f).toInt().coerceAtLeast(3)
                val label = if (n.name.length > maxChars) n.name.take(maxChars - 1) + "…" else n.name
                canvas.drawText(label, left + 8f, top + 30f, textPaint)

                if (rectH > 68f) {
                    val sizeStr = formatSize(n.size)
                    canvas.drawText(sizeStr, left + 8f, top + 56f, subTextPaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
            val scaleX = width.toFloat() / baseWidth
            val scaleY = height.toFloat() / baseHeight
            val tx = event.x
            val ty = event.y

            for (n in nodes) {
                val left = n.x * scaleX
                val top = n.y * scaleY
                val right = (n.x + n.w) * scaleX
                val bottom = (n.y + n.h) * scaleY

                if (tx in left..right && ty in top..bottom) {
                    selectedNode = n
                    invalidate()
                    onNodeSelected?.invoke(n)
                    try {
                        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                            vm?.defaultVibrator
                        } else {
                            @Suppress("DEPRECATION")
                            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator?.vibrate(12)
                        }
                    } catch (_: Exception) {}
                    return true
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun lighten(color: Int, factor: Float): Int {
        val r = (Color.red(color) * (1f + factor)).coerceAtMost(255f).toInt()
        val g = (Color.green(color) * (1f + factor)).coerceAtMost(255f).toInt()
        val b = (Color.blue(color) * (1f + factor)).coerceAtMost(255f).toInt()
        return Color.rgb(r, g, b)
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color) * (1f - factor)).coerceAtLeast(0f).toInt()
        val g = (Color.green(color) * (1f - factor)).coerceAtLeast(0f).toInt()
        val b = (Color.blue(color) * (1f - factor)).coerceAtLeast(0f).toInt()
        return Color.rgb(r, g, b)
    }
}
