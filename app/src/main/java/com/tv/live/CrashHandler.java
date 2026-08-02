package com.tv.live;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SuppressLint("StaticFieldLeak")
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static CrashHandler instance;
    private Context context;
    private Thread.UncaughtExceptionHandler defaultHandler;

    private static final String CRASH_DIR_NAME = "crash_logs";
    private static final int MAX_CRASH_LOG_COUNT = 10;
    private static final String CRASH_FILE_PREFIX = "crash_";
    private static final String CRASH_FILE_SUFFIX = ".txt";

    public static volatile String CRASH_LOG = "";

    private static final long CRASH_PAGE_DISPLAY_DURATION = 60 * 1000;
    private boolean autoRestartEnabled = false;
    private static final long RESTART_DELAY = 1000;

    private CrashHandler() {}

    public static CrashHandler getInstance() {
        if (instance == null) {
            instance = new CrashHandler();
        }
        return instance;
    }

    public void init(Context ctx) {
        context = ctx.getApplicationContext();
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    public void setAutoRestartEnabled(boolean enabled) {
        this.autoRestartEnabled = enabled;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            String crashLog = buildCrashLog(thread, ex);
            CRASH_LOG = crashLog;
            Log.e(TAG, crashLog);
            saveCrashLogToFile(crashLog);
            try {
                Log.e(TAG, "【崩溃】" + ex.getClass().getName() + ": " + ex.getMessage());
            } catch (Exception ignored) {}
            startCrashActivity();
            new Thread(() -> {
                try {
                    Thread.sleep(CRASH_PAGE_DISPLAY_DURATION);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (autoRestartEnabled) restartApp();
                Process.killProcess(Process.myPid());
                System.exit(1);
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "崩溃处理失败", e);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex);
            }
        }
    }

    private String buildCrashLog(Thread thread, Throwable ex) {
        StringBuilder sb = new StringBuilder();
        sb.append("================ 崩溃日志 ================\n");
        sb.append("时间：").append(getCurrentTime()).append("\n");
        sb.append("线程：").append(thread.getName()).append(" (ID: ").append(thread.getId()).append(")\n");
        sb.append("异常类型：").append(ex.getClass().getName()).append("\n");
        sb.append("异常信息：").append(ex.getMessage()).append("\n");
        sb.append("\n========== 设备信息 ==========\n");
        sb.append("品牌：").append(Build.BRAND).append("\n");
        sb.append("型号：").append(Build.MODEL).append("\n");
        sb.append("产品：").append(Build.PRODUCT).append("\n");
        sb.append("系统版本：Android ").append(Build.VERSION.RELEASE).append("\n");
        sb.append("SDK版本：").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("构建版本：").append(Build.DISPLAY).append("\n");
        sb.append("CPU架构：").append(Build.SUPPORTED_ABIS[0]).append("\n");
        sb.append("\n========== APP信息 ==========\n");
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            sb.append("包名：").append(pi.packageName).append("\n");
            sb.append("版本名：").append(pi.versionName).append("\n");
            sb.append("版本号：").append(pi.versionCode).append("\n");
        } catch (PackageManager.NameNotFoundException e) {
            sb.append("包名：").append(context.getPackageName()).append("\n");
        }
        sb.append("\n========== 屏幕信息 ==========\n");
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(metrics);
            sb.append("分辨率：").append(metrics.widthPixels).append(" x ").append(metrics.heightPixels).append("\n");
        } catch (Exception e) {}
        sb.append("\n========== 堆栈信息 ==========\n");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        pw.close();
        sb.append(sw.toString());
        return sb.toString();
    }

    private void saveCrashLogToFile(String crashLog) {
        try {
            File crashDir = getCrashDir();
            if (!crashDir.exists()) crashDir.mkdirs();
            String fileName = CRASH_FILE_PREFIX
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())
                    + CRASH_FILE_SUFFIX;
            File crashFile = new File(crashDir, fileName);
            FileWriter writer = new FileWriter(crashFile);
            writer.write(crashLog);
            writer.flush();
            writer.close();
            cleanOldCrashLogs();
        } catch (Exception e) {
            Log.e(TAG, "保存崩溃日志到文件失败", e);
        }
    }

    private void cleanOldCrashLogs() {
        try {
            File crashDir = getCrashDir();
            File[] files = crashDir.listFiles();
            if (files == null || files.length <= MAX_CRASH_LOG_COUNT) return;
            List<File> fileList = new ArrayList<>(Arrays.asList(files));
            Collections.sort(fileList, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return Long.compare(f2.lastModified(), f1.lastModified());
                }
            });
            for (int i = MAX_CRASH_LOG_COUNT; i < fileList.size(); i++) {
                fileList.get(i).delete();
            }
        } catch (Exception e) {}
    }

    private void restartApp() {
        try {
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + RESTART_DELAY, pendingIntent);
            }
        } catch (Exception e) {}
    }

    private void startCrashActivity() {
        try {
            Intent intent = new Intent(context, CrashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (Exception e) {}
    }

    private File getCrashDir() {
        return new File(context.getFilesDir(), CRASH_DIR_NAME);
    }

    private String getCurrentTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    public List<File> getCrashLogList() {
        try {
            File crashDir = getCrashDir();
            File[] files = crashDir.listFiles();
            if (files == null || files.length == 0) return new ArrayList<>();
            List<File> fileList = new ArrayList<>(Arrays.asList(files));
            Collections.sort(fileList, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return Long.compare(f2.lastModified(), f1.lastModified());
                }
            });
            return fileList;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public String getLatestCrashLog() {
        List<File> list = getCrashLogList();
        if (list.isEmpty()) return null;
        try {
            File latestFile = list.get(0);
            return readFileToString(latestFile);
        } catch (Exception e) {
            return null;
        }
    }

    public int clearAllCrashLogs() {
        try {
            File crashDir = getCrashDir();
            File[] files = crashDir.listFiles();
            if (files == null || files.length == 0) return 0;
            int count = 0;
            for (File file : files) {
                if (file.delete()) count++;
            }
            return count;
        } catch (Exception e) { return 0; }
    }

    private String readFileToString(File file) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            fis.read(buffer);
            fis.close();
            return new String(buffer);
        } catch (Exception e) {
            return null;
        }
    }
}
