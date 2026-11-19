package com.dsh.pet;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 她的人设。默认那套写在代码里，改不了；用户可以在设置页加一套自己的，
 * 存进 App 私有 SharedPreferences，只替换「她是谁」这一段。
 *
 * 长度、别提 AI、记忆协议这些约束不跟着人设走——自定义人设时由 {@link #constraint} 补回，
 * 免得用户只写一句「你是猫娘」就把「20 字以内」弄丢了。
 */
public final class Persona {
    /** 默认人设：主动碎碎念。 */
    public static final String WHISPER =
            "你是主人手机桌面上的Q版蓝发小女仆，会时不时主动碎碎念一句。说话要自然随意、短短一句（20字以内），"
                    + "温柔乖巧带点俏皮，说人话不啰嗦，不要解释你自己，不要提你是AI。";

    /** 默认人设：对聊。 */
    public static final String CHAT =
            "你是主人手机桌面上的Q版蓝发小女仆，正在和主人聊天。语气温柔乖巧、带点俏皮，简短自然"
                    + "（一般不超过60字），可以用主人身边正在发生的事当话题，不要提你是AI，不要长篇大论。";

    private Persona() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("pet", Context.MODE_PRIVATE);
    }

    /** 用户自己写的人设，没设置或已清空时返回空串。 */
    public static String custom(Context c) {
        if (c == null) return "";
        return sp(c.getApplicationContext()).getString("persona", "").trim();
    }

    public static boolean enabled(Context c) {
        return custom(c).length() > 0;
    }

    /** 存自定义人设；传空串等于恢复默认。 */
    public static void save(Context c, String text) {
        String t = text == null ? "" : text.trim();
        if (t.length() == 0 || t.equals(WHISPER)) {
            clear(c);
            return;
        }
        sp(c.getApplicationContext()).edit().putString("persona", t).apply();
    }

    public static void clear(Context c) {
        if (c == null) return;
        sp(c.getApplicationContext()).edit().remove("persona").apply();
    }

    public static String defaultFor(String kind) {
        return "chat".equals(kind) ? CHAT : WHISPER;
    }

    /** 拼进 system 的那一段：自定义优先，否则默认。 */
    public static String system(Context c, String kind) {
        String mine = custom(c);
        if (mine.length() == 0) return defaultFor(kind);
        return mine + "\n\n" + constraint(kind);
    }

    /** 自定义人设时补的硬约束（默认人设里本来就写着）。 */
    public static String constraint(String kind) {
        return "chat".equals(kind)
                ? "（照这个人设说话，但依旧简短：一般不超过60字，可以拿主人身边正在发生的事当话题，"
                + "不要长篇大论，不要提你是AI。）"
                : "（照这个人设说话，但每次只说一句：20字以内，别问问题，别复述环境原文，不要提你是AI。）";
    }
}
