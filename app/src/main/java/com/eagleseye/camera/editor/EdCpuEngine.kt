package com.eagleseye.camera.editor

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure-CPU editor renderer. Every op is a faithful 1:1 port of its GLSL
 *  shader (FRAG_EDIT_*, FRAG_GRAIN, FRAG_VIGNETTE, FRAG_TILT_SHIFT, …),
 *  executed on FloatArray channels. No GL anywhere in this path: the preview
 *  and the export can never be black, crash, or depend on GPU capabilities.
 *  Approximate ports exist for a small set of exotic effects so film LOOKS
 *  keep working even without GPU access. */
object EdCpuEngine {

    private val skippedLogged = HashSet<String>()

    fun render(src: Bitmap, ops: List<EditorOp>, curves: List<List<FloatArray>>?, depth: Bitmap?, blendInto: Bitmap? = null): Bitmap {
        val w = src.width; val h = src.height
        val n = w * h
        val px = IntArray(n); src.getPixels(px, 0, w, 0, 0, w, h)
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            r[i] = (c shr 16 and 0xFF) / 255f; g[i] = (c shr 8 and 0xFF) / 255f; b[i] = (c and 0xFF) / 255f
        }
        var depthPx: IntArray? = null
        var depthMask: FloatArray? = null
        for (op in ops) {
            when (op.name) {
                "tune" -> tune(op, r, g, b, n)
                "wb" -> wb(op, r, g, b, n)
                "curves" -> curves(op, r, g, b, n, curves)
                "select" -> select(op, r, g, b, w, h)
                "heal" -> heal(op, r, g, b, w, h)
                "mask" -> mask(op, r, g, b, w, h)
                "hsl" -> hsl(op, r, g, b, n)
                "split" -> split(op, r, g, b, n)
                "structure" -> structure(op, r, g, b, w, h)
                "geom" -> geom(op, r, g, b, w, h)
                "lensblur" -> lensblur(op, r, g, b, w, h)
                "depthblur" -> depthblur(op, r, g, b, w, h, depth)
                "depthblurf" -> depthblur(op, r, g, b, w, h, depth)
                "sharpen" -> sharpen(op, r, g, b, w, h)
                "detail" -> detail(op, r, g, b, w, h)
                "effects" -> effects(op, r, g, b, w, h)
                "optics" -> optics(op, r, g, b, w, h)
                "kaleido" -> kaleido(op, r, g, b, w, h)
                "markup" -> markup(op, r, g, b, w, h)
                "vignette" -> vignette(op, r, g, b, w, h)
                "grain" -> grain(op, r, g, b, n, w, h)
                "tilt" -> tilt(op, r, g, b, w, h)
                "streak" -> streak(op, r, g, b, w, h)
                "frame" -> frame(op, r, g, b, w, h)
                "blend" -> blend(op, r, g, b, w, h)
                "bw_grain" -> bw(op, r, g, b, n)
                "duotone" -> duotone(op, r, g, b, n)
                "color_shift" -> colorShift(op, r, g, b, n)
                "instant" -> instant(op, r, g, b, n)
                "retro" -> retro(op, r, g, b, n)
                "velvia" -> velvia(op, r, g, b, n)
                "bleach" -> bleach(op, r, g, b, n)
                "winter" -> winter(op, r, g, b, n)
                "portra800" -> portra(op, r, g, b, n)
                "obsidian" -> obsidian(op, r, g, b, n)
                "false_color" -> falseColor(op, r, g, b, n)
                "cross" -> cross(op, r, g, b, n)
                "fat_pixel" -> fatPixel(op, r, g, b, w, h)
                "1bit" -> oneBit(op, r, g, b, n)
                "dust" -> dust(op, r, g, b, w, h)
                "crt" -> crt(op, r, g, b, w, h)
                "vhs" -> vhs(op, r, g, b, w, h)
                "glitch" -> glitch(op, r, g, b, w, h)
                "starburst" -> starburst(op, r, g, b, w, h)
                "terminal" -> terminal(op, r, g, b, w, h)
                "halftone" -> halftone(op, r, g, b, w, h)
                "cmyk_dots" -> cmykDots(op, r, g, b, w, h)
                "teletext" -> teletext(op, r, g, b, w, h)
                "infrared" -> infrared(op, r, g, b, n)
                "film_grade" -> filmGrade(op, r, g, b, n)
                "tilt_shift" -> tilt(op.p("focusY", 0.5f).p("width", op.params["width"] ?: 0.3f).p("feather", op.params["feather"] ?: 0.3f).p("forceBlur", 1f), r, g, b, w, h)
                else -> if (skippedLogged.add(op.name)) android.util.Log.i("EAGLES_EDIT", "CPU engine skipping effect: ${op.name}")
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val opx = IntArray(n)
        for (i in 0 until n) {
            val R = (r[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val G = (g[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val B = (b[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            opx[i] = (0xFF shl 24) or (R shl 16) or (G shl 8) or B
        }
        out.setPixels(opx, 0, w, 0, 0, w, h)
        return out
    }

    private fun lum(r: Float, g: Float, b: Float) = r * 0.2126f + g * 0.7152f + b * 0.0722f

    /** Per-film grade, ported from photoncam's applyGradeAndRolloff. */
    private fun filmGrade(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val sat = op.params["sat"] ?: 1f
        val con = op.params["contrast"] ?: 1f
        val lift = op.params["lift"] ?: 0f
        val rol = op.params["rolloff"] ?: 0f
        if (sat == 1f && con == 1f && lift == 0f && rol == 0f) return
        val inv = 1f - sat
        val rW = inv * 0.213f; val gW = inv * 0.715f; val bW = inv * 0.072f
        val off = lift + (1f - con) * 128f
        val s = rol.coerceIn(0f, 1f)
        val rScale = 1f - s * 0.18f
        val rLift = s * 14f
        for (i in 0 until n) {
            val rc = r[i] * 255f * con + off
            val gc = g[i] * 255f * con + off
            val bc = b[i] * 255f * con + off
            val lum = rW * rc + gW * gc + bW * bc
            r[i] = ((lum + sat * rc) * rScale + rLift).coerceIn(0f, 255f) / 255f
            g[i] = ((lum + sat * gc) * rScale + rLift).coerceIn(0f, 255f) / 255f
            b[i] = ((lum + sat * bc) * rScale + rLift).coerceIn(0f, 255f) / 255f
        }
    }

    private fun tune(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val br = op.params["brightness"] ?: 1f
        val co = op.params["contrast"] ?: 1f
        val sa = op.params["saturation"] ?: 1f
        val wa = op.params["warmth"] ?: 0f
        val hi = op.params["highlights"] ?: 0f
        val sh = op.params["shadows"] ?: 0f
        val am = op.params["ambience"] ?: 0f
        for (i in 0 until n) {
            var cr = r[i] * max(br, 0.05f)
            var cg = g[i] * max(br, 0.05f)
            var cb = b[i] * max(br, 0.05f)
            var l = lum(cr, cg, cb)
            l = (l - 0.5f) * co + 0.5f
            val shm = 1f - smoothstep(0.05f, 0.5f, l)
            val him = smoothstep(0.5f, 0.95f, l)
            l = l + shm * sh * 0.18f + him * hi * 0.18f
            val amb = max(0f, am)
            l = l * (1f - amb * 0.22f) + amb * 0.12f
            val sat = (1f + sa).coerceIn(0f, 3f)
            cr = l + (cr - l) * sat; cg = l + (cg - l) * sat; cb = l + (cb - l) * sat
            var rr = cr * (1f + wa * 0.25f); val gg = cg * (1f - wa * 0.05f); var bb = cb * (1f - wa * 0.22f)
            val m = max(max(rr, gg), bb); if (m > 1f) { rr /= m; bb /= m }
            r[i] = rr.coerceIn(0f, 1f); g[i] = gg.coerceIn(0f, 1f); b[i] = bb.coerceIn(0f, 1f)
        }
    }

    private fun wb(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val te = op.params["temp"] ?: 0f
        val ti = op.params["tint"] ?: 0f
        for (i in 0 until n) {
            var rr = r[i] * (1f + te * 0.22f + ti * 0.12f)
            var gg = g[i] * (1f - te * 0.05f + ti * 0.07f)
            var bb = b[i] * (1f - te * 0.22f + ti * 0.12f)
            val m = max(max(rr, gg), bb); if (m > 1f) { rr /= m; gg /= m; bb /= m }
            r[i] = rr.coerceIn(0f, 1f); g[i] = gg.coerceIn(0f, 1f); b[i] = bb.coerceIn(0f, 1f)
        }
    }

    private fun curves(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int, curves: List<List<FloatArray>>?) {
        val chan = (op.params["channel"] ?: 0f) < 0.5f
        val mix = op.params["mix"] ?: 1f
        val lut = Array(4) { ch ->
            if (curves != null && ch < curves.size) EditorCurves.sample(curves[ch]) else EditorCurves.linear()
        }
        if (chan) {
            for (i in 0 until n) {
                val ll = lum(r[i], g[i], b[i])
                val l = (ll * 255f).toInt().coerceIn(0, 255)
                val v = lut[0][l]
                val s = if (ll > 1e-4f) v / ll else 1f
                r[i] = r[i] + (r[i] * s - r[i]) * mix
                g[i] = g[i] + (g[i] * s - g[i]) * mix
                b[i] = b[i] + (b[i] * s - b[i]) * mix
            }
        } else {
            for (i in 0 until n) {
                val ir = (r[i] * 255f).toInt().coerceIn(0, 255)
                val ig = (g[i] * 255f).toInt().coerceIn(0, 255)
                val ib = (b[i] * 255f).toInt().coerceIn(0, 255)
                r[i] = r[i] + (lut[1][ir] - r[i]) * mix
                g[i] = g[i] + (lut[2][ig] - g[i]) * mix
                b[i] = b[i] + (lut[3][ib] - b[i]) * mix
            }
        }
    }

    private fun select(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val spots = ArrayList<FloatArray>()
        for (i in 0 until 6) {
            val x = op.params["p${i}x"] ?: break
            spots.add(floatArrayOf(
                x, op.params["p${i}y"] ?: 0f, op.params["p${i}r"] ?: 0.1f,
                op.params["p${i}w"] ?: 1f, op.params["p${i}b"] ?: 0f, op.params["p${i}s"] ?: 1f, op.params["p${i}t"] ?: 0f
            ))
        }
        if (spots.isEmpty()) return
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var cr = r[i]; var cg = g[i]; var cb = b[i]
            val ux = (x + 0.5f) / w; val uy = (y + 0.5f) / h
            for (sp in spots) {
                val dx = (ux - sp[0]) / max(sp[2], 1e-3f)
                val dy = (uy - sp[1]) / max(sp[2], 1e-3f)
                val wgt = exp(-(dx * dx + dy * dy) * 1.7f) * sp[3]
                if (wgt < 0.01f) continue
                cr += cr * (sp[4] * 0.45f) * wgt
                cg += cg * (sp[4] * 0.45f) * wgt
                cb += cb * (sp[4] * 0.45f) * wgt
                var l = lum(cr, cg, cb)
                val s = (1f + (sp[5] - 1f) * wgt).coerceIn(0f, 3f)
                cr = l + (cr - l) * s; cg = l + (cg - l) * s; cb = l + (cb - l) * s
                l = lum(cr, cg, cb)
                cr += cr * (sp[6] * 0.3f) * wgt; cg += cg * (sp[6] * 0.3f) * wgt; cb += cb * (sp[6] * 0.3f) * wgt
            }
            r[i] = cr; g[i] = cg; b[i] = cb
        }
    }

    private fun heal(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val spots = ArrayList<FloatArray>()
        for (i in 0 until 6) {
            val x = op.params["p${i}x"] ?: break
            spots.add(floatArrayOf(
                x, op.params["p${i}y"] ?: 0f, (op.params["p${i}r"] ?: 0.1f).coerceIn(0.005f, 0.5f),
                op.params["p${i}w"] ?: 0.5f
            ))
        }
        if (spots.isEmpty()) return
        // Each spot heals in its own padded local region, sampled from a ROI
        // snapshot so the ring taps see the pre-heal input exactly like the
        // shader's uTexture read — no full-frame copies at export resolution.
        for (sp in spots) {
            val cxp = sp[0] * w; val cyp = sp[1] * h
            val rp = sp[2] * max(w, h)
            val pad = (rp * 1.9f).toInt().coerceAtLeast(4)
            val x0 = (cxp - pad).toInt().coerceIn(0, w - 1); val x1 = (cxp + pad).toInt().coerceIn(0, w - 1)
            val y0 = (cyp - pad).toInt().coerceIn(0, h - 1); val y1 = (cyp + pad).toInt().coerceIn(0, h - 1)
            if (x1 - x0 < 1 || y1 - y0 < 1) continue
            val rw = x1 - x0 + 1; val rh = y1 - y0 + 1
            val sr = FloatArray(rw * rh); val sg = FloatArray(rw * rh); val sb = FloatArray(rw * rh)
            var k = 0
            for (yy in y0..y1) {
                val row = yy * w
                var kk = k
                for (xx in x0..x1) {
                    sr[kk] = r[row + xx]; sg[kk] = g[row + xx]; sb[kk] = b[row + xx]
                    kk++
                }
                k += rw
            }
            val soft = (0.35f + 0.5f * sp[3]).coerceIn(0.2f, 1f)
            val inner = rp * soft
            for (yy in y0..y1) for (xx in x0..x1) {
                val dxn = (xx + 0.5f) / w - sp[0]
                val dyn = (yy + 0.5f) / h - sp[1]
                val dist = sqrt(dxn * dxn + dyn * dyn)
                if (dist > rp) continue
                val i = yy * w + xx
                var cr = 0f; var cg = 0f; var cb = 0f
                for (tt in 0 until 8) {
                    val ang = tt * 6.2831853f / 8f
                    val txp = (cxp + cos(ang) * rp * 1.6f).toInt().coerceIn(x0, x1)
                    val typ = (cyp + sin(ang) * rp * 1.6f).toInt().coerceIn(y0, y1)
                    val tidx = (typ - y0) * rw + (txp - x0)
                    cr += sr[tidx]; cg += sg[tidx]; cb += sb[tidx]
                }
                var w2 = 1f - ((dist - inner) / max(rp - inner, 1e-4f)).coerceIn(0f, 1f)
                w2 = w2 * w2 * (3f - 2f * w2)
                if (w2 > 0.01f) {
                    r[i] = r[i] + (cr * 0.125f - r[i]) * w2
                    g[i] = g[i] + (cg * 0.125f - g[i]) * w2
                    b[i] = b[i] + (cb * 0.125f - b[i]) * w2
                }
            }
        }
    }

    private fun mask(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val ov = op.overlay ?: return
        val mw = ov.width; val mh = ov.height
        val mp = IntArray(mw * mh); ov.getPixels(mp, 0, mw, 0, 0, mw, mh)
        val br = op.params["brightness"] ?: 0f
        val sa = op.params["saturation"] ?: 0f
        val wa = op.params["warmth"] ?: 0f
        val st = op.params["strength"] ?: 1f
        val ex = op.params["exposure"] ?: 0f
        val ct = op.params["contrast"] ?: 0f
        val inv = (op.params["inverted"] ?: 0f) > 0.5f
        val eMul = 2f.pow(ex * 1.2f)
        for (y in 0 until h) for (x in 0 until w) {
            val mx2 = ((x + 0.5f) / w * mw).toInt().coerceIn(0, mw - 1)
            val my2 = ((y + 0.5f) / h * mh).toInt().coerceIn(0, mh - 1)
            var m = ((mp[my2 * mw + mx2] ushr 24) and 0xFF) / 255f
            if (inv) m = 1f - m
            val i = y * w + x
            var cr = (r[i] * eMul - 0.5f) * (1f + ct) + 0.5f
            var cg = (g[i] * eMul - 0.5f) * (1f + ct) + 0.5f
            var cb = (b[i] * eMul - 0.5f) * (1f + ct) + 0.5f
            cr *= max(1f + br * 0.45f, 0.05f)
            cg *= max(1f + br * 0.45f, 0.05f)
            cb *= max(1f + br * 0.45f, 0.05f)
            val l0 = lum(cr, cg, cb)
            val sat = (1f + sa).coerceIn(0f, 3f)
            cr = l0 + (cr - l0) * sat; cg = l0 + (cg - l0) * sat; cb = l0 + (cb - l0) * sat
var rr = cr * (1f + wa * 0.2f); var gg = cg; var bb = cb * (1f - wa * 0.2f)
        val mx = max(max(rr, gg), bb); if (mx > 1f) { rr /= mx; gg /= mx; bb /= mx }
            val s2 = (st.coerceIn(0f, 1.5f) * m).coerceIn(0f, 1f)
            r[i] = r[i] + (rr - r[i]) * s2; g[i] = g[i] + (gg - g[i]) * s2; b[i] = b[i] + (bb - b[i]) * s2
        }
    }

    private fun hsv2rgb(hh: Float, ss: Float, vv: Float): FloatArray {
        val p = FloatArray(3)
        val hv = hh * 6f - 3f
        for (k in 0 until 3) p[k] = abs(hv - floatArrayOf(0f, 4f, 2f)[k]).let { if (it > 1f) 1f else it }.let { 1f - (it - 1f).coerceAtLeast(0f) }
        val pp = floatArrayOf(abs(hv) - 1f, abs(hv - 4f) - 1f, abs(hv - 2f) - 1f)
        val f = FloatArray(3) { (pp[it].coerceIn(0f, 1f)) }
        val m = FloatArray(3) { if (f[it] <= 0f) 1f + f[it] else 1f }
        // hsv2rgb approximation matching the shader: p=abs(fract(c.xxx+off)*6-3); clamp(p-1,0,1)
        val q = FloatArray(3) { (abs((hh + floatArrayOf(0f, 2f / 3f, 1f / 3f)[it]) % 1f * 6f - 3f) - 1f).coerceIn(0f, 1f) }
        return floatArrayOf(vv * (1f - ss + ss * q[0].coerceIn(0f, 1f)), vv * (1f - ss + ss * q[1].coerceIn(0f, 1f)), vv * (1f - ss + ss * q[2].coerceIn(0f, 1f)))
    }

    private fun hsl(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val aH = FloatArray(6) { op.params["h$it"] ?: 0f }
        val aS = FloatArray(6) { op.params["s$it"] ?: 0f }
        val aL = FloatArray(6) { op.params["l$it"] ?: 0f }
        if (aH.all { it == 0f } && aS.all { it == 0f } && aL.all { it == 0f }) return
        for (i in 0 until n) {
            val cr = r[i]; val cg = g[i]; val cb = b[i]
            val mx = max(max(cr, cg), cb); val mn = min(min(cr, cg), cb)
            val d = mx - mn; val l = (mx + mn) * 0.5f
            val sat = d / max(mx + mn, 1e-5f)
            var h: Float
            h = if (d < 1e-4f) 0f
            else if (mx == cr) (cg - cb) / d / 6f
            else if (mx == cg) (cb - cr) / d / 6f + 0.3333f
            else (cr - cg) / d / 6f + 0.6667f
            h = (h + 1f) % 1f
            val dh = FloatArray(6) { min(abs(h - it * 0.1667f), 1f - abs(h - it * 0.1667f)) }
            val wt = FloatArray(6) { exp(-dh[it] * dh[it] * 900f) }
            var sumH = 0f; var sumS = 0f; var sumL = 0f
            for (k in 0 until 6) { sumH += aH[k] * wt[k]; sumS += aS[k] * wt[k]; sumL += aL[k] * wt[k] }
            val nL = (l + sumL * 0.28f).coerceIn(0f, 1f)
            val nS = (sat * (1f + sumS)).coerceIn(0f, 1f)
            var nH = (h + sumH * 0.075f) % 1f
            if (sat < 0.02f) nH = h
            val o = hsv2rgb(nH, nS, nL)
            r[i] = o[0]; g[i] = o[1]; b[i] = o[2]
        }
    }

    private fun split(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val shR = op.params["shR"] ?: 0f; val shG = op.params["shG"] ?: 0f; val shB = op.params["shB"] ?: 0f
        val hiR = op.params["hiR"] ?: 0f; val hiG = op.params["hiG"] ?: 0f; val hiB = op.params["hiB"] ?: 0f
        val bal = op.params["balance"] ?: 0f; val st = op.params["strength"] ?: 0f
        for (i in 0 until n) {
            val cr = r[i]; val cg = g[i]; val cb = b[i]
            val l = lum(cr, cg, cb)
            val s = (1f - (l * 1.4f - bal).coerceIn(0f, 1f)).let { it * it }
            val hh = (l - bal).coerceIn(0f, 1f).let { it * it }
            val tr = cr + shR * s * 0.8f + hiR * hh * 0.8f
            val tg = cg + shG * s * 0.8f + hiG * hh * 0.8f
            val tb = cb + shB * s * 0.8f + hiB * hh * 0.8f
            val m = st.coerceIn(0f, 1f)
            r[i] = cr + (tr - cr) * m; g[i] = cg + (tg - cg) * m; b[i] = cb + (tb - cb) * m
        }
    }

    private fun structure(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val am = op.params["amount"] ?: 0f
        if (am <= 0f) return
        val ox = (0.0025f * w).coerceAtLeast(1f)
        val oy = (0.0025f * h).coerceAtLeast(1f)
        val lc = FloatArray(w * h) { lum(r[it], g[it], b[it]) }
        val lba = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val xx = ((x + dx * ox).toInt()).coerceIn(0, w - 1)
                val yy = ((y + dy * oy).toInt()).coerceIn(0, h - 1)
                s += lc[yy * w + xx]
            }
            lba[y * w + x] = s / 9f
        }
        for (i in lc.indices) {
            val gd = lc[i] - lba[i]
            r[i] = (r[i] * (1f + gd * am * 2.2f)).coerceIn(0f, 1f)
            g[i] = (g[i] * (1f + gd * am * 2.2f)).coerceIn(0f, 1f)
            b[i] = (b[i] * (1f + gd * am * 2.2f)).coerceIn(0f, 1f)
        }
    }

    private fun geom(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val ang = op.params["angle"] ?: 0f
        val pv = op.params["perspV"] ?: 0f
        val ph = op.params["perspH"] ?: 0f
        if (ang == 0f && pv == 0f && ph == 0f) return
        val ca = cos(-ang); val sa = sin(-ang)
        val rr = FloatArray(r.size); val gg = FloatArray(r.size); val bb = FloatArray(r.size)
        for (y in 0 until h) for (x in 0 until w) {
            var cxp = (x + 0.5f) / w - 0.5f; var cyp = (y + 0.5f) / h - 0.5f
            var rx = cxp * ca - cyp * sa
            var ry = cxp * sa + cyp * ca
            val px = rx * (1f + pv * (-ry * 0.5f))
            val py = ry * (1f + ph * (rx * 0.5f))
            var ux = px + 0.5f; var uy = py + 0.5f
            if (ux < 0f) ux = -ux else if (ux > 1f) ux = 2f - ux
            if (uy < 0f) uy = -uy else if (uy > 1f) uy = 2f - uy
            ux = ux.coerceIn(0f, 1f); uy = uy.coerceIn(0f, 1f)
            val fx = ux * w - 0.5f; val fy = uy * h - 0.5f
            val x0 = fx.toInt().coerceIn(0, w - 1); val y0 = fy.toInt().coerceIn(0, h - 1)
            val x1 = (x0 + 1).coerceIn(0, w - 1); val y1 = (y0 + 1).coerceIn(0, h - 1)
            val tx = fx - x0; val ty = fy - y0
            val i00 = y0 * w + x0; val i10 = y0 * w + x1; val i01 = y1 * w + x0; val i11 = y1 * w + x1
            val i = y * w + x
            rr[i] = r[i00] * (1 - tx) * (1 - ty) + r[i10] * tx * (1 - ty) + r[i01] * (1 - tx) * ty + r[i11] * tx * ty
            gg[i] = g[i00] * (1 - tx) * (1 - ty) + g[i10] * tx * (1 - ty) + g[i01] * (1 - tx) * ty + g[i11] * tx * ty
            bb[i] = b[i00] * (1 - tx) * (1 - ty) + b[i10] * tx * (1 - ty) + b[i01] * (1 - tx) * ty + b[i11] * tx * ty
        }
        System.arraycopy(rr, 0, r, 0, r.size); System.arraycopy(gg, 0, g, 0, g.size); System.arraycopy(bb, 0, b, 0, b.size)
    }

    private fun lensblur(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val fy = op.params["focusY"] ?: 0.5f
        val fw = op.params["focusW"] ?: 0.25f
        val fe = op.params["feather"] ?: 0.2f
        val am = op.params["amount"] ?: 0.5f
        val shr = FloatArray(r.size); val shg = FloatArray(r.size); val shb = FloatArray(r.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val x0 = (x - 1).coerceAtLeast(0); val x1 = (x + 1).coerceAtMost(w - 1)
            val y0 = (y - 1).coerceAtLeast(0); val y1 = (y + 1).coerceAtMost(h - 1)
            val c = i
            val l = y * w + x0; val rr2 = y * w + x1; val u = y0 * w + x; val d = y1 * w + x
            val ul = y0 * w + x0; val ur = y0 * w + x1; val dl = y1 * w + x0; val dr = y1 * w + x1
            shr[i] = r[c] * 0.2f + (r[l] + r[rr2]) * 0.16f + (r[u] + r[d]) * 0.12f + (r[ul] + r[ur] + r[dl] + r[dr]) * 0.06f
            shg[i] = g[c] * 0.2f + (g[l] + g[rr2]) * 0.16f + (g[u] + g[d]) * 0.12f + (g[ul] + g[ur] + g[dl] + g[dr]) * 0.06f
            shb[i] = b[c] * 0.2f + (b[l] + b[rr2]) * 0.16f + (b[u] + b[d]) * 0.12f + (b[ul] + b[ur] + b[dl] + b[dr]) * 0.06f
        }
        for (i in 0 until r.size) {
            val y = i / w
            val vv = (y + 0.5f) / h
            val bandp = smoothstep(fy - fw * (1f + fe) * 0.5f, fy - fw * 0.5f, vv) - smoothstep(fy + fw * 0.5f, fy + fw * (1f + fe) * 0.5f, vv)
            val ap = am * (1f - bandp) * 0.62f
            r[i] = r[i] + (shr[i] - r[i]) * ap
            g[i] = g[i] + (shg[i] - g[i]) * ap
            b[i] = b[i] + (shb[i] - b[i]) * ap
        }
    }

    private fun depthblur(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, depth: Bitmap?) {
        if (depth == null) return
        val am = (op.params["amount"] ?: 0f).coerceIn(0f, 1f)
        val fd = op.params["focusDepth"] ?: 0.5f
        val rg = op.params["range"] ?: 0.35f
        val bl = op.params["blades"] ?: 0f
        val dw = depth.width; val dh = depth.height
        val dp = IntArray(dw * dh); depth.getPixels(dp, 0, dw, 0, 0, dw, dh)
        val blur = FloatArray(w * h)
        var bradMax = 0f
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x * dw / w).coerceIn(0, dw - 1); val dy = (y * dh / h).coerceIn(0, dh - 1)
            val d = ((dp[dy * dw + dx] shr 16) and 0xFF) / 255f
            val blv = (abs(d - fd) / max(rg, 0.02f)).coerceIn(0f, 1f) * am
            blur[y * w + x] = blv
            bradMax = max(bradMax, blv * 0.024f * h * 0.5f)
        }
        if (bradMax <= 0.5f) return
        val nr = FloatArray(r.size); val ng = FloatArray(r.size); val nb = FloatArray(r.size)
        val n = w * h
        val nB = (bl + 0.5f).toInt().coerceAtLeast(0)
        for (i in 0 until n) {
            val blv = blur[i]
            val brad = blv * 0.024f * h * 0.5f
            var ar = r[i]; var ag = g[i]; var ab = b[i]
            var ws = 1f
            if (brad > 0.5f) {
                for (ri in 1..3) {
                    val f = ri / 3f
                    for (k in 0 until 12) {
                        val a = (k + 0.5f) / 12f * 6.2831853f + 0.26f
                        val dir = if (nB >= 3) {
                            val ang = a
                            val nf = nB.toFloat()
                            val aa = ang % (6.2831853f / nf)
                            cos(3.1415926f / nf) / max(cos(aa - 3.1415926f / nf), 0.2f)
                        } else 1f
                        val dx = cos(a) * dir * brad * f
                        val dy = sin(a) * dir * brad * f
                        val sx = (i % w) + dx; val sy = (i / w) + dy
                        if (sx < 0f || sy < 0f || sx > w - 1f || sy > h - 1f) continue
                        val si = sy.toInt() * w + sx.toInt()
                        ar += r[si]; ag += g[si]; ab += b[si]; ws += 1f
                    }
                }
            }
            nr[i] = ar / ws; ng[i] = ag / ws; nb[i] = ab / ws
        }
        System.arraycopy(nr, 0, r, 0, n); System.arraycopy(ng, 0, g, 0, n); System.arraycopy(nb, 0, b, 0, n)
    }

    private fun sharpen(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val in2 = op.params["intensity"] ?: 0f
        if (in2 <= 0f) return
        val shr = FloatArray(r.size); val shg = FloatArray(r.size); val shb = FloatArray(r.size)
        for (y in 0 until h) for (x in 0 until w) {
            val y0 = (y - 1).coerceAtLeast(0); val y1 = (y + 1).coerceAtMost(h - 1)
            val x0 = (x - 1).coerceAtLeast(0); val x1 = (x + 1).coerceAtMost(w - 1)
            val c = y * w + x
            val idxs = intArrayOf(
                y0 * w + x0, y0 * w + x, y0 * w + x1,
                y * w + x0, c, y * w + x1,
                y1 * w + x0, y1 * w + x, y1 * w + x1
            )
            shr[c] = r[idxs[4]] - (r[idxs[0]] + r[idxs[1]] + r[idxs[2]] + r[idxs[3]] - 8f * r[idxs[4]] + r[idxs[5]] + r[idxs[6]] + r[idxs[7]] + r[idxs[8]]) * in2
            shg[c] = g[idxs[4]] - (g[idxs[0]] + g[idxs[1]] + g[idxs[2]] + g[idxs[3]] - 8f * g[idxs[4]] + g[idxs[5]] + g[idxs[6]] + g[idxs[7]] + g[idxs[8]]) * in2
            shb[c] = b[idxs[4]] - (b[idxs[0]] + b[idxs[1]] + b[idxs[2]] + b[idxs[3]] - 8f * b[idxs[4]] + b[idxs[5]] + b[idxs[6]] + b[idxs[7]] + b[idxs[8]]) * in2
        }
        System.arraycopy(shr, 0, r, 0, r.size); System.arraycopy(shg, 0, g, 0, g.size); System.arraycopy(shb, 0, b, 0, b.size)
    }

    // ── Faithful CPU ports of FRAG_EDIT_DETAIL / EFFECTS / OPTICS / MARKUP so the
    //    GL→CPU fallback (OffscreenEditor returns null on any GL error) never drops
    //    the pro-editor passes. ──
    private fun detail(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val am = op.params["amount"] ?: 0f
        val rad = op.params["radius"] ?: 0f
        val det = op.params["detail"] ?: 0f
        val msk = op.params["masking"] ?: 0f
        val lumNR = op.params["lumNR"] ?: 0f
        val colNR = op.params["colorNR"] ?: 0f
        if (am == 0f && lumNR == 0f && colNR == 0f) return
        val kb = (1f + rad * 3.5f).toInt().coerceAtLeast(1)
        val nr = FloatArray(r.size); val ng = FloatArray(r.size); val nb = FloatArray(r.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val cr = r[i]; val cg = g[i]; val cb = b[i]
            val lca = lum(cr, cg, cb)
            val sLx = (x - kb).coerceIn(0, w - 1); val sRx = (x + kb).coerceIn(0, w - 1)
            val sUy = (y - kb).coerceIn(0, h - 1); val sDy = (y + kb).coerceIn(0, h - 1)
            val nLx = (x - 1).coerceIn(0, w - 1); val nRx = (x + 1).coerceIn(0, w - 1)
            val nUy = (y - 1).coerceIn(0, h - 1); val nDy = (y + 1).coerceIn(0, h - 1)
            val b2r = (cr + r[y * w + nLx] + r[y * w + nRx] + r[nUy * w + x] + r[nDy * w + x]) * 0.2f
            val b2g = (cg + g[y * w + nLx] + g[y * w + nRx] + g[nUy * w + x] + g[nDy * w + x]) * 0.2f
            val b2b = (cb + b[y * w + nLx] + b[y * w + nRx] + b[nUy * w + x] + b[nDy * w + x]) * 0.2f
            val bfr = (r[y * w + sLx] + r[y * w + sRx] + r[sUy * w + x] + r[sDy * w + x]) * 0.25f
            val bfg = (g[y * w + sLx] + g[y * w + sRx] + g[sUy * w + x] + g[sDy * w + x]) * 0.25f
            val bfb = (b[y * w + sLx] + b[y * w + sRx] + b[sUy * w + x] + b[sDy * w + x]) * 0.25f
            val lcb = lum(b2r, b2g, b2b)
            val edge = kotlin.math.abs(lca - lcb)
            var gate = (1.25f - edge / max(msk + 0.08f, 0.081f)).coerceIn(0f, 1f)
            gate = 1f - (1f - gate).pow(1.5f)
            val smallAmt = 0.45f + 0.55f * det.coerceIn(0f, 1f)
            var shr = cr + (cr - b2r) * am * 0.9f * smallAmt * gate
            var shg = cg + (cg - b2g) * am * 0.9f * smallAmt * gate
            var shb = cb + (cb - b2b) * am * 0.9f * smallAmt * gate
            shr += cr + (cr - bfr) * am * 0.4f * (1f - smallAmt) * gate
            shg += cg + (cg - bfg) * am * 0.4f * (1f - smallAmt) * gate
            shb += cb + (cb - bfb) * am * 0.4f * (1f - smallAmt) * gate
            val nrL = (1f - edge / max(lumNR + 0.06f, 0.061f)).coerceIn(0f, 1f)
            val fL = nrL * lumNR * 0.85f
            var orr = shr + (shr * (lcb / max(lca, 1e-4f)) - shr) * fL
            var org = shg + (shg * (lcb / max(lca, 1e-4f)) - shg) * fL
            var orb = shb + (shb * (lcb / max(lca, 1e-4f)) - shb) * fL
            val nrC = (1f - edge / max(colNR + 0.06f, 0.061f)).coerceIn(0f, 1f)
            val fC = nrC * colNR * 0.65f
            val lumo = lum(orr, org, orb)
            orr = orr + (lumo - orr) * fC
            org = org + (lumo - org) * fC
            orb = orb + (lumo - orb) * fC
            nr[i] = orr; ng[i] = org; nb[i] = orb
        }
        System.arraycopy(nr, 0, r, 0, r.size); System.arraycopy(ng, 0, g, 0, g.size); System.arraycopy(nb, 0, b, 0, b.size)
    }

    private fun effects(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val tx = op.params["text"] ?: 0f
        val cl = op.params["clarity"] ?: 0f
        val dh = op.params["dehaze"] ?: 0f
        if (tx == 0f && cl == 0f && dh == 0f) return
        val sr = r.copyOf(); val sg = g.copyOf(); val sb = b.copyOf()
        val off = (0.004f * w).toInt().coerceAtLeast(1)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val cr = sr[i]; val cg = sg[i]; val cb = sb[i]
            val l = lum(cr, cg, cb)
            val nLx = (x - 1).coerceIn(0, w - 1); val nRx = (x + 1).coerceIn(0, w - 1)
            val nUy = (y - 1).coerceIn(0, h - 1); val nDy = (y + 1).coerceIn(0, h - 1)
            val lb1 = (l + lum(sr[y * w + nLx], sg[y * w + nLx], sb[y * w + nLx]) + lum(sr[y * w + nRx], sg[y * w + nRx], sb[y * w + nRx])
                    + lum(sr[nUy * w + x], sg[nUy * w + x], sb[nUy * w + x]) + lum(sr[nDy * w + x], sg[nDy * w + x], sb[nDy * w + x])) * 0.25f
            val sLx = (x - off).coerceIn(0, w - 1); val sRx = (x + off).coerceIn(0, w - 1)
            val sUy = (y - off).coerceIn(0, h - 1); val sDy = (y + off).coerceIn(0, h - 1)
            val lb2 = (lum(sr[y * w + sLx], sg[y * w + sLx], sb[y * w + sLx]) + lum(sr[y * w + sRx], sg[y * w + sRx], sb[y * w + sRx])
                    + lum(sr[sUy * w + x], sg[sUy * w + x], sb[sUy * w + x]) + lum(sr[sDy * w + x], sg[sDy * w + x], sb[sDy * w + x])
                    + lum(sr[sUy * w + sLx], sg[sUy * w + sLx], sb[sUy * w + sLx]) + lum(sr[sUy * w + sRx], sg[sUy * w + sRx], sb[sUy * w + sRx])
                    + lum(sr[sDy * w + sLx], sg[sDy * w + sLx], sb[sDy * w + sLx]) + lum(sr[sDy * w + sRx], sg[sDy * w + sRx], sb[sDy * w + sRx])) * 0.125f
            var orr = cr * (1f + (lb1 - l) * tx * 1.6f)
            var org = cg * (1f + (lb1 - l) * tx * 1.6f)
            var orb = cb * (1f + (lb1 - l) * tx * 1.6f)
            val midt = 4f * l * (1f - l)
            orr *= (1f + (lb2 - l) * cl * 2.4f * midt)
            org *= (1f + (lb2 - l) * cl * 2.4f * midt)
            orb *= (1f + (lb2 - l) * cl * 2.4f * midt)
            val hz = smoothstep(0.18f, 0.85f, l)
            orr *= (1f + dh * 0.55f - hz * dh * 0.46f)
            org *= (1f + dh * 0.55f - hz * dh * 0.46f)
            orb *= (1f + dh * 0.55f - hz * dh * 0.46f)
            var ld = lum(orr, org, orb)
            ld = (ld - 0.5f) * (1f + dh * 0.6f) + 0.5f
            val m = (1f - dh * 0.62f).coerceIn(0f, 1f)
            orr = ld + (orr - ld) * m
            org = ld + (org - ld) * m
            orb = ld + (orb - ld) * m
            r[i] = orr.coerceIn(0f, 1f); g[i] = org.coerceIn(0f, 1f); b[i] = orb.coerceIn(0f, 1f)
        }
    }

    private fun optics(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val dist = op.params["distortion"] ?: 0f
        val ca = op.params["ca"] ?: 0f
        if (dist == 0f && ca == 0f) return
        val sr = r.copyOf(); val sg = g.copyOf(); val sb = b.copyOf()
        val nr = FloatArray(r.size); val ng = FloatArray(r.size); val nb = FloatArray(r.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val cx = (x + 0.5f) / w - 0.5f
            val cy = (y + 0.5f) / h - 0.5f
            val r2 = cx * cx + cy * cy
            val k = 1f + dist * r2 * 2.2f
            val uu = cx * k + 0.5f
            val vv = cy * k + 0.5f
            val rad = (uu - 0.5f) * (uu - 0.5f) + (vv - 0.5f) * (vv - 0.5f)
            val rad2 = rad * 2f + 1f
            val rr = 1f + ca * 0.012f * rad2
            val rb = 1f - ca * 0.012f * rad2
            nr[i] = bilin(sr, uu * rr * w, vv * h, w, h)
            ng[i] = bilin(sg, uu * w, vv * h, w, h)
            nb[i] = bilin(sb, uu * rb * w, vv * h, w, h)
        }
        System.arraycopy(nr, 0, r, 0, r.size); System.arraycopy(ng, 0, g, 0, g.size); System.arraycopy(nb, 0, b, 0, b.size)
    }

    // Kaleidoscope fold — mirrors FRAG_KALEIDO: sector-fold the polar angle and
    // re-sample the mirrored wedge; cross-faded with the original by intensity.
    private fun kaleido(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val slices = op.params["slices"] ?: 6f
        val inten = (op.params["intensity"] ?: 1f).coerceIn(0f, 1f)
        if (inten <= 0f) return
        val n = max(2f, kotlin.math.floor(slices))
        val sec = 2.0 * Math.PI / n
        val sr = r.copyOf(); val sg = g.copyOf(); val sb = b.copyOf()
        val nr = FloatArray(r.size); val ng = FloatArray(r.size); val nb = FloatArray(r.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val px = (x + 0.5f) / w - 0.5f
            val py = (y + 0.5f) / h - 0.5f
            val rr = sqrt(px * px + py * py)
            val a = kotlin.math.atan2(py.toDouble(), px.toDouble())
            val fa = a - sec * kotlin.math.floor(a / sec)
            val na = sec * 0.5 - abs(fa - sec * 0.5)
            val qx = (cos(na) * rr + 0.5).toFloat()
            val qy = (sin(na) * rr + 0.5).toFloat()
            val kr = bilin(sr, qx * w, qy * h, w, h)
            val kg = bilin(sg, qx * w, qy * h, w, h)
            val kb = bilin(sb, qx * w, qy * h, w, h)
            nr[i] = sr[i] + (kr - sr[i]) * inten
            ng[i] = sg[i] + (kg - sg[i]) * inten
            nb[i] = sb[i] + (kb - sb[i]) * inten
        }
        System.arraycopy(nr, 0, r, 0, r.size); System.arraycopy(ng, 0, g, 0, g.size); System.arraycopy(nb, 0, b, 0, b.size)
    }

    private fun markup(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val ov = op.overlay ?: return
        val mix = op.params["mix"] ?: 1f
        val mw = ov.width; val mh = ov.height
        val mp = IntArray(mw * mh); ov.getPixels(mp, 0, mw, 0, 0, mw, mh)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val mx = ((x + 0.5f) / w * mw).toInt().coerceIn(0, mw - 1)
            val my = ((y + 0.5f) / h * mh).toInt().coerceIn(0, mh - 1)
            val ip = my * mw + mx
            val ia = ((mp[ip] ushr 24) and 0xFF) / 255f
            val m2 = ia * mix
            r[i] = r[i] + (((mp[ip] shr 16 and 0xFF) / 255f) - r[i]) * m2
            g[i] = g[i] + (((mp[ip] shr 8 and 0xFF) / 255f) - g[i]) * m2
            b[i] = b[i] + (((mp[ip] and 0xFF) / 255f) - b[i]) * m2
        }
    }

