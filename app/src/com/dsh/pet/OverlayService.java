package com.dsh.pet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * 宠物悬浮窗服务：帧动画 + 头顶气泡 + 主动搭话 + 聊天输入。
 * 识别（前台应用/屏幕文字）来自 ScreenReader；大脑来自宿主插件 /pet-brain/say。
 */
public class OverlayService extends Service {
    public static final String ACTION_START = "com.dsh.pet.START";
    public static final String ACTION_STOP = "com.dsh.pet.STOP";
    public static final String ACTION_SAY = "com.dsh.pet.SAY";
    private static final String CHANNEL_ID = "pet";
    private static final int NOTIF_ID = 42;
    private static final long MIN_GAP_MS = 75_000L;       // 两次主动搭话的最小间隔
    private static final long SWITCH_GAP_MS = 60_000L;    // 换应用触发的冷却
    private static final long ROAM_MIN_MS = 40_000L;      // 两次漫步的最小间隔
    private static final long ROAM_MAX_MS = 110_000L;     // 最大间隔
    private static final float ROAM_SPEED = 240f;          // 漫步速度 px/s

    /** 活着的实例（清理面板要摸它的帧缓存）。 */
    private static OverlayService inst;

    private WindowManager wm;
    private PetRoot petRoot;
    private WindowManager.LayoutParams lp;

    private ChatBar chatBar;
    private boolean started;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private android.hardware.display.DisplayManager displayManager;
    private final Random random = new Random();
    private long lastBusyDiag = 0L;
    private final Runnable idleNext = new IdleNext(this);
    private final JSONArray history = new JSONArray();
    private long lastSpeakAt = 0L;
    /** 上次压缩聊天上下文的时间：攒够十条原话或隔六小时才压一次。 */
    private long ctxCompressedAt = 0L;
    private boolean compressing;
    /** 压缩失败后的退避时间点：失败时模型可能一直失败，别每说一句就再试一次烧 token。 */
    private long ctxRetryAfter = 0L;
    private static final int CTX_BATCH = 10;
    private static final long CTX_GAP_MS = 6L * 3600L * 1000L;
    private static final int CTX_BUDGET = 600;          // 正经聊天时注入的摘要字数
    private static final int CTX_BUDGET_WHISPER = 300;  // 碎碎念也带上摘要，少一点就够，省 token
    private long lastSwitchSeen = 0L;
    private boolean thinking;

    private volatile Walk walk;
    private boolean roaming;
    private boolean roamEnabled = true;
    /** 上次写进通知栏的状态，用来避免无谓的 notify() 抖动。 */
    private String lastNotifText = "";
    private final StatusTick statusTick = new StatusTick(this);
    private long roamPauseUntil = 0L;
    private String pendingReply;

    private final Runnable proactive = new Proactive(this);
    private final Runnable roamNext = new RoamNext(this);
    private final Runnable watcher = new Watcher(this);
    private final Runnable reschedule = new Reschedule(this);

    @Override
    public void onCreate() {
        super.onCreate();
        ctxCompressedAt = getSharedPreferences("pet", MODE_PRIVATE).getLong("ctxCompressedAt", 0L);
        // 用户点过「收起」就不再自己出现：即便被开机广播、系统恢复或其他路径拉起来。
        // 「启动宠物」会先把 pet_wanted 置回 true 再发 START，所以不会被这道闸挡掉。
        if (!Config.wanted(this)) {
            stopSelf();
            return;
        }
        inst = this;
        Logs.init(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        chatBar = new ChatBar(this, wm, new ChatSender(this));
        startForegroundSafe();
        try {
            displayManager = (android.hardware.display.DisplayManager)
                    getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager != null) {
                displayManager.registerDisplayListener(new Displays(this), handler);
            }
        } catch (Throwable ignored) {
        }
    }

    private void startForegroundSafe() {
        try {
            startForeground(NOTIF_ID, buildNotification(statusLine()));
            handler.postDelayed(statusTick, 2000L);
        } catch (Throwable ignored) {
            // 某些 ROM 在通知被禁用时会抛错；忽略，悬浮窗仍可用
        }
    }

