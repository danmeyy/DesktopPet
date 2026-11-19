package com.dsh.pet;

import android.content.Context;
import android.graphics.Color;

/**
 * 旧 Java 界面的调色板残留。界面已全部迁到 Compose（GlassKit.kt），
 * Java 侧只剩 Backdrop 画背景层时要用的一点颜色，所以这个类被削到只剩这些。
 */
final class Ui {

    static int BG = Color.WHITE;
    static int BLUE = 0xFF3482FF;
    static boolean GLASS;

    private Ui() {
    }

    /** 把当前风格的配色灌进来（Backdrop 画底色前调用一次）。 */
    static void use(Context c) {
        Theme.Spec s = Theme.spec(c);
        BG = s.bg;
        BLUE = s.accent;
        GLASS = s.glass;
    }

    /** 两个颜色按比例混合，t=0 取 a，t=1 取 b。 */
    static int blend(int a, int b, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.rgb(
                Math.round(ar + (br - ar) * k),
                Math.round(ag + (bg - ag) * k),
                Math.round(ab + (bb - ab) * k));
    }
}
