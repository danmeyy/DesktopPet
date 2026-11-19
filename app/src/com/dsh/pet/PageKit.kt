package com.dsh.pet

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil
import kotlin.math.max

/**
 * 六个日志/记忆/用量页共用的「画」的骨架：背景图、返回 + 标题 + 副标题、滚动列。
 * 内容和顺序由各页自己决定，这里只管壳子。
 *
 * 1.150：滚动容器从 verticalScroll 的 Column 换成 LazyColumn。原来所有条目一次性组合、
 * 一次性测量，where 攒到 2000 条时内容高 43 万 px，直接撞 Compose 262143px 的 Constraints
 * 上限崩掉（pet-crash-1.149.txt）。LazyColumn 只组合视口附近的 item，条目再多也不会超限。
 * 页头是本列的第一个 item，跟着内容一起滚（不是固定顶栏）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PageFrame(
    spec: Theme.Spec,
    bg: Bitmap?,
    title: String,
    subtitle: String? = null,
    subtitleSize: Float = 12f,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    // 与旧页一致：窄屏留 18dp，宽屏把内容夹在 560dp 以内
    val side = max(18, (widthDp - 560) / 2).dp
    // 子页面以前只有「卡片样式」，没有真的采样背景。这里统一挂 Haze：
    // 背景图 + 压暗层一起进 hazeSource，卡片 hazeEffect 采样它，糊的才是身下真正那块画面。
    val haze = remember { HazeState() }
    val glass = bg != null
    // 背景图实际的绘制原点与尺寸：卡片必须按同一套坐标做映射，否则卡片里那块背景
    // 会和外面错位 —— 1.138 之前用「屏幕 dp 宽高」去算，跟 Image(Crop) 的实际缩放对不上，
    // 表现就是卡片里像贴了一张缩放过的小图。
    val bgOrigin = remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val bgSize = remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    CompositionLocalProvider(
        LocalHaze provides (if (glass) haze else null),
        LocalBgOrigin provides bgOrigin.value,
        LocalBgSize provides bgSize.value,
        // 子页面也关掉越界拉伸：设置页 1.135 就关了，子页面一直漏着
        LocalOverscrollConfiguration provides null
    ) {
        Box(Modifier.fillMaxSize().background(Color(spec.bg))) {
            if (bg != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .hazeSource(haze)
                        .onGloballyPositioned {
                            bgOrigin.value = it.positionInRoot()
                            bgSize.value = it.size
                        }
                ) {
                    Image(
                        bitmap = bg.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        // 必须 FillBounds：卡片按「位图铺满背景框」做映射，Crop 会额外缩放/裁切，
                        // 两边就对不上了。Backdrop.source 本来就是屏幕尺寸，FillBounds 不变形。
                        contentScale = ContentScale.FillBounds
                    )
                    // 照片有亮有暗，字必须自带对比度：统一压一层暗（1.136 从 42% 降到 10%）。
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.10f)))
                }
            }
            Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    // 与旧 padding 等价：留白属于内容，跟着一起滚
                    contentPadding = PaddingValues(
                        start = side,
                        end = side,
                        top = 22.dp,
                        bottom = 34.dp
                    )
                ) {
                    // 页头是第一个 item：跟着内容一起滚，不是固定顶栏
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "‹",
                                color = TextColor,
                                fontSize = 26.sp,
                                modifier = Modifier
                                    .clickable { onBack() }
                                    // 左内边距只留一层：列已经有 side，这里再垫 10dp 会让
                                    // 返回箭头和卡片左缘错开一截，看着像多出一条竖线。
                                    .padding(start = 0.dp, end = 12.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(title, color = TextColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                if (subtitle != null) {
                                    Text(
                                        subtitle,
                                        color = SubColor,
                                        fontSize = subtitleSize.sp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                            if (action != null) {
                                Spacer(Modifier.width(6.dp))
                                action()
                            }
                        }
                    }
                    content()
                }
            }
        }
    }
}

/** 卡片：与旧 Ui.card 一样，下面留 10dp 的间距。 */
@Composable
internal fun PageCard(
    soft: Bitmap?,
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(
        soft,
        modifier = modifier,
        padding = padding,
        spacing = spacing,
        content = content
    )
}

/**
 * 一张「连续的卡片」被拆成多行放进 LazyColumn 时用的一行。
 *
 * 1.149 的日志卡是一整张 PageCard，2000 条记录把内容撑到 43 万 px；把行拆成 lazy item 后
 * 卡片也得跟着拆，否则单张卡自己仍然超 Constraints。这里按「首/中/末」画一段与 GlassCard
 * 同材质的卡面 —— 圆角只出现在首末，描边只画整张卡的外缘（左右竖边每段都画，上下横边和
 * 圆角只在首末段画），拼起来和一张完整的卡片一致：接缝处既不重复描边（半透明叠两次会变
 * 深），也不断线。
 */
