package com.dsh.pet;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * 宠物的大脑：POST 到宿主插件 @local/pet-brain（同机 loopback，不需要登录令牌）。
 * 模型和账号都在 DSH 宿主里，App 自己不存任何 key。
 */
public final class Brain {
    private static final String URL_SAY = "http://127.0.0.1:3080/pet-brain/say";
    private static final String URL_PING = "http://127.0.0.1:3080/pet-brain/health";
    private static final String URL_DIAG = "http://127.0.0.1:3080/pet-brain/diag";
    private static final String URL_HABITS = "http://127.0.0.1:3080/pet-brain/habits";
    private static final String URL_COMPRESS = "http://127.0.0.1:3080/pet-brain/compress";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Listener {
        void onReply(String text);

        void onError(String reason);
    }

    private Brain() {
    }

    /** 异步请求一句台词；回调在主线程。 */
    public static void say(final Context context, final String kind, final String text,
                           final String historyJson, final JSONObject ctx, final String digest,
                           final Listener listener) {
        boolean direct = context != null && Config.direct(context);
        String persona = context == null || !Persona.enabled(context) ? null : Persona.custom(context);
        Runnable job = direct
                ? new DirectTask(context.getApplicationContext(), kind, text, historyJson, ctx, digest, listener)
                : new AskTask(kind, text, historyJson, ctx, digest, listener, persona);
        Thread t = new Thread(job, direct ? "pet-direct" : "pet-brain");
        t.setDaemon(true);
        t.start();
    }

    private static final String REMEMBER_HINT =
            "\n\n只有当主人说到了**长期有效**的偏好、习惯、固定安排或约定时（比如作息、忌口、每周的固定安排、"
                    + "答应过的事），才在回答最后附上 <remember>要点</remember>，写成像\"主人…\"的一句陈述，最多两条。"
                    + "当前正在做什么、临时状态、情绪、天气这类一次性的事一律不要记；没有可记的就完全不要出现这个标签。";

    private static String contextLine(JSONObject ctx) {
        if (ctx == null) return "";
        StringBuilder sb = new StringBuilder();
        String time = ctx.optString("time", "");
        if (time.length() > 0) sb.append("现在").append(time);
        String app = ctx.optString("app", "");
        if (app.length() > 0) {
            if (sb.length() > 0) sb.append('，');
            sb.append("主人正在用「").append(app).append('」');
            String page = ctx.optString("activity", "");
            if (page.length() > 0) sb.append("的「").append(page).append("」页面");
        }
        String screen = ctx.optString("screen", "");
        if (screen.length() > 0) {
            if (sb.length() > 0) sb.append('，');
            sb.append("屏幕上是：").append(screen.length() > 1200 ? screen.substring(0, 1200) : screen);
        }
        if (ctx.has("battery")) {
            if (sb.length() > 0) sb.append('，');
            sb.append("电量").append(ctx.optInt("battery", 0)).append('%');
            if (ctx.optBoolean("charging", false)) sb.append("（在充电）");
        }
        int idle = ctx.optInt("idleSec", 0);
        if (idle > 0) {
            if (sb.length() > 0) sb.append('，');
            sb.append("已经").append(Math.round(idle / 60f)).append("分钟没碰屏幕");
        }
        String habit = ctx.optString("habit", "");
        if (habit.length() > 0) {
            if (sb.length() > 0) sb.append('，');
            sb.append("按以往习惯，这个时间点主人常在：").append(habit);
        }
        return sb.toString();
    }

    /** 把 App 侧的异常/状态送回宿主日志，便于排查（不弹任何东西）。 */
    public static void diag(final String msg) {
        Thread t = new Thread(new DiagTask(msg), "pet-diag");
        t.setDaemon(true);
        t.start();
    }

    public static void ping(final Listener listener) {
        Thread t = new Thread(new PingTask(listener), "pet-ping");
        t.setDaemon(true);
        t.start();
    }

