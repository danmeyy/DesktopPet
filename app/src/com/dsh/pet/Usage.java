package com.dsh.pet;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 直连模式的 token 账本。
 *
 * 宿主模式下这笔账在插件那边（{@code $DSH_HOME/pet-memory/usage.jsonl}，走 /pet-brain/usage 读），
 * 这里只负责「自己填 Key 直连」时把接口返回的 usage 记下来。两边记的都是**接口真实上报的数字**，
 * 不用字数估算——算不准的账不如不算。
 *
 * 单价按 DeepSeek 官方牌价（人民币/百万 token），并且**分时段**：
 * 高峰（北京时间周一至周五 9:00–12:00、14:00–18:00，不含法定节假日）输入未命中 2 元、
 * 命中 0.04 元、输出 8 元；空闲时段是一半（1 / 0.02 / 4）。
 * 每一笔都按它自己发生的时刻算价，所以跨时段的那一天不会被算成同一个价。
 */
public class Usage {

    /** 高峰价（元 / 百万 token）。 */
    public static final double PRICE_MISS = 2.0;
    public static final double PRICE_HIT = 0.04;
    public static final double PRICE_OUT = 8.0;
    /** 空闲价 = 高峰价的一半。 */
    public static final double PRICE_MISS_OFF = 1.0;
    public static final double PRICE_HIT_OFF = 0.02;
    public static final double PRICE_OUT_OFF = 4.0;

    private static final java.util.TimeZone TZ = java.util.TimeZone.getTimeZone("Asia/Shanghai");

    /** 这笔发生在高峰时段吗？（周一到周五 9–12、14–18，北京时间） */
    public static boolean peak(long ts) {
        java.util.Calendar cal = java.util.Calendar.getInstance(TZ);
        cal.setTimeInMillis(ts);
        int dow = cal.get(java.util.Calendar.DAY_OF_WEEK);
        if (dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY) return false;
        int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
        return (h >= 9 && h < 12) || (h >= 14 && h < 18);
    }

    /** 按发生时刻计价。 */
    public static double cost(long ts, long input, long cacheRead, long output) {
        long miss = Math.max(0L, input - cacheRead);
        if (peak(ts)) {
            return miss * PRICE_MISS / 1e6 + cacheRead * PRICE_HIT / 1e6 + output * PRICE_OUT / 1e6;
        }
        return miss * PRICE_MISS_OFF / 1e6 + cacheRead * PRICE_HIT_OFF / 1e6
                + output * PRICE_OUT_OFF / 1e6;
    }

    /** 只有日汇总、没有时刻时（宿主模式）按高峰价算，宁高不低。 */
    public static double costPeak(long input, long cacheRead, long output) {
        long miss = Math.max(0L, input - cacheRead);
        return miss * PRICE_MISS / 1e6 + cacheRead * PRICE_HIT / 1e6 + output * PRICE_OUT / 1e6;
    }

    private static final String FILE_NAME = "usage.jsonl";
    private static final long KEEP_DAYS = 60L;
    private static final long PRUNE_BYTES = 512 * 1024L;

    private static final class Entry {
        long at;
        String kind;
        long input;
        long output;
        long cacheRead;
    }

    /** 记一次直连调用。字段全部来自接口响应的 usage，缺失就记 0。 */
    public static synchronized void add(Context c, String kind, long input, long output, long cacheRead) {
        if (c == null) return;
        JSONObject row = new JSONObject();
        try {
            row.put("at", System.currentTimeMillis());
            row.put("kind", kind == null ? "chat" : kind);
            row.put("input", Math.max(0L, input));
            row.put("output", Math.max(0L, output));
            row.put("cacheRead", Math.max(0L, cacheRead));
        } catch (JSONException e) {
            return;
        }
        File f = fileOf(c);
        try {
            FileOutputStream out = new FileOutputStream(f, true);
            OutputStreamWriter w = new OutputStreamWriter(out, Charset.forName("UTF-8"));
            w.write(row.toString());
            w.write("\n");
            w.close();
        } catch (Throwable ignored) {
            return;
        }
        if (f.length() > PRUNE_BYTES) prune(c);
    }

    /** 读本机账本，按时间倒序（最新在前）。 */
    public static synchronized List<long[]> read(Context c) {
        List<long[]> out = new ArrayList<>();
        File f = fileOf(c);
        if (!f.exists()) return out;
        long floor = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600L * 1000L;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), Charset.forName("UTF-8")));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() == 0) continue;
                try {
                    JSONObject o = new JSONObject(line);
                    long at = o.optLong("at", 0L);
                    if (at < floor) continue;
                    String kind = o.optString("kind", "chat");
                    out.add(new long[]{
                            at,
                            "chat".equals(kind) ? 1L : 0L,
                            o.optLong("input", 0L),
                            o.optLong("output", 0L),
                            o.optLong("cacheRead", 0L)
                    });
                } catch (JSONException ignored) {
                }
            }
            r.close();
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void prune(Context c) {
        List<long[]> rows = read(c);
        File f = fileOf(c);
        try {
            FileOutputStream out = new FileOutputStream(f, false);
            OutputStreamWriter w = new OutputStreamWriter(out, Charset.forName("UTF-8"));
            for (int i = rows.size() - 1; i >= 0; i--) {
                long[] r = rows.get(i);
                JSONObject o = new JSONObject();
                o.put("at", r[0]);
                o.put("kind", r[1] == 1L ? "chat" : "whisper");
                o.put("input", r[2]);
                o.put("output", r[3]);
                o.put("cacheRead", r[4]);
                w.write(o.toString());
                w.write("\n");
            }
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static File fileOf(Context c) {
        return new File(c.getFilesDir(), FILE_NAME);
    }

    // ---------- 汇总与换算 ----------

    /** 一次会话的账：{day, calls, input, output, cacheRead}。 */
    static final class Day {
        String day;
        int calls;
        long input, output, cacheRead;
        /** 按每笔发生时刻累加出来的钱。 */
        double money;

        long miss() {
            long m = input - cacheRead;
            return m < 0 ? 0 : m;
        }

        /** 已经按发生时刻算好的钱。 */
        double money() {
            return money;
        }
    }

    static String dayOf(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ts));
    }

    static String clock(long ts) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ts));
    }

    static String moneyText(double yuan) {
        if (yuan >= 1.0) return String.format(Locale.US, "¥%.2f", yuan);
        if (yuan >= 0.01) return String.format(Locale.US, "¥%.3f", yuan);
        return String.format(Locale.US, "¥%.4f", yuan);
    }

    static String tokens(long n) {
        if (n >= 10000L) return String.format(Locale.US, "%.1f 万", n / 10000.0);
        return String.valueOf(n);
    }

    /** 从本机账本汇总成天。 */
    static List<Day> daysFromLocal(Context c) {
        List<long[]> rows = read(c);
        List<Day> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            long[] r = rows.get(i);
            String day = dayOf(r[0]);
            Day d = find(out, day);
            if (d == null) {
                d = new Day();
                d.day = day;
                out.add(d);
            }
            d.calls++;
            d.input += r[2];
            d.output += r[3];
            d.cacheRead += r[4];
            d.money += cost(r[0], r[2], r[4], r[3]);
        }
        return new ArrayList<>(out);
    }

    private static Day find(List<Day> list, String day) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).day.equals(day)) return list.get(i);
        }
        return null;
    }
}
