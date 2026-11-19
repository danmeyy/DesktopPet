package com.dsh.pet;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * 界面风格：默认 / Material 3 / HyperOS 4 / 毛玻璃。
 *
 * 每个风格都有自己的底子（圆角、按钮形状、背景渐变），在此之上允许两处自定义：
 * 背景明暗（浅 / 中 / 深）和强调色，另加圆角档位。文字色不开放自定义——它是按背景亮度
 * 自动配的，手改很容易变成看不见的字，这不是「自由」，是坑。
 *
 * 「默认」这一档永远存在，任何时候都能一键恢复，所以自定义不会把人锁死。
 */
public final class Theme {

    public static final String DEFAULT = "default";
    public static final String M3 = "m3";
    public static final String HYPER = "hyper";
    public static final String GLASS = "glass";

    /** 1.136 起去掉「液态玻璃」：旧值 {@code liquid} 在 style() 里自动迁到毛玻璃。 */
    private static final String LEGACY_LIQUID = "liquid";

    public static final String[] STYLES = {DEFAULT, M3, HYPER, GLASS};
    public static final String[] NAMES = {"默认", "Material 3", "HyperOS 4", "毛玻璃"};
    public static final String[] NOTES = {
            "白卡、蓝按钮、18dp 圆角，最像系统自带的那一档",
            "紫调、24dp 大圆角、填充式为主，Google 现行设计语言",
            "浅灰底、纯白卡、胶囊按钮，小米 HyperOS 4 的观感",
            "卡片里透出自己身下那块已经糊好的背景，柔和、安静"
    };

    public static final int TONE_LIGHT = 0;
    public static final int TONE_MID = 1;
    public static final int TONE_DARK = 2;
    static final String[] TONE_NAMES = {"浅", "中", "深"};

    static final int[] ACCENTS = {
            Color.parseColor("#3482FF"),
            Color.parseColor("#6750A4"),
            Color.parseColor("#12A594"),
            Color.parseColor("#16A34A"),
            Color.parseColor("#FF7A45"),
            Color.parseColor("#E5487E"),
            Color.parseColor("#E5484D"),
            Color.parseColor("#6B7280")
    };
    static final String[] ACCENT_NAMES = {"蓝", "紫", "青", "绿", "橙", "粉", "红", "灰"};

    private static final int[] RADII = {10, 16, 20, 26};

    private static final String PREFS = "pet";
    private static final String KEY_STYLE = "ui_style";

    private Theme() {
    }

    // ---------- 选中的风格 ----------

    public static String style(Context c) {
        String s = prefs(c).getString(KEY_STYLE, DEFAULT);
        for (int i = 0; i < STYLES.length; i++) {
            if (STYLES[i].equals(s)) return s;
        }
        // 1.136 删掉了「液态玻璃」：存过 liquid 的机器搬到毛玻璃，
        // 连带把它那套微调（明暗/强调色/圆角/透明度/模糊）一起搬过去，别让用户的调整凭空消失。
        if (LEGACY_LIQUID.equals(s)) {
            migrateLiquid(c);
            return GLASS;
        }
        return DEFAULT;
    }