    private void showPet() {
        if (petRoot != null) return;
        try {
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            int pw = Math.max(1, (int) (Math.min(sw, sh) * 0.22f));
            int ph = Math.max(1, Math.round(pw * 113f / 125f));   // 裁剪框 125x113
            lp = new WindowManager.LayoutParams(
                    pw,                                        // 固定像素：拖动时只改 x/y，不重排
                    ph,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 24;
            lp.y = 300;
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;   // 别跟着输入法动
            petRoot = new PetRoot(this, wm, lp, new StopAction(this), new ChatOpen(this));
            petRoot.pet().setOnSized(new PetSized(this));
            petRoot.pet().setOnLoop(new NextAnim(this));
            wm.addView(petRoot, lp);
            applyPetSize();   // 构造时已经算好尺寸，这里立即落地（否则首帧是 0x0 或旧比例）
        } catch (Throwable t) {
            petRoot = null;
            android.util.Log.e("dsh-pet", "addView failed", t);
            Brain.diag("addView failed: " + t);
            // 别把整段异常糊在屏幕上：用户只需要知道去开哪个开关
            toast("暂时显示不出来，请去看一下权限授权页也可能你已经收起宠物");
        }
    }

    void applyPetSize() {
        if (petRoot == null) return;
        int w = petRoot.pet().wantWidth();
        int h = petRoot.pet().wantHeight();
        if (w <= 0 || h <= 0) return;
        petRoot.applyPetSize(w, h);   // 窗口是 WRAP_CONTENT，跟着内容走
    }

    private static final class PetSized implements Runnable {
        private final OverlayService svc;

        PetSized(OverlayService s) {
            this.svc = s;
        }

        @Override
        public void run() {
            svc.applyPetSize();
        }
    }

    private int dp(int v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String msg) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    // ---------- 主动搭话 / 聊天 ----------

    void openChat() {
        runOnUi(new ChatShow(this));
    }

    void showChatBar() {
        if (chatBar == null) return;
        if (petRoot != null) {
            petRoot.hideBubble();
            // 输入条一开就要弹键盘，宠物这边先停帧，别和输入法动画抢主线程/合成器
            petRoot.pet().setPaused(true);
        }
        chatBar.show();
        updateNotification();
    }

    private void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else handler.post(r);
    }

    /** 该不该主动开口。 */
    boolean canSpeak() {
        if (!Sight.ready) return false;
        if (chatBar != null && chatBar.isShowing()) return false;

        if (thinking) return false;
        if (System.currentTimeMillis() - lastSpeakAt < MIN_GAP_MS) return false;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return false;
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (h < 8 || h > 23) return Config.nightSpeak(this);   // 深夜默认不吵，可在设置里放开
        return true;
    }

    void startProactive() {
        handler.removeCallbacks(proactive);
        handler.removeCallbacks(watcher);
        handler.postDelayed(proactive, 25_000L);
        handler.postDelayed(watcher, 20_000L);
        handler.removeCallbacks(idleNext);
        handler.postDelayed(idleNext, 12_000L);
        scheduleRoam(20_000L);
    }

    // ---------- 自主漫步 ----------

    void scheduleRoam(long delay) {
        handler.removeCallbacks(roamNext);
        handler.postDelayed(roamNext, delay);
    }

    void stopRoam() {
        handler.removeCallbacks(roamNext);
        boolean wasWalking = walk != null;
        if (walk != null) {
            android.view.Choreographer.getInstance().removeFrameCallback(walk);
            walk = null;
        }
        roaming = false;
        // 漫步时窗口是放大的且不可触摸，中止时必须缩回来，否则宠物会“卡”在大窗里点不动
        if (wasWalking && petRoot != null) {
            PetRoot root = petRoot;
            root.endWalkShrink();
            root.pet().setFlip(false);
            int n = root.pet().count();
            if (n > 0) root.pet().play(random.nextInt(n));
        }
    }

