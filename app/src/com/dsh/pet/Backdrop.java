package com.dsh.pet;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 设置页的背景：默认（跟风格走）/ 系统壁纸 / 自选图片 / 纯色。
 *
 * 壁纸只在「静态壁纸」下读得到画面——动态壁纸是各家自己画的 Surface，系统不给第三方取帧，
 * 所以检测到动态壁纸时会退回风格底色并在页面上说明，不装作读到了。
 */
final class Backdrop {
    static final String DEFAULT = "default";
    static final String WALLPAPER = "wallpaper";
    static final String IMAGE = "image";
    static final String SOLID = "solid";

    private static final String PREFS = "pet";
    private static final String KEY_MODE = "bg_mode";
    private static final String KEY_BLUR_CARD = "bg_blur_card";
    private static final String KEY_BLUR_BTN = "bg_blur_btn";
    static final int BLUR_CARD_DEFAULT = 55;
    static final int BLUR_BTN_DEFAULT = 55;
    private static final String FILE = "backdrop.dat";

    private static Drawable wallpaperCache;
    private static int wallpaperStamp = -1;
    private static Bitmap imageCache;
    private static long imageStamp = -1;

    private Backdrop() {
    }

    static String mode(Context c) {
        String m = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, DEFAULT);
        // 壁纸这条路在这台机器上读不到，老设置里如果还记着 wallpaper，直接落回默认
        if (WALLPAPER.equals(m)) return DEFAULT;
        return m;
    }

    static void setMode(Context c, String mode) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply();
    }

    static boolean active(Context c) {
        return !DEFAULT.equals(mode(c));
    }

    static void reset(Context c) {
        setMode(c, DEFAULT);
    }

    /** 卡片背后的模糊强度，0–100；只糊卡片后面的背景，不糊字。 */
    static int cardBlur(Context c) {
        return Math.max(0, Math.min(100, c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_BLUR_CARD, BLUR_CARD_DEFAULT)));
    }

    static void setCardBlur(Context c, int percent) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_BLUR_CARD, Math.max(0, Math.min(100, percent))).apply();
    }

    /** 按钮背后的模糊强度，独立于卡片。 */
    static int buttonBlur(Context c) {
        return Math.max(0, Math.min(100, c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_BLUR_BTN, BLUR_BTN_DEFAULT)));
    }

    static void setButtonBlur(Context c, int percent) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_BLUR_BTN, Math.max(0, Math.min(100, percent))).apply();
    }

    static void resetBlur(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_BLUR_CARD).remove(KEY_BLUR_BTN).apply();
    }

    static boolean hasImage(Context c) {
        return file(c).isFile() && file(c).length() > 0;
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    /** 选了一张图：复制进私有目录，省得以后拿不到权限。 */
    static boolean save(Context c, Uri uri) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) return false;
            out = new FileOutputStream(file(c));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            imageCache = null;
            imageStamp = -1;
            setMode(c, IMAGE);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 系统当前壁纸是不是动态壁纸；是的话取不到画面。 */
    static boolean liveWallpaper(Context c) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(c);
            return wm.getWallpaperInfo() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static Drawable wallpaper(Context c) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(c);
            int stamp;
            try {
                stamp = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            } catch (Throwable t) {
                stamp = 0;
            }
            if (wallpaperCache == null || stamp != wallpaperStamp) {
                Drawable d = null;
                try {
                    d = wm.getDrawable();
                } catch (Throwable ignored) {
                }
                if (d == null) {
                    try {
                        d = wm.getFastDrawable();
                    } catch (Throwable ignored) {
                    }
                }
                if (d == null) d = fromWallpaperFile(wm);
                wallpaperCache = d;
                wallpaperStamp = stamp;
            }
            return wallpaperCache;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 有些 ROM 的 getDrawable() 直接抛 SecurityException，这里退回自己解文件。 */
    private static Drawable fromWallpaperFile(WallpaperManager wm) {
        java.io.FileInputStream in = null;
        try {
            android.os.ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
            if (pfd == null) return null;
            in = new java.io.FileInputStream(pfd.getFileDescriptor());
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp == null) return null;
            return new android.graphics.drawable.BitmapDrawable(null, bmp);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static Bitmap image(Context c, int maxWidth) {
        File f = file(c);
        if (!f.isFile()) return null;
        long stamp = f.lastModified();
        if (imageCache != null && imageStamp == stamp) return imageCache;
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
            int sample = 1;
            int w = Math.max(1, probe.outWidth);
            while (w / sample > Math.max(720, maxWidth)) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
            imageCache = bmp;
            imageStamp = stamp;
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }


    /** 全 App 共用的一张「全透明背景图」：所有卡片的底都从它开始叠，不各自去画背景。 */
    private static Drawable clearDrawable;

    static Drawable clear(Context c) {
        if (clearDrawable == null) {
            clearDrawable = new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT);
        }
        return clearDrawable;
    }

    // ---------- 毛玻璃：卡片/按钮各自取「自己脚底下」那块背景来糊 ----------

    // 诊断用：卡片到底有没有真的画过那层糊图
    static volatile int glassDraws;
    static volatile int glassNulls;
    static volatile String glassLast = "-";
    static volatile String glassArgs = "-";

    private static Bitmap sourceCache;
    private static String sourceKey;
    /** 实时模糊用的整页录制节点：背景层每帧录一次，卡片按圆角裁一块回放。 */
    static android.graphics.RenderNode live;
    static volatile float liveRadius;

    private static final java.util.HashMap<Integer, Bitmap> blurCaches = new java.util.HashMap<>();
    private static String blurKey;

    /** 当前背景的一张位图（壁纸或自选图），按屏幕尺寸裁好。 */
    static Bitmap source(Context c) {
        String m = mode(c);
        String key = m + "|" + (WALLPAPER.equals(m) ? wallpaperStamp : imageStamp);
        if (sourceCache != null && key.equals(sourceKey)) return sourceCache;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        // 模糊不需要原分辨率：底图按半屏建，糊的时候再缩一半 → 最终 1/4 屏，
        // 内存只有全屏的 1/16，糊出来拉伸上去反而更平滑。
        int w = Math.max(120, dm.widthPixels / 2);
        int h = Math.max(120, dm.heightPixels / 2);
        Bitmap out = null;
        try {
            if (WALLPAPER.equals(m)) {
                Drawable d = wallpaper(c);
                if (d != null) {
                    Bitmap raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas cs = new Canvas(raw);
                    d.setBounds(0, 0, w, h);
                    d.draw(cs);
                    out = raw;
                }
            } else if (IMAGE.equals(m)) {
                Bitmap raw = image(c, w);
                if (raw != null) out = cover(raw, w, h);
            }
        } catch (Throwable ignored) {
        }
        sourceCache = out;
        sourceKey = key;
        blurCaches.clear();
        blurKey = null;
        return out;
    }

    /** 按百分比降采样做一次模糊，结果缓存；0 表示不糊。 */

    static Bitmap blurred(Context c, int percent) {
        Bitmap src = source(c);
        if (src == null) return null;
        int p = Math.max(0, Math.min(100, percent));
        // 只在「底图换了」时清缓存；不同百分比各自留一份（卡片和按钮的百分比不同，
        // 之前按 p 清空会让两者互相把对方的缓存踢掉，等于每帧重新糊一遍）。
        if (!String.valueOf(sourceKey).equals(blurKey)) {
            blurCaches.clear();
            blurKey = String.valueOf(sourceKey);
        }
        Bitmap hit = blurCaches.get(p);
        if (hit != null) return hit;
        Bitmap out = src;
        if (p > 0) {
            // 固定缩到 1/4 再做方框模糊：小图上的 1px ≈ 屏幕上的 4px，倍数直观、好算。
            // 半径按百分比直接换算成屏幕像素（p=100 时约 120px），不再叠一堆曲线，
            // 这样 30% 也看得见、100% 就是一整片色块——之前"拖了没反应"就是因为曲线太缓。
            Bitmap small = Bitmap.createScaledBitmap(src,
                    Math.max(8, src.getWidth() / 2), Math.max(8, src.getHeight() / 2), true);
            // 小图半径：p=20 → 11px（屏幕 44px），p=55 → 30px（屏幕 120px），p=100 → 55px 直接成色块
            int r = Math.max(6, Math.round(p * 0.35f));
            boxBlur(small, r);
            saturate(small, 1.28f);
            out = small;
        }
        blurCaches.put(p, out);
        return out;
    }

    /** 三次方框模糊，近似高斯；只在小图上跑，代价可以忽略。 */
    /** 提饱和度（简单版：把颜色往「自己的灰度」外面拉一点）。 */
    private static void saturate(Bitmap bmp, float k) {
        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int gray = (r * 30 + g * 59 + b * 11) / 100;
                r = clamp255(gray + Math.round((r - gray) * k));
                g = clamp255(gray + Math.round((g - gray) * k));
                b = clamp255(gray + Math.round((b - gray) * k));
                px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h);
        } catch (Throwable ignored) {
        }
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static void boxBlur(Bitmap bmp, int r) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] a = new int[w * h];
        bmp.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            blurLines(a, w, h, r, true);
            blurLines(a, w, h, r, false);
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void blurLines(int[] a, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h;
        int lines = horizontal ? h : w;
        int step = horizontal ? 1 : w;
        int lineStep = horizontal ? w : 1;
        for (int i = 0; i < lines; i++) {
            int base = horizontal ? i * w : i;
            for (int j = 0; j < len; j++) {
                int lo = Math.max(0, j - r);
                int hi = Math.min(len - 1, j + r);
                int cnt = hi - lo + 1;
                int sa = 0, sr = 0, sg = 0, sb = 0;
                for (int k = lo; k <= hi; k++) {
                    int c = a[base + k * step];
                    sa += (c >>> 24) & 0xFF;
                    sr += (c >> 16) & 0xFF;
                    sg += (c >> 8) & 0xFF;
                    sb += c & 0xFF;
                }
                int keep = a[base + j * step];
                int alpha = sa / cnt;
                a[base + j * step] = (alpha << 24) | ((sr / cnt) << 16) | ((sg / cnt) << 8) | (sb / cnt);
                // 保留原位引用避免编译器警告
                if (lineStep == 0 && keep == 0) return;
            }
        }
    }

    private static Bitmap cover(Bitmap src, int w, int h) {
        float s = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        int dw = Math.max(w, Math.round(src.getWidth() * s));
        int dh = Math.max(h, Math.round(src.getHeight() * s));
        Bitmap scaled = Bitmap.createScaledBitmap(src, dw, dh, true);
        int x = Math.max(0, (dw - w) / 2);
        int y = Math.max(0, (dh - h) / 2);
        return Bitmap.createBitmap(scaled, x, y, w, h);
    }

    /** 毛玻璃底：卡片/按钮脚底下的背景糊一糊，再盖半透明底和亮边，字还是清的。 */
    /**
     * 液态玻璃（Liquid Glass）：卡片里透出来的是「自己身下那块背景」，糊到只剩色块；
     * 上面再叠三层，这是它和普通半透明卡片的区别——
     *   ① 实心底色（半透明，管「透多少」）
     *   ② 顶部的镜面高光（上亮下淡，像玻璃有一条反光的边）
     *   ③ 一圈渐变细边（左上接近白、右下接近透明），外加外侧一层软阴影
     */
    static final class Layer extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

        Layer(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            Ui.use(c);
            String mode = mode(c);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            boolean drew = false;
            Drawable wp = null;
            Bitmap bmp = null;
            if (WALLPAPER.equals(mode)) {
                wp = wallpaper(c);
                if (wp != null) {
                    wp.setBounds(0, 0, w, h);
                    wp.draw(canvas);
                    drew = true;
                }
            } else if (IMAGE.equals(mode)) {
                bmp = image(c, w);
                if (bmp != null) {
                    drawCover(canvas, bmp, w, h);
                    drew = true;
                }
            }
            recordLive(c, w, h, wp, bmp);

            // 先把模糊底图备好：卡片在 Layer 之后才绘制，没有这一步时第一帧
            // 会拿不到位图，只能盖一层半透明底，看起来就是「这张卡没糊」。
            if (drew) {
                Theme.Spec sp = Theme.spec(c);
                blurred(c, sp.cardBlur);
                blurred(c, sp.buttonBlur);
            }

            if (!drew) {
                // 默认 / 纯色 / 读不到图时的兜底：风格底色（毛玻璃是渐变）
                if (Ui.GLASS) {
                    android.graphics.drawable.GradientDrawable g =
                            new android.graphics.drawable.GradientDrawable(
                                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                                    new int[]{Ui.blend(Ui.BG, Ui.BLUE, 0.18f), Ui.blend(Ui.BG, Color.BLACK, 0.25f)});
                    g.setBounds(0, 0, w, h);
                    g.draw(canvas);
                } else {
                    canvas.drawColor(Ui.BG);
                }
            }
            // 不盖薄雾：背景原样显示，可读性靠卡片/按钮自己的半透明底与模糊
        }

        private void recordLive(Context c, int w, int h, Drawable wp, Bitmap bmp) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
            try {
                if (live == null) live = new android.graphics.RenderNode("pet-glass");
                if (live.getWidth() != w || live.getHeight() != h) live.setPosition(0, 0, w, h);
                android.graphics.RecordingCanvas rc = live.beginRecording(w, h);
                if (wp != null) {
                    wp.setBounds(0, 0, w, h);
                    wp.draw(rc);
                } else if (bmp != null) {
                    drawCover(rc, bmp, w, h);
                } else {
                    rc.drawColor(Ui.BG);
                }
                live.endRecording();
                Theme.Spec sp = Theme.spec(c);
                float dp = 3f + sp.cardBlur * (sp.cardStyle == 4 ? 0.42f : 0.30f);
                liveRadius = dp * c.getResources().getDisplayMetrics().density;
                live.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        liveRadius, liveRadius, android.graphics.Shader.TileMode.CLAMP));
            } catch (Throwable t) {
                live = null;
            }
        }

        private void drawCover(Canvas canvas, Bitmap bmp, int w, int h) {
            float sx = (float) w / bmp.getWidth();
            float sy = (float) h / bmp.getHeight();
            float s = Math.max(sx, sy);
            float dw = bmp.getWidth() * s;
            float dh = bmp.getHeight() * s;
            float left = (w - dw) / 2f;
            float top = (h - dh) / 2f;
            canvas.save();
            canvas.translate(left, top);
            canvas.scale(s, s);
            canvas.drawBitmap(bmp, 0, 0, paint);
            canvas.restore();
        }
    }

    /** 卡片/按钮用的「实时玻璃」：把背景层那颗挂过模糊的 node 裁一块画进来。 */
}
