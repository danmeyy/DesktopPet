package com.dsh.pet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 自填 Key 直连模式下的长期记忆（宿主模式下用宿主的 pet-memory，不重复存）。 */
final class Facts {
    private static final int MAX = 80;

    private Facts() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("pet", Context.MODE_PRIVATE);
    }

    static JSONArray load(Context c) {
        try {
            String raw = sp(c).getString("facts", "[]");
            return new JSONArray(raw);
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    /** 拼成给模型看的几行，没有就返回空串。 */
    static String text(Context c) {
        JSONArray arr = load(c);
        if (arr.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, arr.length() - 20);
        for (int i = from; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            String t = o == null ? "" : o.optString("text", "");
            if (t.trim().length() > 0) sb.append("- ").append(t.trim()).append('\n');
        }
        return sb.toString();
    }

    static void add(Context c, List<String> items) {
        if (items == null || items.isEmpty()) return;
        JSONArray arr = load(c);
        List<String> have = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) have.add(o.optString("text", ""));
        }
        try {
            for (String raw : items) {
                String t = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
                if (t.length() == 0) continue;
                if (t.length() > 80) t = t.substring(0, 80);
                if (have.contains(t)) continue;
                JSONObject o = new JSONObject();
                o.put("text", t);
                o.put("at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",
                        java.util.Locale.US).format(new java.util.Date()));
                arr.put(o);
                have.add(t);
            }
            while (arr.length() > MAX) arr.remove(0);
            sp(c).edit().putString("facts", arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 删掉一条（记忆页面用）。 */
    static void remove(Context c, String text) {
        if (text == null) return;
        String want = text.trim();
        JSONArray arr = load(c);
        JSONArray next = new JSONArray();
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                String t = o == null ? "" : o.optString("text", "");
                if (t.trim().equals(want)) continue;
                if (o != null) next.put(o);
            }
            sp(c).edit().putString("facts", next.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 全部清空。 */
    static void clear(Context c) {
        sp(c).edit().putString("facts", "[]").apply();
    }
}
