package com.dsh.pet;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 只做一件事：进程一起来就装崩溃兜底。
 *
 * <p>放在 ContentProvider 里，是因为它比任何 Activity 都早实例化。之前把兜底写在
 * {@code SettingsActivity.onCreate()} 里，结果「一进就崩、共享区没有文件」——说明崩在
 * Activity 被实例化之前（类加载/静态初始化），onCreate 根本没跑到。
 *
 * <p>堆栈写两处：共享区 {@code /sdcard/DSHBox/pet-crash.txt}（有「所有文件访问」时能写），
 * 以及应用私有目录 {@code files/crash.txt}（总能写）。
 */
public class CrashLog extends ContentProvider {

    @Override
    public boolean onCreate() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                write(t, e);
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
        return true;
    }

    private void write(Thread t, Throwable e) {
        String head = "time=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                + "\nthread=" + t.getName()
                + "\nversion=" + version()
                + "\n\n";
        String body = head + Log.getStackTraceString(e);
        try {
            File shared = new File("/sdcard/DSHBox");
            if (shared.isDirectory() || shared.mkdirs()) {
                writeTo(new File(shared, "pet-crash.txt"), body);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (getContext() != null) {
                writeTo(new File(getContext().getFilesDir(), "crash.txt"), body);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeTo(File f, String text) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f, false);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(text);
            w.flush();
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private String version() {
        try {
            return getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "?";
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