    private fun bilin(src: FloatArray, px: Float, py: Float, w: Int, h: Int): Float {
        val fx = px - 0.5f; val fy = py - 0.5f
        val x0 = fx.toInt().coerceIn(0, w - 1); val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceIn(0, w - 1); val y1 = (y0 + 1).coerceIn(0, h - 1)
        val tx = (fx - x0).coerceIn(0f, 1f); val ty = (fy - y0).coerceIn(0f, 1f)
        val i00 = y0 * w + x0; val i10 = y0 * w + x1; val i01 = y1 * w + x0; val i11 = y1 * w + x1
        return src[i00] * (1 - tx) * (1 - ty) + src[i10] * tx * (1 - ty) + src[i01] * (1 - tx) * ty + src[i11] * tx * ty
    }

    private fun vhs(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val track = op.params["tracking"] ?: 0f
        val in2 = op.params["intensity"] ?: 0f
        val i = max(track, in2)
        val tb = 2.013f
        val no = i * 0.6f; val sc = i * 0.5f; val wob = i * 0.4f; val vig = i * 0.6f
        val cs = i * 0.5f; val hs = i * 0.8f; val bl = i * 0.3f; val cb2 = i * 0.8f
        fun hh(p0: Float, p1: Float): Float {
            var x = p0 * 443.9f + p1 * 397.3f
            x = x - Math.floor(x.toDouble()).toFloat()
            x = x + x * (x + 19.2f)
            x = x - Math.floor(x.toDouble()).toFloat()
            return (x * x) - Math.floor((x * x).toDouble()).toFloat()
        }
        fun luma(cr: Float, cg: Float, cb: Float) = cr * 0.299f + cg * 0.587f + cb * 0.114f
        fun iq2(cr: Float, cg: Float, cb: Float) = 0.596f * cr - 0.274f * cg - 0.322f * cb
        val nr = FloatArray(r.size); val ng = FloatArray(r.size); val nb = FloatArray(r.size)
        val sd = Math.floor((tb * 0.8f).toDouble()).toFloat()
        val on0 = if (hh(sd, 7.7f) > 0.55f) 1f else 0f
        val ep = Math.floor((tb / 1.8f).toDouble()).toFloat()
        val e0 = hh(ep, 1.3f)
        val onT = if (e0 > 0.5f) 1f else 0f
        val ph = tb * 0.85f + e0 * 3.7f
        val phf = ph - Math.floor(ph.toDouble()).toFloat()
        val life = (if (phf > 0.2f) 1f else 0f) * smoothstep(1f, 0.5f, phf)
        val useBl = if (bl > 0.01f) 1f else 0f
        val useC = if (cb2 > 0.01f) 1f else 0f
        for (y in 0 until h) for (x in 0 until w) {
            val i0 = y * w + x
            val ux = (x + 0.5f) / w; val uy = (y + 0.5f) / h
            var uu = ux; var vv = uy
            val jx = (hh(uy * 600f, sd * 3.1f) - 0.5f) * 0.0012f * wob * on0
            val jy = (hh(uy * 600f + 9f, sd * 3.1f) - 0.5f) * 0.0004f * wob * on0
            uu += jx; vv += jy
            val sy = 0.86f + e0 * 0.10f
            val sw2 = 0.012f + hh(ep, 9.9f) * 0.020f
            val band = smoothstep(sw2 * 2f, 0f, abs(vv - sy))
            val sp = hh(uu * 110f, e0 * 37f) * 2f - 1f
            uu += band * sp * 0.0012f * hs * life * onT
            val sy2 = 0.97f + 0.01f * sin(tb * 0.31f) + 0.006f * sin(tb * 1.7f)
            val band2 = exp(-((vv - sy2) * 160f).pow(2f))
            val s2v = (hh(uu * 240f, tb * 3f) * 2f - 1f) * 0.10f
            uu += band2 * s2v * hs
            val sh = cs * 0.012f; val jg = sin(tb * 0.7f + vv * 25f) * sh * 0.4f
            val offL = sh + jg; val offR = sh - jg * 0.4f
            val sxL = (((uu - offL) % 1f + 1f) % 1f * w).toInt().coerceIn(0, w - 1)
            val sxC = (((uu) % 1f + 1f) % 1f * w).toInt().coerceIn(0, w - 1)
            val sxR = (((uu + offR) % 1f + 1f) % 1f * w).toInt().coerceIn(0, w - 1)
            val syy = (((vv) % 1f + 1f) % 1f * h).toInt().coerceIn(0, h - 1)
            var cr = r[syy * w + sxL]; var cg = g[syy * w + sxC]; var ccb = b[syy * w + sxR]
            if (useC > 0f) {
                val d = cb2 * 0.005f
                val sxd = (((uu + d) % 1f + 1f) % 1f * w).toInt().coerceIn(0, w - 1)
                val sxn = (((uu - d) % 1f + 1f) % 1f * w).toInt().coerceIn(0, w - 1)
                val el = luma(cr, cg, ccb)
                val er = luma(r[syy * w + sxd], g[syy * w + sxd], b[syy * w + sxd])
                val el2 = luma(r[syy * w + sxn], g[syy * w + sxn], b[syy * w + sxn])
                val dr2 = abs(el - er); val dl2 = abs(el - el2)
                val src = if (dr2 > dl2) 1f else 0f
                val m = max(dr2, dl2) * 8f
                val mm = (if (m > 1f) 1f else m) * cb2 * 0.6f
                if (mm > 0f) {
                    cr = cr + (if (src > 0f) r[syy * w + sxd] else r[syy * w + sxn] - cr) * 0f + (if (src > 0f) r[syy * w + sxd] - cr else r[syy * w + sxn] - cr) * mm
                    cg = cg + (if (src > 0f) g[syy * w + sxd] - cg else g[syy * w + sxn] - cg) * mm
                    ccb = ccb + (if (src > 0f) b[syy * w + sxd] - ccb else b[syy * w + sxn] - ccb) * mm
                }
            }
            if (useBl > 0f || useC > 0f) {
                val o = max(cb2 * 0.02f, bl * 0.015f)
                val sv = luma(cr, cg, ccb)
                var ys = sv * (0.30f * useBl + 1f - useBl)
                var isv = iq2(cr, cg, ccb) * 0.22f * useC
                var qsv = (0.211f * cr - 0.523f * cg + 0.312f * ccb) * 0.22f * useC
                for (jj in 1..4) {
                    val fj = jj.toFloat()
                    val ax = (((uu + o * fj) % 1f + 1f) % 1f * w).toInt().coerceIn(0, w - 1)
                    val bx = (((uu - o * fj) % 1f + 1f) % 1f * w).toInt().coerceIn(0, w - 1)
                    val ia = syy * w + ax; val ib = syy * w + bx
                    val wL = 0.16f * useBl / (fj * 0.5f + 1f)
                    val wC = 0.14f * useC / (fj * 0.55f + 1f)
                    ys += wL * (luma(r[ia], g[ia], b[ia]) + luma(r[ib], g[ib], b[ib]))
                    isv += wC * (iq2(r[ia], g[ia], b[ia]) + iq2(r[ib], g[ib], b[ib]))
                    qsv += wC * (0.211f * r[ia] - 0.523f * g[ia] + 0.312f * b[ia] + 0.211f * r[ib] - 0.523f * g[ib] + 0.312f * b[ib])
                }
                val cn2 = (hh(vv * 80f, Math.floor((tb * 1.7f).toDouble()).toFloat()) - 0.5f) * 0.10f * cb2
                val iqq = isv + cn2 * 0.5f; val qqq = qsv + cn2 * 0.5f
                cr = (ys + 0.956f * iqq + 0.621f * qqq).coerceIn(0f, 1f)
                cg = (ys - 0.272f * iqq - 0.647f * qqq).coerceIn(0f, 1f)
                ccb = (ys - 1.106f * iqq + 1.703f * qqq).coerceIn(0f, 1f)
            }
            val rf = (hh(Math.floor((uu * 300f).toDouble()).toFloat(), Math.floor((vv * 300f).toDouble()).toFloat() + Math.floor((tb * 18f).toDouble()).toFloat()) - 0.5f) * 0.09f * no
            val l2 = Math.floor((vv * 300f).toDouble()).toFloat()
            val hp = hh(l2, Math.floor((tb * 0.15f).toDouble()).toFloat())
            val xpp = ((uu * 80f + hh(l2, 15f)) % 1f + 1f) % 1f
            val dp = (if (hp > 0.97f) track else 0f) * (if (abs(xpp - 0.5f) > 0.5f) 0.6f else 0f)
            val ssv = sin(vv * h * 3.14159f) * 0.5f + 0.5f
            val scan = 1f + (0.55f + 0.45f * ssv - 1f) * sc
            val vc2 = ((uu - 0.5f) * 1.6f).let { it * it } + ((vv - 0.5f) * 1.6f).let { it * it }
            val vig2 = 1f + (1f - vc2) * vig
            cr += rf + dp; cg += rf + dp; ccb += rf + dp
            cr *= scan * vig2; cg *= scan * vig2; ccb *= scan * vig2
            cr = (cr * 0.88f + 0.07f).let { it * 1.08f }.coerceIn(0f, 1f)
            cg = (cg * 0.88f + 0.07f).coerceIn(0f, 1f)
            ccb = (ccb * 0.88f + 0.07f).let { it * 0.92f }.coerceIn(0f, 1f)
            cr = max(cr, 0f).pow(0.88f).coerceIn(0f, 1f)
            cg = max(cg, 0f).pow(0.88f).coerceIn(0f, 1f)
            ccb = max(ccb, 0f).pow(0.88f).coerceIn(0f, 1f)
            val agc = 1f + 0.04f * sin(tb * 0.57f) + 0.025f * sin(tb * 1.13f)
            cr *= agc; cg *= agc; ccb *= agc
            if (i > 0.3f) {
                val q = (i - 0.3f) * 1.6f
                val st2 = 12f
                cr += (Math.floor((cr * st2 + 0.5f).toDouble()).toFloat() / st2 - cr) * q
                cg += (Math.floor((cg * st2 + 0.5f).toDouble()).toFloat() / st2 - cg) * q
                ccb += (Math.floor((ccb * st2 + 0.5f).toDouble()).toFloat() / st2 - ccb) * q
            }
            nr[i0] = cr.coerceIn(0f, 1f); ng[i0] = cg.coerceIn(0f, 1f); nb[i0] = ccb.coerceIn(0f, 1f)
        }
        System.arraycopy(nr, 0, r, 0, r.size); System.arraycopy(ng, 0, g, 0, g.size); System.arraycopy(nb, 0, b, 0, b.size)
    }

