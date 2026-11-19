package com.dsh.pet

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 她看见了什么：近两天读屏拿到的原文。 */
class SeenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logs.init(this)
        setContent {
            val style = remember { Theme.style(this) }
            val spec = remember { Theme.spec(this) }
            val glass = Theme.GLASS == style
            // 子页面也要真采样背景：调色板一次性装好，毛玻璃的采样由 PageFrame 的 Haze 负责
            installTheme(spec, glass)
            val bg = remember { if (glass) Backdrop.source(this) else null }
            val soft = remember {
                if (!glass) null
                else Backdrop.blurred(this, Theme.cardBlur(this, style))
            }
            SeenScreen(spec, bg, soft)
        }
    }

    @Composable
    private fun SeenScreen(spec: Theme.Spec, bg: Bitmap?, soft: Bitmap?) {
        val rows = remember { Logs.screenItems() }
        PageFrame(
            spec = spec,
            bg = bg,
            title = "她看见了什么",
            subtitle = "近两天 · 读屏拿到的原文",
            subtitleSize = 12f,
            onBack = { closePage(this) },
            action = {
                HeaderButton(soft, "时间线") { closePage(this) }
            }
        ) {
            item { Spacer(Modifier.height(11.dp)) }
            if (rows.isEmpty()) {
                item {
                    EmptyCard(soft, "最近两天她什么都没读到", "要么无障碍没开，要么她一直待在同一块屏幕上没触发读取。")
                }
            } else {
                val shown = Math.min(rows.size, MAX_ROWS)
                // 每条本来就是一张独立的卡，直接一条一个 item
                items(count = shown) { i ->
                    val it = rows[i]
                    GlassCard(soft, modifier = Modifier.padding(bottom = 10.dp), padding = 0.dp, spacing = 0.dp) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (it.who.isEmpty()) "未知应用" else it.who,
                                    color = AccentColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(Logs.stamp(it.at), color = SubColor, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(it.text, color = BodyColor, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                }
                if (rows.size > shown) {
                    item {
                        Text(
                            "只显示最近 " + shown + " 条，共 " + rows.size + " 条",
                            color = SubColor, fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_ROWS = 200
    }
}
