package com.dsh.pet

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 聊天记录：近三天的对话，按天分隔，打开时贴到底部（最新一条）。 */
class ChatLogActivity : ComponentActivity() {

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
            ChatLogScreen(spec, bg, soft)
        }
    }

    @Composable
    private fun ChatLogScreen(spec: Theme.Spec, bg: Bitmap?, soft: Bitmap?) {
        val items = remember { Logs.chatItems() }
        // 旧页是从后往前遍历，让最新的一条落在最下面
        val ordered = remember(items) { items.reversed() }
        // 换天的那条日期也跟着变成独立 item：日期条和气泡各自一项，顺序不变
        val rows = remember(ordered) {
            val out = ArrayList<ChatRow>()
            var lastDay: String? = null
            for (it in ordered) {
                val day = Logs.dayKey(it.at)
                if (day != lastDay) {
                    lastDay = day
                    out.add(ChatRow(Logs.day(it.at), null))
                }
                out.add(ChatRow(null, it))
            }
            out
        }
        val listState = rememberLazyListState()
        LaunchedEffect(rows.size) {
            if (rows.isEmpty()) return@LaunchedEffect
            // LazyColumn 没有 maxValue：等一帧量出内容，滚到最后一项（越界的滚动会被夹住），
            // 再补一次到底，等价于 1.149 的 scrollTo(maxValue)
            withFrameNanos { }
            listState.scrollToItem(rows.size - 1)
            var guard = 0
            while (guard++ < 8 && listState.canScrollForward) {
                listState.scrollBy(200_000f)
            }
        }

        PageFrame(
            spec = spec,
            bg = bg,
            title = "聊天记录",
            subtitle = "近三天 · 只存在本机",
            subtitleSize = 12f,
            onBack = { closePage(this) },
            listState = listState
        ) {
            item { Spacer(Modifier.height(11.dp)) }
            if (rows.isEmpty()) {
                item {
                    EmptyCard(soft, "最近三天还没聊过", "点宠物打开输入条说句话，这里就会记下来。")
                }
            } else {
                items(count = rows.size) { i ->
                    val row = rows[i]
                    val day = row.day
                    if (day != null) {
                        Text(
                            day,
                            color = SubColor,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                        )
                    } else {
                        Bubble(spec, soft, row.item!!)
                    }
                }
            }
        }
    }

    @Composable
    private fun Bubble(spec: Theme.Spec, soft: Bitmap?, it: Logs.Item) {
        val me = "me" == it.who
        val whisper = !me && "whisper" == it.kind
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            horizontalArrangement = if (me) Arrangement.End else Arrangement.Start
        ) {
            Column(horizontalAlignment = if (me) Alignment.End else Alignment.Start) {
                if (whisper) {
                    Text(
                        "她主动说的",
                        color = SubColor,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 6.dp, bottom = 3.dp)
                    )
                }
                // 气泡也按卡片画：以前是纯 Box + 半透明色，底下那张背景图是清清楚楚透出来的。
                // 走 GlassCard 后，它和卡片一样有「预糊位图 + hazeEffect」，毛玻璃风格下一定是糊的。
                GlassCard(
                    soft,
                    modifier = Modifier.widthIn(max = 280.dp),
                    corner = FieldCorner,
                    tint = if (me) AccentColor.copy(alpha = 0.62f) else FieldTint,
                    borderColor = Color.Transparent,
                    padding = 0.dp,
                    spacing = 0.dp,
                    gap = 0.dp,
                    fillWidth = false
                ) {
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            it.text,
                            color = if (me) OnAccent else if (whisper) BodyColor else TextColor,
                            fontSize = (if (whisper) 13 else 14).sp,
                            lineHeight = (if (whisper) 19 else 20).sp
                        )
                    }
                }
                Text(
                    Logs.clock(it.at),
                    color = SubColor,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp)
                )
            }
        }
    }
}

/** 聊天记录里的一行：day 非空是换天的日期条，否则是一条消息。 */
private class ChatRow(val day: String?, val item: Logs.Item?)
