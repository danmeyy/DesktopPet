package com.dsh.pet

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.ArrayList

/** 她的记忆：宿主模式走 pet-brain 的 /facts、/forget，直连模式读本机 Facts。 */
class MemoryActivity : ComponentActivity() {

    private var host = false
    private var items by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var digests by mutableStateOf<List<Logs.Digest>>(emptyList())
    private var capState by mutableStateOf(Logs.ctxCap())
    private var status by mutableStateOf("读取中…")
    private var bad by mutableStateOf(false)
    private var armed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logs.init(this)
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
            MemoryScreen(spec, bg, soft)
        }
    }

    @Composable
    private fun MemoryScreen(spec: Theme.Spec, bg: Bitmap?, soft: Bitmap?) {
        PageFrame(
            spec = spec,
            bg = bg,
            title = "她的记忆",
            subtitle = if (host) "存在 DSHBox 那边 · 说得上话才记得住" else "存在这台手机上 · 只给直连用",
            subtitleSize = 12.5f,
            onBack = { closePage(this) }
        ) {
            // 1.150：内容逐块变成 lazy item，块与块之间的顺序/间距与 1.149 一致
            item {
                PageCard(soft) {
                    Text(
                        "她听到「记住……」这类话时才会往这里写，最多留 60 条（直连模式 80 条）。" +
                            "删掉的意思是她下次就真的不记得了，建议只删那些记错的、过期的。",
                        color = BodyColor,
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 12.dp)
                    )
                }
            }

            item {
                Text(
                    status,
                    color = if (bad) Color(0xFFFF7A45) else SubColor,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp, end = 4.dp, bottom = 8.dp)
                )
            }

            if (items.isNotEmpty()) {
                // 最多 60/80 条，一张卡还远不到 Constraints 上限，整块留一张卡
                item {
                    PageCard(soft) {
                        items.forEachIndexed { i, item ->
                            if (i > 0) DividerLine()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.first, color = TextColor, fontSize = 14.5.sp)
                                    if (item.second.isNotEmpty()) {
                                        Text(
                                            item.second.replace('T', ' '),
                                            color = SubColor,
                                            fontSize = 11.5.sp,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                                Text(
                                    "×",
                                    color = SubColor,
                                    fontSize = 18.sp,
                                    modifier = Modifier
                                        .clickable { forget(item.first) }
                                        .padding(horizontal = 12.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 聊天摘要：她每隔一阵把聊天压成几句要点，等级决定存多久；这里能看、能改、能加
            item {
                Text(
                    "聊天摘要",
                    color = TextColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp)
                )
            }
            item {
                PageCard(soft) {
                    Text(
                        "她每隔一阵把聊天压成几句要点，等级决定存多久：3 天 / 7 天 / 15 天 / 30 天 / 永久。" +
                            "点一行可以改文字和等级，× 是删掉；改完立刻生效，下次开口就按新的来。",
                        color = BodyColor,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 12.dp)
                    )
                    DividerLine()
                    Text(
                        "容量就是最多留多少条，可以自己拖。用到 50% / 80% / 90% / 95% 会提醒一次；" +
                            "满了以后每加一批先删掉 2% 最久没被用到的，腾地方给新的。",
                        color = SubColor,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 12.dp)
                    )
                }
            }

            item {
                val cap = Logs.ctxCap()
                val used = digests.size
                val pct = if (cap <= 0) 0 else Math.min(100, Math.round(used * 100f / cap))
                val bar = when {
                    pct >= 100 -> Color(0xFFFF5A5A)
                    pct >= 90 -> Color(0xFFFF7A45)
                    pct >= 80 -> Color(0xFFFFB020)
                    else -> AccentColor
                }
                Column(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("上下文占用", color = TextColor, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("$used / $cap 条 · $pct%", color = bar, fontSize = 12.5.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(LineColor)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(pct / 100f)
                                .fillMaxHeight()
                                .background(bar)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Slider(
                        value = capState.toFloat(),
                        onValueChange = { capState = it.toInt() },
                        onValueChangeFinished = {
                            Logs.setCtxCap(capState)
                            reload()
                        },
                        valueRange = 50f..2000f,
                        steps = 38,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentColor,
                            activeTrackColor = AccentColor,
                            inactiveTrackColor = LineColor
                        )
                    )
                    Text("最多留 $capState 条", color = SubColor, fontSize = 11.5.sp)
                }
            }
            if (digests.size == 1) {
                item { PageCard(soft) { DigestRow(digests[0]) } }
            } else if (digests.isNotEmpty()) {
                items(count = digests.size) { i ->
                    CardRunRow(
                        soft = soft,
                        first = i == 0,
                        last = i == digests.size - 1,
                        padding = 2.dp
                    ) {
                        if (i > 0) DividerLine()
                        DigestRow(digests[i])
                    }
                }
            }
            item { Spacer(Modifier.height(if (digests.size > 1) 24.dp else 10.dp)) }
            item { GhostButton("加一条摘要") { editDigest(null) } }
            item { Spacer(Modifier.height(8.dp)) }
            item { GhostButton("智能删除") { smartDelete() } }
            item { Spacer(Modifier.height(8.dp)) }
            item { GhostButton("清空摘要") { clearDigests() } }
            item { Spacer(Modifier.height(10.dp)) }
            item { GhostButton("刷新") { reload() } }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                GhostButton("清空全部") {
                    if (!armed) {
                        armed = true
                        Toast.makeText(this@MemoryActivity, "再点一次就全清掉", Toast.LENGTH_SHORT).show()
                    } else {
                        armed = false
                        clearAll()
                    }
                }
            }
        }
    }

    @Composable
    private fun DigestRow(d: Logs.Digest) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { editDigest(d) }
                .padding(horizontal = 2.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                levelLabel(d.lv),
                color = if (d.lv >= 5) AccentColor else SubColor,
                fontSize = 11.sp,
                modifier = Modifier.width(46.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(d.text, color = TextColor, fontSize = 14.5.sp)
                Text(
                    Logs.stamp(d.at) + " · " + if (d.lv >= 5) "永久保留" else "存 " + Logs.ctxDays(d.lv) + " 天",
                    color = SubColor,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                "×",
                color = SubColor,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable { removeDigest(d) }
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
    }

    private fun levelLabel(lv: Int): String = when (lv) {
        1 -> "3天"
        2 -> "7天"
        3 -> "15天"
        4 -> "30天"
        else -> "永久"
    }

    private fun removeDigest(d: Logs.Digest) {
        digests = digests.filterNot { it.at == d.at && it.text == d.text }
        Logs.digestSave(digests)
        Toast.makeText(this, "删了，她下次就不记得这条", Toast.LENGTH_SHORT).show()
    }

    /** 加/改一条摘要：文字 + 等级，存回去后立刻生效。 */
    private fun editDigest(old: Logs.Digest?) {
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(px(20), px(8), px(20), 0)
        val input = EditText(this)
        input.hint = "一句话，最多 60 字"
        input.setText(old?.text ?: "")
        input.setSelection(input.text.length)
        input.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(60))
        input.maxLines = 3
        box.addView(input)

        var lv = if (old != null) old.lv else 3
        val labels = listOf("3 天", "7 天", "15 天", "30 天", "永久")
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, px(10), 0, 0)
        val chips = ArrayList<TextView>()

        fun style(tv: TextView, on: Boolean) {
            tv.setTextColor(if (on) OnAccent.toArgb() else TextColor.toArgb())
            tv.background = GradientDrawable().apply {
                cornerRadius = px(9).toFloat()
                setColor(if (on) AccentColor.toArgb() else FieldTint.toArgb())
            }
        }

        labels.forEachIndexed { i, label ->
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 13f
            tv.gravity = Gravity.CENTER
            tv.setPadding(px(4), px(7), px(4), px(7))
            tv.setOnClickListener {
                lv = i + 1
                chips.forEachIndexed { k, v -> style(v, k + 1 == lv) }
            }
            chips.add(tv)
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        chips.forEachIndexed { i, v -> style(v, i + 1 == lv) }
        box.addView(row)

        AlertDialog.Builder(this)
            .setTitle(if (old == null) "加一条摘要" else "改这条摘要")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val t = input.text.toString().trim().replace(Regex("\\s+"), " ")
                if (t.isEmpty()) {
                    Toast.makeText(this, "空的，没存", Toast.LENGTH_SHORT).show()
                } else {
                    val item = Logs.Digest(old?.at ?: System.currentTimeMillis(), lv, t.take(60))
                    digests = if (old == null) listOf(item) + digests
                    else digests.map { if (it.at == old.at && it.text == old.text) item else it }
                    Logs.digestSave(digests)
                    Toast.makeText(this, "存好了", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 智能删：先算会删几条给用户看，确认了再真删。 */
    private fun smartDelete() {
        val counts = Logs.smartClean(false)
        val total = counts.sum()
        if (total == 0) {
            Toast.makeText(this, "没有可以智能删掉的", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("智能删除")
            .setMessage(
                "会删掉 $total 条：\n" +
                    "· 重复或已经被别的条目包含：" + counts[0] + " 条\n" +
                    "· 寒暄、没什么信息：" + counts[1] + " 条\n" +
                    "· 太久没用（3 天档超过 7 天、7 天档超过 20 天）：" + counts[2] + " 条\n" +
                    "· 超过容量，腾到九成：" + counts[3] + " 条\n\n" +
                    "删了找不回来，继续吗？"
            )
            .setPositiveButton("删") { _, _ ->
                val done = Logs.smartClean(true)
                Toast.makeText(this, "删了 " + done.sum() + " 条", Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearDigests() {
        AlertDialog.Builder(this)
            .setTitle("清空摘要")
            .setMessage("把压出来的聊天摘要全部删掉？原话还在，但她下次就想不起更早聊过什么了。")
            .setPositiveButton("清空") { _, _ ->
                Logs.digestSave(emptyList())
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun reload() {
        armed = false
        bad = false
        status = "读取中…"
        Thread {
            var list: List<Pair<String, String>> = emptyList()
            var err: String? = null
            try {
                if (host) {
                    val o = JSONObject(Web.read(URL_FACTS, null))
                    if (!o.optBoolean("ok")) {
                        err = "宿主说没准备好：" + o.optString("error", "?")
                    } else {
                        val arr = o.optJSONArray("facts")
                        val tmp = ArrayList<Pair<String, String>>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val t = arr.optString(i, "").trim()
                                if (t.isNotEmpty()) tmp.add(Pair(t, ""))
                            }
                        }
                        list = tmp
                    }
                } else {
                    val arr = Facts.load(this)
                    val tmp = ArrayList<Pair<String, String>>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val t = o.optString("text", "").trim()
                        if (t.isNotEmpty()) tmp.add(Pair(t, o.optString("at", "")))
                    }
                    list = tmp
                }
            } catch (e: IllegalStateException) {
                err = if ("HTTP 404" == e.message) "宿主里没有 pet-brain 插件，先去设置页导出插件装上"
                else "DSHBox 回了 " + e.message
            } catch (t: Throwable) {
                err = "连不上 DSHBox，看看它是不是没开着"
            }
            val shown = list
            val dg = Logs.digests()
            val error = err
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                items = shown
                digests = dg
                capState = Logs.ctxCap()
                if (error != null) {
                    bad = true
                    status = error
                } else {
                    bad = false
                    status = if (shown.isEmpty()) "还没有记住任何事"
                    else "共 " + shown.size + " 条 · 点右边的 × 删掉某一条"
                }
            }
        }.start()
    }

    private fun forget(text: String) {
        if (host) {
            bad = false
            status = "删除中…"
            Thread {
                var err: String? = null
                try {
                    val body = JSONObject()
                    if (text.isNotEmpty()) body.put("text", text)
                    val o = JSONObject(Web.read(URL_FORGET, body.toString()))
                    if (!o.optBoolean("ok")) err = "宿主没删掉：" + o.optString("error", "?")
                } catch (t: Throwable) {
                    err = "连不上 DSHBox"
                }
                val error = err
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (error != null) {
                        bad = true
                        status = error
                    } else {
                        reload()
                    }
                }
            }.start()
        } else {
            Facts.remove(this, text)
            reload()
        }
    }

    private fun clearAll() {
        if (host) {
            bad = false
            status = "清空中…"
            Thread {
                var err: String? = null
                try {
                    val o = JSONObject(Web.read(URL_FORGET, "{}"))
                    if (!o.optBoolean("ok")) err = "宿主没删掉：" + o.optString("error", "?")
                } catch (t: Throwable) {
                    err = "连不上 DSHBox"
                }
                val error = err
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (error != null) {
                        bad = true
                        status = error
                    } else {
                        reload()
                    }
                }
            }.start()
        } else {
            Facts.clear(this)
            reload()
        }
    }

    private companion object {
        const val URL_FACTS = "http://127.0.0.1:3080/pet-brain/facts"
        const val URL_FORGET = "http://127.0.0.1:3080/pet-brain/forget"
    }
}