    private fun glitch(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val in2 = op.params["intensity"] ?: 0f
        if (in2 <= 0f) return
        val t = 2.013f
        fun h(n: Float): Float {
            val x = sin(n) * 43758.5453f
            return x - Math.floor(x.toDouble()).toFloat()
        }
        val nr = FloatArray(r.size); val ng = FloatArray(r.size); val nb = FloatArray(r.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i0 = y * w + x
            val bv = Math.floor(((y + 0.5f) / h * 40f + t * 5f).toDouble()).toFloat()
            val j = (h(bv) - 0.5f) * 0.05f * in2 * (if (h(bv * 1.37f) > 0.85f) 1f else 0f)
            val uj = (x + 0.5f) / w + j
            val sxr = (min(uj + 0.01f * in2, 1f) * w).toInt().coerceIn(0, w - 1)
            val sxb = (max(uj - 0.01f * in2, 0f) * w).toInt().coerceIn(0, w - 1)
            val sxc = (uj.coerceIn(0f, 1f) * w).toInt().coerceIn(0, w - 1)
            nr[i0] = r[y * w + sxr]; ng[i0] = g[y * w + sxc]; nb[i0] = b[y * w + sxb]
        }
        System.arraycopy(nr, 0, r, 0, r.size); System.arraycopy(ng, 0, g, 0, g.size); System.arraycopy(nb, 0, b, 0, b.size)
    }

