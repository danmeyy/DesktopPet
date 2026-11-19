package com.dsh.pet;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 悬浮窗里的宠物：读取 assets/index.json，按动作循环播放 WebP 帧序列。
 * 点一下换一个动作，长按收起，拖动改变位置。
 */
public class PetView extends View {
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final Runnable onClose;
    private final Runnable onChat;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();
    private final Rect src = new Rect();
    private int cropX, cropY, cropW, cropH;   // 帧内有效区域（去掉左右透明留白）
    private final Random random = new Random();

    private final List<String> ids = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final List<Integer> fpsList = new ArrayList<>();
    private final List<Integer> countList = new ArrayList<>();

    private Bitmap[] frames = new Bitmap[0];
    private int frameIndex = 0;
    private int fps = 10;
    private int animIndex = -1;
    /** 后台解码线程：切动作时不再卡主线程。 */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile int loadToken;
    /** 解过的动作留一份，切回同一个动作就不用再解码（走路的动作一直在重复用）。 */
    private final LinkedHashMap<String, Frames> cache = new LinkedHashMap<>(4, 0.75f, true);
    private long cacheBytes;
    private static final long CACHE_MAX = 42L * 1024 * 1024;

    private float downRawX, downRawY;
    private int startX, startY;
    private boolean dragging, longPressed, frozen;
    /** 聊天输入条打开时暂停换帧：键盘弹出、输入法动画期间少一路窗口刷新就少一份卡。 */
    private boolean paused;
    private boolean flip;
    private View windowView;   // 悬浮窗的根 view（拖动时要 update 它，而不是 this）
    private Runnable onSized;
    private Runnable onLoop;
    private long animStartedAt;
    /** 某帧还没解出来时的时间戳，用来判断「等太久了就跳过」。 */
    private long missAt;
    private int wantW, wantH;
    private final Runnable longPressRunnable = new LongPress(this);
    private final Runnable changeRunnable = new ChangeAnim(this);
    private long lastTapAt = 0L;

    private final Runnable tick = new Ticker(this);

    public PetView(Context ctx, WindowManager wm, WindowManager.LayoutParams lp, Runnable onClose, Runnable onChat) {
        super(ctx);
        this.wm = wm;
        this.lp = lp;
        this.onClose = onClose;
        this.onChat = onChat;
        loadIndex();
        loadCrop();
        if (!ids.isEmpty()) loadAnim(random.nextInt(ids.size()));
    }

    public void setWindowView(View v) {
        this.windowView = v;
    }

    /** 暂停/恢复换帧。输入条打开期间用它，把主线程留给键盘和文本输入。 */
    public void setPaused(boolean p) {
        this.paused = p;
    }

    /** 窗口大小跟随画面比例变化（16:9），避免透明边距吃掉触摸。 */
    public void setOnSized(Runnable r) {
        this.onSized = r;
    }

    public boolean isDragging() {
        return dragging;
    }

    /** 水平镜像：往左走时用。 */
    public void setFlip(boolean f) {
        if (flip != f) {
            flip = f;
            invalidate();
        }
    }

