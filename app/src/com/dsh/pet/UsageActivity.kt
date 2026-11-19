package com.dsh.pet

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

/** 用量：宿主模式问 pet-brain 的 /usage，直连模式读本机账本。 */
class UsageActivity : ComponentActivity() {

    private var host = false
    private var days by mutableStateOf<List<Usage.Day>>(emptyList())
    private var status by mutableStateOf("读取中…")
    private var bad by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = !Config.direct(this)
        reload()
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
            UsageScreen(spec, bg, soft)
        }
    }

    @Composable
    private fun UsageScreen(spec: Theme.Spec, bg: Bitmap?, soft: Bitmap?) {
        val today = Usage.dayOf(System.currentTimeMillis())
        val month = today.substring(0, 7)
        val todaySum = sum(days, today, today)
        val week = sum(days, shift(today, -6), today)
        val monthSum = sum(days, month + "-01", month + "-31")

        PageFrame(
            spec = spec,
            bg = bg,
            title = "用量",
            subtitle = "接口真实回报的 token，按 DeepSeek 牌价折算",
            subtitleSize = 12f,
            onBack = { closePage(this) },
            action = {
                Text(
                    "刷新",
                    color = SubColor,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { reload() }
                        .padding(start = 10.dp, top = 4.dp, end = 2.dp, bottom = 4.dp)
                )
            }
        ) {
            // 1.150：每块内容各自一个 lazy item，顺序与间距与 1.149 一致（卡片自带 14dp 卡底）
            item { Spacer(Modifier.height(14.dp)) }
            item { Text(status, color = if (bad) Color(0xFFFF7A45) else SubColor, fontSize = 12.5.sp) }
            item { Spacer(Modifier.height(10.dp)) }

            // 今天
            item {
                PageCard(soft, padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Text("今天", color = SubColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text(Usage.moneyText(todaySum.money), color = TextColor, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            todaySum.calls.toString() + " 次 · 输入 " + Usage.tokens(todaySum.input) +
                                " · 输出 " + Usage.tokens(todaySum.output) + " token",
                            color = SubColor, fontSize = 12.5.sp
                        )
                        if (todaySum.cacheRead > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "其中缓存命中 " + Usage.tokens(todaySum.cacheRead) + " token",
                                color = SubColor, fontSize = 12.sp
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }

            // 近七天 / 本月
            item {
                Row(Modifier.fillMaxWidth()) {
                    GlassCard(soft, modifier = Modifier.weight(1f), padding = 0.dp, spacing = 0.dp) {
                        Column(Modifier.padding(14.dp)) {
                            Text("近七天", color = SubColor, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(Usage.moneyText(week.money), color = TextColor, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                week.calls.toString() + " 次 · " + Usage.tokens(week.input + week.output) + " token",
                                color = SubColor, fontSize = 11.5.sp
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    GlassCard(soft, modifier = Modifier.weight(1f), padding = 0.dp, spacing = 0.dp) {
                        Column(Modifier.padding(14.dp)) {
                            Text("本月", color = SubColor, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(Usage.moneyText(monthSum.money), color = TextColor, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                monthSum.calls.toString() + " 次 · " + Usage.tokens(monthSum.input + monthSum.output) + " token",
                                color = SubColor, fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }

            if (days.isNotEmpty()) {
                item { Spacer(Modifier.height(18.dp)) }
                item { Text("每天", color = SubColor, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                item { Spacer(Modifier.height(8.dp)) }
                // 最多 7 行，一张卡还远不到 Constraints 上限
                item {
                    PageCard(soft) {
                        val listed = ArrayList<Usage.Day>()
                        for (d in days) {
                            if (listed.size >= 7) break
                            if (d.input == 0L && d.output == 0L && d.calls == 0) continue
                            listed.add(d)
                        }
                        if (listed.isEmpty()) {
                            Text("还没有完整的一天。", color = BodyColor, fontSize = 13.5.sp, lineHeight = 20.sp)
                        }
                        listed.forEachIndexed { i, d ->
                            if (i > 0) DividerLine()
                            DayRow(d)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(18.dp)) }
            item {
                PageCard(soft) {
                    Text(
                        "换算用的牌价（元 / 百万 token）：高峰时段输入未命中 ¥2、命中 ¥0.04、输出 ¥8；" +
                            "空闲时段全部减半（¥1 / ¥0.02 / ¥4）。高峰是北京时间周一至周五 9:00–12:00 与 14:00–18:00，" +
                            "周末和节假日全天都算空闲。每一笔按它自己的发生时刻计价。",
                        color = BodyColor, fontSize = 13.5.sp, lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "没有 token 数字的那几天说明接口没回报 usage，这里不会用字数估算去凑。",
                        color = BodyColor, fontSize = 13.5.sp, lineHeight = 20.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun DayRow(d: Usage.Day) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(d.day.substring(5), color = SubColor, fontSize = 13.sp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(d.calls.toString() + " 次", color = TextColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "入 " + Usage.tokens(d.input) + " · 出 " + Usage.tokens(d.output),
                    color = SubColor, fontSize = 11.5.sp
                )
            }
            Text(Usage.moneyText(d.money), color = TextColor, fontSize = 14.sp)
        }
    }

    private fun reload() {
        status = "读取中…"
        bad = false
        days = emptyList()
        if (host) loadHost() else show(Usage.daysFromLocal(this), null)
    }

    private fun loadHost() {
        Thread {
            var list: List<Usage.Day>? = null
            var err: String? = null
            try {
                val o = JSONObject(Web.read(URL_USAGE, null, 6000))
                val arr = o.optJSONArray("days")
                val tmp = ArrayList<Usage.Day>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val j = arr.optJSONObject(i) ?: continue
                        val day = j.optString("day", "")
                        if (day.isEmpty()) continue
                        val d = Usage.Day()
                        d.day = day
                        d.calls = j.optInt("calls", 0)
                        d.input = j.optLong("input", 0L)
                        d.output = j.optLong("output", 0L)
                        d.cacheRead = j.optLong("cacheRead", 0L)
                        d.money = Usage.costPeak(d.input, d.cacheRead, d.output)
                        tmp.add(d)
                    }
                }
                list = tmp
            } catch (e: IllegalStateException) {
                val m = e.message ?: ""
                err = if (m == "HTTP 404") m + "：宿主里没有 pet-brain 插件" else m
            } catch (t: Throwable) {
                err = "连不上宿主：" + t.javaClass.simpleName
            }
            val shown = list ?: emptyList()
            val error = err
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                show(shown, error)
            }
        }.start()
    }

    private fun show(list: List<Usage.Day>, error: String?) {
        if (isFinishing) return
        days = list
        if (error != null) {
            bad = true
            status = error
        } else if (list.isEmpty()) {
            bad = false
            status = "还没有记账"
        } else {
            bad = false
            status = if (host) "来源：DSHBox 里的 pet-brain 插件" else "来源：本机直连账本"
        }
    }

    private fun sum(list: List<Usage.Day>, from: String, to: String): Usage.Day {
        val out = Usage.Day()
        out.day = from
        for (d in list) {
            if (d.day != from && d.day.compareTo(from) < 0) continue
            if (d.day.compareTo(to) > 0) continue
            out.calls += d.calls
            out.input += d.input
            out.output += d.output
            out.cacheRead += d.cacheRead
            out.money += d.money
        }
        return out
    }

    private companion object {
        const val URL_USAGE = "http://127.0.0.1:3080/pet-brain/usage"

        fun shift(day: String, delta: Int): String {
            return try {
                val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val d = f.parse(day)!!
                f.format(Date(d.time + delta * 86400000L))
            } catch (e: Throwable) {
                day
            }
        }
    }
}