    private fun starburst(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val in2 = op.params["intensity"] ?: 0f
        if (in2 <= 0f) return
        val apr = w.toFloat() / h
        for (y in 0 until h) for (x in 0 until w) {
            val i0 = y * w + x
            val ux = (x + 0.5f) / w * 2f - 1f
            val uy = (y + 0.5f) / h * 2f - 1f
            val uxx = ux * apr; val uyy = uy
            val rr2 = sqrt(uxx * uxx + uyy * uyy)
            val aa = Math.atan2(uyy.toDouble(), uxx.toDouble()).toFloat()
            val st = (1f - abs(sin((aa + 2.013f * 0.05f) * 8f * 0.5f))).pow(8f) * exp(-rr2 * 2f) * (1f - exp(-rr2 * 6f))
            val sr = (1f - abs(sin((aa + 2.013f * 0.03f) * 8f))).pow(12f) * 0.3f
            val hs2 = exp(-abs(uyy) * 30f) * exp(-abs(uxx) * 0.5f) * 0.15f
            val fl = (st + sr + hs2) * in2
            r[i0] = (r[i0] + fl).coerceIn(0f, 1f)
            g[i0] = (g[i0] + fl * 0.87f).coerceIn(0f, 1f)
            b[i0] = (b[i0] + fl * 0.53f).coerceIn(0f, 1f)
        }
    }

