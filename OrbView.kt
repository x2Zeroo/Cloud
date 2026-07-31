package com.cloud.assistant

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.*
import kotlin.random.Random

class OrbView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Strand(
        val thetaOff: Float, val phiOff: Float, val speed: Float, val wobble: Float,
        val wobblePhase: Float, val len: Float, val segs: Int, val thickness: Float, val phase: Float
    )
    private data class Node(val theta: Float, val phi: Float, val r: Float, val tw: Float, val speed: Float)

    private val strands = List(46) {
        Strand(
            thetaOff = Random.nextFloat() * (PI * 2).toFloat(),
            phiOff = acos(2 * Random.nextFloat() - 1),
            speed = 0.15f + Random.nextFloat() * 0.3f,
            wobble = 0.6f + Random.nextFloat() * 1.4f,
            wobblePhase = Random.nextFloat() * (PI * 2).toFloat(),
            len = 0.35f + Random.nextFloat() * 0.5f,
            segs = 18 + Random.nextInt(10),
            thickness = 0.4f + Random.nextFloat() * 0.9f,
            phase = Random.nextFloat() * (PI * 2).toFloat()
        )
    }
    private val nodes = List(70) {
        Node(
            theta = Random.nextFloat() * (PI * 2).toFloat(),
            phi = acos(2 * Random.nextFloat() - 1),
            r = 0.55f + Random.nextFloat() * 0.5f,
            tw = Random.nextFloat() * (PI * 2).toFloat(),
            speed = 0.1f + Random.nextFloat() * 0.25f
        )
    }

    private var rotY = 0f
    private var level = 0f
    private var targetLevel = 0f
    private var textPulse = 0f
    var listening = false

    private val strandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var running = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            invalidate()
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    /** External RMS level from SpeechRecognizer, 0..1. */
    fun setTargetLevel(v: Float) { targetLevel = v.coerceIn(0f, 1f) }

    /** Trigger a visual pulse when a new reply/text event happens. */
    fun pulse() { textPulse = 1f }

    private fun sphToXY(theta: Float, phi: Float, r: Float, R: Float, cx: Float, cy: Float): FloatArray {
        val x0 = r * sin(phi) * cos(theta)
        val y0 = r * cos(phi)
        val z0 = r * sin(phi) * sin(theta)
        val cosY = cos(rotY); val sinY = sin(rotY)
        val x1 = x0 * cosY - z0 * sinY
        val z1 = x0 * sinY + z0 * cosY
        val persp = 2.4f
        val f = persp / (persp + z1)
        return floatArrayOf(cx + x1 * R * f, cy + y0 * R * f, z1, f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        textPulse *= 0.94f
        val driveLevel = max(targetLevel, textPulse)
        level += (driveLevel - level) * 0.15f
        val energy = 0.5f + level * 1.6f

        val cx = w / 2f; val cy = h / 2f - h * 0.02f
        val baseR = min(w, h) * 0.30f
        val R = baseR * (1 + level * 0.35f)

        // Radial glow
        glowPaint.shader = RadialGradient(
            cx, cy, R * 1.1f,
            intArrayOf(
                Color.argb((255 * (0.22f + level * 0.35f)).roundToInt(), 200, 235, 255),
                Color.argb((255 * (0.10f + level * 0.15f)).roundToInt(), 90, 170, 230),
                Color.argb(0, 10, 30, 50)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, R * 1.1f, glowPaint)

        if (Build_supportsBlendPlus()) strandPaint.blendMode = BlendMode.PLUS
        val timeMs = System.nanoTime() / 1_000_000.0

        for (s in strands) {
            path.reset()
            for (k in 0..s.segs) {
                val frac = k / s.segs.toFloat()
                val theta = (s.thetaOff + frac * s.len * (PI * 2).toFloat() + timeMs * 0.0002 * s.speed
                        + sin(timeMs * 0.0006 * s.speed + s.wobblePhase + frac * 6) * 0.4 * s.wobble * (0.3 + level)).toFloat()
                val phi = (s.phiOff + sin(frac * 5 + timeMs * 0.0004 + s.phase) * 0.5 * s.wobble).toFloat()
                val r = 0.65f + 0.3f * sin(frac * PI.toFloat()) + level * 0.25f
                val p = sphToXY(theta, phi, r, R, cx, cy)
                if (k == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1])
            }
            val alpha = (0.10f + 0.22f * energy) * (if (listening) 1f else 0.75f)
            val a = (255 * min(alpha, if (listening) 0.55f else 0.4f)).roundToInt().coerceIn(0, 255)
            strandPaint.color = if (listening) Color.argb(a, 150, 220, 255) else Color.argb(a, 180, 210, 235)
            strandPaint.strokeWidth = s.thickness * resources.displayMetrics.density * (0.8f + level * 0.6f)
            canvas.drawPath(path, strandPaint)
        }

        if (Build_supportsBlendPlus()) nodePaint.blendMode = BlendMode.PLUS
        for (n in nodes) {
            val theta = (n.theta + timeMs * 0.0003 * n.speed).toFloat()
            val p = sphToXY(theta, n.phi, n.r, R, cx, cy)
            val twinkle = 0.4f + 0.6f * sin(timeMs * 0.003 + n.tw).toFloat()
            val depthA = (p[2] + 1) / 2
            val a = ((0.2f + depthA * 0.8f) * twinkle * (0.6f + level * 0.8f)).coerceIn(0f, 1f)
            val size = max((0.8f + depthA * 1.6f + level * 1.5f) * resources.displayMetrics.density, 0.5f)
            nodePaint.color = Color.argb((a * 255).roundToInt(), 235, 248, 255)
            canvas.drawCircle(p[0], p[1], size, nodePaint)
        }

        rotY += 0.0012f + level * 0.004f
    }

    private fun Build_supportsBlendPlus() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
}