    /** 按名字里的关键字找动作序号，找不到返回 -1。 */
    public int indexOfName(String part) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).contains(part)) return i;
        }
        return -1;
    }

    /** 切到指定动作。 */
    public void play(int idx) {
        if (idx >= 0 && idx < ids.size()) loadAnim(idx);
    }

    public int current() {
        return animIndex;
    }

    /** 一共有多少个动作。 */
    public int count() {
        return ids.size();
    }

    public int wantWidth() {
        return wantW;
    }

    public int wantHeight() {
        return wantH;
    }

    /** 一个动作完整播完一轮时回调（用于自动换下一个动作）。 */
    public void setOnLoop(Runnable r) {
        this.onLoop = r;
    }

    public long sinceAnimStart() {
        return android.os.SystemClock.uptimeMillis() - animStartedAt;
    }

    /** Ticker 播到最后一帧时调用。 */
    void loopDone() {
        if (SystemClock.uptimeMillis() - animStartedAt < 2500L) return;   // 太短的动画别切太勤
        if (onLoop != null) onLoop.run();
    }

    private void loadIndex() {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    getContext().getAssets().open("index.json")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ids.add(o.getString("id"));
                names.add(o.optString("name", o.getString("id")));
                fpsList.add(o.optInt("fps", 10));
                countList.add(o.optInt("count", 0));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 素材四周留了大量透明边，按 crop.json 裁掉，否则宠物看着小、还挡住周围点击。 */
    private void loadCrop() {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    getContext().getAssets().open("crop.json")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject o = new JSONObject(sb.toString());
            cropX = o.getInt("x");
            cropY = o.getInt("y");
            cropW = o.getInt("w");
            cropH = o.getInt("h");
        } catch (Throwable ignored) {
            cropX = 0;
            cropY = 0;
            cropW = 0;
            cropH = 0;
        }
    }

    private void loadAnim(int idx) {
        if (ids.isEmpty()) return;
        idx = ((idx % ids.size()) + ids.size()) % ids.size();
        if (idx == animIndex && frames.length > 0) return;
        animIndex = idx;
        fps = fpsList.get(idx);
        String id = ids.get(idx);
        int n = countList.get(idx);
        Frames hit = cache.get(id);
        if (hit != null && complete(hit.b, n)) {
            releaseFrames();                            // 旧帧若在缓存里就不回收
            frames = hit.b;
            frameIndex = 0;
            animStartedAt = SystemClock.uptimeMillis();
            applyCrop(hit.b[0]);
            recomputeSize();
            invalidate();
            return;
        }
        int token = ++loadToken;
        AssetManager am = getContext().getAssets();
        // 第一帧同步解出来（约 3ms），保证立刻有画面；其余丢后台慢慢补
        Bitmap first = decodeFrame(am, id, 1);
        if (first == null) {
            Brain.diag("loadAnim fail idx=" + idx + " id=" + id + " n=" + n);
            invalidate();
            return;                     // 保留旧帧，别把宠物变成空白
        }
        releaseFrames();
        Bitmap[] next = new Bitmap[n];
        next[0] = first;
        frames = next;
        frameIndex = 0;
        animStartedAt = SystemClock.uptimeMillis();
        applyCrop(first);
        recomputeSize();
        invalidate();
        if (n > 1) io.execute(new Loader(this, token, id, n, am));
    }

    private static boolean complete(Bitmap[] arr, int n) {
        if (arr == null || arr.length != n) return false;
        for (Bitmap b : arr) if (b == null || b.isRecycled()) return false;
        return true;
    }

    /** 后台把剩下的帧补齐；每次填一帧就刷一次，播到哪都有图。 */
    void fillFrames(int token, String id, int n, AssetManager am) {
        int fails = 0;
        for (int i = 2; i <= n; i++) {
            if (token != loadToken) return;             // 已经换动作了，别往旧数组里塞
            Bitmap[] cur = frames;
            if (cur.length != n) return;
            Bitmap b = decodeFrame(am, id, i);
            if (b == null) {
                // 这一帧解不出来（内存紧/解码器不认）就跳过，后面还能补；别让整段动画只剩前几帧
                if (++fails >= 8) return;
                continue;
            }
            fails = 0;
            cur[i - 1] = b;
            postInvalidateOnAnimation();
        }
        if (token == loadToken) cachePut(id, frames);
    }

    private static long bytesOf(Bitmap[] a) {
        long n = 0;
        for (Bitmap b : a) if (b != null && !b.isRecycled()) n += b.getByteCount();
        return n;
    }

    private void cachePut(String id, Bitmap[] arr) {
        synchronized (cache) {
            if (!complete(arr, arr.length)) return;
            long bytes = bytesOf(arr);
            if (bytes <= 0 || bytes > CACHE_MAX) return;  // 单个动作就超预算，不缓存
            Frames old = cache.put(id, new Frames(arr, bytes));
            if (old != null) cacheBytes -= old.bytes;
            cacheBytes += bytes;
            while (cacheBytes > CACHE_MAX && cache.size() > 1) {
                Iterator<Map.Entry<String, Frames>> it = cache.entrySet().iterator();
                Map.Entry<String, Frames> e = it.next();
                if (e.getValue().b == frames && it.hasNext()) e = it.next();
                if (e.getValue().b == frames) break;      // 正在播的不能丢
                cacheBytes -= e.getValue().bytes;         // 用入缓存时定死的字节数
                recycle(e.getValue().b);
                it.remove();
            }
            if (cacheBytes < 0) cacheBytes = 0;
        }
    }

    /** 当前这一组帧如果还挂在缓存里就别回收，缓存之后还要用它。 */
    private void releaseFrames() {
        if (frames.length == 0) return;
        boolean inCache = false;
        synchronized (cache) {
            for (Frames f : cache.values()) if (f.b == frames) { inCache = true; break; }
        }
        if (inCache) return;
        for (Bitmap b : frames) if (b != null && !b.isRecycled()) b.recycle();
    }

    /** 一组解码好的帧 + 它占多少字节（入缓存时定死，位图被回收也算得准）。 */
    private static final class Frames {
        final Bitmap[] b;
        final long bytes;

        Frames(Bitmap[] b, long bytes) {
            this.b = b;
            this.bytes = bytes;
        }
    }

    /** 空闲动作缓存当前占用多少字节（清理面板显示用）。 */
    public long cacheSize() {
        synchronized (cache) {
            return cacheBytes;
        }
    }

    /** 缓存了几个动作。 */
    public int cacheCount() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /**
     * 丢掉空闲动作缓存（正在播的这一组保留，否则画面会黑）。
     * 缓存在解码线程和清理线程里都会被改，统一按 cache 加锁。
     */
    public void clearCache() {
        synchronized (cache) {
            Iterator<Map.Entry<String, Frames>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Frames> e = it.next();
                if (e.getValue().b == frames) continue;
                cacheBytes -= e.getValue().bytes;
                recycle(e.getValue().b);
                it.remove();
            }
            if (cacheBytes < 0) cacheBytes = 0;
        }
    }

    private static void recycle(Bitmap[] arr) {
        for (Bitmap b : arr) if (b != null && !b.isRecycled()) b.recycle();
    }

    /** 解一帧，顺手按 crop.json 裁掉四周透明边：384×216 一帧 0.32MB，80 帧就 25MB，裁成 125×113 只剩 1/5.8。 */
    private Bitmap decodeFrame(AssetManager am, String id, int i) {
        try {
            Bitmap full = BitmapFactory.decodeStream(am.open("frames/" + id + "/" + String.format("%03d.webp", i)));
            if (full == null) return null;
            if (cropW > 0 && cropH > 0 && cropX + cropW <= full.getWidth() && cropY + cropH <= full.getHeight()) {
                try {
                    Bitmap cut = Bitmap.createBitmap(full, cropX, cropY, cropW, cropH);
                    if (cut != full) full.recycle();
                    return cut;
                } catch (Throwable ignored) {
                }
            }
            return full;
        } catch (Throwable e) {
            return null;
        }
    }

    private void applyCrop(Bitmap b) {
        int bw = b.getWidth();
        int bh = b.getHeight();
        if (cropW > 0 && bw == cropW && bh == cropH) {
            src.set(0, 0, bw, bh);
            return;
        }
        if (cropW > 0 && cropH > 0 && cropX + cropW <= bw && cropY + cropH <= bh) {
            src.set(cropX, cropY, cropX + cropW, cropY + cropH);
        } else {
            src.set(0, 0, bw, bh);
        }
    }

    private static final class Loader implements Runnable {
        private final PetView view;
        private final int token;
        private final String id;
        private final int n;
        private final AssetManager am;

        Loader(PetView v, int token, String id, int n, AssetManager am) {
            this.view = v;
            this.token = token;
            this.id = id;
            this.n = n;
            this.am = am;
        }

        @Override
        public void run() {
            view.fillFrames(token, id, n, am);
        }
    }

    /** 按屏幕短边算宠物尺寸（横竖屏都一样大），裁剪比例决定高度。 */
    public void recomputeSize() {
        if (src.width() <= 0 || src.height() <= 0) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int base = Math.min(sw, sh);
        int w = Math.max(1, (int) (base * 0.22f));
        int h = Math.max(1, Math.round(w * (float) src.height() / Math.max(1, src.width())));
        if (w != wantW || h != wantH) {
            wantW = w;
            wantH = h;
            if (onSized != null) onSized.run();
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        if (wantW > 0 && wantH > 0) {
            setMeasuredDimension(wantW, wantH);
        } else {
            super.onMeasure(wSpec, hSpec);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (frames.length == 0) return;
        Bitmap b = frames[frameIndex % frames.length];
        if (b == null || b.isRecycled()) return;
        int w = getWidth(), h = getHeight();
        dst.set(0, 0, w, h);
        if (flip) {
            canvas.save();
            canvas.scale(-1f, 1f, w / 2f, h / 2f);
            canvas.drawBitmap(b, src.isEmpty() ? null : src, dst, paint);
            canvas.restore();
        } else {
            canvas.drawBitmap(b, src.isEmpty() ? null : src, dst, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startX = lp.x;
                startY = lp.y;
                dragging = false;
                longPressed = false;
                handler.postDelayed(longPressRunnable, 650);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (!dragging && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) {
                    dragging = true;
                    frozen = true;          // 拖动期间不刷帧，把主线程让给窗口移动
                    handler.removeCallbacks(longPressRunnable);
                    // 气泡在的时候窗口是「宠物 ∪ 气泡」的大矩形，先收起气泡让窗口缩回宠物，
                    // 否则拖动会让宠物相对窗口跳动
                    if (windowView instanceof PetRoot) ((PetRoot) windowView).beforeDrag();
                    startX = lp.x;
                    startY = lp.y;
                    dx = e.getRawX() - downRawX;
                    dy = e.getRawY() - downRawY;
                }
                if (dragging) {
                    int nx = startX + (int) dx;
                    int ny = startY + (int) dy;
                    // 先靠 translation 立刻跟手（不触发窗口重排），位移超过 14px 才真正挪窗口
                    float tX = Math.round(nx - lp.x);
                    float tY = Math.round(ny - lp.y);
                    if (windowView != null) {
                        windowView.setTranslationX(tX);
                        windowView.setTranslationY(tY);
                    }
                    if (Math.abs(tX) >= 14 || Math.abs(tY) >= 14) {
                        lp.x += (int) tX;
                        lp.y += (int) tY;
                        clampXY();
                        if (windowView != null) {
                            windowView.setTranslationX(0f);
                            windowView.setTranslationY(0f);
                        }
                        try {
                            wm.updateViewLayout(windowView == null ? this : windowView, lp);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                if (dragging) {
                    lp.x = startX + (int) (e.getRawX() - downRawX);
                    lp.y = startY + (int) (e.getRawY() - downRawY);
                    clampXY();
                    if (windowView != null) {
                        windowView.setTranslationX(0f);
                        windowView.setTranslationY(0f);
                    }
                    try {
                        wm.updateViewLayout(windowView == null ? this : windowView, lp);
                    } catch (Throwable ignored) {
                    }
                    if (windowView instanceof PetRoot) ((PetRoot) windowView).noteDragged();
                }
                boolean wasDragging = dragging;
                dragging = false;
                frozen = false;
                if (!wasDragging && !longPressed) {
                    long now = System.currentTimeMillis();
                    if (now - lastTapAt < 320) {
                        // 双击：打开对话输入条
                        lastTapAt = 0L;
                        handler.removeCallbacks(changeRunnable);
                        if (onChat != null) onChat.run();
                    } else {
                        // 单击：稍等一下再换动作，给双击留出窗口
                        lastTapAt = now;
                        handler.removeCallbacks(changeRunnable);
                        handler.postDelayed(changeRunnable, 300);
                    }
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    /** 拖动时把窗口限制在屏幕内。 */
    private void clampXY() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int w = lp.width > 0 ? lp.width : getWidth();
        int h = lp.height > 0 ? lp.height : getHeight();
        int maxX = Math.max(0, sw - w - 6);
        int maxY = Math.max(0, sh - h - 120);
        if (lp.x < 6) lp.x = 6;
        if (lp.x > maxX) lp.x = maxX;
        if (lp.y < 30) lp.y = 30;
        if (lp.y > maxY) lp.y = maxY;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 100);
    }

    private static final class LongPress implements Runnable {
        private final PetView view;

        LongPress(PetView v) {
            this.view = v;
        }

        @Override
        public void run() {
            view.longPressed = true;
            view.onClose.run();
        }
    }

    private static final class ChangeAnim implements Runnable {
        private final PetView view;

        ChangeAnim(PetView v) {
            this.view = v;
        }

        @Override
        public void run() {
            if (view.ids.isEmpty()) return;
            int next = view.random.nextInt(view.ids.size());
            if (view.ids.size() > 1 && next == view.animIndex) next = (next + 1) % view.ids.size();
            view.loadAnim(next);
        }
    }

    private static final class Ticker implements Runnable {
        private final PetView view;

        Ticker(PetView v) {
            this.view = v;
        }

        @Override
        public void run() {
            if (view.frames.length == 0) {
                view.handler.postDelayed(this, 500);
                return;
            }
            if (!view.frozen && !view.paused) {
                int next = view.frameIndex + 1;
                if (next >= view.frames.length) {
                    next = 0;
                    view.loopDone();
                }
                if (next == 0 || view.frames[next] != null) {
                    view.frameIndex = next;
                    view.missAt = 0L;
                    view.invalidate();
                } else if (view.missAt == 0L) {
                    // 后台还没解出这一帧，先原地等一下（通常几十毫秒就补上了）
                    view.missAt = SystemClock.uptimeMillis();
                } else if (SystemClock.uptimeMillis() - view.missAt > 400L) {
                    // 缺帧等过 0.4 秒就跳过。注意这里不重置 missAt：后面还没解出来的帧全部立刻跳过，
                    // 否则每缺一帧都要再等一次，整段能拖到一分半，动作看着像卡住了
                    view.frameIndex = next;
                }
            }
            view.handler.postDelayed(this, Math.max(16, 1000 / Math.max(1, view.fps)));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        for (Bitmap b : frames) if (b != null && !b.isRecycled()) b.recycle();
        frames = new Bitmap[0];
        for (Frames f : cache.values()) if (f.b != frames) recycle(f.b);
        cache.clear();
        cacheBytes = 0;
        super.onDetachedFromWindow();
    }
}
