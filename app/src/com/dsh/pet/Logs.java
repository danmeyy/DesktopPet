package com.dsh.pet;

import android.content.Context;
import android.content.SharedPreferences;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本机日志：读屏记录（只留近两天）、聊天原话（只留近三天）和压缩后的上下文要点。
 *
 * 都是应用私有目录里的 JSONL（一行一条），不出本机、不上传；
 * 写盘走单独线程，别让无障碍回调卡在主线程上。
 */
final class Logs {
    static final int SCREEN_DAYS = 2;
    /** 原话只留三天；更早的交给压缩条目（按等级存 3–30 天或永久）。 */
    static final int CHAT_DAYS = 3;
    /** 压缩条目的留存天数，下标 = 等级-1；等级 5 是永久（0 表示不按天裁）。 */
    static final int[] CTX_DAYS = {3, 7, 15, 30, 0};
    /** 上下文容量：默认 400 条，用户可以在记忆页里调（50..2000）。 */
    static final int DEFAULT_CTX = 400;
    static final int MAX_CTX = 2000;
    /** 用量预警线，跨过哪条提示哪条。 */
    static final int[] CTX_WARN = {50, 80, 90, 95, 100};
    private static final Object CTX_LOCK = new Object();
    private static final int MAX_CTX_TEXT = 60;

    /** 文件超过这个大小就先裁一次，避免无限长。 */
    private static final long MAX_BYTES = 600L * 1024;
    /** 裁完只留最新的这么多条；
     *  光靠「超过两天才丢」是不够的——读屏密集时两天的量就能撑满，
     *  而全是新数据时裁剪一条都丢不掉，于是每次追加都要重写整个文件。 */
    private static final int MAX_LINES = 2000;
    private static final int MAX_TEXT = 4000;
    /** 同一块屏幕内容 20 秒内重复读到不重复记（contentChanged 很密）。 */
    private static final long DEDUPE_MS = 20_000L;

    static final class Item {
        final long at;
        final String who;
        final String text;
        /** chat = 对话（你说的、回你的）；whisper = 她自己冒出来的碎碎念。 */
        final String kind;

        Item(long at, String who, String text, String kind) {
            this.at = at;
            this.who = who;
            this.text = text;
            this.kind = kind == null ? "chat" : kind;
        }
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static Context app;
    private static String lastKey = "";
    private static long lastKeyAt = 0L;
    private static String lastWhereKey = "";
    private static long lastWhereAt = 0L;

    private Logs() {
    }

    static void init(Context c) {
        if (c != null && app == null) app = c.getApplicationContext();
    }

    // ---------- 写 ----------

    static void screen(String appName, String text) {
        final Context c = app;
        if (c == null || text == null || text.length() == 0) return;
        String content = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
        String key = appName + "\u0000" + content;
        long now = System.currentTimeMillis();
        synchronized (Logs.class) {
            if (key.equals(lastKey) && now - lastKeyAt < DEDUPE_MS) return;
            lastKey = key;
            lastKeyAt = now;
        }
        final String line = line(now, appName == null ? "" : appName, content, "");
        if (line == null) return;
        IO.execute(new Append(c, "screen.jsonl", line, SCREEN_DAYS));
    }

    /** 「你在哪」：只记应用名与页面名，不记任何屏幕文字。二十秒一条，同样的地方不重复记。 */
    static void where(String appName, String page) {
        final Context c = app;
        String a = appName == null ? "" : appName;
        if (c == null || a.length() == 0) return;
        final String p = page == null ? "" : page;
        String key = a + "\u0000" + p;
        long now = System.currentTimeMillis();
        synchronized (Logs.class) {
            if (key.equals(lastWhereKey) && now - lastWhereAt < DEDUPE_MS) return;
            lastWhereKey = key;
            lastWhereAt = now;
        }
        final String line = line(now, a, p, "where");
        if (line == null) return;
        IO.execute(new Append(c, "where.jsonl", line, SCREEN_DAYS));
    }

    static void chat(String who, String text, String kind) {
        final Context c = app;
        if (c == null || text == null || text.length() == 0) return;
        String content = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
        final String line = line(System.currentTimeMillis(), who == null ? "" : who, content, kind);
        if (line == null) return;
        IO.execute(new Append(c, "chat.jsonl", line, CHAT_DAYS));
    }

