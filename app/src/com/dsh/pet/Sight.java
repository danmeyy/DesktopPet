package com.dsh.pet;

/** 读屏结果的内存缓存：无障碍服务写，宠物服务读。 */
public final class Sight {
    public static volatile boolean ready = false;
    public static volatile String app = "";        // 前台应用名
    public static volatile String activity = "";   // 前台页面（Activity 短名），可能为空
    public static volatile String screen = "";     // 屏幕上抓到的文字
    public static volatile long at = 0L;           // 最近一次读屏
    public static volatile long changedAt = 0L;    // 最近一次切换应用
    public static volatile int eventCount = 0;

    private Sight() {
    }

    /** 读屏结果：应用 + 文字。 */
    public static void update(String appName, String text) {
        boolean appChanged = appName != null && !appName.equals(app);
        app = appName == null ? "" : appName;
        screen = text == null ? "" : text;
        at = System.currentTimeMillis();
        if (appChanged) changedAt = at;
        eventCount++;
        Logs.screen(app, screen);
    }

    /**
     * 「你在哪」：只记应用名和页面名，**不读任何文字**，也单独一条路径，
     * 和读屏互不打扰。换应用时把上一次的文字清掉——别拿旧应用的屏幕
     * 去配新应用的名字。
     */
    public static void place(String appName, String pageName) {
        String a = appName == null ? "" : appName;
        String p = pageName == null ? "" : pageName;
        if (a.equals(app) && p.equals(activity)) return;
        if (!a.equals(app)) screen = "";
        app = a;
        activity = p;
        Logs.where(a, p);
    }

    public static long ageMs() {
        return at == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - at;
    }
}