    private static void migrateLiquid(Context c) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        String from = "ui_" + LEGACY_LIQUID + "_";
        String to = "ui_" + GLASS + "_";
        for (java.util.Map.Entry<String, ?> en : p.getAll().entrySet()) {
            String k = en.getKey();
            if (k == null || !k.startsWith(from)) continue;
            String target = to + k.substring(from.length());
            Object v = en.getValue();
            if (!p.contains(target)) {
                if (v instanceof Integer) e.putInt(target, (Integer) v);
                else if (v instanceof String) e.putString(target, (String) v);
            }
            e.remove(k);
        }
        e.putString(KEY_STYLE, GLASS).apply();
    }

    public static void setStyle(Context c, String style) {
        String v = DEFAULT;
        for (int i = 0; i < STYLES.length; i++) {
            if (STYLES[i].equals(style)) v = style;
        }
        prefs(c).edit().putString(KEY_STYLE, v).apply();
    }

    public static String nameOf(String style) {
        for (int i = 0; i < STYLES.length; i++) {
            if (STYLES[i].equals(style)) return NAMES[i];
        }
        return NAMES[0];
    }

    // ---------- 每个风格的自定义 ----------

    static int tone(Context c, String style) {
        return clamp(prefs(c).getInt("ui_" + style + "_tone", defaultTone(style)), 0, 2);
    }

    static void setTone(Context c, String style, int tone) {
        prefs(c).edit().putInt("ui_" + style + "_tone", clamp(tone, 0, 2)).apply();
    }

    static int accentIndex(Context c, String style) {
        return clamp(prefs(c).getInt("ui_" + style + "_accent", defaultAccent(style)), 0, ACCENTS.length - 1);
    }

    static void setAccentIndex(Context c, String style, int index) {
        prefs(c).edit().putInt("ui_" + style + "_accent", clamp(index, 0, ACCENTS.length - 1)).apply();
    }

    static int radiusIndex(Context c, String style) {
        return clamp(prefs(c).getInt("ui_" + style + "_radius", defaultRadius(style)), 0, RADII.length - 1);
    }

    static void setRadiusIndex(Context c, String style, int index) {
        prefs(c).edit().putInt("ui_" + style + "_radius", clamp(index, 0, RADII.length - 1)).apply();
    }

    /** 毛玻璃卡片的不透明度 0–100（0 全透，100 全实）。 */
    static int cardOpacity(Context c, String style) {
        ensureGlassProfile(c);
        return clamp(prefs(c).getInt("ui_" + style + "_card_op", 0), 0, 100);
    }

    static void setCardOpacity(Context c, String style, int percent) {
        prefs(c).edit().putInt("ui_" + style + "_card_op", clamp(percent, 0, 100)).apply();
    }

    /** 毛玻璃卡片的模糊强度 0–100。 */
    static int cardBlur(Context c, String style) {
        return clamp(prefs(c).getInt("ui_" + style + "_card_blur", 10), 0, 100);
    }

    static void setCardBlur(Context c, String style, int percent) {
        prefs(c).edit().putInt("ui_" + style + "_card_blur", clamp(percent, 0, 100)).apply();
    }

    static int buttonOpacity(Context c, String style) {
        return clamp(prefs(c).getInt("ui_" + style + "_btn_op", 0), 0, 100);
    }

    static void setButtonOpacity(Context c, String style, int percent) {
        prefs(c).edit().putInt("ui_" + style + "_btn_op", clamp(percent, 0, 100)).apply();
    }

    /** 毛玻璃主按钮底色；0 表示「跟强调色走」。 */

    /** 大字（标题/正文主色）颜色；0 = 跟随当前风格的自动配色。 */
    static int textColor(Context c, String style) {
        return prefs(c).getInt("ui_" + style + "_text", 0);
    }

    static void setTextColor(Context c, String style, int color) {
        prefs(c).edit().putInt("ui_" + style + "_text", color).apply();
    }

    /** 大字深浅（不透明度百分比）；-1 = 跟随风格。 */
    static int textAlpha(Context c, String style) {
        return prefs(c).getInt("ui_" + style + "_text_alpha", -1);
    }

    static void setTextAlpha(Context c, String style, int percent) {
        prefs(c).edit().putInt("ui_" + style + "_text_alpha", percent).apply();
    }

    /** 小字（说明文字）颜色；0 = 跟随风格。 */
    static int subColor(Context c, String style) {
        return prefs(c).getInt("ui_" + style + "_sub", 0);
    }

    static void setSubColor(Context c, String style, int color) {
        prefs(c).edit().putInt("ui_" + style + "_sub", color).apply();
    }

    static int subAlpha(Context c, String style) {
        return prefs(c).getInt("ui_" + style + "_sub_alpha", -1);
    }

    static void setSubAlpha(Context c, String style, int percent) {
        prefs(c).edit().putInt("ui_" + style + "_sub_alpha", percent).apply();
    }

    /** 把自定义颜色/深浅叠到风格自带的文字色上（没设置就原样返回）。 */
    private static int tinted(Context c, String style, int base, boolean sub) {
        int custom = sub ? subColor(c, style) : textColor(c, style);
        int alpha = sub ? subAlpha(c, style) : textAlpha(c, style);
        int color = base;
        if (custom != 0) {
            int a = alpha >= 0 ? alpha * 255 / 100 : 255;
            color = (a << 24) | (custom & 0x00FFFFFF);
        } else if (alpha >= 0) {
            int srcA = (base >>> 24) & 0xFF;
            int a = srcA * alpha / 100;
            color = (a << 24) | (base & 0x00FFFFFF);
        }
        return color;
    }

    static int buttonPrimary(Context c, String style) {
        return prefs(c).getInt("ui_" + style + "_btn_primary", 0);
    }

    static void setButtonPrimary(Context c, String style, int color) {
        prefs(c).edit().putInt("ui_" + style + "_btn_primary", color).apply();
    }

    /** 毛玻璃次按钮底色；0 表示「白膜」。 */
    static int buttonSecondary(Context c, String style) {
        return prefs(c).getInt("ui_" + style + "_btn_secondary", 0);
    }

    static void setButtonSecondary(Context c, String style, int color) {
        prefs(c).edit().putInt("ui_" + style + "_btn_secondary", color).apply();
    }

    static int buttonBlur(Context c, String style) {
        return clamp(prefs(c).getInt("ui_" + style + "_btn_blur", 10), 0, 100);
    }

    static void setButtonBlur(Context c, String style, int percent) {
        prefs(c).edit().putInt("ui_" + style + "_btn_blur", clamp(percent, 0, 100)).apply();
    }

    /** 玻璃配方的版本号：改一次默认值就 +1，老的自定义值清掉一次，免得新默认永远看不见。 */
    private static final int GLASS_PROFILE = 10;

    private static void ensureGlassProfile(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getInt("ui_glass_profile", 0) >= GLASS_PROFILE) return;
        android.content.SharedPreferences.Editor e = p.edit();
        for (String st : STYLES) {
            e.remove("ui_" + st + "_card_op");
            e.remove("ui_" + st + "_card_blur");
            e.remove("ui_" + st + "_btn_op");
            e.remove("ui_" + st + "_btn_blur");
        }
        e.putInt("ui_glass_profile", GLASS_PROFILE).apply();
    }

    static boolean customized(Context c, String style) {
        SharedPreferences p = prefs(c);
        return p.contains("ui_" + style + "_tone")
                || p.contains("ui_" + style + "_accent")
                || p.contains("ui_" + style + "_radius")
                || p.contains("ui_" + style + "_card_op")
                || p.contains("ui_" + style + "_card_blur")
                || p.contains("ui_" + style + "_btn_op")
                || p.contains("ui_" + style + "_btn_blur");
    }

    /** 只清这一个风格的自定义，风格本身还在。 */
    static void reset(Context c, String style) {
        prefs(c).edit()
                .remove("ui_" + style + "_tone")
                .remove("ui_" + style + "_accent")
                .remove("ui_" + style + "_radius")
                .remove("ui_" + style + "_card_op")
                .remove("ui_" + style + "_card_blur")
                .remove("ui_" + style + "_btn_op")
                .remove("ui_" + style + "_btn_blur")
                .apply();
    }

    // ---------- 给设置页用的小访问器 ----------

    static String toneName(int index) {
        return TONE_NAMES[clamp(index, 0, TONE_NAMES.length - 1)];
    }

    static int accentCount() {
        return ACCENTS.length;
    }

    static String accentName(int index) {
        return ACCENT_NAMES[clamp(index, 0, ACCENT_NAMES.length - 1)];
    }

    static int accentAt(int index) {
        return ACCENTS[clamp(index, 0, ACCENTS.length - 1)];
    }

    static int radiusCount() {
        return RADII.length;
    }

    static int radiusAt(int index) {
        return RADII[clamp(index, 0, RADII.length - 1)];
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------- 各风格默认值 ----------

    private static int defaultTone(String style) {
        if (GLASS.equals(style)) return TONE_DARK;
        return HYPER.equals(style) ? TONE_MID : TONE_LIGHT;
    }

    private static int defaultAccent(String style) {
        if (M3.equals(style)) return 1;
        if (HYPER.equals(style)) return 0;
        if (GLASS.equals(style)) return 0;
        return 0;
    }

    private static int defaultRadius(String style) {
        if (M3.equals(style)) return 3;
        if (HYPER.equals(style)) return 2;
        if (GLASS.equals(style)) return 3;
        return 1;
    }

    // ---------- 解析成实际配色 ----------

    static final class Spec {
        int bg;
        int card;
        int text;
        int sub;
        int body;
        int accent;
        int accentText;
        int line;
        int field;
        int radiusCard;
        int radiusButton;
        boolean pillButton;
        boolean glass;
        int strokeColor;
        boolean stroke;
        /** 卡片长相：0 描边白卡 / 1 填充大圆角（M3）/ 2 平面微阴影（HyperOS）/ 3 半透明玻璃 */
        int cardStyle;
        /** 按钮长相：0 实心圆角 / 1 填充胶囊（M3）/ 2 描边胶囊（HyperOS）/ 3 半透明玻璃 */
        int buttonStyle;
        /** 卡片投影高度（dp），0 表示不投 */
        int cardElevation;
        /** 毛玻璃：卡片/按钮的不透明度（0–255）与模糊强度（0–100） */
        int cardAlpha;
        int buttonAlpha;
        int cardBlur;
        int buttonBlur;
    }

    /** 当前外观的一枚指纹：任何一项变了，界面就该重建。 */
    static String stamp(Context c) {
        String style = style(c);
        return style + "|" + tone(c, style) + "|" + accentIndex(c, style) + "|" + radiusIndex(c, style)
                + "|" + cardOpacity(c, style) + "|" + cardBlur(c, style)
                + "|" + buttonOpacity(c, style) + "|" + buttonBlur(c, style);
    }

    static Spec spec(Context c) {
        String style = style(c);
        int tone = tone(c, style);
        int accent = ACCENTS[accentIndex(c, style)];
        int radius = RADII[radiusIndex(c, style)];

        Spec s = new Spec();
        s.accent = accent;
        s.radiusCard = radius;
        s.radiusButton = pillButton(style) ? 26 : Math.max(10, radius - 4);
        s.pillButton = pillButton(style);
        s.glass = GLASS.equals(style);

        if (tone == TONE_DARK) {
            s.bg = Color.parseColor("#151922");
            s.card = s.glass ? Color.parseColor("#2EFFFFFF") : Color.parseColor("#1E2430");
            s.text = Color.parseColor("#F3F6FB");
            s.sub = Color.parseColor("#9AA6B8");
            s.body = Color.parseColor("#CBD4E1");
            s.line = s.glass ? Color.parseColor("#33FFFFFF") : Color.parseColor("#2A3242");
            s.field = s.glass ? Color.parseColor("#1FFFFFFF") : Color.parseColor("#242B38");
            s.stroke = true;
            s.strokeColor = s.glass ? Color.parseColor("#40FFFFFF") : Color.parseColor("#333C4C");
        } else if (tone == TONE_MID) {
            s.bg = Color.parseColor("#E9ECF2");
            s.card = Color.parseColor("#FFFFFF");
            s.text = Color.parseColor("#12141A");
            s.sub = Color.parseColor("#79808D");
            s.body = Color.parseColor("#4A505C");
            s.line = Color.parseColor("#DDE1E9");
            s.field = Color.parseColor("#F1F3F7");
            s.stroke = false;
            s.strokeColor = s.line;
        } else {
            s.bg = M3.equals(style) ? Color.parseColor("#FEF7FF") : Color.parseColor("#F7F7F9");
            s.card = Color.WHITE;
            s.text = M3.equals(style) ? Color.parseColor("#1D1B20") : Color.parseColor("#0E0F12");
            s.sub = M3.equals(style) ? Color.parseColor("#79747E") : Color.parseColor("#8A8F99");
            s.body = M3.equals(style) ? Color.parseColor("#49454F") : Color.parseColor("#4B5058");
            s.line = M3.equals(style) ? Color.parseColor("#E7E0EC") : Color.parseColor("#EDEEF2");
            s.field = M3.equals(style) ? Color.parseColor("#F3EDF7") : Color.parseColor("#F4F5F7");
            s.stroke = true;
            s.strokeColor = s.line;
        }

        s.accentText = luminance(s.accent) > 0.62 ? Color.parseColor("#101318") : Color.WHITE;

        if (GLASS.equals(style)) {
            s.cardAlpha = Math.round(255f * cardOpacity(c, style) / 100f);
            s.buttonAlpha = Math.round(255f * buttonOpacity(c, style) / 100f);
            s.cardBlur = cardBlur(c, style);
            s.buttonBlur = buttonBlur(c, style);
            // 底色跟着明暗档走：浅=白、中=灰白、深=近黑，再乘上不透明度
            int base;
            if (tone == TONE_DARK) base = Color.rgb(24, 27, 34);
            else if (tone == TONE_MID) base = Color.rgb(240, 242, 247);
            else base = Color.rgb(255, 255, 255);
            s.card = Color.argb(s.cardAlpha, Color.red(base), Color.green(base), Color.blue(base));
            s.field = Color.argb(Math.min(255, s.cardAlpha + 20), Color.red(base), Color.green(base), Color.blue(base));
        }

        if (M3.equals(style)) {
            s.cardStyle = 1;
            s.buttonStyle = 1;
            s.cardElevation = 1;
        } else if (HYPER.equals(style)) {
            s.cardStyle = 2;
            s.buttonStyle = 2;
            s.cardElevation = 1;
        } else if (GLASS.equals(style)) {
            s.cardStyle = 3;
            s.buttonStyle = 3;
            s.cardElevation = 0;
        } else {
            s.cardStyle = 0;
            s.buttonStyle = 0;
            s.cardElevation = 0;
        }
        // 用户自定义的大字/小字颜色与深浅，最后一步盖上去
        s.text = tinted(c, style, s.text, false);
        s.sub = tinted(c, style, s.sub, true);
        return s;
    }

    private static boolean pillButton(String style) {
        return HYPER.equals(style) || GLASS.equals(style) || M3.equals(style);
    }

    /** 0(黑)~1(白) 的相对亮度，用来决定按钮文字该用黑还是白。 */
    static double luminance(int color) {
        double r = Color.red(color) / 255.0;
        double g = Color.green(color) / 255.0;
        double b = Color.blue(color) / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }
}
