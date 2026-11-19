package com.dsh.pet

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlin.math.roundToInt

/**
 * 一套最小的玻璃组件：卡片自己把「已经糊好」的背景铺满整块，再叠底色、细边和高光。
 *
 * 两代实现并存：
 *  - 设置页/子页面把 [LocalHaze] 灌进来，卡片用 hazeEffect 实时采样身下那块背景（1.134 起）；
 *  - API < 31 没有 RenderEffect，退回「预糊位图」：由调用方把 [soft] 传进来，卡片裁剪着铺满。
 */
@Composable
fun GlassCard(
    soft: Bitmap?,
    modifier: Modifier = Modifier,
    corner: Dp = CardCorner,
    tint: Color = LocalCardTint.current,
    borderColor: Color = CardBorder,
    padding: Dp = 18.dp,
    spacing: Dp = 10.dp,
    blurDp: Dp = LocalCardBlur.current,
    gap: Dp = 14.dp,
    fillWidth: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val bounds = remember { mutableStateOf(Rect.Zero) }
    val shape = RoundedCornerShape(corner)
    val haze = LocalHaze.current
    // 一定铺一层「预糊位图」兜底：Haze 在 API 31 上默认关掉模糊
    // （HazeDefaults.blurEnabled() = SDK >= 32），只靠 hazeEffect 时这张卡就是全透的，
    // 背景反而比原来更清楚 —— 1.136 现场就是这个样子。
    // hazeEffect 继续保留：31+ 上实时层负责跟手，位图保证「任何机器上卡片里一定是糊的」。
    val useBitmap = soft != null && haze == null
    val base = modifier.padding(bottom = gap)
        .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
        .clip(shape)
    val withHaze = if (haze != null) {
        base.hazeEffect(haze) {
            blurRadius = blurDp
            backgroundColor = Color.Transparent
        }
    } else base
    Box(withHaze.onGloballyPositioned { bounds.value = it.boundsInWindow() }) {
        // 背景层：matchParentSize 不参与测量，卡片高度只由里面的内容决定
        Box(Modifier.matchParentSize()) {
            if (useBitmap) {
                // 只画「卡片身下」那一小块：把「根坐标」映射回位图，取源矩形画满卡片。
                // 关键：必须用背景图自己的原点/尺寸（LocalBgOrigin/LocalBgSize），
                // 不能用屏幕 dp 宽高 —— 以前那样算出来的比例和 Image 的实际绘制对不上，
                // 卡片里会像贴了一张缩放过的小图，和外面明显错位。
                val bmp = soft!!.asImageBitmap()
                val origin = LocalBgOrigin.current
                val boxW = LocalBgSize.current.width.toFloat()
                val boxH = LocalBgSize.current.height.toFloat()
                Box(
                    Modifier.matchParentSize().drawBehind {
                        if (boxW <= 0f || boxH <= 0f || size.width < 1f || size.height < 1f) {
                            return@drawBehind
                        }
                        val b = bounds.value
                        val relL = b.left - origin.x
                        val relT = b.top - origin.y
                        val sw = (size.width / boxW * bmp.width).roundToInt().coerceIn(1, bmp.width)
                        val sh = (size.height / boxH * bmp.height).roundToInt().coerceIn(1, bmp.height)
                        val left = (relL / boxW * bmp.width).roundToInt().coerceIn(0, bmp.width - sw)
                        val top = (relT / boxH * bmp.height).roundToInt().coerceIn(0, bmp.height - sh)
                        drawImage(
                            image = bmp,
                            srcOffset = IntOffset(left, top),
                            srcSize = IntSize(sw, sh),
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.Low
                        )
                        // 页面背景在图上面压了 10% 的黑；卡片里这块也得压同样的比例，
                        // 否则卡片内会比外面亮一档，边缘看得出「贴了一块图」。
                        drawRect(Color.Black.copy(alpha = 0.10f))
                    }
                )
            }
        }
        // 底色和描边放在采样层上面：让 1dp 细边保持锐利
        Box(
            Modifier
                .matchParentSize()
                .background(tint)
                .border(1.dp, borderColor, shape)
        )
        Column(
            Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) { content() }
    }
}

@Composable
private fun screenWidth(): Dp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp

@Composable
private fun screenHeight(): Dp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp

/** 卡片里的一行：左边标题+说明，右边自定义（开关、勾、箭头）。 */
@Composable
fun RowItem(
    title: String,
    desc: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (desc != null) {
                Text(desc, color = SubColor, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SwitchItem(title: String, desc: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    RowItem(title, desc, onClick = { onChange(!checked) }) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AccentColor)
        )
    }
}

