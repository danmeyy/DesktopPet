package com.dsh.pet;

import android.content.Context;
import android.content.SharedPreferences;

/** 大脑来源与 API 配置（存在 App 私有 SharedPreferences 里，不上传）。 */
public final class Config {
    public static final String MODE_AUTO = "auto";
    public static final String MODE_HOST = "host";
    public static final String MODE_DIRECT = "direct";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("pet", Context.MODE_PRIVATE);
    }

    public static String mode(Context c) {
        return sp(c).getString("mode", MODE_AUTO);
    }

    public static void setMode(Context c, String m) {
        sp(c).edit().putString("mode", m).apply();
    }

    public static String key(Context c) {
        return sp(c).getString("key", "").trim();
    }

    public static void setKey(Context c, String k) {
        sp(c).edit().putString("key", k == null ? "" : k.trim()).apply();
    }

    public static String model(Context c) {
        String m = sp(c).getString("model", "").trim();
        return m.length() > 0 ? m : "deepseek-chat";
    }

    public static void setModel(Context c, String m) {
        sp(c).edit().putString("model", m == null ? "" : m.trim()).apply();
    }

    public static String base(Context c) {
        String b = sp(c).getString("base", "").trim();
        if (b.length() == 0) return "https://api.deepseek.com";
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    public static void setBase(Context c, String b) {
        sp(c).edit().putString("base", b == null ? "" : b.trim()).apply();
    }

    /** 是否走自填 Key 直连。auto 模式下只要填了 key 就直连。 */
    public static boolean direct(Context c) {
        String m = mode(c);
        if (MODE_HOST.equals(m)) return false;
        if (MODE_DIRECT.equals(m)) return key(c).length() > 0;
        return key(c).length() > 0;
    }

    /** 用户是否想要宠物在桌面上。收起后置 false，避免系统把服务拉回来后它自己又冒出来。 */
    public static boolean wanted(Context c) {
        return sp(c).getBoolean("pet_wanted", true);
    }

    public static void setWanted(Context c, boolean on) {
        sp(c).edit().putBoolean("pet_wanted", on).apply();
    }

    /** 深夜（0:00–8:00）要不要主动开口。默认关：那个点吵人。 */
    public static boolean nightSpeak(Context c) {
        return sp(c).getBoolean("night_speak", false);
    }

    public static void setNightSpeak(Context c, boolean on) {
        sp(c).edit().putBoolean("night_speak", on).apply();
    }
}
