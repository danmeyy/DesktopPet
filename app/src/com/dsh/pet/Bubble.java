package com.dsh.pet;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** 宠物头顶的对话气泡：独立一个小悬浮窗，自动淡入淡出。 */
public class Bubble {
    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int screenW;
    private final int screenH;
    private final int maxW;
    private final int dp8;
    private TextView view;
    private WindowManager.LayoutParams lp;
    private final Runnable fadeOut = new FadeOut(this);
    private int anchorTop;
    private int anchorBottom;

    public Bubble(Context ctx, WindowManager wm) {
        this.ctx = ctx;
        this.wm = wm;
        this.screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        this.screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        this.maxW = (int) (screenW * 0.78f);
        this.dp8 = dp(8);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    public void show(String text, int petLeft, int petTop, int petSize) {
        if (text == null || text.length() == 0) return;
        hide();
        view = new TextView(ctx);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        view.setTextColor(Color.parseColor("#1B2430"));
        view.setLineSpacing(dp(2), 1f);
        view.setMaxWidth(maxW);
        view.setMaxLines(6);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F7FAFF"));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.parseColor("#C9D8EE"));
        view.setBackground(bg);
        view.setPadding(dp(12), dp(9), dp(12), dp(9));
        view.setElevation(dp(6));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.START | Gravity.TOP;
        lp.x = Math.max(dp(8), Math.min(petLeft, screenW - maxW - dp(8)));
        lp.y = Math.max(dp(8), petTop);
        this.anchorTop = petTop;
        this.anchorBottom = petTop + petSize;

        try {
            wm.addView(view, lp);
        } catch (Throwable e) {
            view = null;
            Brain.diag("bubble addView failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return;
        }
        view.post(new Adjust(this));
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(180).start();

        long life = 2600L + text.length() * 110L;
        if (life > 9000L) life = 9000L;
        handler.postDelayed(fadeOut, life);
    }

    public boolean isShowing() {
        return view != null;
    }

    public void hide() {
        handler.removeCallbacks(fadeOut);
        final TextView v = view;
        view = null;
        if (v == null) return;
        v.animate().alpha(0f).setDuration(150).withEndAction(new Detach(this, v)).start();
    }

    void adjust() {
        if (view == null || lp == null) return;
        int h = view.getHeight();
        if (h <= 0) return;
        int y = anchorTop - h - dp(6);
        if (y < dp(56)) {
            // 上方空间不够（宠物贴顶），改放到宠物下方
            y = anchorBottom + dp(6);
            int maxY = screenH - h - dp(96);
            if (y > maxY) y = Math.max(dp(56), maxY);
        }
        lp.y = y;
        try {
            wm.updateViewLayout(view, lp);
        } catch (Throwable ignored) {
        }
    }

    void detach(TextView v) {
        try {
            wm.removeView(v);
        } catch (Throwable ignored) {
        }
    }

    private static final class Adjust implements Runnable {
        private final Bubble bubble;

        Adjust(Bubble b) {
            this.bubble = b;
        }

        @Override
        public void run() {
            bubble.adjust();
        }
    }

    private static final class FadeOut implements Runnable {
        private final Bubble bubble;

        FadeOut(Bubble b) {
            this.bubble = b;
        }

        @Override
        public void run() {
            bubble.hide();
        }
    }

    private static final class Detach implements Runnable {
        private final Bubble bubble;
        private final TextView view;

        Detach(Bubble b, TextView v) {
            this.bubble = b;
            this.view = v;
        }

        @Override
        public void run() {
            bubble.detach(view);
        }
    }
}
