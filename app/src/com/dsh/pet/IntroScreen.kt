package com.dsh.pet

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「她能做什么」的全部内容，Compose 版：介绍页整页用它，设置里「介绍」页也直接嵌它。
 * 条目、顺序、小标题和说明文字与旧 Intro.java 一一对应。
 */
@Composable
internal fun IntroContent(soft: Bitmap? = null, modifier: Modifier = Modifier) {

    val ability = listOf(
        "浮在所有应用上面" to "不是小部件，是真正的悬浮窗——刷视频、看小说、回消息，她一直在屏幕角落陪着你。",
        "109 个动作全在包里" to "点一下换一个动作：发呆、打瞌睡、鼓捣、转圈、螃蟹走路……全部离线，不联网加载。",
        "动作自己会换" to "一个动作播放完自动接下一个，不用管她；闲着的时候也不会一直重复同一个。",
        "会自己走来走去" to "每隔一两分钟溜达到屏幕另一处，迈着小碎步，朝哪边走就朝哪边转。",
        "听得懂「去左边」" to "打字说「去右边」「到中间」「过来」「站住」「随便走」，她就照着做。",
        "看你一眼，接上一句话" to
            "开了无障碍之后，她每二十秒记一次「你在哪个应用、哪个页面」（只取名字，不读屏幕文字），" +
            "开口说话前再读一次当前屏幕，然后主动搭一句。",
        "会聊天，也会记住你" to
            "点她或者双击跟她说话；聊到长期的偏好和习惯（几点睡、忌口、固定安排），她会记下来。" +
            "更早的聊天也不会全忘：每隔一阵把聊天压成几句要点，按等级存 3 天到永久，下次开口自己想起来。",
        "习惯也看在眼里" to "按小时统计你常用哪些应用，到了那个点就能说「这个点你一般都在……」。",
        "能翻她记了什么" to
            "设置页「关于 → 记忆」把她说要记住的事全列出来，记错的可以一条条删，也能一次清空。" +
            "下面还有她压出来的聊天摘要：文字、保存等级都能改，也能自己加一条。" +
            "摘要的容量能自己拉（50–2000 条），占用到 50%/80%/90%/95%/100% 各提醒一次；" +
            "满了先删 2% 最久没用到的，也能手动挑、或让她按「重复 / 寒暄 / 太久没用 / 超容量」自己清一批。",
        "话说多少你说了算" to
            "两次主动搭话至少隔 75 秒，换应用时要隔 3 分钟以上才可能开口。0 点到早上 8 点默认不出声，" +
            "想让她夜里也说，开关在设置 →「说话」里；点她一下随时都会应，不受这条限制。",
        "读屏有账本" to
            "「你都在哪」记她每二十秒捕捉到的应用与页面；「读屏时间线」记她什么时候读过屏；" +
            "「她看见了什么」是读到的原文，这三页只看得到近两天；「聊天记录」只留最近三天的原话，" +
            "你俩的对话和她主动冒出来的碎碎念都在里面。四页都在本机，不上传。",
        "花了多少看得见" to
            "设置页「关于 → 用量」按接口回报的 token 记账：今天、近七天、本月几次、多少输入输出、折多少钱，" +
            "按 DeepSeek 的高峰/空闲两档价换算，不靠字数猜。",
        "四套界面，随便换" to
            "默认 / Material 3 / HyperOS 4 / 毛玻璃。每套都能单独调背景明暗、强调色、圆角，还能改大字和小字的颜色与深浅；" +
            "换一套只影响那一套，不会互串，随时能一键回默认。",
        "毛玻璃能自己配背景" to
            "只有毛玻璃这一档能选一张自己的图当整页背景（复制到本机，不上传），其余三档只用自己的纯色底。" +
            "卡片会把身下那块背景实时糊一遍再透出来；透明度、模糊、按钮的透明度和模糊都能单独调。",
        "收起之后不会自己回来" to
            "「大脑 → 宠物」里点「收起宠物」，她就不在桌面上了，而且不会再被系统悄悄拉回来。" +
            "想叫她回来，打开这里点「启动宠物」即可。",
        "宿主插件一键导出" to
            "走 DSHBox 登录态需要一个小插件。这份插件就装在 APK 里随包走，不用另找文件：" +
            "设置页「大脑 → 宿主插件」点一下，就把它解压到 DSHBox 的共享区，里面带一份安装说明。" +
            "装的时候不用照着步骤走：把「把共享区里的 pet-brain 插件装进 profile」这句话丢给 DSHBox，" +
            "它会自己装好；装完完全退出 DSHBox 再打开就生效。",
        "两种大脑，随便换" to "用 DSHBox 的登录态，或者自己填一个 DeepSeek API key 直连，都行。"
    )

    Column(modifier.fillMaxWidth()) {

        // ---- 她能做什么 ----
        IntroSection("她能做什么", null)
        GlassCard(soft, padding = 0.dp, spacing = 0.dp) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp)) {
                ability.forEachIndexed { i, pair ->
                    if (i > 0) DividerLine()
                    IntroRow(pair.first, pair.second)
                }
            }
        }

        // ---- 怎么互动 ----
        IntroSection("怎么跟她互动", "不用进 App，直接在她身上操作：")
        IntroTable(
            soft,
            listOf(
                "点一下" to "换一个动作",
                "双击" to "弹出输入框，跟她说话（说完自动收起）",
                "按住拖" to "把她拖到屏幕任意位置",
                "长按" to "收起（收进通知栏，点通知能再叫出来）",
                "通知栏" to "「说一句」「收起」两个按钮；标题下面还写着她的状态——散步中/站住不动/发呆，后面跟个方位"
            )
        )

        // ---- 指令 ----
        IntroSection("说句话就能让她动", "整句都是一个指令才生效，闲聊不会误触发：")
        IntroTable(
            soft,
            listOf(
                "去左边" to "走到屏幕左侧（右边 / 上边 / 下边同理）",
                "到中间" to "走到屏幕正中间（中央、正中也可以）",
                "去左上角" to "四个角都能去：左上角 / 右上角 / 左下角 / 右下角",
                "过来" to "走到你面前的中间位置",
                "站住" to "别再乱走了（别动 / 停下 / 待着 都行）",
                "随便走" to "恢复自己溜达（自由活动 / 动起来 / 溜达 都行）"
            )
        )

        // ---- 大脑 ----
        IntroSection("两种大脑怎么选", "她只是「嘴」和「眼睛」，思考交给模型：")
        IntroItem(
            soft, "用 DSHBox 的登录态（默认）",
            "优点：不用申请 key，模型和登录态跟 DSHBox 共用，改设置不用动这里。\n" +
                "缺点：手机上必须装着 DSHBox，并且它得在跑。"
        )
        IntroItem(
            soft, "自填 DeepSeek API Key",
            "优点：只装这一个 App 就能用，不依赖 DSHBox。\n" +
                "缺点：要自己去 platform.deepseek.com 申请 key，按量计费。"
        )
        IntroItem(
            soft, "自动（推荐）",
            "填了 key 就直连，没填就走 DSHBox。换手机、临时没网都不用手动切。"
        )

        // ---- 换手机 ----
        IntroSection("换台手机怎么装", "APK 是自包含的：动作图、人设、插件都在包里，不用另拷别的文件。")
        IntroItem(
            soft, "四步就能用",
            "① 装上 APK，打开，允许「显示在其他应用上层」；\n" +
                "② 想让她主动搭话，再到系统设置里开无障碍；\n" +
                "③ 选大脑：填一个 DeepSeek API key 直连（只装这一个 App 就行），或者用 DSHBox 的登录态；\n" +
                "④ 用 DSHBox 的话，设置页「大脑 → 宿主插件 → 导出插件」，照解压出来的安装说明装一次，" +
                "完全退出 DSHBox 再打开。"
        )
        IntroItem(
            soft, "新手机没装 DSHBox",
            "只填 API key 走直连就行。除了不能共用 DSHBox 的登录态，别的功能都在：" +
                "说话、记事、聊天摘要、用量、四套界面。"
        )
        IntroItem(
            soft, "旧手机上的记忆不会自己过去",
            "长期记忆、聊天摘要、聊天记录都存在那台手机的本机目录里，换机不跟着走——" +
                "重新聊、重新攒比拷文件省事。"
        )

        // ---- 无障碍 ----
        IntroSection("关于无障碍权限", "这是唯一一个「她怎么看你」的开关，也是最容易让人警惕的一个，所以说清楚。")
        IntroItem(
            soft, "为什么非要这个权限",
            "Android 不给普通应用看屏幕内容的通道。她想知道你在刷什么、屏幕上写着什么，只有无障碍这一条路；" +
                "没有它，她就只能站在那儿，看不见你在干嘛。"
        )
        IntroItem(
            soft, "她具体读什么",
            "两条路，互不打扰：① 每 20 秒（以及每次开口前）记一次「应用名 + 页面名」，只取名字、不读任何文字；" +
                "② 只在开口说话之前，读一次当前屏幕上的可见文字摘要（最多 1500 字，重复内容只留一次，节点数与深度都有上限）。" +
                "密码框连同子节点整片跳过，不读。"
        )
        IntroItem(
            soft, "她不做什么",
            "不截图、不点按、不替你操作、不读通知、不记历史。读完只留在内存里，供这一次说话用。"
        )
        IntroItem(
            soft, "这些字会去哪儿",
            "只作为一句环境上下文（截到 1200 字）发给模型。走 DSHBox 就是本机处理；自填 Key 就是发给你选的 DeepSeek 接口。不会存成对话记录。"
        )
        IntroItem(
            soft, "怎么打开",
            "设置 → 无障碍（部分机型叫「无障碍服务」/「已下载的服务」）→ 找到「桌面宠物」→ 打开开关 → 弹窗选「允许」。"
        )
        IntroItem(
            soft, "会被系统关掉",
            "小米、OPPO、vivo 会定期「体检」，长时间不用可能自动关闭。关了就再开一次，否则她只会说泛泛的话，不再主动接你的话。"
        )
        IntroItem(
            soft, "不开会怎样",
            "她照样能动、能换动作、能聊天、能记住你；只是不会主动搭话，也不知道你在用什么应用。"
        )

        // ---- 机型 ----
        IntroSection("各家手机怎么设置", "不设置也能用，但要想她一直在、气泡能弹，各家都要额外开几项：")
        IntroItem(
            soft, "小米 / 红米 / 澎湃",
            "设置 → 应用设置 → 应用管理 → 桌面宠物 →\n" +
                "① 权限管理 → 显示在其他应用上层：允许\n" +
                "② 其他权限 →「后台弹出界面」：允许（不开的话她会说话但气泡不弹）\n" +
                "③ 省电策略 → 无限制；自启动：打开\n" +
                "另外：设置 → 无障碍 → 桌面宠物 → 打开（系统可能每天自动关）"
        )
        IntroItem(
            soft, "OPPO / 一加 / 真我",
            "设置 → 应用管理 → 桌面宠物 →\n" +
                "① 权限 → 悬浮窗：允许\n" +
                "② 耗电管理 → 允许后台活动 / 允许自启动\n" +
                "③ 最近任务里锁一下，别让她被清掉"
        )
        IntroItem(
            soft, "vivo / iQOO",
            "设置 → 应用与权限 → 权限管理 → 桌面宠物 →\n" +
                "① 悬浮窗：允许；② 后台弹出界面：允许\n" +
                "i管家 → 应用管理 → 自启动：打开\n" +
                "电池 → 后台耗电管理 → 允许后台高耗电"
        )
        IntroItem(
            soft, "华为 / 荣耀",
            "设置 → 应用 → 应用启动管理 → 桌面宠物 → 改为「手动管理」，\n" +
                "把「自启动」「关联启动」「后台活动」三项都打开；\n" +
                "再在 设置 → 权限管理 → 悬浮窗：允许"
        )
        IntroItem(
            soft, "三星",
            "设置 → 应用程序 → 桌面宠物 → 权限 → 显示在其他应用上层：允许；\n" +
                "电池 → 后台使用限制 → 把她从「休眠应用」里移除"
        )
        IntroItem(
            soft, "魅族 / 华硕 / 中兴 / 摩托 / 联想 / Nothing",
            "这几家在「关于 → 这台手机」里都有逐条路径，点一行直接跳过去；跳不过会自动退到应用详情页，不会卡住。"
        )
        IntroItem(
            soft, "原生 Android / Pixel",
            "只需要「显示在其他应用上层」+ 无障碍，其余不用调，最省心"
        )

        // ---- 常见问题 ----
        IntroSection("常见问题", null)
        IntroItem(
            soft, "气泡不出来 / 她说话了但看不见字",
            "多半是「后台弹出界面」没授权（小米、vivo 有这个开关）。桌面宠物 → 其他权限 → 后台弹出界面：允许。"
        )
        IntroItem(
            soft, "她一直站着不动",
            "先在输入框说一句「随便走」。如果还不动：可能刚被指令定住（指令后会站三分钟），或者正在跟你聊天、被你按住。"
        )
        IntroItem(
            soft, "过一段时间她消失了",
            "被系统省电策略清掉了。把省电策略设为「无限制」、允许自启动，再把通知栏里那条留住（不要划掉）。"
        )
        IntroItem(
            soft, "她不主动搭话了",
            "无障碍被系统关了。设置 → 无障碍 → 桌面宠物 → 重新打开。"
        )
        IntroItem(
            soft, "我没填 key，她也不说话",
            "检查 DSHBox 是否在跑、是否登录；或者改成「自填 API Key」。"
        )
        IntroItem(
            soft, "换手机怎么把记忆带过去",
            "记忆存在本机：在旧机上打开 App 用「导出」不方便，目前建议直接在新机上重新聊；开发版才有多机同步。"
        )
        IntroItem(
            soft, "会耗电吗",
            "她是一个前台服务 + 悬浮窗，静止时只有逐帧动画，比短视频省得多；走路那几秒会稍高一点。"
        )
        IntroItem(
            soft, "很占内存吗",
            "换过的动作会留在内存里当缓存，上限 42MB；设置页「存储与清理」能看到当前占用，点一下就能放掉（正在播的那一个不动）。"
        )
        IntroItem(
            soft, "换了手机还能用吗",
            "能。装上后打开设置页，最下面「这台手机」会认出你的品牌，给出自启动、悬浮窗、电池这三处的具体路径，点一行直接跳过去。"
        )

        // ---- 隐私 ----
        IntroSection("数据与隐私", null)
        IntroItem(
            soft, "都存在哪",
            "· 动作帧：随 App 打包，不联网。\n" +
                "· 对话与记忆：本机私有目录，卸载 App 就一起没了。\n" +
                "· 习惯统计：只按小时记「哪个应用用了多久」，不记具体内容。\n" +
                "· 自填的 API Key：本机 SharedPreferences，不往外传。"
        )
        IntroItem(
            soft, "会发出去什么",
            "只有两样：你打的字（或她主动开口时的一句环境摘要），以及最近几句对话。" +
                "不发截图、不发应用列表、不发通讯录。"
        )

        // ---- 还有几点 ----
        IntroSection("还有几点", null)
        IntroItem(
            soft, "已知的小脾气",
            "· 走路的那几秒她不吃触摸，手指会落到下面的应用上——这是为了走路不闪。\n" +
                "· 横屏、分屏、折叠屏展开会自动重算尺寸，但折叠屏没实测过。\n" +
                "· 关机重启后她不会自动回来（除非给了自启动权限），再打开一次 App 就行。\n" +
                "· 需要 Android 8.0 以上；鸿蒙 NEXT 装不了普通 APK。"
        )

        IntroSection("关于这个项目", null)
        IntroItem(
            soft, "开源",
            if (Links.hasRepo()) {
                "代码 MIT 许可，源码在 " + Links.REPO + "。" +
                    "动作素材来自 dsh-pet，版权归原作者；本仓库只是个人自用的构建，与原作者无关，" +
                    "二次分发请保留声明。GitHub Actions 打 tag 会自动出包。"
            } else {
                "代码 MIT 许可，动作素材来自 dsh-pet，版权归原作者；二次分发请保留声明。" +
                    "源码仓库里有 GitHub Actions，打 tag 会自动出包。"
            }
        )
    }
}

@Composable
private fun IntroSection(title: String, note: String?) {
    SectionTitle(title)
    if (note != null) {
        Text(
            note,
            color = SubColor,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp)
        )
    }
}

/** 卡内的一行：标题 + 说明，不带自己的背景。 */
@Composable
private fun IntroRow(head: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
        Text(head, color = TextColor, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
        if (detail.isNotEmpty()) {
            Text(
                detail,
                color = BodyColor,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** 一张功能卡：标题 + 说明。 */
@Composable
private fun IntroItem(soft: Bitmap?, head: String, detail: String) {
    GlassCard(soft, padding = 0.dp, spacing = 0.dp) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
            Text(head, color = TextColor, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    color = BodyColor,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/** 一张「标签 → 内容」的表格卡。 */
@Composable
private fun IntroTable(soft: Bitmap?, rows: List<Pair<String, String>>) {
    GlassCard(soft, padding = 0.dp, spacing = 0.dp) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
            rows.forEachIndexed { i, pair ->
                if (i > 0) DividerLine()
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(
                        pair.first,
                        color = AccentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.widthIn(min = 86.dp)
                    )
                    Text(
                        pair.second,
                        color = BodyColor,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                }
            }
        }
    }
}
