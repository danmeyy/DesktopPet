package com.dsh.pet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把 APK 里带的宿主插件（assets/pet-brain-plugin.zip）解到 DSHBox 的共享区，
 * 也就是手机上的 /sdcard/DSHBox/pet-brain-plugin/ —— 在容器里它就是
 * /root/share/DSHBox/pet-brain-plugin/，装插件时不用再过一遍文件管理器。
 *
 * 共享区是 DSHBox 挂出来的普通目录，Android 11 起往别人的公共目录里写东西
 * 需要「所有文件访问」（MANAGE_EXTERNAL_STORAGE）；Android 10 及以下用
 * WRITE_EXTERNAL_STORAGE + requestLegacyExternalStorage 直接写。
 */
public final class PluginExporter {

    /** APK 里插件压缩包的文件名。 */
    public static final String ASSET = "pet-brain-plugin.zip";
    /** 解压后在共享区里的文件夹名。 */
    public static final String FOLDER = "pet-brain-plugin";
    /** DSHBox 共享区在手机存储根目录下的名字（容器里是 /root/share/DSHBox）。 */
    public static final String SHARED = "DSHBox";

    private PluginExporter() {
    }

    /** 共享区根目录：/sdcard/DSHBox，由 DSHBox 打开「共享区」后挂出来。 */
    public static File sharedRoot() {
        //noinspection deprecation
        return new File(Environment.getExternalStorageDirectory(), SHARED);
    }

    /** 插件最终落地的目录。 */
    public static File targetDir() {
        return new File(sharedRoot(), FOLDER);
    }

    /**
     * 系统下载目录（/sdcard/Download）。有「所有文件访问」但没有共享区时退到这里——
     * 这是每个手机都有的公共目录，用户自己找得到，用数据线或文件管理器也搬得走。
     */
    public static File downloadRoot() {
        //noinspection deprecation
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    /** Android 10 及以下才需要运行时申请 WRITE_EXTERNAL_STORAGE。 */
    public static boolean needsLegacyPermission() {
        return Build.VERSION.SDK_INT < 30;
    }

    /** 现在能不能往共享区里写。 */
    public static boolean hasAccess(Context ctx) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 把用户送到该开权限的地方去：11+ 是「所有文件访问」，10- 是存储权限弹窗。 */
    public static void requestAccess(Activity act, int requestCode) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + act.getPackageName()));
                act.startActivityForResult(i, requestCode);
                return;
            } catch (Throwable ignored) {
                // 有些 ROM 没有带包名的那一页，退回总列表
            }
            try {
                act.startActivityForResult(
                        new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), requestCode);
            } catch (Throwable ignored) {
                // 再不行就只能靠设置里手点，调用方的提示里写了路径
            }
            return;
        }
        act.requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, requestCode);
    }

    /** 结果：落到哪、写了几个文件。 */
    /** 下载目录建不出来（个别 ROM 会拦）就退回 App 自己的外部目录。 */
    private static boolean ensureDownloadDir() {
        try {
            File d = downloadRoot();
            if (d.isDirectory()) return true;
            //noinspection ResultOfMethodCallIgnored
            return d.mkdirs() || d.isDirectory();
        } catch (Throwable t) {
            return false;
        }
    }

    public static final class Result {
        public String where;
        public int files;
        /** true = 解到了 DSHBox 共享区。 */
        public boolean shared;
        /** true = 解到了系统下载目录（有全文件权限但没找到共享区）。 */
        public boolean downloads;
    }

    /** 没有共享区时的落点：/sdcard/Android/data/<包名>/files/share，不需要任何权限。 */
    public static File fallbackDir(Context ctx) {
        File f = new File(ctx.getExternalFilesDir(null), "share");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    public static Result export(Context ctx) throws IOException {
        // 有共享区也有权限就解到共享区；否则退到本 App 的外部目录 —— 换一台没装 DSHBox
        // 的手机也能把插件完整取出来，不会卡在「找不到共享区」这一步。
        File root = sharedRoot();
        boolean toShared = root.isDirectory() && hasAccess(ctx);
        boolean toDownload = false;
        File dest;
        if (toShared) {
            dest = root;
        } else if (hasAccess(ctx) && ensureDownloadDir()) {
            // 有「所有文件访问」但没识别到共享区：落到系统下载目录，比 App 私有目录好找
            dest = downloadRoot();
            toDownload = true;
        } else {
            dest = fallbackDir(ctx);
        }
        Result result = new Result();
        result.shared = toShared;
        result.downloads = toDownload;
        File tmp = new File(ctx.getCacheDir(), ASSET);
        copyAsset(ctx.getAssets(), tmp);
        ZipFile zip = new ZipFile(tmp);
        try {
            // 压缩包里的条目本来就带 pet-brain-plugin/ 前缀，所以解到目标根上，
            // 落地正好是 <目标>/pet-brain-plugin/。
            File stale = new File(new File(dest, FOLDER), FOLDER);
            if (stale.isDirectory()) deleteTree(stale);
            result.files = exportToDir(zip, dest);
            result.where = new File(dest, FOLDER).getAbsolutePath();
        } finally {
            try {
                zip.close();
            } catch (IOException ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        return result;
    }

    /** 删掉旧版解歪了的那层嵌套目录（DSHBox/pet-brain-plugin/pet-brain-plugin）。 */
    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void copyAsset(AssetManager assets, File out) throws IOException {
        InputStream in = assets.open(ASSET);
        try {
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } finally {
                os.close();
            }
        } finally {
            in.close();
        }
    }

    private static int exportToDir(ZipFile zip, File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("建不了目录：" + dir);
        int count = 0;
        Enumeration<? extends ZipEntry> it = zip.entries();
        while (it.hasMoreElements()) {
            ZipEntry entry = it.nextElement();
            if (entry.isDirectory()) continue;
            String name = cleanName(entry.getName());
            if (name == null) continue;
            File target = new File(dir, name);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) continue;
            InputStream in = zip.getInputStream(entry);
            try {
                OutputStream os = new FileOutputStream(target);
                try {
                    copy(in, os);
                } finally {
                    os.close();
                }
            } finally {
                in.close();
            }
            count++;
        }
        return count;
    }

    /** 只留安全相对路径，挡掉 ../ 和绝对路径。 */
    private static String cleanName(String raw) {
        String name = raw.replace('\\', '/');
        while (name.startsWith("/")) name = name.substring(1);
        if (name.length() == 0) return null;
        for (String part : name.split("/")) {
            if (part.length() == 0 || part.equals(".") || part.equals("..")) return null;
        }
        return name;
    }

    private static void copy(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
    }
}