@Composable
internal fun CardRunRow(
    soft: Bitmap?,
    first: Boolean,
    last: Boolean,
    padding: Dp = 16.dp,
    blurDp: Dp = LocalCardBlur.current,
    content: @Composable ColumnScope.() -> Unit
) {
    val corner = CardCorner
    val shape = RoundedCornerShape(
        topStart = if (first) corner else 0.dp,
        topEnd = if (first) corner else 0.dp,
        bottomStart = if (last) corner else 0.dp,
        bottomEnd = if (last) corner else 0.dp
    )
    val haze = LocalHaze.current
    val base = Modifier.fillMaxWidth().clip(shape)
    val withHaze = if (haze != null) {
        base.hazeEffect(haze) {
            blurRadius = blurDp
            backgroundColor = Color.Transparent
        }
    } else base
    Box(withHaze) {
        // 底色 + 外圈描边（与 GlassCard 同层：压在采样层上、垫在内容下）
        Box(
            Modifier
                .matchParentSize()
                .background(LocalCardTint.current)
                .cardRunBorder(first, last, corner, CardBorder)
        )
        Column(
            Modifier.padding(
                start = padding,
                end = padding,
                top = if (first) padding else 0.dp,
                bottom = if (last) padding else 0.dp
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) { content() }
    }
}

/**
 * 卡片外圈那圈 1dp 细边，按「整张卡」的外缘画：左右竖边每段都补上，上下横边和圆角只在
 * 首末段画。数值与 Modifier.border 一致 —— 描边宽 ceil(1dp)、向内缩半个描边、圆角减半个
 * 描边，拼起来与一整张卡的 border 落在同样的像素上。
 */
private fun Modifier.cardRunBorder(
    first: Boolean,
    last: Boolean,
    corner: Dp,
    color: Color
): Modifier = drawWithContent {
    drawContent()
    val sw = ceil(1.dp.toPx())
    val half = sw / 2f
    val c = corner.toPx()
    val w = size.width
    val h = size.height
    if (w <= sw || h <= 0f) return@drawWithContent
    // 首/末段把圆角那一段让给圆弧，竖边只补圆角以外的部分（半透明叠两次会变深）
    val top = if (first) c.coerceIn(0f, h) else 0f
    val bottom = if (last) (h - c).coerceIn(top, h) else h
    drawRect(color, Offset(0f, top), Size(sw, bottom - top))
    drawRect(color, Offset(w - sw, top), Size(sw, bottom - top))
    if (c > 0f) {
        if (first) {
            clipRect(0f, 0f, w, top) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(half, half),
                    size = Size(w - sw, c * 2f),
                    cornerRadius = CornerRadius((c - half).coerceAtLeast(0f)),
                    style = Stroke(sw)
                )
            }
        }
        if (last) {
            clipRect(0f, bottom, w, h) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(half, h - c * 2f + half),
                    size = Size(w - sw, c * 2f),
                    cornerRadius = CornerRadius((c - half).coerceAtLeast(0f)),
                    style = Stroke(sw)
                )
            }
        }
    } else {
        if (first) drawRect(color, Offset(0f, 0f), Size(w, sw))
        if (last) drawRect(color, Offset(0f, h - sw), Size(w, sw))
    }
}

/** 页头右上角那颗小按钮（旧 Ui.button 的非主色版）。 */
@Composable
internal fun HeaderButton(soft: Bitmap?, text: String, onClick: () -> Unit) {
    GlassCard(
        soft,
        modifier = Modifier.padding(start = 2.dp),
        padding = 0.dp,
        spacing = 0.dp,
        gap = 0.dp,
        // 不撑满：撑满会把 Row 里 weight(1f) 的标题列挤成 0 宽，
        // 标题/副标题整段消失，只剩这颗按钮浮在顶上（1.136 的「胶囊标题」）。
        fillWidth = false
    ) {
        Box(
            Modifier
                .heightIn(min = 40.dp)
                .clickable { onClick() }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = TextColor, fontSize = 14.sp)
        }
    }
}

/** 空态卡：标题 + 一段说明。 */
@Composable
internal fun EmptyCard(soft: Bitmap?, head: String, body: String) {
    PageCard(soft, padding = 18.dp) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(head, color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(body, color = BodyColor, fontSize = 13.5.sp, lineHeight = 20.sp)
        }
    }
}

internal fun closePage(a: Activity) {
    a.finish()
    a.overridePendingTransition(animRes(a, "miui_hold"), animRes(a, "miui_out"))
}

internal fun openPage(a: Activity, cls: Class<*>) {
    a.startActivity(android.content.Intent(a, cls))
    a.overridePendingTransition(animRes(a, "miui_in"), animRes(a, "miui_hold"))
}

private fun animRes(a: Activity, name: String): Int =
    a.resources.getIdentifier(name, "anim", a.packageName)

/** 旧页里的 HttpURLConnection 写法，原样搬过来：3 秒连接、超时/错误抛 IllegalStateException("HTTP n")。 */
internal object Web {

    fun read(url: String, body: String?, readTimeout: Int = 8000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = readTimeout
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val input = if (code >= 400) c.errorStream else c.inputStream
        val text = input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        if (code >= 400) throw IllegalStateException("HTTP " + code)
        return text
    }
}

/** 背景图绘制区的左上角（根坐标，px）与尺寸（px），卡片按它对齐采样。 */
internal val LocalBgOrigin = androidx.compose.runtime.compositionLocalOf { androidx.compose.ui.geometry.Offset.Zero }
internal val LocalBgSize = androidx.compose.runtime.compositionLocalOf { androidx.compose.ui.unit.IntSize.Zero }
