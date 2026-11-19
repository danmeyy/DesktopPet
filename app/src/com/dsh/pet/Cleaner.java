package com.dsh.pet;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * 清理：把「用得到但不必一直留着」的东西丢掉。
 *
 * 清什么：
 *   1. 内存里的空闲动作帧缓存（换过的动作解出来的位图，最多 42MB）——正在播的那一组保留。
 *   2. 本机缓存目录（getCacheDir）里的临时文件。
 *   3. files/habits.json：本地使用习惯快照，推给宿主后就没用了，留一份只会重复。
 *   4. 宿主侧 pet-brain.log（每次请求一行，会一直涨）压到最近 200 行，事实记忆裁到 60 条。
 *
 * 不清什么：API Key、模型/接口地址、直连模式下的长期记忆、宠物本身的状态，
 * 以及宿主侧的 facts.json 里最近 60 条记忆。
 */
public final class Cleaner {

    private static final String URL_STAT = "http://127.0.0.1:3080/pet-brain/stat";
    private static final String URL_CLEAN = "http://127.0.0.1:3080/pet-brain/cleanup";

    private Cleaner() {
    }

    /** 本机占用快照。 */
    public static final class Usage {
        public long frames;      // 内存帧缓存
        public long localFiles;  // 缓存目录 + habits.json
        public long hostBytes;   // 宿主日志体积，-1 表示没连上/不需要
        public int hostFacts;    // 宿主记住的事条数，-1 未知
    }

    /** 一次清理的结果（给界面回显）。 */
    public static final class Report {
        public long framesFreed;
        public long filesFreed;
        public long hostFreed;
        public boolean hostTouched;
        public String hostError;
    }

    /** 只算本机那几项：不联网，可以直接在主线程调用。 */
    public static Usage scanLocal(Context ctx) {
        Usage u = new Usage();
        u.frames = OverlayService.framesCacheBytes();
        u.localFiles = dirSize(cacheDir(ctx)) + fileSize(habitsFile(ctx));
        u.hostBytes = -1;
        u.hostFacts = -1;
        return u;
    }

    /** 本机 + 宿主（宿主那部分要联网，别在主线程调）。 */
    public static Usage scan(Context ctx) {
        Usage u = scanLocal(ctx);
        if (usesHost(ctx)) {
            try {
                JSONObject o = new JSONObject(request(URL_STAT, null));
                if (o.has("logBytes")) u.hostBytes = o.getLong("logBytes");
                if (o.has("facts")) u.hostFacts = o.getJSONArray("facts").length();
            } catch (Throwable ignored) {
            }
        }
        return u;
    }

    /** 执行清理。会走网络（只有 host 模式），别在主线程直接调。 */
    public static Report clean(Context ctx) {
        Report r = new Report();

        long before = OverlayService.framesCacheBytes();
        OverlayService.dropFrameCache();
        r.framesFreed = Math.max(0, before - OverlayService.framesCacheBytes());

        File cache = cacheDir(ctx);
        r.filesFreed = dirSize(cache);
        deleteTree(cache);
        File habits = habitsFile(ctx);
        if (habits.exists()) {
            r.filesFreed += habits.length();
            //noinspection ResultOfMethodCallIgnored
            habits.delete();
        }

        if (usesHost(ctx)) {
            r.hostTouched = true;
            try {
                JSONObject o = new JSONObject(request(URL_CLEAN, "{}"));
                if (o.optBoolean("ok")) {
                    r.hostFreed = o.optLong("freedBytes", 0);
                } else {
                    r.hostError = "DSHBox 还没加载新接口";
                }
            } catch (Throwable t) {
                r.hostError = "连不上 DSHBox";
            }
        }
        return r;
    }

    private static boolean usesHost(Context ctx) {
        return !Config.direct(ctx);
    }

    private static File cacheDir(Context ctx) {
        File f = ctx.getCacheDir();
        return f == null ? new File(ctx.getFilesDir(), "cache") : f;
    }

    private static File habitsFile(Context ctx) {
        return new File(ctx.getFilesDir(), "habits.json");
    }

    public static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += dirSize(k);
        return n;
    }

    private static long fileSize(File f) {
        return f != null && f.exists() ? f.length() : 0;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static String human(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024f);
        return String.format("%.1f MB", bytes / (1024f * 1024f));
    }

    private static String request(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(1500);
            c.setReadTimeout(6000);
            c.setRequestMethod(body == null ? "GET" : "POST");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.getBytes(Charset.forName("UTF-8"));
                c.setFixedLengthStreamingMode(bytes.length);
                OutputStream os = c.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();
            }
            int code = c.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    code >= 400 ? c.getErrorStream() : c.getInputStream(), Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
