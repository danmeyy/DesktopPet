package com.dsh.pet

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 读屏时间线：近两天她每次读屏的时间和应用。 */
class ScreenLogActivity : ComponentActivity() {

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
            ScreenLogScreen(spec, bg, soft)
        }
    }

    @Composable
    private fun ScreenLogScreen(spec: Theme.Spec, bg: Bitmap?, soft: Bitmap?) {
        val rows = remember { Logs.screenItems() }
        PageFrame(
            spec = spec,
            bg = bg,
            title = "读屏时间线",
            subtitle = "近两天 · 她每次读屏的时间和应用",
            subtitleSize = 12f,
            onBack = { closePage(this) },
            action = {
                HeaderButton(soft, "看见了什么") { openPage(this, SeenActivity::class.java) }
            }
        ) {
            item { Spacer(Modifier.height(11.dp)) }
            if (rows.isEmpty()) {
                item {
                    EmptyCard(soft, "最近两天没有读屏记录", "确认无障碍服务已经打开、并且她在正常运行。读屏记录只存本机。")
                }
            } else {
                // 整张卡按行拆成 lazy item：卡面由 CardRunRow 的首/中/末三段拼回同一张卡，
                // 条目再多也只是多几个 item，不会再把内容撑成一张 43 万 px 的卡
                if (rows.size == 1) {
                    // 只剩一条时整张卡才 40 多 dp 高，26dp 圆角会被形状按比例缩掉一截，
                    // 分片画法对不上，直接画一整张卡
                    item {
                        PageCard(soft) { ScreenRow(rows[0]) }
                    }
                } else {
                    items(count = rows.size) { i ->
                        CardRunRow(soft, first = i == 0, last = i == rows.size - 1) {
                            if (i > 0) DividerLine()
                            ScreenRow(rows[i])
                        }
                    }
                }
                // 分片画的卡没有 GlassCard 的 gap，把 14dp 连同原来的 10dp 一起补回来；
                // 只有一条时走的是整张 PageCard，gap 自带，只需原来的 10dp
                item { Spacer(Modifier.height(if (rows.size == 1) 10.dp else 24.dp)) }
                item {
                    Text("共 " + rows.size + " 次 · 只保留最近两天", color = SubColor, fontSize = 11.sp)
                }
            }
        }
    }

    /** 时间线里的一行：样式与 1.149 完全一致，只是改为逐行组合。 */
    @Composable
    private fun ScreenRow(item: Logs.Item) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                Logs.clock(item.at),
                color = SubColor,
                fontSize = 13.sp,
                modifier = Modifier.width(74.dp)
            )
            Text(
                if (item.who.isEmpty()) "未知应用" else item.who,
                color = TextColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(item.text.length.toString() + " 字", color = SubColor, fontSize = 12.sp)
        }
    }
}