/**
 * 带百分比的滑杆行。
 *
 * 拖动过程中就回调 [onLive]（卡片立即重绘），松手才回调 [onCommit]（写盘）。
 * 之前只在 onValueChangeFinished 写值，所以拖的时候画面完全不动。
 */
@Composable
fun SliderItem(
    title: String,
    value: Int,
    onLive: (Int) -> Unit,
    onCommit: (Int) -> Unit
) {
    var v by remember { mutableStateOf(value.toFloat()) }
    LaunchedEffect(value) { v = value.toFloat() }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextColor, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("${v.toInt()}%", color = AccentColor, fontSize = 13.sp)
        }
        Slider(
            value = v,
            onValueChange = {
                v = it
                onLive(it.toInt())
            },
            onValueChangeFinished = { onCommit(v.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = AccentColor,
                activeTrackColor = AccentColor,
                inactiveTrackColor = LineColor
            )
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        color = SubColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 10.dp)
    )
}

@Composable
fun Dot(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 10.dp)
            .size(if (selected) 34.dp else 30.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                2.dp,
                if (selected) OnAccent else LineColor,
                CircleShape
            )
            .clickable { onClick() }
    )
}

@Composable
fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(FieldCorner))
                    .background(if (on) AccentColor else FieldTint)
                    .border(
                        1.dp,
                        if (on) Color.Transparent else LineColor,
                        RoundedCornerShape(FieldCorner)
                    )
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (on) OnAccent else TextColor.copy(alpha = 0.85f),
                    fontSize = 14.sp
                )
            }
        }
    }
}

/**
 * 主要按钮：毛玻璃底下是「跟随卡片模糊的强调色半透明底 + 1dp 亮边」，不再盖一层不透明色。
 * 非毛玻璃风格仍然是实心强调色（这里是各风格 spec 自己的选择，不是写死的白）。
 */
@Composable
fun ActionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haze = LocalHaze.current
    val glass = GlassMode && haze != null
    val shape = RoundedCornerShape(ButtonCorner)
    val base = modifier.fillMaxWidth().clip(shape)
    // 读 current 必须在 composable 上下文里，hazeEffect 的 block 不是 composable
    val btnBlur = LocalButtonBlur.current
    val btnAlpha = LocalButtonAlpha.current
    val btnBase = LocalButtonPrimary.current ?: AccentColor
    val bg = if (glass) {
        base.hazeEffect(haze!!) {
            blurRadius = btnBlur
            backgroundColor = Color.Transparent
        }
    } else {
        base.background(AccentColor)
    }
    Box(
        bg.border(
            1.dp,
            if (glass) Color.White.copy(alpha = 0.45f) else Color.Transparent,
            shape
        ).clickable { onClick() }
    ) {
        // 底色单独一层：haze 只做模糊，颜色自己画，这样换色一定看得见
        if (glass) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(btnBase.copy(alpha = btnAlpha))
            )
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Text(text, color = OnAccent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 次要按钮：玻璃时是「跟随卡片模糊的灰色半透明底 + 1dp 边」，实心风格用该风格的输入框底色。 */
@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haze = LocalHaze.current
    val glass = GlassMode && haze != null
    val shape = RoundedCornerShape(ButtonCorner)
    val base = modifier.fillMaxWidth().clip(shape)
    val btnBlur = LocalButtonBlur.current
    val btnAlpha = LocalButtonAlpha.current
    val btnBase = LocalButtonSecondary.current ?: Color.White
    val bg = if (glass) {
        base.hazeEffect(haze!!) {
            blurRadius = btnBlur
            backgroundColor = Color.Transparent
        }
    } else {
        base.background(FieldTint)
    }
    Box(
        bg.border(
            1.dp,
            if (glass) Color.White.copy(alpha = 0.32f) else LineColor,
            shape
        ).clickable { onClick() }
    ) {
        if (glass) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(btnBase.copy(alpha = btnAlpha * 0.45f))
            )
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Text(text, color = TextColor, fontSize = 15.sp)
        }
    }
}

// ---------- 主题注入 ----------

