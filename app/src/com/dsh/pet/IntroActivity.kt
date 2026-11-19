package com.dsh.pet

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 介绍页：整页就是「她能做什么」那块内容 + 一个回设置按钮。 */
class IntroActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            IntroScreen(spec, bg, soft)
        }
    }

    @Composable
    private fun IntroScreen(spec: Theme.Spec, bg: Bitmap?, soft: Bitmap?) {
        PageFrame(
            spec = spec,
            bg = bg,
            title = "介绍",
            subtitle = null,
            onBack = { closePage(this) }
        ) {
            // 1.150：整页三块各自一个 lazy item。介绍正文是固定文案（十几张卡），
            // 高度远不到 Constraints 上限，不必再拆
            item { IntroContent(soft) }
            item { Spacer(Modifier.height(2.dp)) }
            item { GhostButton("回到设置") { closePage(this@IntroActivity) } }
        }
    }
}
