package com.eagleseye.camera.engine

import android.graphics.*
import android.opengl.GLES30
import android.opengl.GLUtils
import com.eagleseye.camera.DateStampStyle
import com.eagleseye.camera.StampLcd
import com.eagleseye.camera.FrameConfig
import com.eagleseye.camera.FrameMode
import com.eagleseye.camera.FrameType
import com.eagleseye.camera.dateStampVisual
import com.eagleseye.camera.drawStampText
import java.text.SimpleDateFormat
import java.util.*

object FrameDraw {
    // Window rect [l, t, r, b] in pixels for a frame type + config + mode.
    // Shared by the GL preview texture, capture pipeline and UI previews.
    fun frameWindow(ft: FrameType, c: FrameConfig, w: Float, h: Float, mode: FrameMode = FrameMode.OVERLAY): FloatArray {
        val base = when (ft) {
            FrameType.CLASSIC_POLAROID -> {
                val b = w * c.borderWidth.coerceIn(0.02f, 0.2f)
                val bot = w * c.bottomSpace.coerceIn(0.05f, 0.35f)
                floatArrayOf(b, b, w - b, h - b - bot)
            }
            FrameType.INSTAX -> {
                val b = w * 0.055f
                floatArrayOf(b, b, w - b, h - b * 1.9f)
            }
            FrameType.FILM_35MM -> {
                val tb = h * 0.075f
                floatArrayOf(w * 0.035f, tb, w * 0.965f, h - tb)
            }
            FrameType.CARTRIDGE_110 -> {
                val b = w * 0.045f
                floatArrayOf(b, b, w - b, h - b - h * 0.08f)
            }
            FrameType.THICK_MATTE -> {
                val b = w * 0.10f
                floatArrayOf(b, b, w - b, h - b)
            }
            FrameType.MINIMAL_MAT -> {
                val b = w * 0.07f
                floatArrayOf(b, b, w - b, h - b)
            }
            FrameType.VELVET_CINEMA -> {
                val lr = w * 0.06f
                val tb = h * 0.115f
                floatArrayOf(lr, tb, w - lr, h - tb)
            }
            FrameType.FILM_8MM -> {
                val l = w * 0.16f
                val r = w * 0.965f
                val tb = h * 0.035f
                floatArrayOf(l, tb, r, h - tb)
            }
            FrameType.SLIDE_MOUNT -> {
                val b = w * 0.048f
                floatArrayOf(b, b, w - b, h - b)
            }
            else -> floatArrayOf(0f, 0f, w, h)
        }
        if (mode == FrameMode.EXTENDED || mode == FrameMode.EXTENDED_OVERLAY) {
            val l = base[0]; val t = base[1]; val r = base[2]; val b = base[3]
            val cx = (l + r) / 2f; val cy = (t + b) / 2f
            val f = 0.82f
            return floatArrayOf(cx - (r - l) * f / 2f, cy - (b - t) * f / 2f, cx + (r - l) * f / 2f, cy + (b - t) * f / 2f)
        }
        return base
    }

    fun paperColor(ft: FrameType, c: FrameConfig): Int = when (ft) {
        FrameType.CLASSIC_POLAROID -> if (c.polarFrameCode.isNotEmpty()) c.paperColor.toInt() else 0xFFEDE6D6.toInt()
        FrameType.INSTAX -> 0xFFF8F8F8.toInt()
        FrameType.FILM_35MM -> 0xFF1A1410.toInt()
        FrameType.CARTRIDGE_110 -> 0xFF2C2620.toInt()
        FrameType.THICK_MATTE -> 0xFFD8D4D0.toInt()
        FrameType.MINIMAL_MAT -> 0xFFECEAE8.toInt()
        FrameType.VELVET_CINEMA -> 0xFF0A0A0A.toInt()
        FrameType.FILM_8MM -> 0xFF0C0A08.toInt()
        FrameType.SLIDE_MOUNT -> 0xFF080808.toInt()
        else -> 0xFF000000.toInt()
    }

