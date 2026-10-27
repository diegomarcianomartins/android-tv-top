package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * O cartão de um lembrete, no alto da tela e sobre qualquer app (a mesma permissão da janela das câmeras). Não pega o
 * foco nem o toque: o filme continua e o controle também. Some sozinho; lembretes na mesma hora entram no mesmo
 * cartão. Um serviço em primeiro plano no processo principal, só enquanto o cartão está na tela.
 */
public final class ReminderPopup extends Service {
    private static final String CHANNEL = "reminders", EXTRA = "texts";
    private static final int NOTIFICATION = 10;
    private static final long SHOW_MS = 30_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout box;
    private TextView body;

    static void show(Context c, List<String> texts) {
        Intent i = new Intent(c, ReminderPopup.class).putStringArrayListExtra(EXTRA, new ArrayList<>(texts));
        try {
            c.startForegroundService(i);
        } catch (RuntimeException e) {
            AppLog.w("reminder: " + e.getMessage());
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
                .setContentTitle(Ui.tr("Reminder"))
                .build());
        List<String> texts = intent == null ? null : intent.getStringArrayListExtra(EXTRA);
        if (texts == null || texts.isEmpty() || !Settings.canDrawOverlays(this)) {
            if (texts != null && !texts.isEmpty()) AppLog.w("reminder: not allowed to draw over other apps");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (box == null) build();
        for (String t : texts) body.append((body.length() == 0 ? "" : "\n") + t);
        AppLog.w("reminder shown (" + texts.size() + ")");
        chime();
        main.removeCallbacksAndMessages(null);
        main.postDelayed(this::stopSelf, SHOW_MS);
        return START_NOT_STICKY;
    }

    private void build() {
        float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Math.round(44 * k), Math.round(28 * k), Math.round(44 * k), Math.round(34 * k));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF01C1C1E);
        background.setCornerRadius(30 * k);
        background.setStroke(Math.max(1, Math.round(2 * k)), 0x40FFFFFF);
        box.setBackground(background);

        TextView title = new TextView(this);
        title.setText(Ui.tr("Reminder") + "  ·  " + android.text.format.DateFormat.getTimeFormat(this).format(new Date()));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26 * k);
        title.setTextColor(0xB3FFFFFF);
        title.setTypeface(Ui.semibold);
        box.addView(title);

        body = new TextView(this);
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, 42 * k);
        body.setTextColor(Color.WHITE);
        body.setTypeface(Ui.semibold);
        body.setMaxWidth(Math.round(1200 * k));
        body.setPadding(0, Math.round(10 * k), 0, 0);
        box.addView(body);

        wm = getSystemService(WindowManager.class);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.round(64 * k);
        wm.addView(box, lp);
    }

    /** O som de notificação da TV, uma vez (sem ele, só o cartão). */
    private void chime() {
        try {
            Ringtone r = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            if (r != null) r.play();
        } catch (RuntimeException ignored) {
        }
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (box != null && wm != null) {
            try { wm.removeView(box); } catch (RuntimeException ignored) {}
        }
        box = null;
        super.onDestroy();
    }
}