    /** 存一条压缩要点；等级决定它在盘上活多久。 */
    static void digest(int lv, String text) {
        final Context c = app;
        if (c == null || text == null) return;
        final int level = Math.max(1, Math.min(5, lv));
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.length() == 0) return;
        if (t.length() > MAX_CTX_TEXT) t = t.substring(0, MAX_CTX_TEXT);
        IO.execute(new AppendDigest(c, new Digest(System.currentTimeMillis(), level, t)));
    }

    private static String line(long at, String who, String text, String kind) {
        try {
            JSONObject o = new JSONObject().put("t", at).put("who", who).put("text", text);
            if (kind != null && kind.length() > 0) o.put("kind", kind);
            return o.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    // ---------- 读 ----------

    static List<Item> screenItems() {
        return read("screen.jsonl", SCREEN_DAYS);
    }

    static List<Item> whereItems() {
        return read("where.jsonl", SCREEN_DAYS);
    }

    static List<Item> chatItems() {
        return read("chat.jsonl", CHAT_DAYS);
    }

    /** 上次压缩之后新攒的对话（新的在前，只算真的说过话的，不含碎碎念）。 */
    static List<Item> chatSince(long since) {
        List<Item> out = new ArrayList<>();
        for (Item it : chatItems()) {
            if (it.at <= since) continue;
            // 碎碎念也算上下文：不带上它，模型会反复说同一个意思
            if (!"chat".equals(it.kind) && !"whisper".equals(it.kind)) continue;
            out.add(it);
        }
        return out;
    }

    static final class Digest {
        final long at;
        final int lv;
        final String text;
        /** 上次被挑进提示词的时间，0 = 还没用过。到容量上限时先甩最久没用过的。 */
        final long use;

        Digest(long at, int lv, String text) {
            this(at, lv, text, 0L);
        }

        Digest(long at, int lv, String text, long use) {
            this.at = at;
            this.lv = lv;
            this.text = text;
            this.use = use;
        }
    }

    /** 上下文容量（条数），默认 400。 */
    static int ctxCap() {
        Context c = app;
        if (c == null) return DEFAULT_CTX;
        int v = c.getSharedPreferences("pet", Context.MODE_PRIVATE).getInt("ctxCap", DEFAULT_CTX);
        return Math.max(50, Math.min(MAX_CTX, v));
    }

    static void setCtxCap(int v) {
        Context c = app;
        if (c == null) return;
        int n = Math.max(50, Math.min(MAX_CTX, v));
        c.getSharedPreferences("pet", Context.MODE_PRIVATE).edit().putInt("ctxCap", n).apply();
    }

    /** 未过期的压缩要点，新的在前。 */
    static List<Digest> digests() {
        List<Digest> out = readDigests(app);
        for (int i = 1; i < out.size(); i++) {
            Digest cur = out.get(i);
            int j = i - 1;
            while (j >= 0 && out.get(j).at < cur.at) {
                out.set(j + 1, out.get(j));
                j--;
            }
            out.set(j + 1, cur);
        }
        return out;
    }

    static int ctxUsed() {
        return digests().size();
    }

    /** 按容量算的占用率 0..100。 */
    static int ctxPercent() {
        int cap = ctxCap();
        if (cap <= 0) return 0;
        return Math.min(100, (int) Math.round(ctxUsed() * 100.0 / cap));
    }

    /** 越过 50/80/90/95/100 里还没提醒过的那条线，就回一句话；否则 null。 */
    static String warnIfNeeded() {
        Context c = app;
        if (c == null) return null;
        int pct = ctxPercent();
        SharedPreferences sp = c.getSharedPreferences("pet", Context.MODE_PRIVATE);
        int warned = sp.getInt("ctxWarn", 0);
        if (pct < CTX_WARN[0]) {
            if (warned != 0) sp.edit().putInt("ctxWarn", 0).apply();
            return null;
        }
        int hit = 0;
        for (int t : CTX_WARN) if (pct >= t) hit = t;
        if (hit <= warned) return null;
        sp.edit().putInt("ctxWarn", hit).apply();
        int used = ctxUsed();
        int cap = ctxCap();
        if (hit >= 100) {
            return "上下文已经满了（" + used + "/" + cap + " 条），接下来会先删掉 2% 最久没用过的";
        }
        return "上下文用到 " + pct + "%（" + used + "/" + cap + " 条），快到上限了";
    }

    /** 读盘：只取没过期的，不排序。 */
    private static List<Digest> readDigests(Context c) {
        List<Digest> out = new ArrayList<>();
        if (c == null) return out;
        File f = new File(c.getFilesDir(), "context.jsonl");
        if (!f.isFile()) return out;
        long now = System.currentTimeMillis();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), Charset.forName("UTF-8")));
            String s;
            while ((s = r.readLine()) != null) {
                if (s.length() == 0) continue;
                try {
                    JSONObject o = new JSONObject(s);
                    long t = o.optLong("t", 0L);
                    int lv = Math.max(1, Math.min(5, o.optInt("lv", 1)));
                    int days = CTX_DAYS[lv - 1];
                    if (days > 0 && t < now - days * 24L * 3600L * 1000L) continue;
                    String text = o.optString("text", "").trim();
                    if (text.length() == 0) continue;
                    out.add(new Digest(t, lv, text, o.optLong("u", 0L)));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        } finally {
            close(r);
        }
        return out;
    }