    private fun halftone(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val ds = op.params["dotSize"] ?: 7f
        val in2 = op.params["intensity"] ?: 0.75f
        val ang = op.params["angle"] ?: 0f
        val ca2 = cos(ang); val sa2 = sin(ang)
        for (y in 0 until h) for (x in 0 until w) {
            val i0 = y * w + x
            val ux = (x + 0.5f) / w; val uy = (y + 0.5f) / h
            val rx = (ca2 * ux - sa2 * uy) * w
            val ry = (sa2 * ux + ca2 * uy) * h
            val cx = Math.floor((rx / ds).toDouble()).toFloat() * ds + ds * 0.5f
            val cy = Math.floor((ry / ds).toDouble()).toFloat() * ds + ds * 0.5f
            val dist = sqrt((rx - cx) * (rx - cx) + (ry - cy) * (ry - cy)) / (ds * 0.5f)
            val lm = croc(r[i0], g[i0], b[i0])
            val dm = 1f - smoothstep(lm - 0.05f, lm + 0.05f, dist)
            val dc = if (dm > 0.5f) 0.05f else 0.95f
            r[i0] = (r[i0] + (dc - r[i0]) * in2).coerceIn(0f, 1f)
            g[i0] = (g[i0] + (dc - g[i0]) * in2).coerceIn(0f, 1f)
            b[i0] = (b[i0] + (dc - b[i0]) * in2).coerceIn(0f, 1f)
        }
    }

