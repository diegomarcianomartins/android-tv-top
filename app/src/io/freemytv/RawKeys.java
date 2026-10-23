package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;

/**
 * Os botões do controle lidos direto do receptor (/dev/input/eventN), onde o serviço de acessibilidade não pode ficar
 * ligado (Remotes.Remote.rawDevice: o projetor Amlogic). Um serviço em primeiro plano no processo :keys, só no modo
 * Free My TV. Não segura a tecla: o aparelho ainda faz o que já fazia com ela.
 */
public final class RawKeys extends Service {
    private static final String CHANNEL = "remote-keys";
    private static final int NOTIFICATION = 8;
    private static final long RETRY_MS = 30_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile InputStream input;

    /** Liga ou desliga a leitura (HomeMode, com o modo Free My TV). */
    static void set(Context c, boolean on) {
        Intent i = new Intent(c, RawKeys.class);
        if (!on) {
            c.stopService(i);
            AppLog.w("remote buttons (receiver) off");
            return;
        }
        try {
            c.startForegroundService(i);
            AppLog.w("remote buttons (receiver) on");
        } catch (RuntimeException e) {
            AppLog.w("remote buttons (receiver): " + e.getMessage());
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Ui.init(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Free My TV", NotificationManager.IMPORTANCE_MIN));
        startForeground(NOTIFICATION, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(Ui.tr("Remote Buttons"))
                .build());
        if (!running) {
            running = true;
            Thread t = new Thread(this::loop, "FreeMyTV raw keys");
            t.setPriority(Thread.MAX_PRIORITY);
            t.start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        InputStream in = input;
        if (in != null) try { in.close(); } catch (IOException ignored) {}
        super.onDestroy();
    }

    private void loop() {
        Remotes.Remote r = Remotes.CURRENT;
        if (r == null || r.rawDevice.isEmpty()) return;
        while (running) {
            File dev = find(r.rawDevice);
            if (dev == null) {
                AppLog.w("remote buttons (receiver): no " + r.rawDevice);
            } else {
                try (InputStream in = new FileInputStream(dev)) {
                    input = in;
                    read(in, r.rawCodes);
                } catch (IOException e) {
                    if (running) AppLog.w("remote buttons (receiver): " + e.getMessage());
                }
            }
            input = null;
            if (!running) break;
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * Eventos de 16 bytes (Android de 32 bits): segundos e microssegundos (4 + 4), tipo e código (2 + 2), valor (4).
     * Só EV_KEY apertada (valor 1) de um código configurável.
     */
    private void read(InputStream in, int[] codes) throws IOException {
        byte[] ev = new byte[16];
        while (running) {
            int n = 0;
            while (n < ev.length) {
                int got = in.read(ev, n, ev.length - n);
                if (got < 0) throw new IOException("receiver closed");
                n += got;
            }
            int type = (ev[8] & 0xFF) | (ev[9] & 0xFF) << 8, code = (ev[10] & 0xFF) | (ev[11] & 0xFF) << 8;
            int value = (ev[12] & 0xFF) | (ev[13] & 0xFF) << 8 | (ev[14] & 0xFF) << 16 | ev[15] << 24;
            if (type != 1 || value != 1) continue;
            for (int i = 0; i + 1 < codes.length; i += 2) {
                if (codes[i] != code) continue;
                int key = codes[i + 1];
                String pkg = Prefs.keyApp(key);
                if (!pkg.isEmpty()) main.post(() -> RemoteKeys.run(this, key, pkg));
                break;
            }
        }
    }

    /** O /dev/input/eventN cujo nome (em /sys/class/input) é `name`. */
    private static File find(String name) {
        File[] all = new File("/sys/class/input").listFiles((d, n) -> n.startsWith("event"));
        if (all == null) return null;
        for (File e : all) {
            try (BufferedReader r = new BufferedReader(new FileReader(new File(e, "device/name")))) {
                if (name.equals(r.readLine())) return new File("/dev/input", e.getName());
            } catch (IOException ignored) {
            }
        }
        return null;
    }
}
