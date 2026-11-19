package com.dsh.pet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * 界面全部用 Compose 重写的设置页：布局、内容、顺序与旧版 Java 页一一对应，
 * 只是把「画」这件事整个换掉了 —— 卡片、按钮、页签条都走 GlassKit。
 *
 * 1.136 的三条硬规矩：
 *  1. 一个板块（权限/大脑/记忆/…）只有一张卡，板块内的行用 DividerLine 分隔；
 *  2. 颜色/圆角/底色只从 Theme.spec 来（installTheme 统一灌），不写死 Color.White；
 *  3. 卡片之间至少 14dp（GlassCard 的 gap），板块标题与卡片之间 10dp（SectionTitle）。
 */
class SettingsActivity : ComponentActivity() {

    private var pick: ((Uri?) -> Unit)? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pick?.invoke(uri)
        pick = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        Logs.init(this)
        var revision by mutableStateOf(0)
        val rebake: () -> Unit = { revision++ }
        setContent {
            val rev = revision
            val style = remember(rev) { Theme.style(this) }
            val spec = remember(rev) { Theme.spec(this) }
            val glass = Theme.GLASS == style
            // 调色板一次性装好：文字/底色/圆角/模糊全从这一个 spec 来，风格之间不会串
            installTheme(spec, glass)
            // 只有毛玻璃一档才铺背景图：其余风格的卡片是不透明实色，铺图只会白卡配白字。
            val image = if (glass) remember(rev) { Backdrop.source(this) } else null
            // 交给卡片的「已经用 CPU 糊好的位图」：API < 31 没有 RenderEffect 时靠它兜底。
            val soft: Bitmap? = if (glass) remember(rev, style) {
                Backdrop.blurred(this, Theme.cardBlur(this, style))
            } else null
            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner) {
                val obs = LifecycleEventObserver { _, e ->
                    if (e == Lifecycle.Event.ON_RESUME) rebake()
                }
                owner.lifecycle.addObserver(obs)
                onDispose { owner.lifecycle.removeObserver(obs) }
            }
            SettingsScreen(
                bg = image,
                soft = soft,
                style = style,
                spec = spec,
                onRebake = rebake,
                onPickImage = {
                    pick = { uri ->
                        if (uri != null) {
                            try {
                                Backdrop.save(this, uri)
                                Backdrop.setMode(this, Backdrop.IMAGE)
                                toast("背景换好了")
                            } catch (e: Throwable) {
                                toast("这张图读不了：" + e.message)
                            }
                            rebake()
                        }
                    }
                    picker.launch(arrayOf("image/*"))
                }
            )
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /**
     * 这个页面是 Compose 重写的，真机上万一在组合阶段抛异常，系统往往连崩溃弹窗都不给，
     * 直接闪退。所以自己接一层：把堆栈写到共享区（没权限就写私有目录），
     * 然后退回旧的 Java 设置页，至少让应用还能进去。
     */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val text = "time=" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()) + "\n\n" + android.util.Log.getStackTraceString(e)
            try {
                val shared = java.io.File("/sdcard/DSHBox")
                if (shared.isDirectory || shared.mkdirs()) {
                    java.io.File(shared, "pet-crash.txt").writeText(text)
                }
            } catch (x: Throwable) { }
            try {
                java.io.File(filesDir, "crash.txt").writeText(text)
            } catch (x: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SettingsScreen(
    bg: Bitmap?,
    soft: Bitmap?,
    style: String,
    spec: Theme.Spec,
    onRebake: () -> Unit,
    onPickImage: () -> Unit
) {
    val ctx = LocalContext.current
    val act = ctx as Activity
    val haze = remember { HazeState() }
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("大脑", "日志", "外观", "介绍", "关于")
    // 滑杆拖动中的临时值：只覆盖卡片的底色/模糊，松手才写盘。
    var liveTint by remember { mutableStateOf<Color?>(null) }
    var liveBlur by remember { mutableStateOf<Dp?>(null) }
    var liveBtnAlpha by remember { mutableStateOf<Float?>(null) }
    // 按钮底色用状态存：点色点后立刻重绘，不等 onRebake 的整页重建
    var btnPrimary by remember { mutableStateOf(Theme.buttonPrimary(ctx, style)) }
    var btnSecondary by remember { mutableStateOf(Theme.buttonSecondary(ctx, style)) }
    var liveBtnBlur by remember { mutableStateOf<Dp?>(null) }
    val glass = bg != null
    // 背景图的实际绘制原点/尺寸，卡片按它对齐采样（和子页面同一套）
    val bgOrigin = remember { mutableStateOf(Offset.Zero) }
    val bgSize = remember { mutableStateOf(IntSize.Zero) }

    CompositionLocalProvider(
        LocalHaze provides (if (glass) haze else null),
        LocalCardTint provides (liveTint ?: CardTint),
        LocalCardBlur provides (liveBlur ?: CardBlur),
        LocalButtonAlpha provides (liveBtnAlpha
            ?: (Theme.buttonOpacity(ctx, style) / 100f)),
        LocalButtonBlur provides (liveBtnBlur
            ?: blurDpFor(Theme.buttonBlur(ctx, style))),
        LocalButtonPrimary provides (if (btnPrimary == 0) null else Color(btnPrimary)),
        LocalButtonSecondary provides (if (btnSecondary == 0) null else Color(btnSecondary)),
        LocalBgOrigin provides bgOrigin.value,
        LocalBgSize provides bgSize.value
    ) {
        Box(Modifier.fillMaxSize().background(Color(spec.bg))) {
            if (bg != null) {
                // 背景图和压暗层一起进 hazeSource：卡片采样到的就是「压暗之后」的画面
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
                        // 与卡片采样保持一致：位图直接铺满背景框，不做 Crop 的额外缩放
                        contentScale = ContentScale.FillBounds
                    )
                    // 照片有亮有暗，字必须自带对比度：统一压一层暗（1.136 从 42% 降到 10%）
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.10f)))
                }
            }
            Column(Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    androidx.compose.foundation.LocalOverscrollConfiguration provides null
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp)
                    ) {
                        HeaderCard(soft)
                        when (tab) {
                            0 -> BrainPage(ctx, act, soft, onRebake)
                            1 -> LogPage(ctx, soft)
                            2 -> AppearancePage(
                                ctx, act, soft, style, spec, onRebake, onPickImage,
                                onLiveOpacity = { liveTint = cardTintFor(it) },
                                onLiveBlur = { liveBlur = blurDpFor(it) },
                                onLiveBtnAlpha = { liveBtnAlpha = it / 100f },
                                onLiveBtnBlur = { liveBtnBlur = blurDpFor(it) },
                                onPickPrimary = { btnPrimary = it; Theme.setButtonPrimary(ctx, style, it) },
                                onPickSecondary = { btnSecondary = it; Theme.setButtonSecondary(ctx, style, it) },
                                onLiveDone = {
                                    liveTint = null
                                    liveBlur = null
                                    liveBtnAlpha = null
                                    liveBtnBlur = null
                                    onRebake()
                                }
                            )
                            3 -> IntroPage(soft)
                            else -> AboutPage(ctx, act, soft)
                        }
                        Gap(26.dp)
                    }
                }
                Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    GlassCard(soft, corner = CardCorner, padding = 5.dp, spacing = 0.dp, gap = 0.dp) {
                        Row(Modifier.fillMaxWidth()) {
                            tabs.forEachIndexed { i, name ->
                                val on = i == tab
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(ButtonCorner))
                                        .background(if (on) AccentColor else Color.Transparent)
                                        .clickable {
                                            if (tab != i) {
                                                tab = i
                                                liveTint = null
                                                liveBlur = null
                                            }
                                        }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        name,
                                        color = if (on) OnAccent else TextColor.copy(alpha = 0.8f),
                                        fontSize = 13.sp,
                                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 板块标题 + 一张卡。板块里的所有文字/行/按钮都放进这张卡，用 DividerLine 分开。 */
@Composable
private fun Section(
    title: String,
    soft: Bitmap?,
    spacing: Dp = 10.dp,
    // 拖动滑杆时 LocalCardTint/LocalCardBlur 是"预览值"；微调卡自己钉住已保存的值，
    // 免得透明度拖到 0 时连滑杆都看不见、没法再拖回来。
    tint: Color = LocalCardTint.current,
    blurDp: Dp = LocalCardBlur.current,
    content: @Composable ColumnScope.() -> Unit
) {
    SectionTitle(title)
    GlassCard(soft, spacing = spacing, tint = tint, blurDp = blurDp) { content() }
}

@Composable
private fun HeaderCard(soft: Bitmap?) {
    val ctx = LocalContext.current
    // 应用图标在 API 26+ 上是 adaptive icon（一个 XML），painterResource 只认
    // VectorDrawable 和 raster（PNG/JPG/WEBP），直接喂它会抛
    // IllegalArgumentException: Only VectorDrawables and rasterized asset types are supported。
    // 所以走 PackageManager 拿 Drawable，再自己画成位图。
    val icon = remember { appIcon(ctx, 108) }
    GlassCard(soft, corner = CardCorner) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(54.dp).clip(RoundedCornerShape(FieldCorner))
                )
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text("桌面宠物", color = TextColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "蓝毛小女仆 · 住在你的桌面上",
                    color = SubColor, fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** 小圆角标签：右边那个「已授权 / 未开启」。 */
@Composable
private fun Chip(text: String, good: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(FieldCorner))
            .background(if (good) Color(0xFF1F8F58) else LineColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            color = if (good) OnAccent else TextColor.copy(alpha = 0.85f),
            fontSize = 11.5.sp
        )
    }
}

@Composable
private fun BrainPage(ctx: Context, act: Activity, soft: Bitmap?, onRebake: () -> Unit) {
    // 状态先声明：整块「大脑」合成一张卡，变量不能在卡里面声明
    var mode by remember { mutableStateOf(Config.mode(ctx)) }
    val key = remember { mutableStateOf(Config.key(ctx) ?: "") }
    val model = remember { mutableStateOf(Config.model(ctx) ?: "") }
    val base = remember { mutableStateOf(Config.base(ctx) ?: "") }
    // 人设：未自定义时直接把默认原文填进输入框（以前留空只显 placeholder，
    // 看着像「默认人设不见了」）。在这个基础上改，保存时等于原文就按恢复默认处理。
    var persona by remember { mutableStateOf(Persona.custom(ctx).ifBlank { Persona.WHISPER }) }
    var nightSpeak by remember { mutableStateOf(Config.nightSpeak(ctx)) }

    PermSection(ctx, act, soft)

    Section("大脑", soft) {
        Text(
            "她说话要靠一个模型。可以用 DSHBox 的登录态（不用填 key），也可以自己填一个 DeepSeek API Key 直连——那样不装 DSHBox 也能用。",
            color = SubColor, fontSize = 13.sp
        )
        DividerLine()
        ModeRow("自动", "填了 key 就直连，没填就走 DSHBox", mode == Config.MODE_AUTO) {
            mode = Config.MODE_AUTO; Config.setMode(ctx, mode)
        }
        DividerLine()
        ModeRow("只用 DSHBox 的登录态", "不用填 key，但手机上得装 DSHBox", mode == Config.MODE_HOST) {
            mode = Config.MODE_HOST; Config.setMode(ctx, mode); onRebake()
        }
        DividerLine()
        ModeRow("自填 API Key 直连", "独立使用，不依赖 DSHBox", mode == Config.MODE_DIRECT) {
            mode = Config.MODE_DIRECT; Config.setMode(ctx, mode)
        }
        DividerLine()
        RowItem(
            "第一次用 DSHBox 登录态？",
            "得先在 DSHBox 里装 pet-brain 插件，否则她只会说「连不上大脑」",
            onClick = { showPluginGuide(ctx, act) }
        ) { Chip("看怎么装", true) }
        DividerLine()
        Field("API Key（sk-…，只存在本机）", key.value) { key.value = it }
        Field("模型（留空用 deepseek-chat）", model.value) { model.value = it }
        Field("接口地址（留空用 https://api.deepseek.com）", base.value) { base.value = it }
        DividerLine()
        GhostButton("保存设置") {
            Config.setKey(ctx, key.value.trim())
            Config.setModel(ctx, model.value.trim())
            Config.setBase(ctx, base.value.trim())
            Toast.makeText(ctx, "存好了", Toast.LENGTH_SHORT).show()
        }
        DividerLine()
        Text(
            "底下这段就是她的人设。默认那套改不了；下面是默认原文，可以直接在这上面改，也可以另存一套，写「你是……」。长度（20 字 / 60 字）、别提 AI、记忆协议这些我会替你补上。点「用回默认人设」就恢复成默认原文。",
            color = SubColor, fontSize = 12.sp
        )
        Field(
            "她的人设（下面就是默认那套，可以直接改）",
            persona,
            minLines = 5,
            placeholder = "她的人设……"
        ) { persona = it }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GhostButton("保存人设", Modifier.weight(1f)) {
                val t = persona.trim()
                Persona.save(ctx, persona)
                Toast.makeText(
                    ctx,
                    if (t.isEmpty() || t == Persona.WHISPER) "回到默认人设了" else "人设存好了",
                    Toast.LENGTH_SHORT
                ).show()
            }
            GhostButton("用回默认人设", Modifier.weight(1f)) {
                Persona.clear(ctx)
                // 恢复成默认原文，而不是清空成 placeholder
                persona = Persona.WHISPER
                Toast.makeText(ctx, "回到默认人设了", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Section("说话", soft) {
        Text(
            "她平时会自己找话说：安静一会儿就碎碎念一句，换到新应用也会看一眼要不要开口。\n\n" +
                "两次主动开口至少隔 75 秒；刚换应用时要隔 3 分钟以上，才有三成机会说一句。\n\n" +
                "0 点到早上 8 点默认不出声——想让她夜里也照常说，把下面这个打开。",
            color = SubColor, fontSize = 12.5.sp
        )
        DividerLine()
        SwitchItem(
            "夜里也说话（0:00–8:00）",
            if (nightSpeak) "开着：夜里也会主动搭话" else "默认关：这个点不打扰你",
            nightSpeak
        ) {
            nightSpeak = it
            Config.setNightSpeak(ctx, it)
        }
    }

    Section("宿主插件", soft) {
        Text(
            "选「只用 DSHBox 的登录态」时，她说话、记事、清日志都靠 DSHBox 里一个叫 pet-brain 的小插件。没装的时候她会一直说「连不上大脑」——那不是没登录，是那头没人应答。\n\n" +
                "点下面的按钮，插件会解压到 DSHBox 的共享区（手机上是 /sdcard/DSHBox/pet-brain-plugin，容器里就是 /root/share/DSHBox/pet-brain-plugin），里面带一份安装说明。\n\n" +
                "没识别到共享区时也有退路：如果已经给了「所有文件访问」，会落到系统下载目录 /sdcard/Download/pet-brain-plugin/，" +
                "自己找得到、也搬得动；连这个权限都没有，就落到本 App 自己的外部目录 Android/data/com.dsh.pet/files/share/pet-brain-plugin/，" +
                "不用任何权限，拷出来一样能用——换一台没装 DSHBox 的手机时就是这条路。\n\n" +
                "插件是随 APK 走的，版本跟当前安装的 App 一致；装完要整个退出 DSHBox 再打开才生效。\n\n" +
                "装的时候最省事：导出之后，把「把共享区里的 pet-brain 插件装进 profile」这句话丢给 DSHBox 的助手，" +
                "它会照说明把文件放好、把 profile 的清单改好。",
            color = SubColor, fontSize = 12.5.sp
        )
        DividerLine()
        GhostButton("导出插件到共享区") { exportPlugin(ctx, act) }
    }

    Section("宠物", soft) {
        Text(
            "收起之后她就不会自己回来了：点「收起宠物」，再从后台划掉这个 App，等十几秒——桌面上不该再出现她。想叫她回来，重新打开这里点「启动宠物」即可。",
            color = SubColor,
            fontSize = 12.5.sp
        )
        DividerLine()
        ActionButton("启动宠物") {
            // 先记下「用户想要」，否则服务 onCreate 里那道「已收起就不出现」会把这次启动挡掉
            Config.setWanted(ctx, true)
            val svc = Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_START)
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
            Toast.makeText(ctx, "叫她出来了", Toast.LENGTH_SHORT).show()
        }
        DividerLine()
        GhostButton("收起宠物") {
            // 用 ACTION_STOP 而不是 stopService：这样会记下「用户不想看到她」，
            // 系统把服务拉回来时也不会自己冒出来。
            ctx.startService(
                Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)
            )
            Toast.makeText(ctx, "收起来了", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun ModeRow(title: String, desc: String, on: Boolean, onClick: () -> Unit) {
    RowItem(title, desc, onClick = onClick) {
        Box(
            Modifier
                .size(20.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (on) AccentColor else LineColor),
            contentAlignment = Alignment.Center
        ) {
            if (on) Text("✓", color = OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PermSection(ctx: Context, act: Activity, soft: Bitmap?) {
    val overlay = Settings.canDrawOverlays(ctx)
    val a11y = a11yOn(ctx)
    val notif = notifOn(ctx)
    val files = allFilesOn(ctx)
    Section("权限", soft) {
        RowItem("显示在其他应用上层", "让她能浮在桌面和别的应用上面", onClick = {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
            )
        }) { Chip(if (overlay) "已授权" else "未授权", overlay) }
        DividerLine()
        RowItem("无障碍读屏", "看懂你在用什么应用、屏幕上写着什么", onClick = {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Chip(if (a11y) "已开启" else "未开启", a11y) }
        DividerLine()
        RowItem("通知", "收起按键常驻在这里，免得找不回来", onClick = {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            )
        }) { Chip(if (notif) "已授权" else "未授权", notif) }
        DividerLine()
        RowItem(
            "所有文件访问",
            "往共享区导出插件、写崩溃日志都要它；不开就只能写应用自己的目录",
            onClick = { openAllFiles(ctx, act) }
        ) { Chip(if (files) "已授权" else "未授权", files) }
        DividerLine()
        Text(
            "权限可能随时被系统取消，得经常关注此页面。如果你收起宠物后划掉后台再进入软件发现无障碍权限掉了，" +
                "请你先启动宠物再授权无障碍，不然你会在无障碍授权页发现桌面宠物的无障碍权限异常。",
            color = SubColor,
            fontSize = 12.5.sp
        )
        ActionButton("一键补齐权限") { fillPerms(ctx, act, overlay, a11y, notif, files) }
    }
}

private fun a11yOn(ctx: Context): Boolean {
    val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    val list = am.getEnabledAccessibilityServiceList(
        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
    )
    return list.any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
}

private fun notifOn(ctx: Context): Boolean {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
    return nm != null && nm.areNotificationsEnabled()
}

/** Android 11 起，写 /sdcard 上应用自己的目录以外的地方要「所有文件访问」。 */
private fun allFilesOn(ctx: Context): Boolean {
    return try {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.os.Environment.isExternalStorageManager()
        } else {
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    } catch (e: Throwable) {
        false
    }
}

private fun openAllFiles(ctx: Context, act: Activity) {
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        try {
            act.startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + ctx.packageName)
                ), 1005
            )
            Toast.makeText(ctx, "把「所有文件访问」打开，回来再点一次", Toast.LENGTH_LONG).show()
            return
        } catch (e: Throwable) {
            act.startActivityForResult(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), 1005)
            return
        }
    }
    act.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1005)
}

private fun fillPerms(
    ctx: Context,
    act: Activity,
    overlay: Boolean,
    a11y: Boolean,
    notif: Boolean,
    files: Boolean
) {
    if (!overlay) {
        act.startActivityForResult(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)), 1001
        )
        Toast.makeText(ctx, "先打开「显示在其他应用上层」，回来再点一次", Toast.LENGTH_LONG).show()
        return
    }
    if (!a11y) {
        act.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(ctx, "到无障碍里找到「桌面宠物」打开，回来再点一次", Toast.LENGTH_LONG).show()
        return
    }
    if (!notif) {
        act.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1002)
        return
    }
    if (!files) {
        openAllFiles(ctx, act)
        return
    }
    Toast.makeText(ctx, "四样都齐了", Toast.LENGTH_SHORT).show()
}