    private fun cmykDots(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val ds = op.params["dotSize"] ?: 6f
        val in2 = op.params["intensity"] ?: 0.75f
        fun dotC(ux: Float, uy: Float, ang: Float, cov: Float, sz: Float): Float {
            val ca2 = cos(ang); val sa2 = sin(ang)
            val rx = (ca2 * ux - sa2 * uy) * w
            val ry = (sa2 * ux + ca2 * uy) * h
            val cx = Math.floor((rx / sz).toDouble()).toFloat() * sz + sz * 0.5f
            val cy = Math.floor((ry / sz).toDouble()).toFloat() * sz + sz * 0.5f
            val dd = sqrt((rx - cx) * (rx - cx) + (ry - cy) * (ry - cy)) / (sz * 0.5f)
            val r2v = sqrt(cov.coerceIn(0f, 1f))
            return 1f - smoothstep(r2v - 0.02f, r2v + 0.02f, dd)
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i0 = y * w + x
            val ux = (x + 0.5f) / w; val uy = (y + 0.5f) / h
            val cr = r[i0]; val cg = g[i0]; val cb = b[i0]
            val k = 1f - max(max(cr, cg), cb)
            val ck = (1f - cr - k) / (1f - k + 1e-4f)
            val mk = (1f - cg - k) / (1f - k + 1e-4f)
            val yk = (1f - cb - k) / (1f - k + 1e-4f)
            val dc = dotC(ux, uy, 0.2618f, ck, ds)
            val dm = dotC(ux, uy, 1.308f, mk, ds)
            val dy = dotC(ux, uy, 0.7854f, yk, ds)
            val dk = dotC(ux, uy, 0f, k, ds)
            var or2 = (1f - dc) * (1f - dk)
            var og = (1f - dm) * (1f - dk)
            var ob = (1f - dy) * (1f - dk)
            or2 = or2.coerceIn(0f, 1f); og = og.coerceIn(0f, 1f); ob = ob.coerceIn(0f, 1f)
            r[i0] = (r[i0] + (or2 - r[i0]) * in2).coerceIn(0f, 1f)
            g[i0] = (g[i0] + (og - g[i0]) * in2).coerceIn(0f, 1f)
            b[i0] = (b[i0] + (ob - b[i0]) * in2).coerceIn(0f, 1f)
        }
    }

