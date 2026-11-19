package com.dsh.pet;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 只在「她要说话之前」读一次屏，其它时间一律不读。
 *
 * 以前是事件驱动的：窗口变化、内容变化都去读，后来靠节流、输入法过滤、
 * 打字信号一层层打补丁，仍然会在 WebView、系统动画上反复读整棵树。
 * 无障碍查询是同步跨进程调用——读多少个节点，前台应用和 SystemUI 的
 * 主线程就要被问多少次，读得越勤越卡。
 *
 * 现在只做两件事：
 *   1. 事件回调里只记「现在前台是哪个应用」，不碰节点树；
 *   2. {@link #readForSpeech()} 在调模型之前读一次，读完整块屏幕。
 * 于是读屏次数 = 她说话的次数，其余时间开销为零。
 */
public class ScreenReader extends AccessibilityService {
    /** 一次读屏最多看多少个控件节点（无障碍树的「格子」）。屏幕复杂时得给够。 */
    private static final int MAX_NODES = 800;
    private static final int MAX_DEPTH = 25;
    private static final int MAX_CHARS = 1500;

    /** 等一次读屏完成的上限：读 400 个节点最多也就几十毫秒。 */
    private static final long READ_TIMEOUT = 800L;

    /** 「你在哪」的捕捉间隔：每 20 秒问一次前台是哪个应用，只取名字不读文字。 */
    private static final long PLACE_GAP = 20000L;

    private final Map<String, String> labelCache = new HashMap<>();
    /** 最近一次「窗口切换」事件里的前台包名——不读屏，只记名字。 */
    private volatile String lastPackage = "";
    /** 最近一次「窗口切换」事件里的页面类名（Activity），可能为空。 */
    private volatile String lastActivity = "";

    /** 输入法包名（含默认与已启用的），每 10 秒最多重查一次。 */
    private final Set<String> imePackages = new HashSet<>();
    private long imeCheckedAt = 0L;

    /** 老是读不出字的包（iQOO/OriginOS 的「交互池」这类空浮层）：读屏和「你在哪」都跳过。 */
    private final Set<String> quietPackages = new HashSet<>();
    private final Map<String, Integer> emptyStreak = new HashMap<>();
    private static final int QUIET_AFTER = 3;
    private boolean quietLoaded = false;

    /** 当前活着的服务实例。 */
    private static volatile ScreenReader instance = null;
    /** 各家的 SystemUI 包名（原生、MIUI/HyperOS、EMUI/MagicOS、One UI、ColorOS、OriginOS、Flyme……）。 */
    private final Set<String> systemUiPackages = new HashSet<>();
    private KeyguardManager keyguard = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PlaceTick placeTick = new PlaceTick(this);

    @Override
    public void onServiceConnected() {
        instance = this;
        Logs.init(this);
        keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        discoverSystemUi();
        Sight.ready = true;
        handler.postDelayed(placeTick, PLACE_GAP);
    }

    /**
     * 主流 ROM 的 SystemUI 包名基本都叫 com.android.systemui，但华为/荣耀、三星、
     * 部分定制 ROM 会换名字。这里先试一份已知名单，再把已装应用里以 systemui 结尾的
     * 全收进来——只在服务连上时跑一次，代价可以接受。
     */
    private void discoverSystemUi() {
        systemUiPackages.add("com.android.systemui");
        String[] known = {
                "com.huawei.android.systemui", "com.hihonor.systemui",
                "com.samsung.android.systemui", "com.miui.systemui",
                "com.oplus.systemui", "com.coloros.systemui", "com.vivo.systemui",
                "com.meizu.systemui", "com.transsion.systemui", "com.nothing.systemui"};
        PackageManager pm = getPackageManager();
        for (int i = 0; i < known.length; i++) {
            try {
                pm.getPackageInfo(known[i], 0);
                systemUiPackages.add(known[i]);
            } catch (Throwable ignored) {
            }
        }
        try {
            List<PackageInfo> all = pm.getInstalledPackages(0);
            for (int i = 0; i < all.size(); i++) {
                String n = all.get(i).packageName;
                if (n != null && n.toLowerCase().endsWith("systemui")) systemUiPackages.add(n);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isSystemUi(String pkg) {
        return pkg != null && systemUiPackages.contains(pkg);
    }

    /** 锁屏上读东西没意义，也容易碰到隐私（通知标题）——锁着就不读。 */
    private boolean locked() {
        try {
            return keyguard != null && keyguard.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        Sight.ready = false;
        handler.removeCallbacks(placeTick);
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * 事件回调只干一件事：记住前台是哪个应用、哪个页面。不读节点、不读文字。
     * 只收「窗口切换」——内容变化、文本变化这些高频事件连门都不让进。
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgName = event.getPackageName();
        String pkg = pkgName == null ? "" : pkgName.toString();
        if (pkg.isEmpty()) return;
        if (pkg.equals(getPackageName())) return;   // 别读自己，否则宠物会自言自语
        if (isSystemUi(pkg) || ignorable(pkg) || isQuiet(pkg)) return;
        String page = shortName(event.getClassName());
        if (!pkg.equals(lastPackage)) lastActivity = "";   // 换了应用，旧页面名作废
        lastPackage = pkg;
        if (page.length() > 0) lastActivity = page;
        markPlace();
    }

    /** 20 秒一次：问前台是谁，只取名字，不读任何文字。 */
    private static final class PlaceTick implements Runnable {
        private final ScreenReader owner;

        PlaceTick(ScreenReader owner) {
            this.owner = owner;
        }

        @Override
        public void run() {
            // 宠物没在跑就别去问前台是谁：问到的也只是给「你都在哪」记账用，
            // 而那条账只在她在场的时候才有意义
            if (OverlayService.running()) {
                try {
                    owner.capturePlace();
                } catch (Throwable ignored) {
                }
            }
            owner.handler.postDelayed(this, PLACE_GAP);
        }
    }

    /**
     * 捕捉「她在哪」：只问当前窗口属于哪个应用（一次 root 查询，不收文字），
     * 和读屏是两条独立的路——读屏仍然是「开口前才读」。
     */
    private void capturePlace() {
        try {
            if (locked()) return;
            String pkg = bestForeground(lastPackage);
            if (pkg != null && pkg.length() > 0 && !pkg.equals(lastPackage)) {
                lastPackage = pkg;
                lastActivity = "";      // 换了应用，页面名等下一个窗口事件
            }
            markPlace();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把「应用名 + 页面名」写进 Sight；不碰文字、不动日志。
     *
     * 只在宠物跑着的时候记：无障碍服务是系统级常驻的，宠物关掉之后它照样会收到
     * 窗口切换事件，不挡住的话「你都在哪」会在她根本没出现的时候一直长。
     */
    private void loadQuiet() {
        if (quietLoaded) return;
        quietLoaded = true;
        try {
            String raw = getSharedPreferences("pet", MODE_PRIVATE).getString("quietPkgs", "");
            if (raw != null && raw.length() > 0) {
                String[] parts = raw.split(",");
                for (int i = 0; i < parts.length; i++) {
                    String p = parts[i].trim();
                    if (p.length() > 0) quietPackages.add(p);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isQuiet(String pkg) {
        loadQuiet();
        return pkg != null && quietPackages.contains(pkg);
    }

    private void saveQuiet() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String p : quietPackages) {
                if (sb.length() > 0) sb.append(',');
                sb.append(p);
            }
            getSharedPreferences("pet", MODE_PRIVATE).edit().putString("quietPkgs", sb.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 记一次「这个包读不出东西」。连续几次都是空的，就认定它是空浮层（OriginOS 的
     * 「交互池」、各种游戏/助手浮层），以后既不当前台应用，也不去读它。
     */
    private void noteEmpty(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            if (pkg.equals(getPackageName()) || isSystemUi(pkg) || ignorable(pkg)) return;
        } catch (Throwable ignored) {
            return;
        }
        Integer n = emptyStreak.get(pkg);
        int v = (n == null ? 0 : n.intValue()) + 1;
        emptyStreak.put(pkg, Integer.valueOf(v));
        if (v >= QUIET_AFTER && quietPackages.add(pkg)) {
            saveQuiet();
            try {
                Brain.diag("quiet pkg=" + pkg + " (读不出字，先跳过)");
            } catch (Throwable ignored) {
            }
        }
    }

    private void noteGood(String pkg) {
        if (pkg != null) emptyStreak.remove(pkg);
    }

    /**
     * 取「真正在前台、而且不是空浮层」的应用包名。
     *
     * 只看焦点窗口在 iQOO/OriginOS 上会一直拿到「交互池」这种没有内容的应用窗口，
     * 于是「你都在哪」全记成它、读屏也读它、什么都读不出来。这里按窗口层级从上往下
     * 找第一个正经应用窗口；都不行才退回焦点窗口，再不行才用上一个已知的包名。
     */
    private String bestForeground(String fallback) {
        String best = "";
        String firstAny = null;
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (int i = 0; i < wins.size() && best.isEmpty(); i++) {
                    AccessibilityWindowInfo w = wins.get(i);
                    if (w == null) continue;
                    try {
                        if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                        AccessibilityNodeInfo root = w.getRoot();
                        if (root == null) continue;
                        String p;
                        try {
                            p = packageOf(root);
                        } finally {
                            root.recycle();
                        }
                        if (p.isEmpty()) continue;
                        if (p.equals(getPackageName()) || isSystemUi(p) || ignorable(p) || isQuiet(p)) continue;
                        if (firstAny == null) firstAny = p;
                        // 空壳窗口（OriginOS 的「交互池」这类浮层：没有子节点也没有文字）
                        // 先跳过，等真的应用窗口；全都没内容时再退回它
                        AccessibilityNodeInfo r2 = w.getRoot();
                        boolean hasContent = false;
                        if (r2 != null) {
                            try {
                                hasContent = r2.getChildCount() > 0 || r2.getText() != null
                                        || r2.getContentDescription() != null;
                            } catch (Throwable ignored) {
                            } finally {
                                r2.recycle();
                            }
                        }
                        if (hasContent) {
                            best = p;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String focused = "";
        if (best.isEmpty()) {
            AccessibilityNodeInfo active = null;
            try {
                active = getRootInActiveWindow();
            } catch (Throwable ignored) {
            }
            if (active != null) {
                try {
                    String p = packageOf(active);
                    if (!p.isEmpty() && !p.equals(getPackageName()) && !isSystemUi(p)
                            && !ignorable(p) && !isQuiet(p)) {
                        focused = p;
                    }
                } catch (Throwable ignored) {
                } finally {
                    active.recycle();
                }
            }
        }
        if (best.isEmpty() && firstAny != null) best = firstAny;
        if (best.isEmpty()) best = focused;
        if (best.isEmpty() && fallback != null) best = fallback;
        return best;
    }

    private void markPlace() {
        if (!OverlayService.running()) return;
        String pkg = lastPackage;
        if (pkg == null || pkg.isEmpty()) return;
        if (pkg.equals(getPackageName()) || ignorable(pkg)) return;
        String label = labelOf(pkg);
        Sight.place(label.isEmpty() ? pkg : label, lastActivity);
    }

    /** `com.tencent.mm.ui.LauncherUI` → `LauncherUI`；拿不到就返回空串。 */
    private static String shortName(CharSequence cls) {
        if (cls == null) return "";
        String s = cls.toString().trim();
        if (s.isEmpty()) return "";
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < s.length()) s = s.substring(dot + 1);
        if (s.length() > 40) s = s.substring(0, 40);
        return s;
    }

    /**
     * 在「她要说话之前」调用：读一次当前屏幕，更新 {@link Sight}。
     *
     * 主线程上直接同步读（一次几十毫秒）；别的线程就投递到主线程并用 latch
     * 等它读完，最多等 {@link #READ_TIMEOUT} 毫秒——超时就当没读到，
     * 让她用上一次的记忆说话，不阻塞回复。
     *
     * @return 是否真的读到了内容
     */
    public static boolean readForSpeech() {
        ScreenReader r = instance;
        if (r == null) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return r.readOnce();
        }
        CountDownLatch latch = new CountDownLatch(1);
        if (!r.handler.post(new ReadOnce(r, latch))) return false;
        try {
            latch.await(READ_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static final class ReadOnce implements Runnable {
        private final ScreenReader owner;
        private final CountDownLatch latch;

        ReadOnce(ScreenReader owner, CountDownLatch latch) {
            this.owner = owner;
            this.latch = latch;
        }

        @Override
        public void run() {
            try {
                owner.readOnce();
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        }
    }

    /** 读一次当前前台应用的屏幕，写进 Sight（顺带进读屏日志）。 */
    private boolean readOnce() {
        try {
            if (locked()) return false;
            capturePlace();          // 先把「在哪」刷新，再读文字，两者不混
            String pkg = bestForeground(lastPackage);
            if (pkg == null || pkg.isEmpty()) return false;
            if (pkg.equals(getPackageName()) || ignorable(pkg) || isSystemUi(pkg) || isQuiet(pkg)) return false;

            String text = readAll(pkg, false);
            if (text.isEmpty()) {
                // 有些 ROM（OriginOS、部分魔改）的节点根本不报包名，严格匹配就什么都读不到。
                // 放宽一次：只要不是自己的、不是状态栏/输入法/空浮层的窗口都收。
                text = readAll(pkg, true);
            }
            if (text.isEmpty()) {
                noteEmpty(pkg);
                return false;
            }
            noteGood(pkg);
            String label = labelOf(pkg);
            Sight.update(label.isEmpty() ? pkg : label, text);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 这些包一律不读：输入法、状态栏/导航栏、系统弹窗宿主。 */
    private boolean ignorable(String pkg) {
        if ("android".equals(pkg) || isSystemUi(pkg)) return true;
        refreshIme();
        return imePackages.contains(pkg);
    }

    /** 从安全设置里取默认输入法与已启用输入法的包名。 */
    private void refreshIme() {
        long now = System.currentTimeMillis();
        if (now - imeCheckedAt < 10_000L && !imePackages.isEmpty()) return;
        imeCheckedAt = now;
        imePackages.clear();
        try {
            collectIme(Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD));
            collectIme(Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_INPUT_METHODS));
        } catch (Throwable ignored) {
        }
    }

    /** 值是 `包名/类名` 或冒号分隔的一串，只取包名部分。 */
    private void collectIme(String raw) {
        if (raw == null || raw.length() == 0) return;
        for (String part : raw.split(":")) {
            int slash = part.indexOf('/');
            String p = slash > 0 ? part.substring(0, slash) : part;
            p = p.trim();
            if (p.length() > 0) imePackages.add(p);
        }
    }

    private String labelOf(String pkg) {
        String cached = labelCache.get(pkg);
        if (cached != null) return cached;
        String label = "";
        try {
            PackageManager pm = getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable ignored) {
        }
        if (!label.isEmpty()) labelCache.put(pkg, label);
        return label;
    }

    /**
     * 读整块屏幕上能看到的字：焦点窗口先读，再把其它属于同一个应用的窗口也读进来
     * （对话框、浮层都算）。状态栏/通知栏（TYPE_SYSTEM）整类跳过；
     * 密码框整棵子树跳过，连提示词都不读。
     */
    private String readAll(String pkg, boolean loose) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        Set<Integer> windowIds = new HashSet<>();

        AccessibilityNodeInfo active = null;
        try {
            active = getRootInActiveWindow();
        } catch (Throwable ignored) {
        }
        if (active != null) {
            try {
                windowIds.add(active.getWindowId());
                if (readable(packageOf(active), pkg, loose)) collect(active, sb, seen);
            } catch (Throwable ignored) {
            } finally {
                active.recycle();
            }
        }

        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (int i = 0; i < wins.size() && sb.length() < MAX_CHARS; i++) {
                    AccessibilityWindowInfo w = wins.get(i);
                    if (w == null) continue;
                    try {
                        if (!windowIds.add(w.getId())) continue;   // 已经读过焦点窗口
                        // 状态栏/通知栏/音量条/锁屏都是 TYPE_SYSTEM，整类不进正文
                        if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                        AccessibilityNodeInfo root = w.getRoot();
                        if (root == null) continue;
                        if (readable(packageOf(root), pkg, loose)) collect(root, sb, seen);
                        root.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String out = sb.toString();
        return out.length() > MAX_CHARS ? out.substring(0, MAX_CHARS) : out;
    }

    private String packageOf(AccessibilityNodeInfo node) {
        try {
            CharSequence p = node.getPackageName();
            return p == null ? "" : p.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 只读「前台应用自己」的窗口。
     * 允许读包名为空的窗口会让有些 ROM 的状态栏/通知栏漏进正文——
     * 这里直接要求包名严格相等，空串也算不匹配。
     */
    private boolean readable(String owner, String pkg, boolean loose) {
        if (owner == null) return false;
        if (!loose) {
            if (owner.isEmpty()) return false;
            if (!owner.equals(pkg)) return false;
        } else {
            // 放宽模式仍然不许读：自己的窗、状态栏/输入法、已知空浮层
            if (owner.equals(getPackageName()) || isSystemUi(owner) || ignorable(owner) || isQuiet(owner)) {
                return false;
            }
        }
        if (owner.equals(getPackageName())) return false;
        return !ignorable(owner);
    }

    /** 广度优先收集可见文字，拼成一行给模型当上下文。 */
    private void collect(AccessibilityNodeInfo root, StringBuilder sb, Set<String> seen) {
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{root, 0});
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES && sb.length() < MAX_CHARS) {
            Object[] item = queue.poll();
            AccessibilityNodeInfo node = (AccessibilityNodeInfo) item[0];
            int depth = (Integer) item[1];
            visited++;
            try {
                boolean secret = false;
                try {
                    secret = node.isPassword();
                } catch (Throwable ignored) {
                }
                if (secret) continue;                       // 密码框：这个节点连同子节点都不读
                if (node.isVisibleToUser()) {
                    addText(sb, seen, node.getText());
                    addText(sb, seen, node.getContentDescription());
                    addText(sb, seen, node.getHintText());
                }
                if (depth < MAX_DEPTH) {
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) queue.add(new Object[]{child, depth + 1});
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        for (Object[] item : queue) {
            try {
                ((AccessibilityNodeInfo) item[0]).recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    private void addText(StringBuilder sb, Set<String> seen, CharSequence t) {
        if (t == null) return;
        String s = t.toString().trim().replace('\n', ' ');
        while (s.contains("  ")) s = s.replace("  ", " ");
        if (s.isEmpty()) return;
        if (s.length() > 120) s = s.substring(0, 120);
        if (!seen.add(s)) return;                       // 面板重复文字只留一次
        if (sb.length() > 0) sb.append(" | ");
        sb.append(s);
    }
}
