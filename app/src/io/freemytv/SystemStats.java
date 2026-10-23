package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.StatFs;
import android.text.format.Formatter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Memory, processor and storage for the home screen's system card, measured every 3 s on a background thread while
 * the home screen shows. The processor's use comes from /proc/stat, which Android 8+ hides from apps on most TVs
 * (SELinux): then it is read through the TV's own ADB, in Free My TV mode. Values are -1 when unknown.
 */
final class SystemStats {
    private static final long EVERY_MS = 3000;

    private final Context context;
    private final Runnable onChange;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread thread;
    private volatile boolean running;
    private long lastIdle = -1, lastTotal = -1;
    private Adb adb;                // stats thread only
    private long adbRetryAt;

    volatile int memory = -1, cpu = -1, storage = -1;     // percent used
    volatile int temperature = -1;                          // °C
    volatile String memoryUse = "", storageUse = "";     // "used / total"

    SystemStats(Context c, Runnable onChange) {
        context = c.getApplicationContext();
        this.onChange = onChange;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "FreeMyTV stats");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void loop() {
        while (running) {
            sample();
            main.post(onChange);
            try {
                Thread.sleep(EVERY_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
        closeAdb();
    }

    private void sample() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(m);
            if (m.totalMem > 0) {
                memory = Math.round(100f * (m.totalMem - m.availMem) / m.totalMem);
                memoryUse = usedOfTotal(m.totalMem - m.availMem, m.totalMem);
            }
        }
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            long total = fs.getTotalBytes(), free = fs.getAvailableBytes();
            if (total > 0) {
                storage = Math.round(100f * (total - free) / total);
                storageUse = usedOfTotal(total - free, total);
            }
        } catch (IllegalArgumentException ignored) {
        }
        cpu = cpuUse();
        temperature = cpuTemperature();
    }

    private String usedOfTotal(long used, long total) {
        return Formatter.formatShortFileSize(context, used) + " / " + Formatter.formatShortFileSize(context, total);
    }

    /**
     * The first line of /proc/stat. Where SELinux hides it from apps, the TV's own ADB shell can still read it: only in
     * Free My TV mode, when the TV already trusts this app's key (never a new "Allow USB debugging?" prompt).
     */
    private String procStat() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            return r.readLine();
        } catch (IOException e) {
            // hidden: try the shell below
        }
        if (SystemClock.elapsedRealtime() < adbRetryAt
                || !HomeMode.FREE.equals(HomeMode.state(context).optString("mode"))) return null;
        try {
            if (adb == null) adb = Adb.connect(context, 0, null);
            String out = adb.shell("head -1 /proc/stat");
            return out.startsWith("cpu ") ? out.trim() : null;
        } catch (IOException e) {
            closeAdb();
            adbRetryAt = SystemClock.elapsedRealtime() + 60_000;
            return null;
        }
    }

    private void closeAdb() {
        if (adb != null) adb.close();
        adb = null;
    }

    /** Percent of the processor in use since the last sample (all cores), or -1 when /proc/stat can't be read. */
    private int cpuUse() {
        try {
            String line = procStat();
            if (line == null || !line.startsWith("cpu ")) return -1;
            String[] f = line.trim().split("\\s+");
            long total = 0, idle = 0;
            for (int i = 1; i < f.length; i++) {
                long v = Long.parseLong(f[i]);
                total += v;
                if (i == 4 || i == 5) idle += v;    // idle and iowait
            }
            int use = -1;
            if (lastTotal >= 0 && total > lastTotal) {
                use = Math.round(100f * (1 - (float) (idle - lastIdle) / (total - lastTotal)));
            }
            lastIdle = idle;
            lastTotal = total;
            return use < 0 ? cpu : Math.max(0, Math.min(100, use));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The processor's temperature (the first thermal zone named like the chip's), or -1. */
    private static int cpuTemperature() {
        File[] zones = new File("/sys/class/thermal").listFiles((dir, name) -> name.startsWith("thermal_zone"));
        if (zones == null) return -1;
        int fallback = -1;
        for (File z : zones) {
            String type = read(new File(z, "type")).toLowerCase(java.util.Locale.ROOT);
            String value = read(new File(z, "temp"));
            if (value.isEmpty()) continue;
            try {
                long t = Long.parseLong(value);
                int c = (int) (t > 1000 ? t / 1000 : t);
                if (c <= 0 || c > 150) continue;
                if (type.contains("soc") || type.contains("cpu")) return c;
                if (fallback < 0) fallback = c;
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static String read(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s = r.readLine();
            return s == null ? "" : s.trim();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