    /** 喂给模型的一段要点，从新到旧挑，总额不超 maxChars（旧的排前面，读着顺）。 */
    static String digestText(int maxChars) {
        List<Digest> pick = new ArrayList<>();
        int used = 0;
        for (Digest d : digests()) {
            int cost = d.text.length() + 4;
            if (used + cost > maxChars) break;
            pick.add(0, d);
            used += cost;
        }
        StringBuilder sb = new StringBuilder();
        for (Digest d : pick) sb.append("- ").append(d.text).append('\n');
        touch(pick);
        return sb.toString().trim();
    }

    /** 上次把「被用到」写回盘的时间：最多五分钟一次，别每说一句话就写文件。 */
    private static long lastTouchAt = 0L;

    private static void touch(List<Digest> pick) {
        Context c = app;
        if (c == null || pick.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastTouchAt < 5 * 60 * 1000L) return;
        lastTouchAt = now;
        ArrayList<String> texts = new ArrayList<>();
        for (Digest d : pick) texts.add(d.text);
        IO.execute(new TouchDigests(c, texts, now));
    }

    /** a 比 b 更该被甩掉：先看多久没用，再看等级低，最后看更旧。 */
    private static boolean olderUse(Digest a, Digest b) {
        long ua = a.use > 0 ? a.use : a.at;
        long ub = b.use > 0 ? b.use : b.at;
        if (ua != ub) return ua < ub;
        if (a.lv != b.lv) return a.lv < b.lv;
        return a.at < b.at;
    }

    /** 智能删除：1 重复/被包含，2 寒暄没信息，3 太久没用，4 超容量。返回各类删了几条。 */
    static int[] smartClean(boolean apply) {
        int[] counts = new int[4];
        Context c = app;
        if (c == null) return counts;
        synchronized (CTX_LOCK) {
            List<Digest> all = readDigests(c);
            // 新的先看：重复时留措辞更新的那条，包含关系时留信息多的那条
            for (int i = 1; i < all.size(); i++) {
                Digest cur = all.get(i);
                int j = i - 1;
                while (j >= 0 && all.get(j).at < cur.at) {
                    all.set(j + 1, all.get(j));
                    j--;
                }
                all.set(j + 1, cur);
            }
            List<Digest> keep = new ArrayList<>();
            for (Digest d : all) {
                String n = norm(d.text);
                boolean dup = false;
                for (int i = 0; i < keep.size(); i++) {
                    String kn = norm(keep.get(i).text);
                    if (kn.equals(n) || (kn.contains(n) && n.length() >= 4)) {
                        dup = true;
                        break;
                    }
                    if (n.contains(kn) && kn.length() >= 4) {
                        keep.remove(i);
                        i--;
                    }
                }
                if (dup) {
                    counts[0]++;
                    continue;
                }
                if (n.length() <= 2 || n.matches("^[嗯哦啊哈好的是对行在okOK嗯嗯好的哈哈]+$")) {
                    counts[1]++;
                    continue;
                }
                long last = d.use > 0 ? d.use : d.at;
                long days = (System.currentTimeMillis() - last) / 86400000L;
                if ((d.lv == 1 && days >= 7) || (d.lv == 2 && days >= 20)) {
                    counts[2]++;
                    continue;
                }
                keep.add(d);
            }
            int cap = ctxCap();
            int target = Math.max(1, cap * 9 / 10);
            while (keep.size() > target) {
                int worst = 0;
                for (int i = 1; i < keep.size(); i++) {
                    Digest a = keep.get(i);
                    Digest b = keep.get(worst);
                    int la = a.lv, lb = b.lv;
                    long ua = a.use > 0 ? a.use : a.at;
                    long ub = b.use > 0 ? b.use : b.at;
                    if (la < lb || (la == lb && ua < ub)) worst = i;
                }
                keep.remove(worst);
                counts[3]++;
            }
            if (apply) writeDigests(c, keep);
        }
        return counts;
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").trim();
    }