    private fun teletext(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val in2 = op.params["intensity"] ?: 0.6f
        val pal = arrayOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 1f), floatArrayOf(0f, 1f, 1f), floatArrayOf(1f, 1f, 1f)
        )
        val grx = 40f; val gry = 25f
        for (y in 0 until h) for (x in 0 until w) {
            val i0 = y * w + x
            val ux = (x + 0.5f) / w; val uy = (y + 0.5f) / h
            val cx = Math.floor((ux * grx).toDouble()).toFloat()
            val cy = Math.floor((uy * gry).toDouble()).toFloat()
            val cuvx = ux * grx - cx; val cuvy = uy * gry - cy
            val sx0 = ((cx + 0.5f) / grx * w).toInt().coerceIn(0, w - 1)
            val sy0 = ((cy + 0.5f) / gry * h).toInt().coerceIn(0, h - 1)
            val cc = sy0 * w + sx0
            var bd = 1e3f; var bi = 0
            for (pi in 0 until 8) {
                val dr2 = r[cc] - pal[pi][0]; val dg = g[cc] - pal[pi][1]; val db = b[cc] - pal[pi][2]
                val d2 = dr2 * dr2 + dg * dg + db * db
                if (d2 < bd) { bd = d2; bi = pi }
            }
            val sxx = Math.floor((cuvx * 2f).toDouble()).toFloat()
            val sxy = Math.floor((cuvy * 3f).toDouble()).toFloat()
            val dn = (cx + sxx) * 12.9898f + (cy + sxy) * 78.233f
            val hn = sin(dn) * 43758.5453f
            val fn = hn - Math.floor(hn.toDouble()).toFloat()
            val on = if (fn > 0.5f) 1f else 0f
            val cr = pal[bi][0] * on; val cg = pal[bi][1] * on; val cb = pal[bi][2] * on
            r[i0] = (r[i0] + (cr - r[i0]) * in2).coerceIn(0f, 1f)
            g[i0] = (g[i0] + (cg - g[i0]) * in2).coerceIn(0f, 1f)
            b[i0] = (b[i0] + (cb - b[i0]) * in2).coerceIn(0f, 1f)
        }
    }

    private fun terminal(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val in2 = op.params["intensity"] ?: 0.6f
        val hue = op.params["color"] ?: 0.5f
        val t = 2.013f
        val C = 72f; val R = 40f
        val F = intArrayOf(
            1337, 35017, 23112, 23093, 35593, 31277, 31277, 23057, 35593, 31277,
            23281, 36009, 13073, 34961, 31277, 23057,
            0, 2, 2112, 5376, 448, 7, 455
        )
        fun hh(p0: Float, p1: Float): Float {
            val dn = p0 * 12.9898f + p1 * 78.233f
            val x = sin(dn) * 43758.5453f
            return x - Math.floor(x.toDouble()).toFloat()
        }
        fun pal(hh: Float): FloatArray = when {
            hh < 0.2f -> floatArrayOf(0.2f, 1f, 0.2f)
            hh < 0.4f -> floatArrayOf(1f, 0.69f, 0f)
            hh < 0.6f -> floatArrayOf(0.75f, 0.75f, 0.75f)
            hh < 0.8f -> floatArrayOf(0f, 1f, 1f)
            else -> floatArrayOf(1f, 0.2f, 0.2f)
        }
        fun fmodF(a: Float, b: Float): Float {
            val q = a / b
            return a - b * Math.floor(q.toDouble()).toFloat()
        }
        fun imodF(a: Float, m: Int): Int = fmodF(a, m.toFloat()).toInt()
        fun chr(c: Int, row: Int, t2: Float): Int {
            val sc = Math.floor((t2 / 5f).toDouble()).toFloat().toInt()
            val ro = row - (fmodF(t2 * 4f, 24f).toInt())
            if (ro < 0 || ro >= R.toInt()) return 16
            if (ro < 4) {
                if (c < 9) {
                    if (c < 5) return 16
                    val d = intArrayOf(3, 0, 0, 0, 3, 3, 4, 5, 15, 6, 13, 12)
                    return d[imodF((c + sc).toFloat(), 12)]
                }
                if (c < 12) return 16
                return if (imodF((ro * 99 + c).toFloat(), 36) < 16) imodF((ro * 99 + c).toFloat(), 36) else 16
            }
            if (ro < 8) {
                val hh2 = imodF((ro * 73 + c * 11 + sc * 37).toFloat(), 36)
                return if (hh2 < 16) hh2 else 16
            }
            if (ro < 12) {
                if (c < 3) return 16
                val hh2 = imodF((ro * 51 + c * 7 + sc * 13).toFloat(), 20) + 16
                return if (hh2 < 23) hh2 else 16
            }
            if (ro < 16) {
                if (c < 4) return 17
                if (c > 10 && c < 14) return 16
                val hh2 = imodF((ro * 63 + c * 17 + sc * 53).toFloat(), 20) + 16
                return if (hh2 < 23) hh2 else 16
            }
            if (ro < 20) {
                if (c < 8) return 16
                val a = intArrayOf(3, 3, 4, 5, 15, 6, 13, 12, 14, 0)
                val bi = (c - 8) / 6
                return if (bi < 10) a[bi] else 16
            }
            if (ro < 24) {
                if (c == 0) return 17
                if (c == 1) return 16
                val a = intArrayOf(3, 0, 5, 5, 8, 9)
                val bi = (c - 2) / 12
                return if (bi < 6) a[bi] else 16
            }
            if (ro < 28) {
                val hh2 = imodF((ro * 47 + c * 23 + sc * 19).toFloat(), 26)
                return if (hh2 < 10) hh2 else 16
            }
            if (ro < 32) {
                val hh2 = imodF((ro * 31 + c * 13 + sc * 67).toFloat(), 26)
                return if (hh2 < 10) 10 + hh2 else 16
            }
            if (ro < 36) {
                val hh2 = imodF((ro * 43 + c * 29 + sc * 41).toFloat(), 20) + 16
                return if (hh2 < 23) hh2 else 16
            }
            val cu = fmodF(t2 * 3f, C * R / 3f).toInt()
            if (c == ((cu % C.toInt()) + C.toInt()) % C.toInt() && row == R.toInt() - 1 && (t2 * 3f) - Math.floor((t2 * 3f).toDouble()).toFloat() > 0.3f) return 20
            return 16
        }
        val fg = pal(hue)
        for (y in 0 until h) for (x in 0 until w) {
            val i0 = y * w + x
            val clx = Math.floor(((x + 0.5f) / w * C).toDouble()).toFloat().toInt()
            val cly = Math.floor(((y + 0.5f) / h * R).toDouble()).toFloat().toInt()
            val ci = chr(clx.coerceIn(0, C.toInt() - 1), cly.coerceIn(0, R.toInt() - 1), t)
            val ma = if (ci in 0..22) F[ci] else 0
            val cuvx = (x + 0.5f) / w * C - clx; val cuvy = (y + 0.5f) / h * R - cly
            val sxx = Math.floor((cuvx * 3f).toDouble()).toFloat().toInt().coerceIn(0, 2)
            val sxy = Math.floor((cuvy * 5f).toDouble()).toFloat().toInt().coerceIn(0, 4)
            val bi = sxy * 3 + sxx
            val bv = (ma shr bi) and 1
            val sc2 = if (sxy == 4) 1f else 0.85f + 0.15f * sin(cly * 3.14159f)
            val fl2 = 1f + 0.03f * sin(t * 23f + clx * 7f + cly * 13f)
            val sl = sin((y + 0.5f) / h * h * 3.14159f) * 0.25f + 0.75f
            val gl = bv * fg[0] * 0.2f
            val bx = fg[2] * 0.05f
            val ocr = if (bv == 1) fg[0] * sc2 * fl2 + gl else bx
            val ocg = if (bv == 1) fg[1] * sc2 * fl2 + gl else bx
            val ocb = if (bv == 1) fg[2] * sc2 * fl2 + gl else bx
            r[i0] = (r[i0] + (ocr - r[i0]) * in2).coerceIn(0f, 1f)
            g[i0] = (g[i0] + (ocg - g[i0]) * in2).coerceIn(0f, 1f)
            b[i0] = (b[i0] + (ocb - b[i0]) * in2).coerceIn(0f, 1f)
        }
    }

    private fun infrared(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val in2 = op.params["intensity"] ?: 0.8f
        for (i in 0 until n) {
            val rr2 = (r[i] * 1.25f + g[i] * 0.28f + b[i] * 0.05f).coerceIn(0f, 1f)
            val gg = (g[i] * 0.9f + 0.04f).coerceIn(0f, 1f)
            val bb = (b[i] * 0.55f + 0.06f).coerceIn(0f, 1f)
            val l = rr2 * 0.299f + gg * 0.587f + bb * 0.114f
            val cl = ((l - 0.5f) * 1.3f + 0.5f).coerceIn(0f, 1f)
            val nr2 = (cl + (rr2 - cl) * 1.6f) * (1f + 0.06f * in2)
            val ng2 = cl + (gg - cl) * 1.2f
            val nb2 = (cl + (bb - cl) * 1.05f) * (1f - 0.08f * in2)
            r[i] = (r[i] + (nr2.coerceIn(0f, 1f) - r[i]) * in2).coerceIn(0f, 1f)
            g[i] = (g[i] + (ng2.coerceIn(0f, 1f) - g[i]) * in2).coerceIn(0f, 1f)
            b[i] = (b[i] + (nb2.coerceIn(0f, 1f) - b[i]) * in2).coerceIn(0f, 1f)
        }
    }

    private fun croc(cr: Float, cg: Float, cb: Float) = cr * 0.299f + cg * 0.587f + cb * 0.114f

    private fun vignette(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val rad = op.params["radius"] ?: 0.55f
        val soft = op.params["softness"] ?: 0.4f
        val in2 = op.params["intensity"] ?: 0f
        if (in2 <= 0f) return
        val ar = w.toFloat() / h
        for (y in 0 until h) for (x in 0 until w) {
            val cx = (x + 0.5f) / w - 0.5f
            val cy = (y + 0.5f) / h - 0.5f
            val d = sqrt(cx * cx * ar * ar + cy * cy)
            val vig = 1f - smoothstep(rad, rad + soft, d) * in2
            val i = y * w + x
            r[i] = (r[i] * vig).coerceIn(0f, 1f); g[i] = (g[i] * vig).coerceIn(0f, 1f); b[i] = (b[i] * vig).coerceIn(0f, 1f)
        }
    }

    private fun grain(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int, w: Int, h: Int) {
        val in2 = op.params["intensity"] ?: 0f
        val sz = op.params["uSize"] ?: op.params["size"] ?: 5f
        val seed = op.params["uFrameSeed"] ?: 7f
        for (i in 0 until n) {
            val x = i % w; val y = i / w
            val l = lum(r[i], g[i], b[i])
            val wt = (1f - abs(l - 0.5f) * 1.8f).coerceIn(0.15f, 1f)
            val v = (x + 0.5f) / sz; val u = (y + 0.5f) / sz
            var hv = (v * 0.06711056f + u * 0.00583715f)
            hv = hv - Math.floor(hv.toDouble()).toFloat()
            val nn = (hv * 52.9829189f) - Math.floor((hv * 52.9829189f).toDouble()).toFloat()
            val noise = nn - 0.5f
            val a = noise * 0.12f * in2 * wt
            r[i] = (r[i] + a).coerceIn(0f, 1f); g[i] = (g[i] + a).coerceIn(0f, 1f); b[i] = (b[i] + a).coerceIn(0f, 1f)
        }
    }

    private fun tilt(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val fy = op.params["focusY"] ?: 0.5f
        val fw = op.params["width"] ?: 0.25f
        val fe = op.params["feather"] ?: 0.2f
        val shr = FloatArray(r.size); val shg = FloatArray(r.size); val shb = FloatArray(r.size)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = (x - 1).coerceAtLeast(0); val x1 = (x + 1).coerceAtMost(w - 1)
            val y0 = (y - 1).coerceAtLeast(0); val y1 = (y + 1).coerceAtMost(h - 1)
            val i = y * w + x
            shr[i] = r[y0 * w + x] * 0.2f + r[i] * 0.6f + r[y1 * w + x] * 0.2f
            shg[i] = g[y0 * w + x] * 0.2f + g[i] * 0.6f + g[y1 * w + x] * 0.2f
            shb[i] = b[y0 * w + x] * 0.2f + b[i] * 0.6f + b[y1 * w + x] * 0.2f
        }
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            val band = smoothstep(fw, fw + fe, abs(v - fy))
            for (x in 0 until w) {
                val i = y * w + x
                r[i] = r[i] + (shr[i] - r[i]) * band
                g[i] = g[i] + (shg[i] - g[i]) * band
                b[i] = b[i] + (shb[i] - b[i]) * band
            }
        }
    }

    private fun streak(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val v = op.params["intensity"] ?: 0f
        if (v <= 0f) return
        // CPU port of KinoStreak (MIT, Keijiro Takahashi) — same pyramid as the GL path:
        // prefilter (vertical 2-tap + threshold) into half height, halve width per level
        // with the 6-tap horizontal box (hscale 1.25), upsample-combine with
        // lerp(fine, coarse, stretch), additive composite streak*color*intensity*5.
        val threshold = op.params["threshold"] ?: 1.0f
        val intensity = op.params["intensity"] ?: (0.2f + v * 0.35f)
        val stretch = op.params["stretch"] ?: 0.75f
        val hh = (h / 2).coerceAtLeast(2)
        val widths = ArrayList<Int>()
        var lw = w
        widths.add(lw)
        while (lw > 16) { lw /= 2; widths.add(lw) }
        val n = widths.size
        val levR = Array(n) { FloatArray(widths[it] * hh) }
        val levG = Array(n) { FloatArray(widths[it] * hh) }
        val levB = Array(n) { FloatArray(widths[it] * hh) }
        // 1. Prefilter: vertical 2-tap + selective HDR headroom gain (only
        // highlights > 0.5 boosted up to 9x like the repo's HDR emitters,
        // midtones stay 1x) + threshold
        val dy = 0.75f / h
        val wl0 = widths[0]
        for (j in 0 until hh) for (x in 0 until wl0) {
            val cv = (j + 0.5f) / hh
            val y0 = ((cv - dy) * h).toInt().coerceIn(0, h - 1)
            val y1 = ((cv + dy) * h).toInt().coerceIn(0, h - 1)
            val i0 = y0 * w + x; val i1 = y1 * w + x
            val cr = (r[i0] + r[i1]) * 0.5f; val cg = (g[i0] + g[i1]) * 0.5f; val cb = (b[i0] + b[i1]) * 0.5f
            val br = max(cr, max(cg, cb))
            val t = ((br - 0.5f) / 0.5f).coerceIn(0f, 1f)
            val g = 1f + 8f * t * t * (3f - 2f * t)
            val gr = cr * g; val gg = cg * g; val gb = cb * g
            val gbr = max(gr, max(gg, gb))
            val k = if (gbr > threshold) (gbr - threshold) / max(gbr, 1e-5f) else 0f
            levR[0][j * wl0 + x] = gr * k; levG[0][j * wl0 + x] = gg * k; levB[0][j * wl0 + x] = gb * k
        }
        // 2. Downsample: 6-tap horizontal box (hscale 1.25), width halved per level
        for (i in 1 until n) {
            val wi = widths[i]; val wp = widths[i - 1]; val dx = 1.25f / wp
            for (j in 0 until hh) for (x in 0 until wi) {
                val u = (x + 0.5f) / wi
                var sr = 0f; var sg = 0f; var sb = 0f
                for (k in -5..5 step 2) {
                    val s = sampleL(levR[i - 1], levG[i - 1], levB[i - 1], wp, hh, u + k * dx, (j + 0.5f) / hh)
                    sr += s.r; sg += s.g; sb += s.b
                }
                val o = j * wi + x
                levR[i][o] = sr / 6f; levG[i][o] = sg / 6f; levB[i][o] = sb / 6f
            }
        }
        // 3. Upsample-combine: lerp(fine, coarse, stretch), coarse stretched up
        var cR = levR[n - 1]; var cG = levG[n - 1]; var cB = levB[n - 1]; var cw = widths[n - 1]
        for (i in n - 2 downTo 0) {
            val wi = widths[i]
            val nr = FloatArray(wi * hh); val ng = FloatArray(wi * hh); val nb = FloatArray(wi * hh)
            for (j in 0 until hh) for (x in 0 until wi) {
                val u = (x + 0.5f) / wi; val vv = (j + 0.5f) / hh
                val crs = sampleL(cR, cG, cB, cw, hh, u, vv)
                val o = j * wi + x
                nr[o] = levR[i][o] + (crs.r - levR[i][o]) * stretch
                ng[o] = levG[i][o] + (crs.g - levG[i][o]) * stretch
                nb[o] = levB[i][o] + (crs.b - levB[i][o]) * stretch
            }
            cR = nr; cG = ng; cB = nb; cw = wi
        }
        // 4. Composite: + bilinear-stretch streak * (0.55, 0.55, 1.0) * intensity * 5
        val kR = 0.55f * intensity * 5f; val kG = 0.55f * intensity * 5f; val kB = 1f * intensity * 5f
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val s = sampleL(cR, cG, cB, wl0, hh, (x + 0.5f) / w, (y + 0.5f) / h)
            r[i] = (r[i] + s.r * kR).coerceIn(0f, 1f)
            g[i] = (g[i] + s.g * kG).coerceIn(0f, 1f)
            b[i] = (b[i] + s.b * kB).coerceIn(0f, 1f)
        }
    }

    private data class LSample(val r: Float, val g: Float, val b: Float)

    private fun sampleL(bufR: FloatArray, bufG: FloatArray, bufB: FloatArray, wl: Int, bh: Int, u: Float, v: Float): LSample {
        val x = u * wl - 0.5f; val y = v * bh - 0.5f
        val x0 = x.toInt().coerceIn(0, wl - 1); val y0 = y.toInt().coerceIn(0, bh - 1)
        val x1 = (x0 + 1).coerceAtMost(wl - 1); val y1 = (y0 + 1).coerceAtMost(bh - 1)
        val fx = (x - x0).coerceIn(0f, 1f); val fy = (y - y0).coerceIn(0f, 1f)
        val a = y0 * wl + x0; val b = y0 * wl + x1; val c = y1 * wl + x0; val d = y1 * wl + x1
        val w00 = (1f - fx) * (1f - fy); val w10 = fx * (1f - fy); val w01 = (1f - fx) * fy; val w11 = fx * fy
        return LSample(
            bufR[a] * w00 + bufR[b] * w10 + bufR[c] * w01 + bufR[d] * w11,
            bufG[a] * w00 + bufG[b] * w10 + bufG[c] * w01 + bufG[d] * w11,
            bufB[a] * w00 + bufB[b] * w10 + bufB[c] * w01 + bufB[d] * w11
        )
    }

    private fun frame(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val ov = op.overlay ?: return
        val fw = ov.width; val fh = ov.height
        val fp = IntArray(fw * fh); ov.getPixels(fp, 0, fw, 0, 0, fw, fh)
        val mix = op.params["mix"] ?: 1f
        for (y in 0 until h) for (x in 0 until w) {
            val sx = (x * fw / w).coerceIn(0, fw - 1); val sy = (y * fh / h).coerceIn(0, fh - 1)
            val c = fp[sy * fw + sx]
            val fa = (((c ushr 24) and 0xFF) / 255f) * mix
            if (fa <= 0f) continue
            val i = y * w + x
            val fr = (c shr 16 and 0xFF) / 255f; val fg2 = (c shr 8 and 0xFF) / 255f; val fb = (c and 0xFF) / 255f
            r[i] = r[i] + (fr - r[i]) * fa; g[i] = g[i] + (fg2 - g[i]) * fa; b[i] = b[i] + (fb - b[i]) * fa
        }
    }

    private fun blend(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val mix = op.params["mix"] ?: 1f
        for (i in r.indices) { r[i] *= mix; g[i] *= mix; b[i] *= mix }
    }

    private fun bw(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        for (i in 0 until n) { val l = lum(r[i], g[i], b[i]); r[i] = l; g[i] = l; b[i] = l }
    }

    private fun duotone(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val in2 = op.params["intensity"] ?: 1f
        for (i in 0 until n) {
            val l = lum(r[i], g[i], b[i])
            val shR = 0.08f + (1f - l) * 1.0f * 0f
            val hR = 0.08f * (1f - l) + 1f * l
            val tr = 1f * l + 0.08f * (1f - l)
            val tg = 0.88f * l + 0.04f * (1f - l)
            val tb = 0.62f * l + 0.14f * (1f - l)
            r[i] = r[i] + (tr - r[i]) * in2; g[i] = g[i] + (tg - g[i]) * in2; b[i] = b[i] + (tb - b[i]) * in2
        }
    }

    private fun colorShift(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val in2 = op.params["intensity"] ?: 0f
        for (i in 0 until n) {
            val cr = r[i].pow(1f - in2 * 0.15f)
            val cg = g[i].pow(1f + in2 * 0.05f)
            val cb = b[i].pow(1f + in2 * 0.1f)
            val l = r[i] * 0.299f + g[i] * 0.587f + b[i] * 0.114f
            val m = (in2 * 0.5f).coerceIn(0f, 1f)
            var mr = cr + (l - cr) * 0.3f; var mg = cg + (l - cg) * 0.3f; var mb = cb + (l - cb) * 0.3f
            r[i] = r[i] + (mr - r[i]) * m; g[i] = g[i] + (mg - g[i]) * m; b[i] = b[i] + (mb - b[i]) * m
        }
    }

    private fun instant(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val in2 = op.params["intensity"] ?: 0f
        for (i in 0 until n) {
            val cr = r[i].pow(0.85f) * 0.95f
            val cg = g[i].pow(0.9f) * 0.98f
            val cb = b[i].pow(0.95f) * 1.05f
            r[i] = r[i] + (cr - r[i]) * in2; g[i] = g[i] + (cg - g[i]) * in2; b[i] = b[i] + (cb - b[i]) * in2
        }
    }

    private fun retro(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val in2 = op.params["intensity"] ?: 0f
        for (i in 0 until n) {
            var cr = r[i].pow(0.8f) * 1.1f
            var cg = g[i].pow(0.85f) * 0.95f
            var cb = b[i].pow(0.9f) * 0.85f
            val l = cr * 0.299f + cg * 0.587f + cb * 0.114f
            cr = cr + (l * 1.05f - cr) * in2 * 0.3f
            cg = cg + (l * 1.05f - cg) * in2 * 0.3f
            cb = cb + (l * 1.05f - cb) * in2 * 0.3f
            r[i] = r[i] + (cr - r[i]) * in2; g[i] = g[i] + (cg - g[i]) * in2; b[i] = b[i] + (cb - b[i]) * in2
        }
    }

    private fun velvia(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val in2 = op.params["intensity"] ?: 1f
        for (i in 0 until n) {
            val l = lum(r[i], g[i], b[i])
            val cl = ((l - 0.5f) * 1.18f + 0.5f).coerceIn(0f, 1f)
            val s = (1f + 0.45f * in2).coerceIn(0f, 3f)
            var cr = cl + (r[i] - cl) * s; var cg = cl + (g[i] - cl) * s; var cb = cl + (b[i] - cl) * s
            cr = (cr * (1f + 0.05f * in2)).coerceIn(0f, 1f); cb = (cb * (1f - 0.06f * in2)).coerceIn(0f, 1f)
            r[i] = cr; g[i] = cg.coerceIn(0f, 1f); b[i] = cb
        }
    }

    private fun bleach(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val in2 = op.params["intensity"] ?: 0.8f
        for (i in 0 until n) {
            val l = lum(r[i], g[i], b[i])
            val lift = (l * 0.1f + 0.06f) * in2
            r[i] = (r[i] * (1f - in2 * 0.25f) + lift).coerceIn(0f, 1f)
            g[i] = (g[i] * (1f - in2 * 0.25f) + lift).coerceIn(0f, 1f)
            b[i] = (b[i] * (1f - in2 * 0.25f) + lift).coerceIn(0f, 1f)
        }
    }

    private fun winter(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val in2 = op.params["intensity"] ?: 0.8f
        for (i in 0 until n) {
            val l = lum(r[i], g[i], b[i])
            var cr = r[i] * (1f - 0.08f * in2); var cg = g[i] * (1f + 0.02f * in2); var cb = b[i] * (1f + 0.12f * in2)
            val s = (1f + 0.1f * in2)
            cr = l + (cr - l) * s; cg = l + (cg - l) * s; cb = l + (cb - l) * s
            r[i] = cr.coerceIn(0f, 1f); g[i] = cg.coerceIn(0f, 1f); b[i] = cb.coerceIn(0f, 1f)
        }
    }

    private fun portra(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val in2 = op.params["intensity"] ?: 1f
        for (i in 0 until n) {
            val l = lum(r[i], g[i], b[i])
            val cl = ((l - 0.5f) * 1.09f + 0.5f).coerceIn(0f, 1f)
            val s = (1f - 0.08f * in2)
            var cr = cl + (r[i] - cl) * s; var cg = cl + (g[i] - cl) * s; var cb = cl + (b[i] - cl) * s
            cr = (cr * (1f + 0.05f * in2)).coerceIn(0f, 1f); cb = (cb * (1f - 0.03f * in2)).coerceIn(0f, 1f)
            r[i] = cr; g[i] = cg.coerceIn(0f, 1f); b[i] = cb
        }
    }

    private fun obsidian(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val in2 = op.params["intensity"] ?: 0.8f
        for (i in 0 until n) {
            var l = lum(r[i], g[i], b[i])
            l = l.pow(0.72f).coerceIn(0f, 1f)
            val s = (1f + 0.25f * in2)
            r[i] = (l + (r[i] - l) * s * 0.2f).coerceIn(0f, 1f)
            g[i] = (l + (g[i] - l) * s * 0.2f).coerceIn(0f, 1f)
            b[i] = (l + (b[i] - l) * s * 0.2f).coerceIn(0f, 1f)
        }
    }

    private fun falseColor(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val in2 = op.params["intensity"] ?: 0.7f
        for (i in 0 until n) {
            val l = lum(r[i], g[i], b[i])
            val hue = l * 0.9f
            var cr = (sin(hue * 6.283f) * 0.5f + 0.5f).pow(1.5f)
            var cg = (sin(hue * 6.283f + 2.094f) * 0.5f + 0.5f).pow(1.5f)
            var cb = (sin(hue * 6.283f + 4.188f) * 0.5f + 0.5f).pow(1.5f)
            r[i] = (r[i] + (cr - r[i]) * in2).coerceIn(0f, 1f)
            g[i] = (g[i] + (cg - g[i]) * in2).coerceIn(0f, 1f)
            b[i] = (b[i] + (cb - b[i]) * in2).coerceIn(0f, 1f)
        }
    }

    private fun cross(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val in2 = op.params["intensity"] ?: 0f
        for (i in 0 until n) {
            r[i] = (r[i] + (1f - r[i]) * in2 * 0.15f).coerceIn(0f, 1f)
            g[i] = (g[i] + (1f - g[i]) * in2 * 0.15f).coerceIn(0f, 1f)
            b[i] = (b[i] + (1f - b[i]) * in2 * 0.15f).coerceIn(0f, 1f)
        }
    }

    private fun fatPixel(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val sz = (op.params["size"] ?: 6f).toInt().coerceAtLeast(1)
        val nr = FloatArray(r.size); val ng = FloatArray(r.size); val nb = FloatArray(r.size)
        for (y in 0 until h) for (x in 0 until w) {
            val bx = (x / sz) * sz; val by = (y / sz) * sz
            val i = y * w + x
            nr[i] = r[by * w + bx]; ng[i] = g[by * w + bx]; nb[i] = b[by * w + bx]
        }
        System.arraycopy(nr, 0, r, 0, r.size); System.arraycopy(ng, 0, g, 0, g.size); System.arraycopy(nb, 0, b, 0, b.size)
    }

    private fun oneBit(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, n: Int) {
        val sc = op.params["scale"] ?: 3f
        val th = 0.5f + (1f / (sc * 3f)).coerceAtMost(0.12f) * 0f
        for (i in 0 until n) {
            val l = lum(r[i], g[i], b[i])
            val v = if (l > 0.5f) 1f else 0f
            r[i] = v; g[i] = v; b[i] = v
        }
    }

    private fun dust(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val in2 = op.params["intensity"] ?: 0f
        val seed = op.params["uSeed"] ?: 13f
        for (y in 0 until h) for (x in 0 until w) {
            val cx = (x / 60).toFloat(); val cy = (y / 90).toFloat()
            var hx = (cx * 41.3f + cy * 289.1f + seed)
            hx = hx - Math.floor(hx.toDouble()).toFloat()
            var hy = hx * 43758.5453f
            hy = hy - Math.floor(hy.toDouble()).toFloat()
            val d = if (hy > 0.997f) hy * 0.6f + 0.4f else 0f
            if (d > 0f) {
                val i = y * w + x
                r[i] = (r[i] + (1f - r[i]) * d * 0.7f * in2).coerceIn(0f, 1f)
                g[i] = (g[i] + (1f - g[i]) * d * 0.7f * in2).coerceIn(0f, 1f)
                b[i] = (b[i] + (1f - b[i]) * d * 0.7f * in2).coerceIn(0f, 1f)
            }
        }
    }

    private fun crt(op: EditorOp, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val in2 = op.params["intensity"] ?: 0f
        for (y in 0 until h) {
            val scan = if (y % 3 == 0) 0.85f else 0.98f
            val off = if (y % 4 == 0) 0.004f * in2 else 0f
            for (x in 0 until w) {
                val i = y * w + x
                val xo = (x + off * w).toInt().coerceIn(0, w - 1)
                val ii = y * w + xo
                r[i] = (r[ii] * scan).coerceIn(0f, 1f)
                g[i] = (g[ii] * scan * (1f - 0.06f * in2)).coerceIn(0f, 1f)
                b[i] = (b[ii] * scan * (1f - 0.10f * in2)).coerceIn(0f, 1f)
            }
        }
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}