package com.eagleseye.camera

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

data class ProPrefs(
    val iso: Int = 400, val ssNs: Long = 16_666_667L,
    val wb: Int = 5600, val focus: Float = 0.5f, val ev: Float = 0f,
    val manualISO: Boolean = false, val manualSS: Boolean = false,
    val manualWB: Boolean = false, val manualFocus: Boolean = false
)

fun loadProPrefs(context: Context): ProPrefs {
    val prefs = context.getSharedPreferences("pro_prefs_v2", Context.MODE_PRIVATE)
    return ProPrefs(
        iso = prefs.getInt("iso", 400),
        ssNs = prefs.getLong("ss", 16_666_667L),
        wb = prefs.getInt("wb", 5600),
        focus = prefs.getFloat("focus", 0.5f),
        ev = prefs.getFloat("ev", 0f),
        manualISO = prefs.getBoolean("miso", false),
        manualSS = prefs.getBoolean("mss", false),
        manualWB = prefs.getBoolean("mwb", false),
        manualFocus = prefs.getBoolean("mfocus", false)
    )
}

fun saveProPrefs(context: Context, p: ProPrefs) {
    context.getSharedPreferences("pro_prefs_v2", Context.MODE_PRIVATE).edit {
        putInt("iso", p.iso)
        putLong("ss", p.ssNs)
        putInt("wb", p.wb)
        putFloat("focus", p.focus)
        putFloat("ev", p.ev)
        putBoolean("miso", p.manualISO)
        putBoolean("mss", p.manualSS)
        putBoolean("mwb", p.manualWB)
        putBoolean("mfocus", p.manualFocus)
    }
}
