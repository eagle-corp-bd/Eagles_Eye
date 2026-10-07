package com.eagleseye.camera.editor

import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal JSON-backed recipe codec for the gallery editor.
 *
 * A recipe is a plain JSON document capturing the ENTIRE non-destructive edit
 * state (global adjustments, curves, masks, markup, geometry, film, frames).
 * Recipes round-trip through the system clipboard or any text channel, so a
 * look can be copied between devices / sessions without any binary blob.
 *
 * Document shape (v1):
 * {
 *   "v": 1, "name": "...", "app": "EaglesEye",
 *   "tune":    {"b":..,"c":..,"s":..,"warmth":..,"highlights":..,"shadows":..,"amb":..,
 *               "exposure":..,"whites":..,"blacks":..,"vibrance":..},
 *   "wb":      {"temp":..,"tint":..},
 *   "hsl":     {"h":[8],"s":[8],"l":[8]},
 *   "split":   {"shR":..,"shG":..,"shB":..,"midR":..,"midG":..,"midB":..,
 *               "hiR":..,"hiG":..,"hiB":..,"balance":..,"strength":..},
 *   "detail":  {"amount":..,"radius":..,"detail":..,"masking":..,"lum":..,"color":..},
 *   "fxl":     {"text":..,"clarity":..,"dehaze":..},
 *   "optics":  {"distortion":..,"ca":..},
 *   "sharp": .., "grain": .., "structure": ..,
 *   "vig":     {"on":0/1,"amount":..,"radius":..,"softness":..},
 *   "tilt":    {"on":0/1,"focusY":..,"width":..,"feather":..},
 *   "geom":    {"angle":..,"perspV":..,"perspH":..},
 *   "lens":    {"on":0/1,"focusY":..,"width":..,"feather":..,"amount":..,
 *               "useDepth":0/1,"focusDepth":..,"range":..,"blades":..,"autoFocus":0/1},
 *   "curves":  [[ [x,y], ... ], ...4],
 *   "select":  [[x,y,r,b,s,t], ...],
 *   "look":    {"idx":..,"str":..},
 *   "fx":      {"idx":..,"val":..},
 *   "film":    "id"|null,
 *   "frame":   {"type":"..."|"NONE","mix":..,"stamp":0/1},
 *   "masks":   [ {mode,size,feather,flow,bright,sat,warm,strength,expos,contr,
 *                 inverted,visible,show,gx0,gy0,gx1,gy1,radial,aiMode,
 *                 rangeMin,rangeMax,strokes:[[x,y,r]],erases:[[x,y,r]]} ],
 *   "marks":   [ {color,size,visible,strokes:[[x,y,w,color,erase]]} ]
 * }
 */
object EditRecipe {

    const val VERSION = 1

    fun newDoc(name: String): JSONObject = JSONObject().apply {
        put("v", VERSION)
        put("app", "EaglesEye")
        put("name", name)
    }

    /** Validates meta + version; returns the doc or null when foreign/garbage. */
    fun parse(text: String): JSONObject? = runCatching {
        val o = JSONObject(text)
        if (o.optString("app") == "EaglesEye" && o.optInt("v", -1) == VERSION) o else null
    }.getOrNull()

    // ── scalar helpers ────────────────────────────────────────────────────────
    fun f(o: JSONObject, k: String, d: Float = 0f): Float = if (o.has(k)) o.getDouble(k).toFloat() else d
    fun b(o: JSONObject, k: String, d: Boolean = false): Boolean = if (o.has(k)) o.getInt(k) != 0 else d
    fun i(o: JSONObject, k: String, d: Int = 0): Int = if (o.has(k)) o.getInt(k) else d

    /** 8-element float array (HSL zones, etc). */
    fun fa(o: JSONObject, k: String): FloatArray {
        val out = FloatArray(8)
        val a = o.optJSONArray(k) ?: return out
        for (i in 0 until minOf(8, a.length())) out[i] = a.getDouble(i).toFloat()
        return out
    }

    /** [[x,y], ...] control points for one curves channel. */
    fun points(o: JSONObject, k: String): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        val a = o.optJSONArray(k) ?: return out
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            out.add(floatArrayOf(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()))
        }
        return out
    }

    /** [[x,y,r], ...] mask brush list. */
    fun brushes(o: JSONObject, k: String): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        val a = o.optJSONArray(k) ?: return out
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            out.add(floatArrayOf(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getDouble(2).toFloat()))
        }
        return out
    }

    /** [[x,y,w,color,erase], ...] markup strokes. */
    fun markStrokes(o: JSONObject, k: String): List<LongArray> {
        val out = mutableListOf<LongArray>()
        val a = o.optJSONArray(k) ?: return out
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            out.add(longArrayOf(
                (p.getDouble(0) * 1e6).toLong(), // x*1e6 keeps sub-pixel precision as int64
                (p.getDouble(1) * 1e6).toLong(), // y
                (p.getDouble(2) * 1e6).toLong(), // w
                p.getLong(3),                     // color
                if (p.optInt(4, 0) != 0) 1L else 0L
            ))
        }
        return out
    }

    // ── builders ──────────────────────────────────────────────────────────────
    fun putF(o: JSONObject, k: String, v: Float) { o.put(k, v.toDouble()) }
    fun putB(o: JSONObject, k: String, v: Boolean) { o.put(k, if (v) 1 else 0) }
    fun putArr(o: JSONObject, k: String, of: JSONArray) { o.put(k, of) }

    fun arr(values: FloatArray): JSONArray = JSONArray().apply { for (v in values) put(v.toDouble()) }

    fun pointsArr(points: List<FloatArray>): JSONArray = JSONArray().apply {
        for (p in points) put(JSONArray().apply { put(p[0].toDouble()); put(p[1].toDouble()) })
    }

    fun brushesArr(brushes: List<FloatArray>): JSONArray = JSONArray().apply {
        for (p in brushes) put(JSONArray().apply { put(p[0].toDouble()); put(p[1].toDouble()); put(p[2].toDouble()) })
    }

    fun markStrokesArr(strokes: List<FloatArray>): JSONArray = JSONArray().apply {
        for (p in strokes) put(JSONArray().apply {
            put(p[0].toDouble()); put(p[1].toDouble()); put(p[2].toDouble())
            put(p[3].toLong()); put(if (p[4] > 0.5f) 1 else 0)
        })
    }
}