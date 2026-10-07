package com.eagleseye.camera

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Star Menu Dropdown ────────────────────────────────────────────────────────
// Blueprint: "Dropdown menu slides down from Star icon. 5 feature toggles.
//  Each feature has yellow corner indicator when active."

@Composable
fun StarMenuPanel(
    modifier: Modifier = Modifier,
    fastBurstOn: Boolean, onFastBurst: () -> Unit,
    hdrOn: Boolean,       onHDR: () -> Unit,
    focusPeakOn: Boolean = false, onFocusPeak: () -> Unit = {},
    histogramOn: Boolean = false, onHistogram: () -> Unit = {},
    nightOn: Boolean = false, onNight: () -> Unit = {},
    rawOn: Boolean = false, rawSupported: Boolean = true, onRaw: () -> Unit = {},
    eisOn: Boolean = false, onEis: () -> Unit = {},
    onDismiss: () -> Unit
) {
    Column(modifier.width(240.dp)
        .background(Color(0xF0111111), RoundedCornerShape(16.dp))
        .border(0.5.dp, Color.White.copy(0.12f), RoundedCornerShape(16.dp))
        .padding(8.dp)) {

        Text("SPECIAL", color=Color.White.copy(0.35f), fontSize=9.sp,
            fontWeight=FontWeight.Bold, letterSpacing=1.5.sp,
            modifier=Modifier.padding(start=8.dp, top=4.dp, bottom=6.dp))

        val features: List<Pair<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, Boolean>, () -> Unit>> = listOf(
            Triple("FAST BURST",        Icons.Default.BurstMode,      fastBurstOn) to onFastBurst,
            Triple("HDR CAPTURE",       Icons.Default.HdrOn,             hdrOn)       to onHDR,
            Triple("FOCUS PEAK",        Icons.Default.GpsFixed,        focusPeakOn)  to onFocusPeak,
            Triple("HISTOGRAM",         Icons.Default.BarChart,        histogramOn)  to onHistogram,
            Triple("NIGHT MODE",        Icons.Default.NightsStay,      nightOn)      to onNight,
            Triple("STABILIZATION",     Icons.Default.Videocam,        eisOn)        to onEis,
        )

        features.forEach { (info, action) ->
            val (label, icon, isOn) = info
            StarFeatureRow(label=label, icon=icon, isOn=isOn, onClick=action)
        }
        StarFeatureRow(
            label = if (rawSupported) "RAW CAPTURE" else "RAW NOT SUPPORTED",
            icon = Icons.Default.CameraAlt,
            isOn = rawOn,
            enabled = rawSupported,
            onClick = onRaw
        )

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StarFeatureRow(
    label: String,
    icon: ImageVector,
    isOn: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(if(isOn) FeatureYellow.copy(0.1f) else Color.Transparent)
        .clickable(
            enabled = enabled,
            interactionSource = remember{MutableInteractionSource()},
            indication = null
        ) { if (enabled) onClick() }
        .padding(horizontal=10.dp, vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(12.dp)) {

        Icon(icon, "Enable $label",
            tint=if(isOn) FeatureYellow else Color.White.copy(if(enabled) 0.5f else 0.22f),
            modifier=Modifier.size(18.dp))

        Text(label, color=if(isOn) Color.White else Color.White.copy(if(enabled) 0.6f else 0.3f),
            fontSize=11.sp, fontWeight=if(isOn) FontWeight.Bold else FontWeight.Normal,
            letterSpacing=0.5.sp, modifier=Modifier.weight(1f))

        // Yellow indicator dot when active
        Box(Modifier.size(if(isOn) 8.dp else 6.dp)
            .background(if(isOn) FeatureYellow else Color.White.copy(if(enabled) 0.15f else 0.08f), CircleShape))
    }
}