    // Paper grain / wear — deterministic (seeded per frame type) so the live GL
    // texture matches capture pixel-for-pixel and the texture cache stays stable.
    fun paperGrain(cv: Canvas, w: Float, h: Float, wear: Float, seed: Int) {
        if (wear <= 0.002f) return
        val rng = java.util.Random(seed.toLong())
        val dark = Paint(Paint.ANTI_ALIAS_FLAG)
        val light = Paint(Paint.ANTI_ALIAS_FLAG)
        val density = (w * h * wear * 0.0006f).toInt().coerceAtMost(1400)
        for (i in 0 until density) {
            val x = rng.nextFloat() * w; val y = rng.nextFloat() * h
            if (rng.nextFloat() < 0.72f) {
                dark.color = android.graphics.Color.argb((6 + rng.nextFloat() * 26f * wear).toInt().coerceIn(0, 44), 0, 0, 0)
                cv.drawCircle(x, y, 0.5f + rng.nextFloat() * 1.2f, dark)
            } else {
                light.color = android.graphics.Color.argb((4 + rng.nextFloat() * 18f * wear).toInt().coerceIn(0, 38), 255, 255, 255)
                cv.drawCircle(x, y, 0.4f + rng.nextFloat() * 1.0f, light)
            }
        }
        // faint vertical paper fibres
        val fibre = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until (w * h * wear * 0.00003f).toInt().coerceAtMost(26)) {
            val fx = rng.nextFloat() * w; val len = 10f + rng.nextFloat() * 40f
            fibre.color = android.graphics.Color.argb((3 + rng.nextFloat() * 7f).toInt(), 255, 255, 255)
            cv.drawLine(fx, rng.nextFloat() * h, fx + (rng.nextFloat() - 0.5f) * 6f, fx * 0f + rng.nextFloat() * h + len, fibre)
        }
    }

    // Soft drop shadow cast by the photo onto the paper — feeds the SHADOW slider.
    fun photoShadow(cv: Canvas, win: FloatArray, w: Float, depth: Float) {
        if (depth <= 0.01f) return
        val off = w * (0.008f + depth * 0.014f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.setShadowLayer(w * (0.006f + depth * 0.016f), off * 0.55f, off * 0.9f,
            android.graphics.Color.argb((24 + depth * 90f).toInt().coerceIn(0, 130), 0, 0, 0))
        p.style = Paint.Style.FILL
        cv.drawRoundRect(RectF(win[0], win[1], win[2], win[3]), w * 0.012f, w * 0.012f, p)
    }

    // Tint wash over the paper area — feeds the OVERLAY slider + polar palettes.
    fun paperTint(cv: Canvas, w: Float, h: Float, tint: Int, alpha: Float) {
        if (alpha <= 0.01f) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.argb(
            (alpha * 255).toInt().coerceIn(0, 255),
            android.graphics.Color.red(tint), android.graphics.Color.green(tint), android.graphics.Color.blue(tint)
        )
        cv.drawRect(0f, 0f, w, h, p)
    }

    // Accents drawn on the paper area only (sprockets, gold line, caption, shadow).
    fun drawAccents(cv: Canvas, w: Float, h: Float, ft: FrameType, win: FloatArray, date: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (ft) {
            FrameType.FILM_35MM -> {
                // Near-black flushed film bands — reads like a real developed 135 strip.
                val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0908.toInt() }
                cv.drawRect(0f, 0f, w, win[1] - h * 0.006f, band)
                cv.drawRect(0f, win[3] + h * 0.006f, w, h, band)
                // sprocket holes — long vertical slots like real 135 perforations
                // (2.8×3.8 mm: taller than wide), with a soft inner glint.
                val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050403.toInt() }
                val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF.toInt() }
                val hr = h * 0.038f; val hw = hr * 0.62f; val cr = hr * 0.26f
                val bandTop = win[1] - h * 0.006f; val bandBot = win[3] + h * 0.006f
                var sx = w * 0.02f
                while (sx < w * 0.99f) {
                    val ty = bandTop - hr - h * 0.006f
                    cv.drawRoundRect(RectF(sx, ty, sx + hw, ty + hr), cr, cr, hole)
                    cv.drawRoundRect(RectF(sx + hw * 0.14f, ty + hr * 0.16f, sx + hw * 0.86f, ty + hr * 0.88f), cr * 0.4f, cr * 0.4f, glint)
                    val by = bandBot + h * 0.006f
                    cv.drawRoundRect(RectF(sx, by, sx + hw, by + hr), cr, cr, hole)
                    cv.drawRoundRect(RectF(sx + hw * 0.14f, by + hr * 0.16f, sx + hw * 0.86f, by + hr * 0.88f), cr * 0.4f, cr * 0.4f, glint)
                    sx += hw * 3.4f
                }
                // rebate edge codes — like real negative strips (brand + exposure + counter)
                val textC = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x5CC9B387.toInt(); textSize = h * 0.0125f
                    typeface = Typeface.create("monospace", Typeface.NORMAL); letterSpacing = 0.06f
                }
                cv.drawText("EAGLES 400  ·  12 EXP", win[0] + w * 0.008f, win[1] - h * 0.023f, textC)
                val num = "12A"
                cv.drawText(num, win[2] - w * 0.03f, win[3] + h * 0.038f, textC)
                val proof = Paint(textC).apply { color = 0x3BC9B387.toInt(); letterSpacing = 0.18f }
                cv.drawText("PROOF · 100", win[0] + w * 0.008f, win[3] + h * 0.038f, proof)
                // warm "dev edge" stains hugging the photo edges (bromide fog)
                val stain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, win[1] - h * 0.012f, 0f, win[1] + h * 0.022f,
                        intArrayOf(0x2EB0601F.toInt(), android.graphics.Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                }
                val stainB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, win[3] - h * 0.022f, 0f, win[3] + h * 0.012f,
                        intArrayOf(android.graphics.Color.TRANSPARENT, 0x2E602008.toInt()), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                }
                cv.drawRect(0f, win[1] - h * 0.012f, w, win[1] + h * 0.03f, stain)
                cv.drawRect(0f, win[3] - h * 0.03f, w, win[3] + h * 0.012f, stainB)
            }
            FrameType.CARTRIDGE_110 -> {
                val notch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF15100C.toInt() }
                val nw = w * 0.16f; val nh = (h - win[3]) * 0.55f
                cv.drawRoundRect(RectF((w - nw) / 2f, win[3] + nh * 0.2f, (w + nw) / 2f, win[3] + nh), nw * 0.2f, nw * 0.2f, notch)
                // cartridge label + exposure number like a real 110 cassette
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x8C6B6155.toInt(); textSize = h * 0.022f
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                }
                cv.drawText("110", (w + nw) / 2f + nw * 0.32f, win[3] + nh * 0.62f, tp)
                val cn = Paint(tp).apply { color = 0x59605A50.toInt(); textSize = h * 0.013f }
                cv.drawText("NO. 24", (w + nw) / 2f + nw * 0.34f, win[3] + nh * 0.85f, cn)
                // glint sheen across the photo for the laminated look
                val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, 0f, h,
                        intArrayOf(0x14FFFFFF.toInt(), android.graphics.Color.TRANSPARENT, 0x10FFFFFF.toInt()),
                        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                }
                cv.drawRect(win[0], win[1], win[2], win[3], sheen)
            }
            FrameType.VELVET_CINEMA -> {
                val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99E8CD98.toInt(); style = Paint.Style.STROKE; strokeWidth = w * 0.0035f }
                cv.drawRoundRect(RectF(win[0], win[1], win[2], win[3]), w * 0.01f, w * 0.01f, gold)
                // inner top highlight
                val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = w * 0.0012f }
                cv.drawRoundRect(RectF(win[0] + w * 0.005f, win[1] + w * 0.005f, win[2] - w * 0.005f, win[3] - w * 0.005f), w * 0.01f, w * 0.01f, hi)
                // letterbox date in the bottom bar
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x44FFFFFF.toInt()
                    textSize = h * 0.016f
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                }
                cv.drawText(date, w / 2f, h - h * 0.035f, tp)
            }
            FrameType.FILM_8MM -> {
                // left-edge sprocket holes — long slots like real 8mm perf
                val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050403.toInt() }
                val hw = w * 0.034f; val hr = h * 0.024f; val cr = hr * 0.35f
                val lx = w * 0.028f
                var hy = h * 0.075f
                while (hy < h * 0.9f) {
                    cv.drawRoundRect(RectF(lx, hy, lx + hw, hy + hr), cr, cr, hole)
                    hy += hr * 2.6f
                }
                // gate dust — a few soft specks on the paper like an old home-movie gate
                val dust = Paint(Paint.ANTI_ALIAS_FLAG)
                val rng = java.util.Random(0x8A5B0L)
                for (i in 0 until 9) {
                    dust.color = android.graphics.Color.argb((14 + rng.nextFloat() * 20f).toInt(), 255, 230, 200)
                    cv.drawCircle(rng.nextFloat() * w * 0.9f + w * 0.05f, rng.nextFloat() * h, 0.6f + rng.nextFloat() * 1.6f, dust)
                }
                // dark rounded corner vignette — projector gate falloff
                val vig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(w / 2f, h / 2f, maxOf(w, h) * 0.78f,
                        intArrayOf(android.graphics.Color.TRANSPARENT, 0x33000000.toInt()),
                        floatArrayOf(0.72f, 1f), Shader.TileMode.CLAMP)
                }
                cv.drawRect(0f, 0f, w, h, vig)
                // tiny reel date+code along the bottom edge
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x4490E0FF.toInt(); textSize = h * 0.014f
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    textAlign = Paint.Align.CENTER
                    letterSpacing = 0.1f
                }
                cv.drawText(date.replace(' ', '·') + "  8008", w * 0.85f, h - h * 0.012f, tp)
            }
            FrameType.SLIDE_MOUNT -> {
                // outer bevel highlight — chamfered plastic mount
                val bevel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = w * 0.006f }
                cv.drawRoundRect(RectF(win[0] - w * 0.014f, win[1] - w * 0.014f, win[2] + w * 0.014f, win[3] + w * 0.014f), w * 0.02f, w * 0.02f, bevel)
                // inner cut-out edge — film aperture frame
                val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = w * 0.004f }
                cv.drawRoundRect(RectF(win[0], win[1], win[2], win[3]), w * 0.012f, w * 0.012f, cut)
                // top sheen across the whole mount
                val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, 0f, h,
                        intArrayOf(0x16FFFFFF.toInt(), android.graphics.Color.TRANSPARENT),
                        floatArrayOf(0f, 0.35f), Shader.TileMode.CLAMP)
                }
                cv.drawRect(0f, 0f, w, h, sheen)
                // index number chips on both bottom corners, like card-mounted slides
                val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF0E0C08.toInt(); textSize = h * 0.02f
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                }
                val cw = w * 0.085f
                val cy = h - h * 0.045f
                cv.drawRoundRect(RectF(w * 0.02f, cy - h * 0.018f, w * 0.02f + cw, cy + h * 0.018f), w * 0.004f, w * 0.004f, Paint().apply { color = 0xFF141210.toInt() })
                cv.drawRoundRect(RectF(w * 0.985f - cw, cy - h * 0.018f, w * 0.985f, cy + h * 0.018f), w * 0.004f, w * 0.004f, Paint().apply { color = 0xFF141210.toInt() })
                cv.drawText("12", w * 0.028f, cy + h * 0.006f, chip)
                cv.drawText("27", w * 0.985f - cw + w * 0.028f, cy + h * 0.006f, chip)
                // marginal caption along the top edge
                val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x33FFFFFF.toInt(); textSize = h * 0.011f
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    textAlign = Paint.Align.CENTER
                    letterSpacing = 0.22f
                }
                cv.drawText("EAGLES • CHROME 135", w / 2f, w * 0.035f, cap)
            }
            FrameType.CLASSIC_POLAROID -> {
                val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000 }
                val off = w * 0.01f
                cv.drawRoundRect(RectF(win[0] + off, win[1] + off, win[2] + off, win[3] + off), w * 0.012f, w * 0.012f, shadow)
                // glossy sheen across the paper — classic instant-print top-light
                val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, w * 0.35f, h,
                        intArrayOf(0x24FFFFFF.toInt(), 0x0CFFFFFF.toInt(), android.graphics.Color.TRANSPARENT),
                        floatArrayOf(0f, 0.28f, 0.62f), Shader.TileMode.CLAMP)
                }
                cv.drawRect(0f, 0f, w, h, sheen)
                // handwritten-style caption + real-polaroid footer emboss
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x8C6E675C.toInt(); textSize = h * 0.03f
                    typeface = Typeface.create("serif", Typeface.ITALIC)
                    textAlign = Paint.Align.CENTER
                }
                cv.drawText(date, w / 2f, win[3] + (h - win[3]) * 0.58f, tp)
                val foot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x406E675C.toInt(); textSize = h * 0.011f
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    textAlign = Paint.Align.CENTER
                    letterSpacing = 0.14f
                }
                cv.drawText("EAGLES  6900  ·  3407", w / 2f, h - h * 0.014f, foot)
                // faint edge scuffs
                val scuff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0F000000 }
                val rng = java.util.Random(0x5EEDL)
                for (i in 0 until 3) cv.drawLine(rng.nextFloat() * w, rng.nextFloat() * h, rng.nextFloat() * w, rng.nextFloat() * h, scuff)            }
            FrameType.INSTAX -> {
                // inner emboss line around the print
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1E000000.toInt(); style = Paint.Style.STROKE; strokeWidth = w * 0.0018f }
                cv.drawRoundRect(RectF(win[0], win[1], win[2], win[3]), w * 0.008f, w * 0.008f, line)
                // glossy sheen
                val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, w * 0.3f, h,
                        intArrayOf(0x20FFFFFF.toInt(), android.graphics.Color.TRANSPARENT),
                        floatArrayOf(0f, 0.55f), Shader.TileMode.CLAMP)
                }
                cv.drawRect(win[0], win[1], win[2], win[3], sheen)
                // bottom band brand text
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x77808080.toInt()
                    textSize = h * 0.02f
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                    letterSpacing = 0.3f
                }
                cv.drawText("EAGLES mini", w / 2f, h - h * 0.022f, tp)
            }
            FrameType.THICK_MATTE -> {
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x12000000; style = Paint.Style.STROKE; strokeWidth = w * 0.004f }
                cv.drawRoundRect(RectF(win[0], win[1], win[2], win[3]), w * 0.02f, w * 0.02f, line)
                // top-edge bevel light
                val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1EFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = w * 0.0022f }
                cv.drawRoundRect(RectF(win[0] + w * 0.002f, win[1] + w * 0.002f, win[2] - w * 0.002f, win[3] - w * 0.002f), w * 0.02f, w * 0.02f, light)
            }
            FrameType.MINIMAL_MAT -> {
                // offset hairline emboss
                val emb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14000000.toInt(); style = Paint.Style.STROKE; strokeWidth = w * 0.0012f }
                val off = w * 0.002f
                cv.drawRoundRect(RectF(win[0] + off, win[1] + off, win[2] + off, win[3] + off), w * 0.01f, w * 0.01f, emb)
            }
            else -> {}
        }
    }
}