    private boolean canRoam() {
        if (petRoot == null || roaming) return false;
        if (!roamEnabled) return false;
        if (System.currentTimeMillis() < roamPauseUntil) return false;
        if (chatBar != null && chatBar.isShowing()) return false;
        if (petRoot.bubbleVisible()) return false;
        if (petRoot.pet().isDragging()) return false;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    /** 一个动作完整播完一轮：换下一个（忙的时候先不变，继续循环当前这个）。 */
    void nextAnimTick() {
        if (petRoot == null) return;
        PetView pv = petRoot.pet();
        boolean busy = roaming || petRoot.bubbleVisible() || pv.isDragging()
                || (chatBar != null && chatBar.isShowing());
        if (busy) return;
        int n = pv.count();
        if (n <= 1) return;
        int cur = pv.current();
        int walkIdx = pv.indexOfName("原地漂浮踏步");
        int idx = cur;
        for (int i = 0; i < 8 && idx == cur; i++) idx = random.nextInt(n);
        if (idx == walkIdx && walkIdx >= 0 && n > 2) idx = (idx + 1) % n;
        if (idx == cur) return;
        pv.play(idx);
        Brain.diag("loop switch " + cur + " -> " + idx);
    }

    /** 兜底：动作特别长时也给个上限，别一个动作挂太久。 */
    void idleTick() {
        if (petRoot == null) {
            handler.postDelayed(idleNext, 10_000L);
            return;
        }
        PetView pv = petRoot.pet();
        boolean busy = roaming || petRoot.bubbleVisible() || pv.isDragging()
                || (chatBar != null && chatBar.isShowing());
        if (!busy && pv.sinceAnimStart() > 25_000L) {
            nextAnimTick();
        } else if (busy) {
            // 一直不换动作时留个证据：是气泡/拖动/漫步挡住了，还是循环压根没播完
            long now = System.currentTimeMillis();
            if (now - lastBusyDiag > 120_000L) {
                lastBusyDiag = now;
                Brain.diag("anim busy roam=" + roaming
                        + " bubble=" + petRoot.bubbleVisible()
                        + " drag=" + pv.isDragging()
                        + " chat=" + (chatBar != null && chatBar.isShowing())
                        + " since=" + pv.sinceAnimStart());
            }
        }
        int gap = busy ? 8_000 : 15_000 + random.nextInt(26_000);
        handler.postDelayed(idleNext, gap);
    }

    void roamTick() {
        if (!canRoam()) {
            scheduleRoam(ROAM_MIN_MS + random.nextInt((int) (ROAM_MAX_MS - ROAM_MIN_MS)));
            return;
        }
        PetView pv = petRoot.pet();
        int pw = pv.wantWidth();
        int ph = pv.wantHeight();
        if (pw <= 0 || ph <= 0) {
            scheduleRoam(15_000L);
            return;
        }
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int x0 = petRoot.x();
        int y0 = petRoot.y();
        int x1 = dp(8) + random.nextInt(Math.max(1, sw - pw - dp(16)));
        int y1 = dp(70) + random.nextInt(Math.max(1, sh - ph - dp(260)));
        if (Math.abs(x1 - x0) < sw / 5) {
            x1 = x0 < sw / 2 ? x0 + sw / 3 : x0 - sw / 3;
            x1 = Math.max(dp(8), Math.min(sw - pw - dp(8), x1));
        }
        walkTo(x1, y1);
    }

    /** 走到 (x1,y1)：切行走动画、按方向镜像、按距离算时长。 */
    void walkTo(int x1, int y1) {
        if (petRoot == null) return;
        if (roaming) {
            if (walk != null) android.view.Choreographer.getInstance().removeFrameCallback(walk);
            walk = null;
            roaming = false;
            petRoot.settle();
        }
        PetView pv = petRoot.pet();
        int pw = pv.wantWidth();
        int ph = pv.wantHeight();
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (pw > 0) x1 = Math.max(dp(6), Math.min(sw - pw - dp(6), x1));
        if (ph > 0) y1 = Math.max(dp(50), Math.min(sh - ph - dp(120), y1));
        int x0 = petRoot.x();
        int y0 = petRoot.y();
        int idle = pv.current();
        int walkIdx = pv.indexOfName("原地漂浮踏步");
        if (walkIdx < 0) walkIdx = pv.indexOfName("奔跑");
        if (walkIdx < 0) walkIdx = pv.indexOfName("踱步");
        if (walkIdx >= 0) pv.play(walkIdx);
        pv.setFlip(x1 > x0);   // 素材朝左，往右走镜像
        long dist = (long) Math.hypot(x1 - x0, y1 - y0);
        long dur = Math.max(700L, Math.min(4500L, (long) (dist * 1000f / ROAM_SPEED)));
        petRoot.beginWalk(x0, y0, x1, y1);
        roaming = true;
        updateNotification();
        walk = new Walk(this, x0, y0, x1, y1, dur, idle);
        android.view.Choreographer.getInstance().postFrameCallback(walk);
        Brain.diag("walk " + x0 + "," + y0 + " -> " + x1 + "," + y1
                + " dur=" + dur + " walkIdx=" + walkIdx + " idle=" + idle);
    }

    void endWalk(int idle, boolean cancelled) {
        if (pendingReply != null) {
            String r = pendingReply;
            pendingReply = null;
            reply(r);
        }
        Brain.diag("endwalk cancelled=" + cancelled + " idle=" + idle + " at=" + (petRoot == null ? -1 : petRoot.x()) + "," + (petRoot == null ? -1 : petRoot.y()));
        roaming = false;
        if (pendingReply != null) {
            String r = pendingReply;
            pendingReply = null;
            reply(r);
        }
        if (petRoot != null) {
            petRoot.settle();
            petRoot.endWalkShrink();
            petRoot.pet().setFlip(false);
            if (idle >= 0) petRoot.pet().play(idle);
        }
        updateNotification();
        scheduleRoam(cancelled ? 15_000L
                : ROAM_MIN_MS + random.nextInt((int) (ROAM_MAX_MS - ROAM_MIN_MS)));
    }

    void stopProactive() {
        handler.removeCallbacks(proactive);
        handler.removeCallbacks(watcher);
        handler.removeCallbacks(idleNext);
        handler.removeCallbacks(reschedule);
        stopRoam();
    }

    void scheduleNext() {
        handler.removeCallbacks(proactive);
        long delay = 60_000L + random.nextInt(75_000);
        handler.postDelayed(proactive, delay);
    }

    void proactiveTick() {
        if (canSpeak()) speak("whisper", null);
        else scheduleNext();
    }

    /** 换应用时也看看要不要搭话（冷却更长）。 */
    void watcherTick() {
        long changed = Sight.changedAt;
        if (changed > lastSwitchSeen) {
            lastSwitchSeen = changed;
            long since = System.currentTimeMillis() - lastSpeakAt;
            if (since > 180_000L && canSpeak() && random.nextInt(100) < 30) {
                speak("whisper", null);
            }
        }
        Habits.record(this, Sight.app);
        handler.postDelayed(watcher, 15_000L);
    }

    void sendChat(String text) {
        if (text == null || text.trim().length() == 0) return;
        remember("user", text);
        Logs.chat("me", text, "chat");
        if (handleCommand(text)) return;
        speak("chat", text);
    }

    /**
     * 本地指令：走动 / 站住 / 自由活动。
     *
     * 只有**整句**都是一个指令才认（“去左边”“站到中间”“过来”“站住”），
     * 不按关键字命中 —— 之前 `contains("到")`/`contains("上")` 会让「我知道了」
     * 「马上回来」这类闲聊触发走动。
     */
    private static final String[] XW = {
            "左上角", "右上角", "左下角", "右下角",
            "左上", "右上", "左下", "右下",
            "左边", "右边", "上面", "下面", "上边", "下边",
            "中间", "中央", "正中", "角落", "这边", "那边",
            "左", "右", "上", "下", "角"};

    private static final String[] LEAD = {"请你", "请", "帮我", "麻烦你", "麻烦", "你", "快",
            "能不能", "可以", "能", "给我"};

    private static final String[] TAIL = {"一下吧", "一下", "吧", "呀", "啊", "吗", "嘛", "哦", "呢",
            "啦", "了", "哟", "呗", "哈", "好不好", "好吗", "谢谢"};

    private static final String[] VERBS = {"走过来", "过去", "过来", "走", "来", "去", "到", "站", "跑",
            "挪", "移", "在", "往", "向", "最", "的", "儿", "我"};

    private static final String[] STOP_WORDS = {"站住", "别动", "不要动", "不许动", "停下", "停住",
            "别走", "待着", "呆着", "站着", "站好", "别跑", "别乱动", "停"};

    private static final String[] FREE_WORDS = {"随便走", "随便逛", "自由活动", "自由", "去玩",
            "动起来", "继续走", "溜达", "自己走", "自己逛"};

    private static String trimWords(String s, String[] words) {
        boolean changed = true;
        while (changed && s.length() > 0) {
            changed = false;
            for (String w : words) {
                if (s.length() >= w.length() && s.startsWith(w)) {
                    s = s.substring(w.length());
                    changed = true;
                } else if (s.length() >= w.length() && s.endsWith(w)) {
                    s = s.substring(0, s.length() - w.length());
                    changed = true;
                }
            }
        }
        return s;
    }

    private static final String[] STOP_TAIL = {"别", "不", "要", "了", "下", "来", "就", "再",
            "乱", "动", "跑", "走", "站", "住", "着", "的"};

    private static final String[] FREE_TAIL = {"走", "逛", "吧", "了", "的", "去", "就", "玩", "自己"};

    /** 有整句命中某组词、且剩下的都是无关紧要的字，才算命中。 */
    private static boolean matchesWords(String core, String[] words, String[] tail) {
        String rem = core;
        boolean hit = false;
        for (String w : words) {
            if (rem.contains(w)) {
                hit = true;
                rem = rem.replace(w, "");
            }
        }
        if (!hit) return false;
        return trimWords(rem, tail).isEmpty();
    }

    /** 纯解析：返回 "stop" / "free" / "move:<目的地>"，不是指令返回 null。 */
    static String parseCommand(String raw) {
        String t = raw.replaceAll("[\\s，。！？、,.!?~～…\\\"'“”‘’（）()]", "");
        if (t.isEmpty()) return null;
        if (t.length() > 9) return null;                   // 长句子一律当对话，不当指令
        String core = trimWords(t, LEAD);
        core = trimWords(core, TAIL);
        if (core.isEmpty()) return null;

        // 1) 站住 / 自由活动：整句命中才算
        if (matchesWords(core, STOP_WORDS, STOP_TAIL)) return "stop";
        if (matchesWords(core, FREE_WORDS, FREE_TAIL)) return "free";

        // 2) 走动：必须带明确目的地，且去掉目的地/动词后不剩别的字
        String dest = null;
        for (String k : XW) {
            if (core.contains(k)) {
                dest = k;
                break;
            }
        }
        String rest = dest == null ? core : core.replace(dest, "");
        String left = trimWords(rest, VERBS);
        if (left.length() > 0) return null;                 // 还有别的内容 → 不是指令
        if (dest == null) {
            boolean come = core.contains("过来") || core.contains("来");
            if (!come) return null;
            dest = "过来";
        }
        return "move:" + dest;
    }

    boolean handleCommand(String raw) {
        String cmd = parseCommand(raw);
        if (cmd == null) return false;
        if (cmd.equals("stop")) {
            roamEnabled = false;
            stopRoam();
            updateNotification();
            if (petRoot != null) petRoot.pet().setFlip(false);
            Brain.diag("cmd stop t=" + raw);
            reply("好，我就站这儿不动");
            return true;
        }
        if (cmd.equals("free")) {
            roamEnabled = true;
            roamPauseUntil = 0L;
            updateNotification();
            scheduleRoam(1500L);
            Brain.diag("cmd free t=" + raw);
            reply("好～我自己溜达去啦");
            return true;
        }
        String dest = cmd.substring("move:".length());
        String t = raw;

        PetView pv = petRoot == null ? null : petRoot.pet();
        if (pv == null) return false;
        int pw = pv.wantWidth();
        int ph = pv.wantHeight();
        if (pw <= 0 || ph <= 0) return false;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int x = petRoot.x();
        int y = petRoot.y();
        boolean corner = dest.endsWith("角") || dest.equals("角落");

        if (dest.contains("左")) x = dp(8);
        if (dest.contains("右")) x = sw - pw - dp(8);
        if (dest.equals("中间") || dest.equals("中央") || dest.equals("正中")) x = (sw - pw) / 2;
        if (dest.contains("上")) y = dp(60);
        if (dest.contains("下")) y = sh - ph - dp(170);
        if (dest.equals("中间") || dest.equals("中央") || dest.equals("正中")) y = (sh - ph) / 2;
        if (dest.equals("角")) {                            // 只说「角」→ 右下角
            x = sw - pw - dp(8);
            y = sh - ph - dp(170);
        }
        if (dest.equals("这边") || dest.equals("那边") || dest.equals("过来")) {
            x = (sw - pw) / 2;
            y = sh - ph - dp(220);
        }

        roamPauseUntil = System.currentTimeMillis() + 180_000L;
        pendingReply = corner ? "好，我去那个角～" : "这就过去～";
        Brain.diag("cmd move t=" + t + " dest=" + dest + " -> " + x + "," + y);
        walkTo(x, y);
        if (!roaming) {
            String r = pendingReply;
            pendingReply = null;
            reply(r);
        }
        return true;
    }

    private void reply(final String text) {
        if (text == null) return;
        Logs.chat("her", text, "chat");
        runOnUi(new ShowBubble(this, text));
        maybeCompress();
    }

    private void remember(String role, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("role", role);
            o.put("text", text);
            history.put(o);
            while (history.length() > 10) history.remove(0);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 攒够一批原话再压缩：省 token，也省得每句话都多跑一次模型。
     * 压出来的条目带等级（1–5），等级决定它在盘上存 3/7/15/30 天还是永久。
     */
    private void maybeCompress() {
        if (compressing) return;
        if (System.currentTimeMillis() < ctxRetryAfter) return;
        compressing = true;
        // 读 chat.jsonl 不能压在主线程上：三天的记录可能有上千行
        Thread t = new Thread(new CtxScan(this, ctxCompressedAt, System.currentTimeMillis()), "pet-ctx");
        t.setDaemon(true);
        t.start();
    }

    /** 后台扫完待压原话，回主线程决定压不压。 */
    void onCtxScan(List<Logs.Item> pending, long last, long now) {
        if (pending.isEmpty() || (pending.size() < CTX_BATCH && now - last < CTX_GAP_MS)) {
            compressing = false;
            return;
        }
        final JSONArray turns = new JSONArray();
        long newest = last;
        // chatSince 是新的在前，喂给模型时按时间正序更像一段对话
        for (int i = pending.size() - 1; i >= 0; i--) {
            Logs.Item it = pending.get(i);
            try {
                JSONObject o = new JSONObject();
                o.put("who", it.who);
                o.put("text", it.text);
                o.put("kind", it.kind);
                turns.put(o);
            } catch (Throwable ignored) {
                continue;
            }
            if (it.at > newest) newest = it.at;
        }
        Brain.compress(this, turns, new CompressDone(this, newest));
    }

    void onCompressed(boolean ok, long mark, String reason) {
        compressing = false;
        if (!ok) {
            android.util.Log.w("dsh-pet", "compress error: " + reason);
            ctxRetryAfter = System.currentTimeMillis() + 10 * 60_000L;
            return;
        }
        // 不管压出几条都推进水位：这一批已经看过了，别反复喂给模型（省 token）
        ctxCompressedAt = mark;
        getSharedPreferences("pet", MODE_PRIVATE).edit().putLong("ctxCompressedAt", mark).apply();
        // 用量跨过 50/80/90/95/100 那条线时提醒一次（同一条线只提醒一次）
        String warn = Logs.warnIfNeeded();
        if (warn != null) {
            try {
                Toast.makeText(this, warn, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 请求一句台词：whisper=主动碎碎念，chat=回应主人。 */
    private void speak(final String kind, final String userText) {
        if (thinking) return;
        thinking = true;
        lastSpeakAt = System.currentTimeMillis();
        // 唯一一次读屏：就在她要开口之前。其余时间完全不动无障碍树。
        ScreenReader.readForSpeech();
        final JSONObject ctx = contextJson();
        final String hist = kind.equals("chat") ? history.toString() : null;
        // 碎碎念也要带摘要：不然她不知道自己之前念叨过什么，会反复说同一个意思
        final String digest = Logs.digestText(kind.equals("chat") ? CTX_BUDGET : CTX_BUDGET_WHISPER);
        Brain.say(this, kind, userText, hist, ctx, digest, new ReplyListener(this, kind));
    }

    void onBrainReply(String kind, String text) {
        thinking = false;
        if (text == null || text.length() == 0) return;
        remember("pet", text);   // 碎碎念也记：否则下一句很容易重复同样的意思
        Logs.chat("her", text, kind.equals("chat") ? "chat" : "whisper");
        if (kind.equals("chat")) maybeCompress();
        final String out = text;
        Brain.diag("reply kind=" + kind + " text=" + (out.length() > 20 ? out.substring(0, 20) : out));
        runOnUi(new ShowBubble(this, out));
        scheduleNext();
    }

    void onBrainError(String kind, String reason) {
        thinking = false;
        android.util.Log.w("dsh-pet", "brain error: " + reason);
        String shortReason = reason == null ? "?" : reason;
        if (shortReason.length() > 70) shortReason = shortReason.substring(0, 70);
        runOnUi(new ShowBubble(this, "（连不上大脑：" + shortReason + "）"));
        if (!kind.equals("chat")) scheduleNext();
    }

    void showBubbleNow(String text) {
        if (petRoot == null) return;
        petRoot.say(text);
    }

    private JSONObject contextJson() {
        JSONObject o = new JSONObject();
        try {
            Calendar c = Calendar.getInstance();
            o.put("time", timeWord(c.get(Calendar.HOUR_OF_DAY)) + " " + c.get(Calendar.HOUR_OF_DAY)
                    + ":" + String.format("%02d", c.get(Calendar.MINUTE)));
            o.put("app", Sight.app);
            if (Sight.activity != null && Sight.activity.length() > 0) o.put("activity", Sight.activity);
            String habit = Habits.summary(this);
            if (habit.length() > 0) o.put("habit", habit);
            if (Sight.screen != null && Sight.screen.length() > 0) o.put("screen", Sight.screen);
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                o.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                o.put("charging", bm.isCharging());
            }
            long age = Sight.ageMs();
            if (age < Long.MAX_VALUE) o.put("idleSec", age / 1000);
            // 把实际送给模型的那行上下文写进宿主日志，方便核对「她到底看到了什么」
            String screen = o.optString("screen", "");
            if (screen.length() > 120) screen = screen.substring(0, 120) + "…";
            Brain.diag("ctx app=" + o.optString("app", "") + " bat=" + o.optInt("battery", -1)
                    + " screen=[" + screen + "]");
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static String timeWord(int h) {
        if (h < 5) return "凌晨";
        if (h < 7) return "清晨";
        if (h < 9) return "早上";
        if (h < 11) return "上午";
        if (h < 13) return "中午";
        if (h < 17) return "下午";
        if (h < 19) return "傍晚";
        if (h < 23) return "晚上";
        return "深夜";
    }

    // ---------- 通知 / 生命周期 ----------

    // ---------- 通知栏状态 ----------

    /**
     * 通知栏里那一行「她此刻在干嘛」：散步中 / 站住不动 / 发呆 / 陪你聊天，后面跟方位。
     * 每 3 秒对一次，走动、停下、开输入条时立刻刷。
     */
    private void updateNotification() {
        String s = statusLine();
        if (s.equals(lastNotifText)) return;
        lastNotifText = s;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(s));
        } catch (Throwable ignored) {
        }
    }

    private String statusLine() {
        if (petRoot == null) return "正在加载";
        if (chatBar != null && chatBar.isShowing()) return "陪你聊天 · " + whereWord();
        if (roaming) return "散步中 · " + whereWord();
        if (!roamEnabled) return "站住不动 · " + whereWord();
        return "发呆 · " + whereWord();
    }

    /** 她现在在屏幕的哪个位置（取窗口中心）。 */
    private String whereWord() {
        if (petRoot == null) return "屏幕上";
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        PetView pv = petRoot.pet();
        int cx = petRoot.x() + pv.wantWidth() / 2;
        int cy = petRoot.y() + pv.wantHeight() / 2;
        boolean left = cx < sw / 3;
        boolean right = cx > sw * 2 / 3;
        boolean top = cy < sh / 3;
        boolean bottom = cy > sh * 2 / 3;
        if (top && left) return "左上角";
        if (top && right) return "右上角";
        if (bottom && left) return "左下角";
        if (bottom && right) return "右下角";
        if (top) return "上边";
        if (bottom) return "下边";
        if (left) return "左边";
        if (right) return "右边";
        return "屏幕中间";
    }

    private static final class StatusTick implements Runnable {
        private final OverlayService service;
        StatusTick(OverlayService s) { this.service = s; }

        @Override
        public void run() {
            service.updateNotification();
            service.handler.postDelayed(this, 3000L);
        }
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "桌面宠物", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent say = new Intent(this, OverlayService.class).setAction(ACTION_SAY);
        PendingIntent sayPi = PendingIntent.getService(this, 2, say,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("桌面宠物")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "说一句", sayPi).build())
                .addAction(new Notification.Action.Builder(null, "收起", stopPi).build());
        return b.build();
    }

    /** 横竖屏切换 / 分屏后重新量一次屏幕，免得宠物被拉大或被挤到屏幕外。 */
    void onScreenChanged() {
        if (petRoot == null) return;
        stopRoam();
        petRoot.onScreenChanged();
        petRoot.pet().recomputeSize();
        applyPetSize();
    }

    private static final class Displays implements android.hardware.display.DisplayManager.DisplayListener {
        private final OverlayService svc;

        Displays(OverlayService s) {
            this.svc = s;
        }

        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            svc.handler.post(new RotateFix(svc));
        }
    }

    private static final class RotateFix implements Runnable {
        private final OverlayService svc;

        RotateFix(OverlayService s) {
            this.svc = s;
        }

        @Override
        public void run() {
            svc.onScreenChanged();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        onScreenChanged();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            // 系统（尤其是 MIUI/ColorOS 的「自启动」「恢复被杀服务」）会重投一个没有
            // START 动作的意图来叫醒服务。用户已经点过「收起」时，这里必须直接退出，
            // 否则 onCreate 里拦住了、onStartCommand 又把宠物画出来。
            if (!Config.wanted(this)) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Config.setWanted(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_SAY.equals(intent.getAction())) {
            startForegroundSafe();
            lastSpeakAt = 0L;
            speak("whisper", null);
            return START_NOT_STICKY;
        }
        Config.setWanted(this, true);
        startForegroundSafe();
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            showPet();
            startProactive();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (displayManager != null) displayManager.registerDisplayListener(null, null);
        } catch (Throwable ignored) {
        }
        Habits.flush(this);
        handler.removeCallbacks(statusTick);
        stopProactive();
        if (chatBar != null && chatBar.isShowing()) chatBar.hide();
        if (petRoot != null) {
            petRoot.dispose();
            if (petRoot.getParent() != null) {
                try {
                    wm.removeView(petRoot);
                } catch (Throwable ignored) {
                }
            }
        }
        petRoot = null;
        started = false;
        if (inst == this) inst = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- 具名回调类（d8 不认匿名类，一律写成 static） ----------

    private static final class StopAction implements Runnable {
        private final OverlayService service;

        StopAction(OverlayService s) {
            this.service = s;
        }

        @Override
        public void run() {
            service.stopSelf();
        }
    }

    private static final class ChatOpen implements Runnable {
        private final OverlayService service;

        ChatOpen(OverlayService s) {
            this.service = s;
        }

        @Override
        public void run() {
            service.openChat();
        }
    }

    private static final class ChatShow implements Runnable {
        private final OverlayService service;

        ChatShow(OverlayService s) {
            this.service = s;
        }

        @Override
        public void run() {
            service.showChatBar();
        }
    }

    private static final class ChatSender implements ChatBar.Sender {
        private final OverlayService service;

        ChatSender(OverlayService s) {
            this.service = s;
        }

        @Override
        public void onSend(String text) {
            service.sendChat(text);
        }

        @Override
        public void onClose() {
            // 输入条收起（发送/关闭/被打断）→ 恢复换帧
            if (service.petRoot != null) service.petRoot.pet().setPaused(false);
            service.updateNotification();
        }
    }

    private static final class ShowBubble implements Runnable {
        private final OverlayService service;
        private final String text;

        ShowBubble(OverlayService s, String t) {
            this.service = s;
            this.text = t;
        }

        @Override
        public void run() {
            service.showBubbleNow(text);
        }
    }

    private static final class NextAnim implements Runnable {
        private final OverlayService service;

        NextAnim(OverlayService s) {
            this.service = s;
        }

        @Override
        public void run() {
            service.nextAnimTick();
        }
    }

    private static final class IdleNext implements Runnable {
        private final OverlayService service;

        IdleNext(OverlayService s) {
            this.service = s;
        }

        @Override
        public void run() {
            service.idleTick();
        }
    }

    private static final class RoamNext implements Runnable {
        private final OverlayService service;

        RoamNext(OverlayService s) {
            this.service = s;
        }

        @Override
        public void run() {
            service.roamTick();
        }
    }

    /** 一次漫步：按 vsync 逐帧改子 View 的 translation（窗口一次不动）。 */
    private static final class Walk implements android.view.Choreographer.FrameCallback {
        private final OverlayService service;
        private final int x0, y0, x1, y1, idle;
        private final long dur;
        private final long t0;

        Walk(OverlayService s, int x0, int y0, int x1, int y1, long dur, int idle) {
            this.service = s;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.dur = dur;
            this.idle = idle;
            this.t0 = android.os.SystemClock.uptimeMillis();
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            PetRoot root = service.petRoot;
            if (root == null || root.pet().isDragging()
                    || (root.bubbleVisible() && service.pendingReply == null)) {
                service.endWalk(idle, true);
                return;
            }
            long el = android.os.SystemClock.uptimeMillis() - t0;
            float t = Math.min(1f, el / (float) dur);
            float e = t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2f) / 2f;
            root.walkTo(Math.round(x0 + (x1 - x0) * e), Math.round(y0 + (y1 - y0) * e));
            if (t >= 1f) {
                service.endWalk(idle, false);
                return;
            }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private static final class Proactive implements Runnable {
        private final OverlayService service;

        Proactive(OverlayService s) {
            this.service = s;
        }

        @Override
        public void run() {
            service.proactiveTick();
        }
    }

    private static final class Watcher implements Runnable {
        private final OverlayService service;

        Watcher(OverlayService s) {
            this.service = s;
        }

        @Override
        public void run() {
            service.watcherTick();
        }
    }

    private static final class Reschedule implements Runnable {
        private final OverlayService service;

        Reschedule(OverlayService s) {
            this.service = s;
        }

        @Override
        public void run() {
            service.scheduleNext();
        }
    }

    private static final class ReplyListener implements Brain.Listener {
        private final OverlayService service;
        private final String kind;

        ReplyListener(OverlayService s, String kind) {
            this.service = s;
            this.kind = kind;
        }

        @Override
        public void onReply(String text) {
            service.onBrainReply(kind, text);
        }

        @Override
        public void onError(String reason) {
            service.onBrainError(kind, reason);
        }
    }

    /** 后台扫「上次压缩之后新攒的原话」。 */
    private static final class CtxScan implements Runnable {
        private final OverlayService service;
        private final long last;
        private final long now;

        CtxScan(OverlayService s, long last, long now) {
            this.service = s;
            this.last = last;
            this.now = now;
        }

        @Override
        public void run() {
            service.handler.post(new CtxScanned(service, Logs.chatSince(last), last, now));
        }
    }

    /** 把扫好的结果递回主线程（具名类，d8 不吃匿名内部类那套）。 */
    private static final class CtxScanned implements Runnable {
        private final OverlayService service;
        private final List<Logs.Item> pending;
        private final long last;
        private final long now;

        CtxScanned(OverlayService s, List<Logs.Item> pending, long last, long now) {
            this.service = s;
            this.pending = pending;
            this.last = last;
            this.now = now;
        }

        @Override
        public void run() {
            service.onCtxScan(pending, last, now);
        }
    }

    /** 压缩回调：存条目、推进水位；回调已经在主线程。 */
    private static final class CompressDone implements Brain.DigestListener {
        private final OverlayService service;
        private final long mark;

        CompressDone(OverlayService s, long mark) {
            this.service = s;
            this.mark = mark;
        }

        @Override
        public void onItems(JSONArray items) {
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.optJSONObject(i);
                    if (o == null) continue;
                    Logs.digest(o.optInt("lv", 1), o.optString("text", ""));
                }
            }
            service.onCompressed(true, mark, null);
        }

        @Override
        public void onError(String reason) {
            service.onCompressed(false, mark, reason);
        }
    }

    /** 宠物是不是真的在跑（悬浮窗服务活着且已经把窗挂上）。 */
    public static boolean running() {
        OverlayService s = inst;
        return s != null && s.started;
    }

    /** 帧缓存现在多大（没在跑就是 0）。 */
    public static long framesCacheBytes() {
        OverlayService s = inst;
        if (s == null || s.petRoot == null) return 0;
        return s.petRoot.pet().cacheSize();
    }

    /** 丢掉空闲动作帧缓存，返回释放的字节数。 */
    public static long dropFrameCache() {
        OverlayService s = inst;
        if (s == null || s.petRoot == null) return 0;
        PetView v = s.petRoot.pet();
        long before = v.cacheSize();
        v.clearCache();
        return Math.max(0, before - v.cacheSize());
    }
}