    /** 把本机习惯快照同步给宿主（宿主存在 $DSH_HOME/pet-memory/，无需 root 就能看）。 */
    public static void pushHabits(final String json) {
        if (json == null || json.length() == 0) return;
        Thread t = new Thread(new HabitsTask(json), "pet-habits");
        t.setDaemon(true);
        t.start();
    }

    private static final class HabitsTask implements Runnable {
        private final String json;

        HabitsTask(String j) {
            this.json = j;
        }

        @Override
        public void run() {
            try {
                request(URL_HABITS, json);
            } catch (Throwable e) {
                Log.w("dsh-pet", "habits push failed", e);
            }
        }
    }

    private static String request(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(50000);
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
            if (code >= 400) {
                // 404 多半是宿主没装 pet-brain 插件，把话说清楚，别让用户以为是没登录
                throw new IllegalStateException("HTTP " + code
                        + (code == 404 ? "：宿主里没有 pet-brain 插件" : ""));
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static final class AskTask implements Runnable {
        private final String kind, text, historyJson, digest;
        private final JSONObject ctx;
        private final Listener listener;
        private final String persona;

        AskTask(String kind, String text, String historyJson, JSONObject ctx, String digest,
                Listener listener, String persona) {
            this.kind = kind;
            this.text = text;
            this.historyJson = historyJson;
            this.ctx = ctx;
            this.digest = digest;
            this.listener = listener;
            this.persona = persona;
        }

        @Override
        public void run() {
            try {
                JSONObject body = new JSONObject();
                body.put("kind", kind);
                if (text != null) body.put("text", text);
                if (ctx != null) body.put("ctx", ctx);
                if (historyJson != null && historyJson.length() > 0) {
                    body.put("history", new org.json.JSONArray(historyJson));
                }
                if (persona != null && persona.length() > 0) body.put("persona", persona);
                if (digest != null && digest.length() > 0) body.put("digest", digest);
                String raw = request(URL_SAY, body.toString());
                JSONObject res = new JSONObject(raw);
                final String reply = res.optString("text", "");
                if (res.optBoolean("ok") && reply.length() > 0) {
                    MAIN.post(new OkPost(listener, reply));
                } else {
                    String err = res.optString("error", "?") + " " + res.optString("message", "");
                    MAIN.post(new ErrPost(listener, err.trim()));
                }
            } catch (Throwable e) {
                Log.w("dsh-pet", "brain failed", e);
                MAIN.post(new ErrPost(listener, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
    }

    /** 压缩结果：每条形如 {"lv":3,"text":"…"}，lv 1–5 决定这条在 App 里存多久。 */
    public interface DigestListener {
        void onItems(JSONArray items);

        void onError(String reason);
    }

    /** 把一批原话压成要点。攒够一批才调，别每句都调。 */
    public static void compress(final Context context, final JSONArray turns, final DigestListener listener) {
        boolean direct = context != null && Config.direct(context);
        Runnable job = direct
                ? new DirectCompress(context.getApplicationContext(), turns, listener)
                : new CompressTask(turns, listener);
        Thread t = new Thread(job, direct ? "pet-direct" : "pet-compress");
        t.setDaemon(true);
        t.start();
    }

    private static final String COMPRESS_SYSTEM =
            "你在整理手机桌面宠物和主人的一段聊天。把它压成 1–3 条要点，每条一行，格式严格是「等级|要点」。"
                    + "等级：1=一般（留3天）2=有用（留7天）3=重要（留15天）4=很想记住（留30天）5=永久。"
                    + "只留以后聊天还用得上的：聊了什么、主人的偏好和情绪、答应过的事、正在进行的计划。"
                    + "不要寒暄、不要复述原句、不要编号和解释，每条不超过40字，宁少勿多。";

    private static String turnsText(JSONArray turns) {
        StringBuilder sb = new StringBuilder();
        if (turns == null) return "";
        int from = Math.max(0, turns.length() - 40);
        for (int i = from; i < turns.length(); i++) {
            JSONObject o = turns.optJSONObject(i);
            if (o == null) continue;
            String text = o.optString("text", "").trim();
            if (text.length() == 0) continue;
            boolean whisper = "whisper".equals(o.optString("kind", ""));
            String who = "her".equals(o.optString("who", ""))
                    ? (whisper ? "宠物(碎碎念)：" : "宠物：") : "主人：";
            int cap = whisper ? 120 : 300;
            sb.append(who).append(text.length() > cap ? text.substring(0, cap) : text).append('\n');
        }
        return sb.toString();
    }

    /** 解析「等级|要点」；容忍编号、列表符号和中英文标点。 */
    private static void addDigest(JSONArray out, String raw) {
        if (raw == null) return;
        // 先剥列表符号，再剥「1. 」这种编号——别把等级自己的数字也剥掉
        String line = raw.trim().replaceAll("^[-*·•\\s]+", "");
        line = line.replaceAll("^\\d+[.、)）]\\s*", "").trim();
        if (line.length() == 0) return;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^[\\[(（]?\\s*([1-5])\\s*[\\])）]?\\s*[|｜:：,，]\\s*(.+)$").matcher(line);
        if (!m.matches()) return;
        String text = m.group(2).trim();
        if (text.length() == 0) return;
        if (text.length() > 60) text = text.substring(0, 60);
        try {
            JSONObject o = new JSONObject();
            o.put("lv", Integer.parseInt(m.group(1)));
            o.put("text", text);
            out.put(o);
        } catch (Throwable ignored) {
        }
    }

    private static JSONArray parseDigestLines(String text) {
        JSONArray out = new JSONArray();
        for (String line : String.valueOf(text).split("\n")) {
            if (out.length() >= 4) break;
            addDigest(out, line);
        }
        return out;
    }

    private static final class CompressTask implements Runnable {
        private final JSONArray turns;
        private final DigestListener listener;

        CompressTask(JSONArray turns, DigestListener l) {
            this.turns = turns;
            this.listener = l;
        }

        @Override
        public void run() {
            try {
                JSONObject body = new JSONObject();
                body.put("turns", turns == null ? new JSONArray() : turns);
                JSONObject res = new JSONObject(request(URL_COMPRESS, body.toString()));
                if (!res.optBoolean("ok")) {
                    MAIN.post(new DigestErr(listener,
                            res.optString("message", res.optString("error", "?"))));
                    return;
                }
                JSONArray items = res.optJSONArray("items");
                MAIN.post(new DigestOk(listener, items == null ? new JSONArray() : items));
            } catch (Throwable e) {
                Log.w("dsh-pet", "compress failed", e);
                MAIN.post(new DigestErr(listener, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
    }

    private static final class DirectCompress implements Runnable {
        private final Context context;
        private final JSONArray turns;
        private final DigestListener listener;

        DirectCompress(Context c, JSONArray turns, DigestListener l) {
            this.context = c;
            this.turns = turns;
            this.listener = l;
        }

        @Override
        public void run() {
            try {
                String key = Config.key(context);
                if (key.length() == 0) {
                    MAIN.post(new DigestErr(listener, "还没有填 API key"));
                    return;
                }
                JSONArray messages = new JSONArray();
                JSONObject sysMsg = new JSONObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", COMPRESS_SYSTEM);
                messages.put(sysMsg);
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", turnsText(turns));
                messages.put(userMsg);

                JSONObject body = new JSONObject();
                body.put("model", Config.model(context));
                body.put("messages", messages);
                body.put("temperature", 0.3);
                body.put("max_tokens", 2000);
                body.put("stream", false);

                JSONObject res = new JSONObject(
                        requestAuth(Config.base(context) + "/chat/completions", body.toString(), key));
                recordUsage(context, "compress", res);
                JSONArray choices = res.optJSONArray("choices");
                String reply = "";
                if (choices != null && choices.length() > 0) {
                    JSONObject msg = choices.optJSONObject(0) == null ? null
                            : choices.optJSONObject(0).optJSONObject("message");
                    if (msg != null) reply = msg.optString("content", "");
                }
                MAIN.post(new DigestOk(listener, parseDigestLines(reply)));
            } catch (Throwable e) {
                Log.w("dsh-pet", "direct compress failed", e);
                String m = e.getMessage();
                MAIN.post(new DigestErr(listener, e.getClass().getSimpleName() + (m == null ? "" : ": " + m)));
            }
        }
    }

    private static final class DigestOk implements Runnable {
        private final DigestListener listener;
        private final JSONArray items;

        DigestOk(DigestListener l, JSONArray items) {
            this.listener = l;
            this.items = items;
        }

        @Override
        public void run() {
            listener.onItems(items);
        }
    }

    private static final class DigestErr implements Runnable {
        private final DigestListener listener;
        private final String reason;

        DigestErr(DigestListener l, String reason) {
            this.listener = l;
            this.reason = reason == null ? "?" : reason;
        }

        @Override
        public void run() {
            listener.onError(reason);
        }
    }

    /** 自填 key：直连 DeepSeek 的 OpenAI 兼容接口。 */
    private static final class DirectTask implements Runnable {
        private final Context context;
        private final String kind, text, historyJson, digest;
        private final JSONObject ctx;
        private final Listener listener;

        DirectTask(Context c, String kind, String text, String historyJson, JSONObject ctx, String digest,
                   Listener l) {
            this.context = c;
            this.kind = kind;
            this.text = text;
            this.historyJson = historyJson;
            this.ctx = ctx;
            this.digest = digest;
            this.listener = l;
        }

        @Override
        public void run() {
            try {
                String key = Config.key(context);
                if (key.length() == 0) {
                    MAIN.post(new ErrPost(listener, "还没有填 API key"));
                    return;
                }
                String facts = Facts.text(context);
                StringBuilder sys = new StringBuilder(Persona.system(context, kind));
                if (facts.length() > 0) {
                    sys.append("\n\n你记得关于主人的事（该用就用，别逐条复述、别说\"我记得\"）：\n").append(facts);
                }
                if (digest != null && digest.length() > 0) {
                    sys.append("\n\n你和主人之前聊过的（越靠后越近；该用就用，别逐条复述、别说\"我记得\"）：\n")
                            .append(digest);
                }
                if ("chat".equals(kind)) sys.append(REMEMBER_HINT);

                JSONArray messages = new JSONArray();
                JSONObject sysMsg = new JSONObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", sys.toString());
                messages.put(sysMsg);
                if (historyJson != null && historyJson.length() > 0) {
                    JSONArray hist = new JSONArray(historyJson);
                    int from = Math.max(0, hist.length() - 6);
                    for (int i = from; i < hist.length(); i++) {
                        JSONObject h = hist.optJSONObject(i);
                        if (h == null) continue;
                        String t = h.optString("text", "").trim();
                        if (t.length() == 0) continue;
                        JSONObject m = new JSONObject();
                        m.put("role", "pet".equals(h.optString("role", "")) ? "assistant" : "user");
                        m.put("content", t.length() > 300 ? t.substring(0, 300) : t);
                        messages.put(m);
                    }
                }
                StringBuilder line = new StringBuilder();
                String surroundings = contextLine(ctx);
                if (surroundings.length() > 0) line.append("（环境：").append(surroundings).append("）\n");
                if ("chat".equals(kind)) {
                    line.append(text != null && text.trim().length() > 0
                            ? "主人说：" + (text.length() > 500 ? text.substring(0, 500) : text)
                            : "（主人没说话，你主动开口）");
                } else {
                    line.append("（现在主动说一句，别问问题，别复述环境原文）");
                }
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", line.toString());
                messages.put(userMsg);

                JSONObject body = new JSONObject();
                body.put("model", Config.model(context));
                body.put("messages", messages);
                body.put("temperature", 1.0);
                body.put("max_tokens", 2000);
                body.put("stream", false);

                String raw = requestAuth(Config.base(context) + "/chat/completions", body.toString(), key);
                JSONObject res = new JSONObject(raw);
                recordUsage(context, kind, res);
                JSONArray choices = res.optJSONArray("choices");
                String reply = "";
                if (choices != null && choices.length() > 0) {
                    JSONObject msg = choices.optJSONObject(0) == null ? null : choices.optJSONObject(0).optJSONObject("message");
                    if (msg != null) reply = msg.optString("content", "");
                }
                if (reply.trim().length() == 0) {
                    MAIN.post(new ErrPost(listener, "empty"));
                    return;
                }
                String[] parts = splitRemember(reply);
                if (parts[1].length() > 0) Facts.add(context, java.util.Arrays.asList(parts[1].split("\n")));
                final String out = parts[0];
                if (out.length() == 0) {
                    MAIN.post(new ErrPost(listener, "empty-after-strip"));
                    return;
                }
                MAIN.post(new OkPost(listener, out));
            } catch (Throwable e) {
                Log.w("dsh-pet", "direct brain failed", e);
                String m = e.getMessage();
                MAIN.post(new ErrPost(listener, e.getClass().getSimpleName() + (m == null ? "" : ": " + m)));
            }
        }
    }

    /** 接口回报的 usage 才是真账：直连模式记在本机，宿主模式由插件记。 */
    private static void recordUsage(Context context, String kind, JSONObject res) {
        JSONObject u = res == null ? null : res.optJSONObject("usage");
        if (u == null) return;
        long in = u.optLong("prompt_tokens", 0L);
        long out = u.optLong("completion_tokens", 0L);
        long hit = u.optLong("prompt_cache_hit_tokens", 0L);
        if (in <= 0 && out <= 0) return;
        Usage.add(context, kind, in, out, hit);
    }

    /** 剥掉 <remember>…</remember>，返回 [正文, 记忆(换行分隔)]。 */
    private static String[] splitRemember(String text) {
        StringBuilder body = new StringBuilder();
        StringBuilder memo = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int s = text.indexOf("<remember>", i);
            if (s < 0) {
                body.append(text.substring(i));
                break;
            }
            int e = text.indexOf("</remember>", s);
            body.append(text, i, s);
            if (e < 0) break;
            String item = text.substring(s + 10, e).replace("</remember>", "").trim();
            if (item.length() > 0) {
                if (memo.length() > 0) memo.append('\n');
                memo.append(item);
            }
            i = e + 11;
        }
        return new String[]{body.toString().trim(), memo.toString()};
    }

    private static String requestAuth(String url, String body, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(60000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Authorization", "Bearer " + key);
            byte[] bytes = body.getBytes(Charset.forName("UTF-8"));
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();
            int code = c.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    code >= 400 ? c.getErrorStream() : c.getInputStream(), Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            if (code >= 400) throw new IllegalStateException("HTTP " + code + " " + sb);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static final class DiagTask implements Runnable {
        private final String msg;

        DiagTask(String m) {
            this.msg = m;
        }

        @Override
        public void run() {
            try {
                JSONObject body = new JSONObject();
                body.put("msg", msg == null ? "" : msg);
                request(URL_DIAG, body.toString());
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class PingTask implements Runnable {
        private final Listener listener;

        PingTask(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void run() {
            try {
                String raw = request(URL_PING, null);
                MAIN.post(new OkPost(listener, raw));
            } catch (Throwable e) {
                MAIN.post(new ErrPost(listener, String.valueOf(e.getMessage())));
            }
        }
    }

    private static final class OkPost implements Runnable {
        private final Listener listener;
        private final String text;

        OkPost(Listener l, String t) {
            this.listener = l;
            this.text = t;
        }

        @Override
        public void run() {
            listener.onReply(text);
        }
    }

    private static final class ErrPost implements Runnable {
        private final Listener listener;
        private final String reason;

        ErrPost(Listener l, String r) {
            this.listener = l;
            this.reason = r;
        }

        @Override
        public void run() {
            listener.onError(reason);
        }
    }
}
