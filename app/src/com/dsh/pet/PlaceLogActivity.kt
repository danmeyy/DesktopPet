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
import androidx.compose.foundation.layout.width
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

/** 你都在哪：近两天每 20 秒和每次开口前的应用与页面。 */
class PlaceLogActivity : ComponentActivity() {

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
            PlaceLogScreen(spec, bg, soft)
        }
    }

    @Composable
    private fun PlaceLogScreen(spec: Theme.Spec, bg: Bitmap?, soft: Bitmap?) {
        val rows = remember { Logs.whereItems() }
        PageFrame(
            spec = spec,
            bg = bg,
            title = "你都在哪",
            subtitle = "近两天 · 每 20 秒和每次开口前的应用与页面",
            subtitleSize = 12f,
            onBack = { closePage(this) }
        ) {
            item { Spacer(Modifier.height(11.dp)) }
            if (rows.isEmpty()) {
                item {
                    EmptyCard(soft, "最近两天没有位置记录", "确认无障碍服务已经打开。这条记录只存本机，不含任何屏幕文字。")
                }
            } else {
                // 这张卡最容易撑爆：每 20 秒一条，攒到 2000 条就是 43 万 px。
                // 按行拆成 lazy item，卡面由 CardRunRow 的首/中/末三段拼回同一张卡
                if (rows.size == 1) {
                    // 一条时整张卡不够高，26dp 圆角会被形状按比例缩小，分片对不上，画整张卡
                    item {
                        PageCard(soft) { PlaceRow(rows[0]) }
                    }
                } else {
                    items(count = rows.size) { i ->
                        CardRunRow(soft, first = i == 0, last = i == rows.size - 1) {
                            if (i > 0) DividerLine()
                            PlaceRow(rows[i])
                        }
                    }
                }
                // 分片画的卡没有 GlassCard 的 gap，把 14dp 连同原来的 10dp 一起补回来；
                // 只有一条时走的是整张 PageCard，gap 自带，只需原来的 10dp
                item { Spacer(Modifier.height(if (rows.size == 1) 10.dp else 24.dp)) }
                item {
                    Text("共 " + rows.size + " 条 · 只保留最近两天", color = SubColor, fontSize = 11.sp)
                }
            }
        }
    }

    /** 位置记录里的一行：样式与 1.149 完全一致，只是改为逐行组合。 */
    @Composable
    private fun PlaceRow(item: Logs.Item) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                Logs.clock(item.at),
                color = SubColor,
                fontSize = 13.sp,
                modifier = Modifier.width(74.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    if (item.who.isEmpty()) "未知应用" else item.who,
                    color = TextColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.text.isNotEmpty()) {
                    Text(item.text, color = SubColor, fontSize = 12.sp)
                }
            }
        }
    }
}
