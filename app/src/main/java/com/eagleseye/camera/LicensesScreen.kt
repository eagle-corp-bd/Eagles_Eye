package com.eagleseye.camera

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class OssComponent(
    val name: String,
    val role: String,
    val authors: String,
    val license: String,
    val copyright: String,
)

@Composable
fun LicensesScreen(onClose: () -> Unit) {
    BackHandler { onClose() }
    val components = listOf(
        OssComponent(
            "SINet",
            "Person & hair segmentation — live preview",
            "NAVER Corp. (CLOVA AI)",
            "MIT License",
            "Copyright (c) NAVER Corp. (CLOVA AI)"
        ),
        OssComponent(
            "BiRefNet",
            "High-accuracy segmentation — capture",
            "ZhengPeng7",
            "MIT License",
            "Copyright (c) 2024 ZhengPeng7"
        ),
        OssComponent(
            "MiDaS",
            "Fast depth estimation",
            "Intel ISL (Intelligent Systems Lab)",
            "MIT License",
            "Copyright (c) 2020 Intel ISL (Intel Corporation)"
        ),
        OssComponent(
            "ZipDepth",
            "Accurate depth estimation",
            "Fabio Tosi, Luca Bartolomei, Matteo Poggi, Stefano Mattoccia — University of Bologna",
            "MIT License",
            "Copyright (c) Tosi, Bartolomei, Poggi & Mattoccia — University of Bologna"
        ),
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(UiBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color(0xF00B0B0E))
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            val closeSrc = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(40.dp)
                    .uiPressScale(closeSrc)
                    .clip(CircleShape)
                    .background(Color.White.copy(0.12f))
                    .clickable(closeSrc, null) { onClose() }
                    .align(Alignment.CenterStart),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, "Close licenses", tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "OPEN SOURCE LICENSES",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                )
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            UiSectionHeader("ON-DEVICE AI MODELS")
            UiSettingsCard {
                components.forEachIndexed { i, c ->
                    if (i > 0) UiCardDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                c.name,
                                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(c.license, color = UiGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        Text(c.role, color = UiTextDim, fontSize = 12.sp)
                        Spacer(Modifier.height(Spacing.xxs))
                        Text(c.authors, color = UiTextDim.copy(0.7f), fontSize = 11.sp, fontFamily = UiFontMono)
                        Spacer(Modifier.height(Spacing.xxs))
                        Text(c.copyright, color = UiTextDim.copy(0.55f), fontSize = 10.sp, fontFamily = UiFontMono)
                    }
                }
            }

            UiSectionHeader("LICENSE TEXT")
            UiSettingsCard {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        "EaglesEye is proprietary software. The models listed above are provided " +
                                "under the MIT License.\n\n" +
                                "Permission is hereby granted, free of charge, to any person obtaining a copy " +
                                "of this software and associated documentation files (the \"Software\"), to deal " +
                                "in the Software without restriction, including without limitation the rights to " +
                                "use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of " +
                                "the Software, and to permit persons to whom the Software is furnished to do so, " +
                                "subject to the following conditions:\n\n" +
                                "The above copyright notice and this permission notice shall be included in all " +
                                "copies or substantial portions of the Software.\n\n" +
                                "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR " +
                                "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS " +
                                "FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR " +
                                "COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER " +
                                "IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN " +
                                "CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.",
                        color = UiTextDim, fontSize = 11.sp, fontFamily = UiFontMono, lineHeight = 16.sp
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}