class FrameTextureGenerator {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dateFormat = SimpleDateFormat("  yy.MM.dd  HH:mm  ", Locale.US)
    private val cache = mutableMapOf<String, Int>()

    data class Size(val w: Int, val h: Int)

    fun getFrameTexture(ft: FrameType, w: Int, h: Int, config: FrameConfig = FrameConfig(), dateText: String = dateFormat.format(Date()), filmName: String = "KODAK PORTRA 400",
                        showDate: Boolean = false, stamp: String = "", stampStyle: DateStampStyle = DateStampStyle.ORANGE_FILM, stampPos: Int = 0): Int {
        if (ft == FrameType.NONE) return 0
        val key = "${ft.name}:${w}x${h}:${config.polarFrameCode}:${config.paperColor}:${config.borderWidth}:${config.bottomSpace}:${config.cornerRadius}:${config.textureWear}:${config.shadowDepth}:${config.overlayColor}:${config.overlayAlpha}:$showDate:$stamp:$stampStyle:$stampPos"
        cache[key]?.let { return it }
        val bitmap = generate(ft, w, h, config, dateText, filmName, showDate, stamp, stampStyle, stampPos)
        val texId = bitmapToTexture(bitmap)
        bitmap.recycle()
        cache[key] = texId
        return texId
    }

