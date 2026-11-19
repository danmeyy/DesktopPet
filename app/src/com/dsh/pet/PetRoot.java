package com.dsh.pet;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 宠物的浮窗：**永远是 petW×petH**，位置只靠 lp.x/lp.y 改。
 *
 * 关键教训：窗口尺寸一变（气泡撑大 / 漫步包围盒撑大 / 收回去）系统会重新分配
 * surface，界面上就会闪一下。所以这里所有会改变尺寸的用法都拿掉了：
 *   - 气泡交给 {@link BubbleWin} 的独立浮窗（宠物窗尺寸不动）
 *   - 漫步时直接挪宠物窗的 x/y（不放大成路线包围盒，也不做 translation）
 * 只有在 MIUI 拒掉第二个浮窗时，才退回「同窗气泡」的老路径（那条路会闪，但能显示）。
 */
public class PetRoot extends FrameLayout {
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final PetView pet;
    private final BubbleWin bubbleWin;
    private final TextView bubble;          // 退路：MIUI 不给第二个浮窗时用
    private int screenW;
    private int screenH;
    private int petW, petH;
    private int petBaseX;
    private int petBaseY;
    private Runnable hideRunnable;
    private boolean roaming;
    private boolean bubbleShown;
    /** 漫步时窗口放大成整条路线的包围盒，宠物靠 translation 在里面移动：不碰 WMS。 */
    private int walkOffX, walkOffY;
    /** 更优路线：直接挪窗口的 surface（一次 SurfaceFlinger 事务，窗口尺寸/位置都不变）。 */
    private android.view.SurfaceControl winSurf;
    private android.view.SurfaceControl.Transaction winTxn;
    private boolean surfTried, surfWalk;
    private Runnable showPet;
    /** 挖孔/刘海/状态栏/手势条占掉的边缘，宠物与走动目标都要让开。 */
    private int safeL = -1, safeT = -1, safeR, safeB;

