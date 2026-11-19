package com.dsh.pet;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 气泡单独占一个浮窗。
 *
 * 为什么不让它跟宠物挤同一个窗：改窗口尺寸（变大/变小）时系统要重新分配 surface，
 * 这一下会闪。气泡单独成窗后宠物窗永远是 petW×petH，出不出气泡都不改尺寸 —— 宠物不闪。
 * 气泡窗是 FLAG_NOT_TOUCHABLE 的，点它是穿透到下面应用的，不会挡住手指。
 *
 * MIUI 偶尔会拒掉后加的浮窗（addView 不抛错但也不显示），所以 usable() 要回读
 * isAttachedToWindow，失败时 PetRoot 会退回「同窗气泡」的老路径。
 */
public class BubbleWin {
    /** 独立气泡窗自己淡出之后要通知 PetRoot：否则 bubbleShown 永远为 true，
     *  OverlayService 会一直判定「正在说话」，宠物动作再也不会自动切换。 */
    public interface OnHidden {
        void onHidden();
    }

    private OnHidden onHidden;

    public void setOnHidden(OnHidden cb) {
        this.onHidden = cb;
    }

    private final WindowManager wm;
    private final TextView view;
    private final WindowManager.LayoutParams lp;
    private boolean added;
    private boolean knownBad;
    private Runnable hideRunnable;

    public BubbleWin(Context ctx, WindowManager wm) {
        this.wm = wm;
        view = new TextView(ctx);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        view.setTextColor(Color.parseColor("#1B2430"));
        view.setLineSpacing(dp(ctx, 2), 1f);
        view.setMaxLines(10);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F7FAFF"));
        bg.setCornerRadius(dp(ctx, 14));
        bg.setStroke(dp(ctx, 1), Color.parseColor("#C9D8EE"));
        view.setBackground(bg);
        view.setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8));
        view.setAlpha(0f);

        lp = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = -4;
        lp.y = -4;
        try {
            wm.addView(view, lp);
            added = true;
        } catch (Throwable t) {
            added = false;
            knownBad = true;
            Brain.diag("bubble window add failed: " + t);
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    /** 这个窗是不是真的挂上了（挂上了才能用分离方案）。 */
    public boolean usable() {
        if (knownBad || !added) return false;
        try {
            return view.isAttachedToWindow();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 真被系统摆到屏幕上了吗（能被换掉的一律走退路）。 */
    public boolean placed() {
        return usable() && view.isShown() && view.getWidth() > 2;
    }

    public void markBad() {
        knownBad = true;
    }

    public boolean visible() {
        return usable() && lp.width > 2 && view.getAlpha() > 0.01f;
    }

    /**
     * 显示气泡。宠物矩形 (px,py,pw,ph) 保持不动，气泡自己找位置：
     * 上方放得下放上面，放不下放下面，都不够就用空间大的一侧，横向居中再夹回屏幕。
     */
    public void show(String text, int px, int py, int pw, int ph,
                     int screenW, int screenH, int maxW) {
        if (!usable()) return;
        cancelHide();
        view.setText(text);
        int spec = View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST);
        view.measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int bw = Math.min(maxW, Math.max(1, view.getMeasuredWidth()));
        int bh = Math.max(1, view.getMeasuredHeight());

        int gap = dp(view.getContext(), 6);
        int margin = dp(view.getContext(), 6);
        int spaceAbove = py - gap - margin;
        int spaceBelow = screenH - (py + ph) - gap - margin;

        int by;
        if (bh <= spaceAbove) {
            by = py - gap - bh;
        } else if (bh <= spaceBelow) {
            by = py + ph + gap;
        } else {
            by = spaceAbove >= spaceBelow ? margin : screenH - margin - bh;
        }
        if (by < margin) by = margin;
        if (by + bh > screenH - margin) by = Math.max(margin, screenH - margin - bh);

        int bx = px + (pw - bw) / 2;
        int maxX = screenW - margin - bw;
        if (bx > maxX) bx = maxX;
        if (bx < margin) bx = margin;
        if (bx < 0) bx = 0;

        lp.x = bx;
        lp.y = by;
        lp.width = bw;
        lp.height = bh;
        try {
            wm.updateViewLayout(view, lp);
        } catch (Throwable t) {
            Brain.diag("bubble update failed: " + t);
            return;
        }
        view.animate().alpha(1f).setDuration(120).start();
        final long life = Math.min(14000L, 3000L + text.length() * 120L);
        hideRunnable = new Hide(this);
        view.postDelayed(hideRunnable, life);
    }

    private void cancelHide() {
        if (hideRunnable != null) {
            view.removeCallbacks(hideRunnable);
            hideRunnable = null;
        }
    }

    /** 淡出后把窗缩成 1×1，别留一块大透明窗。 */
    public void hide() {
        cancelHide();
        if (!usable()) return;
        view.animate().alpha(0f).setDuration(110).withEndAction(new Shrink(this)).start();
    }

    private void shrinkNow() {
        lp.width = 1;
        lp.height = 1;
        lp.x = -4;
        lp.y = -4;
        try {
            wm.updateViewLayout(view, lp);
        } catch (Throwable ignored) {
        }
    }

    public void release() {
        cancelHide();
        if (!added) return;
        added = false;
        try {
            wm.removeView(view);
        } catch (Throwable ignored) {
        }
    }

    private static final class Hide implements Runnable {
        private final BubbleWin win;

        Hide(BubbleWin w) {
            this.win = w;
        }

        @Override
        public void run() {
            win.hide();
            if (win.onHidden != null) win.onHidden.onHidden();
        }
    }

    private static final class Shrink implements Runnable {
        private final BubbleWin win;

        Shrink(BubbleWin w) {
            this.win = w;
        }

        @Override
        public void run() {
            win.shrinkNow();
        }
    }
}
