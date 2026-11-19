package com.dsh.pet;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 使用习惯：按「小时」统计前台应用的出现次数，用来给大脑一句
 * 「这个时间点你通常在用什么」，也用来决定什么时候开口更自然。
 * 数据只存在本应用私有目录，不上传。
 */
public final class Habits {
    private static final Object LOCK = new Object();
    private static final Map<Integer, Map<String, Integer>> DATA = new HashMap<Integer, Map<String, Integer>>();
    private static final Map<String, String> LABELS = new HashMap<String, String>();
    private static boolean loaded;
    private static long lastSave;
    private static long lastPush;

    private Habits() {
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "habits.json");
    }

    static void load(Context ctx) {
        if (loaded) return;
        loaded = true;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(file(ctx)), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            JSONObject root = new JSONObject(sb.toString());
            Iterator<String> hours = root.keys();
            while (hours.hasNext()) {
                String hk = hours.next();
                JSONObject apps = root.optJSONObject(hk);
                if (apps == null) continue;
                Map<String, Integer> m = new HashMap<String, Integer>();
                Iterator<String> keys = apps.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    m.put(k, apps.optInt(k, 0));
                }
                try {
                    DATA.put(Integer.parseInt(hk), m);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (br != null) br.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static void save(Context ctx) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<Integer, Map<String, Integer>> e : DATA.entrySet()) {
                JSONObject apps = new JSONObject();
                for (Map.Entry<String, Integer> a : e.getValue().entrySet()) {
                    apps.put(a.getKey(), a.getValue());
                }
                root.put(String.valueOf(e.getKey()), apps);
            }
            FileOutputStream out = new FileOutputStream(file(ctx));
            try {
                out.write(root.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            lastSave = System.currentTimeMillis();
        } catch (Throwable ignored) {
        }
    }

    /** 记一次观察：当前这个小时里，前台是 pkg。 */
    public static void record(Context ctx, String pkg) {
        if (pkg == null || pkg.length() == 0) return;
        if (pkg.equals(ctx.getPackageName())) return;
        synchronized (LOCK) {
            load(ctx);
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            Map<String, Integer> m = DATA.get(h);
            if (m == null) {
                m = new HashMap<String, Integer>();
                DATA.put(h, m);
            }
            Integer c = m.get(pkg);
            m.put(pkg, c == null ? 1 : c + 1);
            if (System.currentTimeMillis() - lastSave > 60_000L) save(ctx);
            if (System.currentTimeMillis() - lastPush > 300_000L) push(ctx);
        }
    }

    /** 把快照同步给宿主（这样不用 root 也能读到习惯数据）。 */
    static void push(Context ctx) {
        try {
            JSONObject root = new JSONObject();
            JSONObject hours = new JSONObject();
            for (Map.Entry<Integer, Map<String, Integer>> e : DATA.entrySet()) {
                JSONObject apps = new JSONObject();
                for (Map.Entry<String, Integer> a : e.getValue().entrySet()) {
                    apps.put(a.getKey(), a.getValue());
                }
                hours.put(String.valueOf(e.getKey()), apps);
            }
            root.put("hours", hours);
            lastPush = System.currentTimeMillis();
            Brain.pushHabits(root.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 应用名（拿不到就退回包名尾段）。 */
    static String label(Context ctx, String pkg) {
        synchronized (LABELS) {
            String cached = LABELS.get(pkg);
            if (cached != null) return cached;
        }
        String name = pkg;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) name = cs.toString();
        } catch (Throwable ignored) {
            int dot = pkg.lastIndexOf('.');
            if (dot > 0 && dot < pkg.length() - 1) name = pkg.substring(dot + 1);
        }
        synchronized (LABELS) {
            LABELS.put(pkg, name);
        }
        return name;
    }

    /** 一句话：这个时间点你最常看哪两个应用。数据太少时返回空串。 */
    public static String summary(Context ctx) {
        synchronized (LOCK) {
            load(ctx);
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            Map<String, Integer> m = DATA.get(h);
            if (m == null) return "";
            int total = 0;
            for (Integer v : m.values()) total += v;
            if (total < 10) return "";
            String topPkg = null;
            String secondPkg = null;
            int top = 0;
            int second = 0;
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                int v = e.getValue() == null ? 0 : e.getValue();
                if (v > top) {
                    second = top;
                    secondPkg = topPkg;
                    top = v;
                    topPkg = e.getKey();
                } else if (v > second) {
                    second = v;
                    secondPkg = e.getKey();
                }
            }
            StringBuilder sb = new StringBuilder();
            if (topPkg != null) {
                int pct = Math.round(top * 100f / total);
                if (pct >= 8) sb.append(label(ctx, topPkg)).append(" 约").append(pct).append("%");
            }
            if (secondPkg != null) {
                int pct = Math.round(second * 100f / total);
                if (pct >= 8) {
                    if (sb.length() > 0) sb.append("、");
                    sb.append(label(ctx, secondPkg)).append(" 约").append(pct).append("%");
                }
            }
            return sb.toString();
        }
    }

    /** 服务退出前落盘并同步。 */
    public static void flush(Context ctx) {
        synchronized (LOCK) {
            if (!loaded) return;
            save(ctx);
            push(ctx);
        }
    }
}