private fun exportPlugin(ctx: Context, act: Activity) {
    // 只有在共享区真的存在时才值得为它去要「所有文件访问」；
    // 没有共享区（换台手机、没开共享区）就直接落到 App 自己的外部目录，不折腾权限。
    if (PluginExporter.sharedRoot().isDirectory() && !PluginExporter.hasAccess(ctx)) {
        PluginExporter.requestAccess(act, 1004)
        return
    }
    try {
        val r = PluginExporter.export(ctx)
        val msg = when {
            r.shared -> "已导出到 " + r.where
            r.downloads -> "没识别到 DSHBox 共享区（有「所有文件访问」），已导出到系统下载目录：\n" +
                r.where + "\n把 pet-brain-plugin 文件夹挪到 /sdcard/DSHBox/ 就是共享区；" +
                "或者直接让 DSH 会话读这个路径。"
            else -> "已导出到 " + r.where + "\n（没找到共享区，先放这儿；把 pet-brain-plugin 文件夹拷到" +
                " DSHBox 的共享区，或让 DSH 会话直接读这个路径）"
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    } catch (e: Throwable) {
        Toast.makeText(ctx, "导出失败：" + e.message, Toast.LENGTH_LONG).show()
    }
}

private fun showPluginGuide(ctx: Context, act: Activity) {
    android.app.AlertDialog.Builder(act)
        .setTitle("先把插件装进 DSHBox")
        .setMessage(
            "最省事的一步：先点下面的「导出插件」，让文件落到共享区，然后把这句话原样丢给 DSHBox 的助手——\n\n" +
                "「把共享区里的 pet-brain 插件装进 profile」\n\n" +
                "它会照说明把两份文件放好、改好 profile 的清单。装完完全退出 DSHBox 再打开（只退到后台不算），" +
                "http://127.0.0.1:3080/pet-brain/health 返回 ok 就成了。\n\n" +
                "想自己动手（先放文件、最后改清单）：\n" +
                "1. 导出插件，解到 /sdcard/DSHBox/pet-brain-plugin\n" +
                "2. plugin 文件夹放到 DSHBox 的 dshbox-plugins/pet-brain/\n" +
                "3. 再复制一份到 profile 的 node_modules/@local/pet-brain\n" +
                "4. 最后在 profiles/web/package.json 里登记包名\n" +
                "5. 完全退出 DSHBox 再打开"
        )
        .setPositiveButton("导出插件") { _, _ -> exportPlugin(ctx, act) }
        .setNegativeButton("知道了", null)
        .show()
}

@Composable
private fun LogPage(ctx: Context, soft: Bitmap?) {
    Section("日志", soft) {
        Text(
            "分成四页：她以为你在哪、什么时候读过屏、她到底看见了什么、以及你俩的聊天记录。读屏和位置记录只留最近两天，聊天记录只留最近三天的原话，都写在本机，不上传。",
            color = SubColor, fontSize = 13.sp
        )
        DividerLine()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("读屏时间线", Modifier.weight(1f)) {
                ctx.startActivity(Intent(ctx, ScreenLogActivity::class.java))
            }
            GhostButton("看见了什么", Modifier.weight(1f)) {
                ctx.startActivity(Intent(ctx, SeenActivity::class.java))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("你都在哪", Modifier.weight(1f)) {
                ctx.startActivity(Intent(ctx, PlaceLogActivity::class.java))
            }
            GhostButton("聊天记录", Modifier.weight(1f)) {
                ctx.startActivity(Intent(ctx, ChatLogActivity::class.java))
            }
        }
    }
}

@Composable
private fun AppearancePage(
    ctx: Context,
    act: Activity,
    soft: Bitmap?,
    style: String,
    spec: Theme.Spec,
    onRebake: () -> Unit,
    onPickImage: () -> Unit,
    onLiveOpacity: (Int) -> Unit,
    onLiveBlur: (Int) -> Unit,
    onLiveBtnAlpha: (Int) -> Unit,
    onLiveBtnBlur: (Int) -> Unit,
    onPickPrimary: (Int) -> Unit,
    onPickSecondary: (Int) -> Unit,
    onLiveDone: () -> Unit
) {
    val notes = listOf(
        "白卡、蓝按钮、18dp 圆角，最像系统自带的那一档",
        "紫调、24dp 大圆角、填充式为主，Google 现行设计语言",
        "浅灰底、纯白卡、胶囊按钮，小米 HyperOS 4 的观感",
        "卡片里透出自己身下那块已经糊好的背景，柔和、安静"
    )

    Section("界面风格", soft) {
        PreviewCard()
        DividerLine()
        Text(
            "现在是「" + Theme.nameOf(style) + "」。四种底子随便换，每种还能单独调背景明暗、强调色、圆角；换完这一页立刻跟着变。",
            color = SubColor, fontSize = 12.5.sp
        )
        Theme.STYLES.forEachIndexed { i, st ->
            DividerLine()
            RowItem(Theme.NAMES[i], notes.getOrElse(i) { "" }, onClick = {
                Theme.setStyle(ctx, st)
                // 自定义背景只属于玻璃档：切到别的档就把模式收回「跟风格走」，
                // 图片文件留在本机，切回玻璃档还能继续用。
                if (!Theme.GLASS.equals(st)) Backdrop.reset(ctx)
                onLiveDone()
            }) {
                if (st == style) Text("✓", color = AccentColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    Section("微调 · " + Theme.nameOf(style), soft, tint = CardTint, blurDp = CardBlur) {
        Text("背景明暗", color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("深色会自动配浅色文字，保证看得清", color = SubColor, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tone = Theme.tone(ctx, style)
            listOf("浅", "中", "深").forEachIndexed { i, name ->
                Pill(name, i == tone, Modifier.weight(1f)) {
                    Theme.setTone(ctx, style, i); onRebake()
                }
            }
        }

        DividerLine()
        Text("强调色", color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("按钮、选中态、勾选标记都跟着它变", color = SubColor, fontSize = 12.sp)
        val accent = Theme.accentIndex(ctx, style)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in 0 until Theme.accentCount()) {
                Box(
                    Modifier
                        .size(if (i == accent) 30.dp else 26.dp)
                        .clip(RoundedCornerShape(FieldCorner))
                        .background(Color(Theme.accentAt(i)))
                        .border(
                            if (i == accent) 2.dp else 1.dp,
                            if (i == accent) OnAccent else LineColor,
                            RoundedCornerShape(FieldCorner)
                        )
                        .clickable { Theme.setAccentIndex(ctx, style, i); onRebake() }
                )
            }
        }

        DividerLine()
        Text("圆角", color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("卡片和按钮的圆润程度", color = SubColor, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val r = Theme.radiusIndex(ctx, style)
            for (i in 0 until Theme.radiusCount()) {
                Pill(Theme.radiusAt(i).toString() + "dp", i == r, Modifier.weight(1f)) {
                    Theme.setRadiusIndex(ctx, style, i); onRebake()
                }
            }
        }

        if (Theme.GLASS == style) {
            DividerLine()
            SliderItem(
                "卡片透明度",
                Theme.cardOpacity(ctx, style),
                onLive = onLiveOpacity,
                onCommit = {
                    Theme.setCardOpacity(ctx, style, it)
                    onLiveDone()
                }
            )
            SliderItem(
                "卡片模糊",
                Theme.cardBlur(ctx, style),
                onLive = onLiveBlur,
                onCommit = {
                    Theme.setCardBlur(ctx, style, it)
                    onLiveDone()
                }
            )
            SliderItem(
                "按钮透明度",
                Theme.buttonOpacity(ctx, style),
                onLive = onLiveBtnAlpha,
                onCommit = {
                    Theme.setButtonOpacity(ctx, style, it)
                    onLiveDone()
                }
            )
            SliderItem(
                "按钮模糊",
                Theme.buttonBlur(ctx, style),
                onLive = onLiveBtnBlur,
                onCommit = {
                    Theme.setButtonBlur(ctx, style, it)
                    onLiveDone()
                }
            )
            DividerLine()
            Text("主按钮底色", color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("默认跟强调色；选一个就固定用它", color = SubColor, fontSize = 12.sp)
            BtnColorRow(Theme.buttonPrimary(ctx, style), true) { onPickPrimary(it) }
            DividerLine()
            Text("次按钮底色", color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("默认是白膜；选一个就固定用它", color = SubColor, fontSize = 12.sp)
            BtnColorRow(Theme.buttonSecondary(ctx, style), false) { onPickSecondary(it) }
            DividerLine()
            GhostButton("这个风格恢复默认") {
                Theme.reset(ctx, style); onLiveDone()
            }
        }

        DividerLine()
        Text("文字颜色", color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("大字是标题和接口名，小字是说明文字。不选就是跟随当前风格的自动配色。", color = SubColor, fontSize = 12.sp)
        Text("大字颜色", color = TextColor, fontSize = 13.sp)
        BtnColorRow(Theme.textColor(ctx, style), true) {
            Theme.setTextColor(ctx, style, it)
            // 立刻把新颜色灌进调色板（TextColor 是 mutableStateOf，读它的地方会马上重绘）
            TextColor = Color(Theme.spec(ctx).text)
            onRebake()
        }
        SliderItem(
            "大字深浅",
            if (Theme.textAlpha(ctx, style) < 0) 100 else Theme.textAlpha(ctx, style),
            onLive = { },
            onCommit = {
                Theme.setTextAlpha(ctx, style, it)
                val sp2 = Theme.spec(ctx)
                TextColor = Color(sp2.text)
                onRebake()
            }
        )
        DividerLine()
        Text("小字颜色", color = TextColor, fontSize = 13.sp)
        BtnColorRow(Theme.subColor(ctx, style), false) {
            Theme.setSubColor(ctx, style, it)
            SubColor = Color(Theme.spec(ctx).sub)
            onRebake()
        }
        SliderItem(
            "小字深浅",
            if (Theme.subAlpha(ctx, style) < 0) 66 else Theme.subAlpha(ctx, style),
            onLive = { },
            onCommit = {
                Theme.setSubAlpha(ctx, style, it)
                val sp3 = Theme.spec(ctx)
                SubColor = Color(sp3.sub)
                onRebake()
            }
        )
    }

    if (Theme.GLASS == style) Section("背景", soft) {
        RowItem("跟风格走", "背景图不显示，只用风格自己的底色", onClick = {
            Backdrop.reset(ctx); onRebake()
        }) { if (Backdrop.mode(ctx) == Backdrop.DEFAULT) Text("✓", color = AccentColor) }
        DividerLine()
        RowItem("自己选一张", "复制到本机，当整页背景", onClick = {
            if (Backdrop.hasImage(ctx)) {
                Backdrop.setMode(ctx, Backdrop.IMAGE); onRebake()
            } else onPickImage()
        }) { if (Backdrop.mode(ctx) == Backdrop.IMAGE) Text("✓", color = AccentColor) }
        DividerLine()
        GhostButton("选一张图片") { onPickImage() }
    }

}

/** 外观页顶上的那张小预览：真卡片 + 主次按钮（按钮本身用的就是页面里的那两套）。 */
@Composable
private fun PreviewCard() {
    Text("预览", color = SubColor, fontSize = 12.sp)
    Text("桌面宠物", color = TextColor, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    Text("她今天读了 12 次屏，说了 31 句话", color = SubColor, fontSize = 12.5.sp)
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ActionButton("主要按钮", Modifier.weight(1f)) {}
        GhostButton("次要按钮", Modifier.weight(1f)) {}
    }
}

/** 一颗可选中的胶囊。 */
@Composable
private fun Pill(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(FieldCorner))
            .background(if (on) AccentColor else FieldTint)
            .border(1.dp, if (on) Color.Transparent else LineColor, RoundedCornerShape(FieldCorner))
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (on) OnAccent else TextColor, fontSize = 13.sp)
    }
}

@Composable
private fun IntroPage(soft: Bitmap?) {
    SectionTitle("介绍")
    IntroContent(soft)
}

@Composable
private fun AboutPage(ctx: Context, act: Activity, soft: Bitmap?) {
    // 本机那几项先算；宿主那部分要联网（/pet-brain/stat），必须放到后台线程去，
    // 主线程直接联网会抛 NetworkOnMainThreadException，界面就只能显示「—」。
    var usage by remember { mutableStateOf(Cleaner.scanLocal(ctx)) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        val t = Thread({
            val u = Cleaner.scan(ctx)
            main.post { usage = u }
        }, "pet-usage")
        t.isDaemon = true
        t.start()
        onDispose { }
    }
    Section("记忆", soft) {
        Text(
            "她会把你说过「要记住」的事攒下来，下次聊天时自己想起来。这里能看见到底记了什么，也能删掉记错的那几条。",
            color = SubColor, fontSize = 13.sp
        )
        DividerLine()
        GhostButton("看看她记住了什么") { ctx.startActivity(Intent(ctx, MemoryActivity::class.java)) }
    }

    Section("用量", soft) {
        Text(
            "她每说一次话花掉多少 token、折多少钱：今天、近七天、本月各一栏，数字取接口回报的 usage，不是按字数估的。宿主模式下这笔账记在插件里，直连模式记在本机。",
            color = SubColor, fontSize = 13.sp
        )
        DividerLine()
        GhostButton("看看用量") { ctx.startActivity(Intent(ctx, UsageActivity::class.java)) }
    }


    Section("存储与清理", soft) {
        RowItem("空闲动作缓存", "换下来的动作解出来的图，最多 42 MB") {
            Chip(fmtSize(usage.frames), true)
        }
        DividerLine()
        RowItem("本机临时文件", "缓存目录 + 使用习惯快照") {
            Chip(fmtSize(usage.localFiles), true)
        }
        DividerLine()
        RowItem("日志与记忆", "DSHBox 侧的请求日志，会一直变长") {
            Chip(
                if (usage.hostBytes < 0) "—" else fmtSize(usage.hostBytes),
                usage.hostBytes >= 0
            )
        }
        DividerLine()
        Text(
            "点「立即清理」会丢掉：换下来的动作图（正在播的那个不动）、缓存目录里的临时文件、已经推给 DSHBox 的本地习惯快照、DSHBox 侧超过 200 行的请求日志，并把她的长期记忆裁到最近 60 条。\n\n不会动：API Key、模型与接口地址、直连时的长期记忆，以及她正在播放的动画和当前位置。清理后第一次换动作会重新解码一次，约多花 3 毫秒。",
            color = SubColor, fontSize = 12.sp
        )
        DividerLine()
        GhostButton("立即清理") {
            Toast.makeText(ctx, "正在清理…", Toast.LENGTH_SHORT).show()
            val t = Thread({
                val r = Cleaner.clean(ctx)
                val after = Cleaner.scan(ctx)
                main.post {
                    usage = after
                    val msg = "清掉了 " + fmtSize(r.framesFreed + r.filesFreed)
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            }, "pet-clean")
            t.isDaemon = true
            t.start()
        }
    }

    Section("这台手机", soft) {
        Text(Oem.title(), color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        DividerLine()
        Text(Oem.a11yTip(), color = SubColor, fontSize = 12.5.sp)
        val tips = Oem.tips()
        tips.forEachIndexed { i, tip ->
            DividerLine()
            RowItem(tip[0], tip[1], onClick = {
                // 不再按行号猜含义：标题里带「电池」的跳电池优化，其余都跳到自启动/后台管理页。
                if (tip[0].contains("电池")) Oem.openBattery(act) else Oem.openAutoStart(act)
            }) { Text("›", color = SubColor, fontSize = 18.sp) }
        }
        DividerLine()
        Text(
            "点上面任意一行会尽量跳到对应的设置页；跳不过去就打开本应用详情，按说明自己找。",
            color = SubColor, fontSize = 12.sp
        )
    }

    Section("关于", soft) {
        val vn = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Throwable) {
            "?"
        }
        RowItem("版本", "v$vn · MIT")
        if (Links.hasRepo()) {
            DividerLine()
            RowItem("源码", Links.REPO)
        }
        DividerLine()
        Text(
            "对话只在你、本机和所选接口之间流转；记忆存在本机。",
            color = SubColor, fontSize = 11.sp
        )
        if (Links.hasRepo()) {
            Text(
                "非官方构建，与 dsh-pet 作者无关；动作素材版权归原作者，仅供个人使用。",
                color = SubColor, fontSize = 11.sp
            )
        }
    }
}

/** 把应用自己的图标（可能是 adaptive icon）画成位图。 */
private fun appIcon(ctx: Context, px: Int): Bitmap? {
    return try {
        val d = ctx.packageManager.getApplicationIcon(ctx.packageName)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, px, px)
        d.draw(c)
        bmp
    } catch (e: Throwable) {
        null
    }
}

private fun fmtSize(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return (bytes / 1024).toString() + " KB"
    return ((bytes * 10 / 1048576) / 10.0).toString() + " MB"
}

/**
 * 输入框。以前这里是 `remember(value)` 自持一份副本，父级换了值它不一定跟着变，
 * 人设就出现「界面里是空的、存进去的却有值」。现在完全受控：显示什么由 value 决定。
 */
@Composable
private fun Field(
    label: String,
    value: String,
    minLines: Int = 1,
    placeholder: String? = null,
    onChange: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = SubColor, fontSize = 12.sp)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
                .clip(RoundedCornerShape(FieldCorner))
                .background(FieldTint)
                .border(1.dp, LineColor.copy(alpha = 0.55f), RoundedCornerShape(FieldCorner))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(color = TextColor, fontSize = 14.sp, lineHeight = 20.sp),
                minLines = minLines,
                cursorBrush = SolidColor(AccentColor),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                placeholder,
                                color = SubColor.copy(alpha = 0.6f),
                                fontSize = 14.sp
                            )
                        }
                        inner()
                    }
                }
            )
        }
    }
}

/** 按钮底色选择行：第一个圆点是「默认」，后面是 8 个强调色。 */
@Composable
private fun BtnColorRow(current: Int, followAccent: Boolean, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(if (followAccent) AccentColor else Color.White.copy(alpha = 0.35f))
                .border(
                    if (current == 0) 2.dp else 1.dp,
                    if (current == 0) OnAccent else LineColor,
                    RoundedCornerShape(17.dp)
                )
                .clickable { onPick(0) }
        )
        for (i in 0 until Theme.accentCount()) {
            val c = Theme.accentAt(i)
            val on = current != 0 && current.toLong() == c.toLong()
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Color(c))
                    .border(
                        if (on) 2.dp else 1.dp,
                        if (on) OnAccent else LineColor,
                        RoundedCornerShape(17.dp)
                    )
                    .clickable { onPick(c) }
            )
        }
    }
}