/** 按钮的底色不透明度（0..1）与模糊半径：由 Theme.buttonOpacity/buttonBlur 换算，滑杆可实时覆盖。 */
val LocalButtonAlpha = androidx.compose.runtime.compositionLocalOf { 0.62f }
/** 毛玻璃按钮底色；null = 主按钮跟强调色、次按钮用白膜。 */
val LocalButtonPrimary = androidx.compose.runtime.compositionLocalOf<androidx.compose.ui.graphics.Color?> { null }
val LocalButtonSecondary = androidx.compose.runtime.compositionLocalOf<androidx.compose.ui.graphics.Color?> { null }
val LocalButtonBlur = androidx.compose.runtime.compositionLocalOf { 10.dp }
// 全部是 snapshot state：换风格时读这些值的组件一定会重组。
// 以前是普通 var，换风格只靠 revision 触发，Compose 可能跳过某些子树，导致上一个风格的颜色留在页面上。
var CardBlur: Dp by mutableStateOf(10.dp)
var CardCorner: Dp by mutableStateOf(26.dp)
var ButtonCorner: Dp by mutableStateOf(26.dp)
var FieldCorner: Dp by mutableStateOf(16.dp)
var CardTint: Color by mutableStateOf(Color.White.copy(alpha = 0.06f))
var CardBorder: Color by mutableStateOf(Color.White.copy(alpha = 0.45f))
var FieldTint: Color by mutableStateOf(Color.White.copy(alpha = 0.10f))

var TextColor: Color by mutableStateOf(Color.White)
var SubColor: Color by mutableStateOf(Color.White.copy(alpha = 0.66f))
var BodyColor: Color by mutableStateOf(Color(0xFFCBD4E1))
var AccentColor: Color by mutableStateOf(Color(0xFF3482FF))
var OnAccent: Color by mutableStateOf(Color.White)
var LineColor: Color by mutableStateOf(Color(0x33FFFFFF))

/** 当前风格是不是「毛玻璃」。按钮/输入框据此决定走半透明还是实心。 */
var GlassMode: Boolean by mutableStateOf(false)

/** 由页面注入；非 null 时卡片改用 Haze 的实时采样（游龙同款库）。 */
val LocalHaze = compositionLocalOf<HazeState?> { null }

/**
 * 卡片底色/模糊的「局部覆盖」：滑杆拖动时只替换这两个局部值，
 * 读到它们的卡片当场重绘，不用等松手写盘、也不用整页重组。
 */
val LocalCardTint = compositionLocalOf { CardTint }
val LocalCardBlur = compositionLocalOf { CardBlur }

/** 毛玻璃卡片的不透明度百分比 → 白底 alpha（0 = 全透，100 = 0.55）。 */
/** 毛玻璃卡片的底色基底（随明暗档走：浅=白、中=灰白、深=近黑），由 installTheme 设置。 */
var GlassTintBase: Color by mutableStateOf(Color.White)

/** 透明度（0..100）→ 卡片底色。基底来自 spec.card 的 RGB，不写死白色。 */
fun cardTintFor(percent: Int): Color =
    GlassTintBase.copy(alpha = (percent.coerceIn(0, 100) / 100f) * 0.55f)

/** 毛玻璃卡片的模糊百分比 → haze 的 blurRadius。 */
fun blurDpFor(percent: Int): Dp = (1f + percent.coerceIn(0, 100) * 0.2f).dp

/**
 * 一次性把某个风格的 spec 灌进全局调色板。设置页与所有子页面都走这里，
 * 避免各页自己拼一半、写死一半 —— 那正是「风格互串」的来源。
 */
internal fun installTheme(spec: Theme.Spec, glass: Boolean) {
    TextColor = Color(spec.text)
    SubColor = Color(spec.sub)
    BodyColor = Color(spec.body)
    AccentColor = Color(spec.accent)
    OnAccent = Color(spec.accentText)
    LineColor = Color(spec.line)
    FieldTint = Color(spec.field)
    GlassMode = glass
    CardCorner = spec.radiusCard.dp
    ButtonCorner = spec.radiusButton.dp
    FieldCorner = if (CardCorner > 18.dp) CardCorner - 8.dp else 10.dp
    GlassTintBase = Color(android.graphics.Color.red(spec.card), android.graphics.Color.green(spec.card), android.graphics.Color.blue(spec.card))
    CardTint = if (glass) GlassTintBase.copy(alpha = spec.cardAlpha / 255f * 0.55f) else Color(spec.card)
    CardBorder = if (glass) Color.White.copy(alpha = 0.32f) else Color(spec.strokeColor)
    CardBlur = if (glass) blurDpFor(spec.cardBlur) else 10.dp
}

/** @deprecated 用 [installTheme]；留着只是为了让还没改完的调用点编译。 */
internal fun applyPalette(spec: Theme.Spec) {
    TextColor = Color(spec.text)
    SubColor = Color(spec.sub)
    AccentColor = Color(spec.accent)
    BodyColor = Color(spec.body)
    LineColor = Color(spec.line)
}

@Composable
fun Gap(h: Dp) {
    Spacer(Modifier.height(h))
}

@Composable
fun DividerLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LineColor)
    )
}

@Suppress("unused")
@Composable
fun PreviewBadge(bitmap: Bitmap) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))
    )
    Spacer(Modifier.width(8.dp))
}