    private static List<Item> read(String name, int days) {
        List<Item> out = new ArrayList<>();
        Context c = app;
        if (c == null) return out;
        File f = new File(c.getFilesDir(), name);
        if (!f.isFile()) return out;
        long since = System.currentTimeMillis() - days * 24L * 3600L * 1000L;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), Charset.forName("UTF-8")));
            String s;
            while ((s = r.readLine()) != null) {
                if (s.length() == 0) continue;
                try {
                    JSONObject o = new JSONObject(s);
                    long t = o.optLong("t", 0L);
                    if (t < since) continue;
                    out.add(new Item(t, o.optString("who", ""), o.optString("text", ""),
                            o.optString("kind", "chat")));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        } finally {
            close(r);
        }
        // 按时间倒序（新的在前）。手写插入排序，不引 Comparator：
        // d8 处理这种泛型嵌套比较器类时会崩（NullPointerException: String.length() is null）。
        for (int i = 1; i < out.size(); i++) {
            Item cur = out.get(i);
            int j = i - 1;
            while (j >= 0 && out.get(j).at < cur.at) {
                out.set(j + 1, out.get(j));
                j--;
            }
            out.set(j + 1, cur);
        }
        return out;
    }

    /** 时间 → 「08:12:33」；跨天的聊天记录再用日期分组。 */
    static String clock(long at) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(at));
    }

    static String day(long at) {
        return new SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).format(new Date(at));
    }

    static String dayKey(long at) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(at));
    }

    static String stamp(long at) {
        return new SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(new Date(at));
    }

    // ---------- 落盘 ----------

    private static final class Append implements Runnable {
        private final Context context;
        private final String name;
        private final String line;
        private final int days;

        Append(Context c, String name, String line, int days) {
            this.context = c;
            this.name = name;
            this.line = line;
            this.days = days;
        }

        @Override
        public void run() {
            File f = new File(context.getFilesDir(), name);
            OutputStreamWriter w = null;
            try {
                if (f.length() > MAX_BYTES) prune(context, f, days);
                w = new OutputStreamWriter(new FileOutputStream(f, true), Charset.forName("UTF-8"));
                w.write(line);
                w.write("\n");
            } catch (Throwable ignored) {
            } finally {
                close(w);
            }
        }
    }

    /** 等级对应的保存天数，0 = 永久。 */
    static int ctxDays(int lv) {
        int i = Math.max(1, Math.min(5, lv)) - 1;
        return CTX_DAYS[i];
    }

    /** 界面上改过摘要之后整份写回（先写 .tmp 再改名，顺手按等级裁过期）。 */
    static void digestSave(final List<Digest> items) {
        final Context c = app;
        if (c == null) return;
        IO.execute(new SaveDigests(c, items));
    }

    private static final class SaveDigests implements Runnable {
        private final Context context;
        private final List<Digest> items;

        SaveDigests(Context c, List<Digest> items) {
            this.context = c;
            this.items = items;
        }

        @Override
        public void run() {
            synchronized (CTX_LOCK) {
                writeDigests(context, new ArrayList<>(items));
            }
        }
    }

    /** 整份写回：按等级裁过期、到容量上限就先甩最久没用过的 2%（最少 1 条）。 */
    private static void writeDigests(Context c, List<Digest> items) {
        File f = new File(c.getFilesDir(), "context.jsonl");
        File tmp = new File(c.getFilesDir(), "context.jsonl.tmp");
        long now = System.currentTimeMillis();
        int cap = ctxCap();
        List<Digest> keep = new ArrayList<>();
        for (Digest d : items) {
            int lv = Math.max(1, Math.min(5, d.lv));
            int days = CTX_DAYS[lv - 1];
            if (days > 0 && d.at < now - days * 24L * 3600L * 1000L) continue;
            keep.add(d);
        }
        int drop = Math.max(1, (int) Math.ceil(cap * 0.02));
        while (keep.size() > cap) {
            for (int k = 0; k < drop && keep.size() > cap; k++) {
                int worst = -1;
                for (int i = 0; i < keep.size(); i++) {
                    if (worst < 0 || olderUse(keep.get(i), keep.get(worst))) worst = i;
                }
                if (worst < 0) break;
                keep.remove(worst);
            }
        }
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(tmp, false), Charset.forName("UTF-8"));
            for (Digest d : keep) {
                JSONObject o = new JSONObject();
                o.put("t", d.at);
                o.put("lv", Math.max(1, Math.min(5, d.lv)));
                o.put("text", d.text);
                o.put("kind", "digest");
                if (d.use > 0) o.put("u", d.use);
                w.write(o.toString());
                w.write("\n");
            }
            w.flush();
            close(w);
            w = null;
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Throwable ignored) {
        } finally {
            close(w);
            if (tmp.exists()) tmp.delete();
        }
    }

    /** 把「这几条刚被用到」写回盘（最多五分钟一次）。 */
    private static final class TouchDigests implements Runnable {
        private final Context context;
        private final ArrayList<String> texts;
        private final long now;

        TouchDigests(Context c, ArrayList<String> texts, long now) {
            this.context = c;
            this.texts = texts;
            this.now = now;
        }

        @Override
        public void run() {
            synchronized (CTX_LOCK) {
                List<Digest> all = readDigests(context);
                List<Digest> out = new ArrayList<>();
                for (Digest d : all) {
                    out.add(texts.contains(d.text) ? new Digest(d.at, d.lv, d.text, now) : d);
                }
                writeDigests(context, out);
            }
        }
    }

    /** 压缩条目的落盘：按各自等级裁过期，再追加新的一条。 */
    private static final class AppendDigest implements Runnable {
        private final Context context;
        private final Digest item;

        AppendDigest(Context c, Digest item) {
            this.context = c;
            this.item = item;
        }

        @Override
        public void run() {
            synchronized (CTX_LOCK) {
                List<Digest> all = readDigests(context);
                all.add(item);
                writeDigests(context, all);
            }
        }
    }

    /** 把过期行、超额的行丢掉再写回（先写 .tmp 再改名，别写坏原文件）。 */
    private static void prune(Context c, File f, int days) {
        long since = System.currentTimeMillis() - days * 24L * 3600L * 1000L;
        File tmp = new File(c.getFilesDir(), f.getName() + ".tmp");
        BufferedReader r = null;
        OutputStreamWriter w = null;
        java.util.ArrayList<String> keep = new java.util.ArrayList<>();
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), Charset.forName("UTF-8")));
            String s;
            while ((s = r.readLine()) != null) {
                if (s.length() == 0) continue;
                try {
                    if (new JSONObject(s).optLong("t", 0L) < since) continue;
                } catch (Throwable ignored) {
                    continue;
                }
                keep.add(s);
            }
            close(r);
            r = null;
            int from = Math.max(0, keep.size() - MAX_LINES);   // 文件按时间追加，末尾最新
            w = new OutputStreamWriter(new FileOutputStream(tmp, false), Charset.forName("UTF-8"));
            for (int i = from; i < keep.size(); i++) {
                w.write(keep.get(i));
                w.write("\n");
            }
            w.flush();
            close(w);
            w = null;
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Throwable ignored) {
        } finally {
            close(r);
            close(w);
            if (tmp.exists()) tmp.delete();
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