    private fun generate(ft: FrameType, w: Int, h: Int, c: FrameConfig, date: String, film: String, showDate: Boolean, stamp: String, stampStyle: DateStampStyle, stampPos: Int): Bitmap {
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bm)
        cv.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val win = FrameDraw.frameWindow(ft, c, w.toFloat(), h.toFloat())
        paint.color = FrameDraw.paperColor(ft, c)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        // realism layer — same order as the capture path in FilmEngine.applyFrame
        FrameDraw.paperTint(cv, w.toFloat(), h.toFloat(), c.overlayColor.toInt(), c.overlayAlpha)
        FrameDraw.paperGrain(cv, w.toFloat(), h.toFloat(), 0.25f + c.textureWear * 0.75f, ft.hashCode() + w * 31 + h)
        FrameDraw.photoShadow(cv, win, w.toFloat(), c.shadowDepth)
        FrameDraw.drawAccents(cv, w.toFloat(), h.toFloat(), ft, win, "  ${date}  ")
        // soft print-room light across the paper — kills the flat "cartoon" fill
        val grad = Paint(Paint.ANTI_ALIAS_FLAG)
        grad.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0x14FFFFFF.toInt(), 0x06000000.toInt()), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), grad)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        val r = when (ft) {
            FrameType.CLASSIC_POLAROID -> w * 0.016f
            FrameType.INSTAX -> w * 0.012f
            FrameType.THICK_MATTE -> w * 0.024f
            FrameType.MINIMAL_MAT -> w * 0.016f
            FrameType.CARTRIDGE_110 -> w * 0.006f
            else -> 0f
        }.let { if (c.cornerRadius > 0f) w * c.cornerRadius else it }
        cv.drawRoundRect(RectF(win[0], win[1], win[2], win[3]), r, r, paint)
        paint.xfermode = null
        // printed-photo edge: dark print ring + inner highlight so the photo
        // reads as a real print mounted on the paper, not a pasted rectangle.
        val edgeDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = w * 0.005f
            color = android.graphics.Color.argb(42, 0, 0, 0)
        }
        val edgeLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = w * 0.0016f
            color = android.graphics.Color.argb(22, 255, 255, 255)
        }
        val er = r + w * 0.004f
        cv.drawRoundRect(RectF(win[0], win[1], win[2], win[3]), er, er, edgeDark)
        cv.drawRoundRect(RectF(win[0] + w * 0.002f, win[1] + w * 0.002f, win[2] - w * 0.002f, win[3] - w * 0.002f), er, er, edgeLight)
        // date stamp ON the photo area, drawn after the window clear so it shows
        // above the live image — matches the capture-stamp look.
        if (showDate && stamp.isNotBlank()) {
            renderStamp(cv, win, w.toFloat(), h.toFloat(), stamp, stampStyle, stampPos)
        }
        return bm
    }

    // Film-date stamp inside the photo window. Positions mirror capture: 0 B·R, 1 B·L, 2 T·R, 3 T·L.
    private fun renderStamp(cv: Canvas, win: FloatArray, w: Float, h: Float, text: String, style: DateStampStyle, pos: Int) {
        val vis = dateStampVisual(style)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.textSize = (win[3] - win[1]) * 0.03f
        tp.typeface = vis.typeface
        tp.color = vis.color
        if (vis.shadowColor != 0) tp.setShadowLayer(vis.shadowRadius, vis.shadowDx, vis.shadowDy, vis.shadowColor)
        if (vis.letterSpacing > 0f) tp.letterSpacing = vis.letterSpacing
        if (vis.lcd && StampLcd.font != null) tp.typeface = StampLcd.font
        val tw = if (vis.lcd && StampLcd.font == null) com.eagleseye.camera.lcdTextWidth(text, tp.textSize * 0.96f, vis.letterSpacing) else tp.measureText(text)
        val pad = w * 0.015f
        val bx = when (pos) { 1 -> win[0] + pad; 2 -> win[2] - tw - pad; 3 -> win[0] + pad; else -> win[2] - tw - pad }
        val by = when (pos) { 0 -> win[3] - pad; 1 -> win[3] - pad; else -> win[1] + tp.textSize + pad }
        if (vis.chipColor != 0) {
            cv.drawRoundRect(bx - pad, by - tp.textSize * 0.8f, bx + tw + pad, by + tp.textSize * 0.4f, 4f, 4f,
                Paint().apply { color = vis.chipColor; setShadowLayer(3f, 1f, 1f, android.graphics.Color.argb(80, 0, 0, 0)) })
        }
        if (vis.rotation != 0f) {
            cv.save()
            cv.rotate(vis.rotation, w / 2f, h / 2f)
            drawStampText(cv, text, bx, by, tp, vis.stroke, vis.lcd)
            cv.restore()
        } else drawStampText(cv, text, bx, by, tp, vis.stroke, vis.lcd)
    }

    private fun bitmapToTexture(bitmap: Bitmap): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return tex[0]
    }
}
