package com.dsh.pet;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 和宠物说话的输入条：单独一个可聚焦的悬浮窗，发完自动收起键盘。 */
public class ChatBar {
    public interface Sender {
        void onSend(String text);

        void onClose();
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Sender sender;
    private LinearLayout root;
    private EditText input;
    private WindowManager.LayoutParams lp;

    public ChatBar(Context ctx, WindowManager wm, Sender sender) {
        this.ctx = ctx;
        this.wm = wm;
        this.sender = sender;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    public boolean isShowing() {
        return root != null;
    }

    public void show() {
        if (root != null) {
            if (input != null) input.requestFocus();
            return;
        }
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F7FAFF"));
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Color.parseColor("#C9D8EE"));
        bar.setBackground(bg);
        bar.setPadding(dp(11), dp(4), dp(4), dp(4));
        bar.setElevation(dp(8));

        EditText et = new EditText(ctx);
        et.setHint("和她说点什么…");
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        et.setTextColor(Color.parseColor("#1B2430"));
        et.setHintTextColor(Color.parseColor("#8A97A8"));
        et.setBackgroundColor(Color.TRANSPARENT);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setImeOptions(EditorInfo.IME_ACTION_SEND);
        et.setMaxLines(3);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        et.setLayoutParams(etLp);

        Button send = new Button(ctx);
        send.setText("发送");
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        send.setAllCaps(false);
        GradientDrawable sendBg = new GradientDrawable();
        sendBg.setColor(Color.parseColor("#6C8CFF"));
        sendBg.setCornerRadius(dp(16));
        send.setBackground(sendBg);
        send.setTextColor(Color.WHITE);
        send.setPadding(dp(14), dp(4), dp(14), dp(4));
        send.setMinWidth(0);
        send.setMinimumWidth(0);

        TextView close = new TextView(ctx);
        close.setText("×");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        close.setTextColor(Color.parseColor("#8A97A8"));
        close.setPadding(dp(10), dp(2), dp(10), dp(2));

        bar.addView(et);
        bar.addView(send);
        bar.addView(close);

        int sw = ctx.getResources().getDisplayMetrics().widthPixels;
        lp = new WindowManager.LayoutParams(
                Math.min(sw - dp(28), dp(430)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 悬浮窗默认是软件渲染的，输入框每敲一个字都要 CPU 重排重画，打字就会一顿一顿。
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
        lp.y = dp(18);

        send.setOnClickListener(new SendClick(this));
        close.setOnClickListener(new CloseClick(this));
        et.setOnEditorActionListener(new Submit(this));

        try {
            wm.addView(bar, lp);
        } catch (Throwable ignored) {
            return;
        }
        root = bar;
        input = et;
        et.post(new Focus(this, et));
    }

    void focus(EditText et) {
        try {
            et.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) {
        }
    }

    void submit() {
        if (input == null) return;
        String text = input.getText().toString().trim();
        if (text.length() == 0) return;
        input.setText("");
        final String out = text;
        hide();                      // 发完立刻让位，回复走宠物头顶的气泡
        handler.post(new Delivery(this, out));
    }

    void deliver(String text) {
        sender.onSend(text);
    }

    public void hide() {
        LinearLayout v = root;
        root = null;
        input = null;
        if (v == null) return;
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Throwable ignored) {
        }
        try {
            wm.removeView(v);
        } catch (Throwable ignored) {
        }
        sender.onClose();
    }

    private static final class SendClick implements View.OnClickListener {
        private final ChatBar bar;

        SendClick(ChatBar b) {
            this.bar = b;
        }

        @Override
        public void onClick(View v) {
            bar.submit();
        }
    }

    private static final class CloseClick implements View.OnClickListener {
        private final ChatBar bar;

        CloseClick(ChatBar b) {
            this.bar = b;
        }

        @Override
        public void onClick(View v) {
            bar.hide();
        }
    }

    private static final class Submit implements TextView.OnEditorActionListener {
        private final ChatBar bar;

        Submit(ChatBar b) {
            this.bar = b;
        }

        @Override
        public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                bar.submit();
                return true;
            }
            return false;
        }
    }

    private static final class Focus implements Runnable {
        private final ChatBar bar;
        private final EditText et;

        Focus(ChatBar b, EditText e) {
            this.bar = b;
            this.et = e;
        }

        @Override
        public void run() {
            bar.focus(et);
        }
    }

    private static final class Delivery implements Runnable {
        private final ChatBar bar;
        private final String text;

        Delivery(ChatBar b, String t) {
            this.bar = b;
            this.text = t;
        }

        @Override
        public void run() {
            bar.deliver(text);
        }
    }
}