    public PetRoot(Context ctx, WindowManager wm, WindowManager.LayoutParams lp,
                   Runnable onClose, Runnable onChat) {
        super(ctx);
        this.wm = wm;
        this.lp = lp;
        this.screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        this.screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        this.petBaseX = lp.x;
        this.petBaseY = lp.y;
        this.petW = lp.width;
        this.petH = lp.height;

        bubble = new TextView(ctx);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        bubble.setTextColor(Color.parseColor("#1B2430"));
        bubble.setLineSpacing(dp(2), 1f);
        bubble.setMaxLines(10);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F7FAFF"));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#C9D8EE"));
        bubble.setBackground(bg);
        bubble.setPadding(dp(11), dp(8), dp(11), dp(8));
        bubble.setVisibility(GONE);
        LayoutParams blp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.TOP | Gravity.START;
        addView(bubble, blp);

        pet = new PetView(ctx, wm, lp, onClose, onChat);
        LayoutParams plp = new LayoutParams(
                lp.width > 0 ? lp.width : LayoutParams.WRAP_CONTENT,
                lp.height > 0 ? lp.height : LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.TOP | Gravity.START;
        addView(pet, plp);
        pet.setWindowView(this);

        bubbleWin = new BubbleWin(ctx, wm);
        // 独立气泡窗外置时，它自己淡出不会经过 finishHide()，必须回个话把 bubbleShown 清掉
        bubbleWin.setOnHidden(new BubbleGone(this));
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void push() {
        try {
            wm.updateViewLayout(this, lp);
        } catch (Throwable ignored) {
        }
    }

    /** 系统栏 / 挖孔 / 手势条：把这几个边记下来，宠物不许压在上面。 */
    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        applyInsets(in);
        return super.onApplyWindowInsets(in);
    }

    private void applyInsets(WindowInsets in) {
        if (in == null) return;
        int l = 0, t = 0, r = 0, b = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            l = i.left;
            t = i.top;
            r = i.right;
            b = i.bottom;
        } else {
            l = in.getSystemWindowInsetLeft();
            t = in.getSystemWindowInsetTop();
            r = in.getSystemWindowInsetRight();
            b = in.getSystemWindowInsetBottom();
        }
        safeL = Math.max(l, dp(4));
        safeR = Math.max(r, dp(4));
        safeT = Math.max(t, dp(4));
        safeB = Math.max(b, dp(4));
    }

    private int minX() {
        return safeL < 0 ? dp(6) : safeL;
    }

    private int maxX() {
        int right = safeR <= 0 ? dp(6) : safeR;
        return Math.max(minX(), screenW - petW - right);
    }

    private int minY() {
        return safeT < 0 ? dp(30) : safeT + dp(2);
    }

    private int maxY() {
        int bottom = safeB <= 0 ? dp(6) : safeB;
        return Math.max(minY(), screenH - petH - bottom);
    }

    /** 屏幕安全区（挖孔/状态栏/手势条以外）。 */
    public int safeTop() {
        return minY();
    }

    public int safeBottom() {
        return maxY() + petH;
    }

    /** 横竖屏切换：重新量屏幕、把宠物坐标夹回屏幕内、重开气泡。 */
    public void onScreenChanged() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        screenW = sw;
        screenH = sh;
        boolean wasTalking = bubbleVisible();
        String text = null;
        if (wasTalking) {
            text = lastText;
            hideBubbleNow();
        }
        if (petW > 0 && petH > 0) {
            lp.width = petW;
            lp.height = petH;
        }
        if (lp.x > maxX()) lp.x = maxX();
        if (lp.y > maxY()) lp.y = maxY();
        if (lp.x < minX()) lp.x = minX();
        if (lp.y < minY()) lp.y = minY();
        lp.gravity = Gravity.TOP | Gravity.START;
        petBaseX = lp.x;
        petBaseY = lp.y;
        pet.setX(0f);
        pet.setY(0f);
        setTranslationX(0f);
        setTranslationY(0f);
        push();
        if (text != null && text.length() > 0) say(text);
    }

    public PetView pet() {
        return pet;
    }

    /** 画面比例固定后同步宠物尺寸（窗口跟着内容，就这一次改尺寸）。 */
    public void applyPetSize(int w, int h) {
        if (w <= 0 || h <= 0) return;
        petW = w;
        petH = h;
        LayoutParams p = (LayoutParams) pet.getLayoutParams();
        if (p != null) {
            p.width = w;
            p.height = h;
            pet.setLayoutParams(p);
        }
        if (!bubbleVisible() && !roaming) {
            lp.width = w;
            lp.height = h;
            lp.x = petBaseX;
            lp.y = petBaseY;
            push();
        }
        requestLayout();
    }

    private String lastText = "";

    public void say(String text) {
        if (text == null || text.length() == 0) return;
        lastText = text;
        if (bubbleWin.usable()) {
            // 分离方案：宠物窗尺寸完全不动
            bubbleShown = true;
            bubbleWin.show(text, petBaseX, petBaseY, petW, petH, screenW, screenH,
                    (int) (screenW * 0.78f));
            postDelayed(new VerifyBubble(this), 200L);
            return;
        }
        if (bubbleVisible()) {
            bubble.setText(text);
            cancelHide();
            hideRunnable = new HideBubble(this);
            postDelayed(hideRunnable, Math.min(14000L, 3000L + text.length() * 120L));
            return;
        }
        sayInline(text);
    }

    /** 退路：气泡和宠物挤同一个窗（MIUI 拒掉独立气泡窗时）。 */
    private void sayInline(String text) {
        int maxW = (int) (screenW * 0.78f);
        bubble.setText(text);
        bubble.setVisibility(VISIBLE);
        bubble.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int bw = Math.min(maxW, Math.max(1, bubble.getMeasuredWidth()));
        int bh = Math.max(1, bubble.getMeasuredHeight());

        int gap = dp(6);
        int margin = dp(6);
        int spaceAbove = petBaseY - gap - margin;
        int spaceBelow = screenH - (petBaseY + petH) - gap - margin;
        int by;
        if (bh <= spaceAbove) {
            by = petBaseY - gap - bh;
        } else if (bh <= spaceBelow) {
            by = petBaseY + petH + gap;
        } else {
            by = spaceAbove >= spaceBelow ? margin : screenH - margin - bh;
        }
        if (by < margin) by = margin;
        if (by + bh > screenH - margin) by = Math.max(margin, screenH - margin - bh);

        int bx = petBaseX + (petW - bw) / 2;
        int maxX = screenW - margin - bw;
        if (bx > maxX) bx = maxX;
        if (bx < margin) bx = margin;

        int left = Math.max(0, Math.min(petBaseX, bx));
        int top = Math.max(0, Math.min(petBaseY, by));
        int right = Math.min(screenW, Math.max(petBaseX + petW, bx + bw));
        int bottom = Math.min(screenH, Math.max(petBaseY + petH, by + bh));

        // 先把子 View 摆好，再改窗口尺寸：反过来的话会有一帧宠物错位（看起来像闪）
        pet.setX(petBaseX - left);
        pet.setY(petBaseY - top);
        bubble.setX(bx - left);
        bubble.setY(by - top);
        bubble.setAlpha(0f);

        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = left;
        lp.y = top;
        lp.width = Math.max(1, right - left);
        lp.height = Math.max(1, bottom - top);
        push();
        bubble.animate().alpha(1f).setDuration(140).start();

        cancelHide();
        hideRunnable = new HideBubble(this);
        postDelayed(hideRunnable, Math.min(14000L, 3000L + text.length() * 120L));
    }

    private void cancelHide() {
        if (hideRunnable != null) {
            removeCallbacks(hideRunnable);
            hideRunnable = null;
        }
    }

    void finishHide() {
        cancelHide();
        bubbleShown = false;
        bubbleWin.hide();
        if (bubble.getVisibility() != VISIBLE) return;
        bubble.setVisibility(GONE);
        if (roaming) return;
        lp.width = Math.max(1, petW);
        lp.height = Math.max(1, petH);
        lp.x = petBaseX;
        lp.y = petBaseY;
        lp.gravity = Gravity.TOP | Gravity.START;
        pet.setX(0f);
        pet.setY(0f);
        push();
    }

    private void hideBubbleNow() {
        cancelHide();
        bubbleShown = false;
        bubbleWin.hide();
        bubble.setVisibility(GONE);
        if (!roaming) {
            lp.width = Math.max(1, petW);
            lp.height = Math.max(1, petH);
            lp.x = petBaseX;
            lp.y = petBaseY;
            pet.setX(0f);
            pet.setY(0f);
            push();
        }
    }

    /** 收起气泡（打开输入条时用）。 */
    public void hideBubble() {
        finishHide();
    }

    /** 窗口移除后清掉定时任务和气泡窗。 */
    public void dispose() {
        cancelHide();
        bubbleWin.release();
        setOnClickListener(null);
    }

    public void beginWalk(int x0, int y0, int x1, int y1) {
        if (petW <= 0 || petH <= 0) {
            roaming = true;
            return;
        }
        if (bubbleVisible()) hideBubbleNow();
        x0 = clampX(x0);
        y0 = clampY(y0);
        x1 = clampX(x1);
        y1 = clampY(y1);
        petBaseX = x0;
        petBaseY = y0;
        surfWalk = surfaceReady();
        if (surfWalk) {
            // 窗口一个像素都不动，只把 surface 挪过去：不会有“尺寸变了但平移还没生效”的那一帧
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            roaming = true;
            push();
            moveSurface(x0, y0);
            return;
        }
        blinkOff();
        int pad = dp(4);
        int left = Math.min(x0, x1) - pad;
        int top = Math.min(y0, y1) - pad;
        int right = Math.max(x0, x1) + petW + pad;
        int bottom = Math.max(y0, y1) + petH + pad;
        if (left < 0) left = 0;
        if (top < 0) top = 0;
        if (right > screenW) right = screenW;
        if (bottom > screenH) bottom = screenH;
        walkOffX = left;
        walkOffY = top;
        pet.setX(x0 - left);
        pet.setY(y0 - top);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = left;
        lp.y = top;
        lp.width = Math.max(petW, right - left);
        lp.height = Math.max(petH, bottom - top);
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        roaming = true;
        push();
        blinkOn(110);
    }

    /** 漫步中的每一步：只改 surface 位置（或子 View 的 translation），零次 updateViewLayout。 */
    public void walkTo(int vx, int vy) {
        if (!roaming) return;
        petBaseX = vx;
        petBaseY = vy;
        if (surfWalk) {
            moveSurface(vx, vy);
        } else {
            pet.setX(vx - walkOffX);
            pet.setY(vy - walkOffY);
        }
    }

    public void endWalkShrink() {
        if (!roaming) return;
        roaming = false;
        if (surfWalk) {
            surfWalk = false;
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.width = Math.max(1, petW);
            lp.height = Math.max(1, petH);
            lp.x = petBaseX;
            lp.y = petBaseY;
            push();
            moveSurface(petBaseX, petBaseY);   // 和 WMS 的 frame 对齐，视觉不动
            return;
        }
        blinkOff();
        pet.setX(0f);
        pet.setY(0f);
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.width = Math.max(1, petW);
        lp.height = Math.max(1, petH);
        lp.x = petBaseX;
        lp.y = petBaseY;
        push();
        blinkOn(110);
    }

    /** 拿 ViewRootImpl 的 mSurfaceControl（拿不到就退回放大窗口那条路）。 */
    private boolean surfaceReady() {
        if (surfTried) return winSurf != null;
        surfTried = true;
        try {
            java.lang.reflect.Method m = android.view.View.class.getDeclaredMethod("getViewRootImpl");
            m.setAccessible(true);
            Object vri = m.invoke(this);
            if (vri == null) {
                Brain.diag("surf: vri null");
                return false;
            }
            java.lang.reflect.Field f = vri.getClass().getDeclaredField("mSurfaceControl");
            f.setAccessible(true);
            Object sc = f.get(vri);
            if (!(sc instanceof android.view.SurfaceControl)) {
                Brain.diag("surf: field not SurfaceControl");
                return false;
            }
            winSurf = (android.view.SurfaceControl) sc;
            winTxn = new android.view.SurfaceControl.Transaction();
            Brain.diag("surf: ok");
            return true;
        } catch (Throwable t) {
            Brain.diag("surf fail " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private void moveSurface(int x, int y) {
        if (winSurf == null || winTxn == null) return;
        try {
            winTxn.setPosition(winSurf, x, y);
            winTxn.apply();
        } catch (Throwable t) {
            Brain.diag("surf move fail " + t.getClass().getSimpleName() + ": " + t.getMessage());
            winSurf = null;
        }
    }

    /** 退路专用：换窗口尺寸的那一瞬间把人淡掉，再淡回来，遮住 1~2 帧的错位。 */
    private void blinkOff() {
        pet.animate().cancel();
        pet.setAlpha(0f);
    }

    private void blinkOn(int delay) {
        if (showPet == null) showPet = new ShowPet(this);
        removeCallbacks(showPet);
        postDelayed(showPet, delay);
    }

    private static final class ShowPet implements Runnable {
        private final PetRoot root;

        ShowPet(PetRoot r) {
            this.root = r;
        }

        @Override
        public void run() {
            root.pet.animate().alpha(1f).setDuration(90).start();
        }
    }

    public boolean bubbleVisible() {
        if (bubble.getVisibility() == VISIBLE) return true;
        if (!bubbleShown) return false;
        // 外置气泡窗已经不在屏上了，就当没说话（防止标志位卡死）
        return bubbleWin.usable() && bubbleWin.placed();
    }

    /** 外置气泡窗淡出后的回调：只清标志，不碰宠物窗。 */
    private static final class BubbleGone implements BubbleWin.OnHidden {
        private final PetRoot root;

        BubbleGone(PetRoot r) {
            this.root = r;
        }

        @Override
        public void onHidden() {
            root.bubbleShown = false;
        }
    }

    public int x() {
        return petBaseX;
    }

    public int y() {
        return petBaseY;
    }

    /** 把宠物挪到 (x,y)：只改窗口坐标，不改尺寸、不用 translation。 */
    public void moveTo(int x, int y) {
        if (bubbleVisible()) return;
        int nx = clampX(x);
        int ny = clampY(y);
        if (nx == petBaseX && ny == petBaseY) return;
        petBaseX = nx;
        petBaseY = ny;
        lp.x = nx;
        lp.y = ny;
        push();
    }

    /** 把点夹进安全区（挖孔/状态栏/手势条以外）。 */
    public int clampX(int x) {
        if (petW <= 0) return x;
        if (x < minX()) x = minX();
        if (x > maxX()) x = maxX();
        return x;
    }

    public int clampY(int y) {
        if (petH <= 0) return y;
        if (y < minY()) y = minY();
        if (y > maxY()) y = maxY();
        return y;
    }

    public void settle() {
        // 现在没有 translation，不需要归位
    }

    /** 拖动/漫步结束后重新记录基准位置。 */
    public void noteDragged() {
        petBaseX = lp.x;
        petBaseY = lp.y;
        pet.setX(0f);
        pet.setY(0f);
    }

    /** 拖动真正开始时调用：气泡先收起来。 */
    public void beforeDrag() {
        if (bubbleVisible()) hideBubbleNow();
    }

    /** 独立气泡窗如果其实没被摆上屏（MIUI 悄悄丢弃），退回同窗气泡。 */
    private static final class VerifyBubble implements Runnable {
        private final PetRoot root;

        VerifyBubble(PetRoot r) {
            this.root = r;
        }

        @Override
        public void run() {
            if (!root.bubbleShown) return;
            if (root.bubbleWin.placed()) return;
            root.bubbleWin.markBad();
            root.bubbleShown = false;
            if (root.lastText != null && root.lastText.length() > 0) root.sayInline(root.lastText);
        }
    }

    private static final class HideBubble implements Runnable {
        private final PetRoot root;

        HideBubble(PetRoot r) {
            this.root = r;
        }

        @Override
        public void run() {
            root.finishHide();
        }
    }
}
