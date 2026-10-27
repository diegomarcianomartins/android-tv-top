package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Lembretes: um texto, a hora e se repete (todo dia ou toda semana). Criados e apagados pela página do Controle pelo
 * celular (PhoneRemote), guardados em reminders.txt e agendados no AlarmManager; na hora, o ReminderPopup mostra o
 * cartão sobre o app que estiver aberto. Tudo no processo principal. O texto vem do celular: é só dado, só vai para a
 * tela e para o arquivo, nunca para um comando.
 * Só disparam no modo Free My TV (o Original traz tudo de volta): fora dele ficam guardados, sem alarme.
 */
final class Reminders {
    private Reminders() {}

    static final int MAX = 50, MAX_TEXT = 100, ONCE = 0, DAILY = 1, WEEKLY = 2;
    /** Um lembrete que passou há mais que isso (a TV estava desligada) não dispara: a hora já foi. */
    private static final long LATE_MS = 12 * 3600_000L, RANGE_MS = 400 * 24 * 3600_000L;

    static final class Item {
        long id, at;   // at: o próximo disparo, em milissegundos
        int repeat;
        String text;
    }

    /** Chega na hora marcada (e o boot, pelo HomeReceiver, chama o process direto). */
    public static final class Alarm extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            process(context);
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "reminders.txt");
    }

    private static List<Item> read(Context c) {
        List<Item> items = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(file(c)))) {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                String[] p = line.split("\t", 4);
                if (p.length < 4) continue;
                try {
                    Item i = new Item();
                    i.id = Long.parseLong(p[0]);
                    i.at = Long.parseLong(p[1]);
                    i.repeat = Integer.parseInt(p[2]);
                    i.text = p[3];
                    items.add(i);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return items;
    }

    private static void write(Context c, List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item i : items) sb.append(i.id).append('\t').append(i.at).append('\t').append(i.repeat).append('\t').append(i.text).append('\n');
        File tmp = new File(c.getFilesDir(), "reminders.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file(c));
    }

    /** O texto do celular como vai para a tela: sem quebras nem caracteres de controle, até MAX_TEXT. "" se vazio. */
    static String clean(String text) {
        if (text == null) return "";
        String t = text.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        if (t.codePointCount(0, t.length()) > MAX_TEXT) t = t.substring(0, t.offsetByCodePoints(0, MAX_TEXT)).trim();
        return t;
    }

    /** Os lembretes guardados, do mais próximo ao mais distante. */
    static synchronized List<Item> list(Context c) {
        List<Item> items = read(c);
        Collections.sort(items, (a, b) -> Long.compare(a.at, b.at));
        return items;
    }

    /** Cria um lembrete. Lança IllegalArgumentException se não servir (texto vazio, hora fora da faixa ou lista cheia). */
    static synchronized void add(Context c, long at, int repeat, String text) {
        String t = clean(text);
        long now = System.currentTimeMillis();
        if (t.isEmpty() || repeat < ONCE || repeat > WEEKLY || at < now - 60_000 || at > now + RANGE_MS) {
            throw new IllegalArgumentException("?");
        }
        List<Item> items = read(c);
        if (items.size() >= MAX) throw new IllegalArgumentException(Ui.tr("Too many reminders."));
        Item i = new Item();
        i.id = now;
        for (Item other : items) if (other.id >= i.id) i.id = other.id + 1;
        i.at = at;
        i.repeat = repeat;
        i.text = t;
        items.add(i);
        write(c, items);
        process(c);   // agenda (e dispara já um que acabou de passar)
    }

    static synchronized void delete(Context c, long id) {
        List<Item> items = read(c);
        for (Iterator<Item> it = items.iterator(); it.hasNext();) if (it.next().id == id) it.remove();
        write(c, items);
        process(c);
    }

    /**
     * Dispara os que chegaram na hora, passa os que se repetem para a próxima data, apaga os de uma vez só e agenda o
     * alarme do próximo. Fora do modo Free My TV não faz nada além de desligar o alarme.
     */
    static void process(Context c) {
        if (!HomeMode.FREE.equals(HomeMode.state(c).optString("mode"))) {
            cancel(c);
            return;
        }
        List<String> due = new ArrayList<>();
        synchronized (Reminders.class) {
            List<Item> items = read(c);
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (Iterator<Item> it = items.iterator(); it.hasNext();) {
                Item i = it.next();
                if (i.at > now + 1000) continue;
                if (now - i.at < LATE_MS) due.add(i.text);
                changed = true;
                if (i.repeat == ONCE) it.remove(); else i.at = next(i.at, i.repeat, now);
            }
            if (changed) write(c, items);
            arm(c, items);
        }
        if (!due.isEmpty()) ReminderPopup.show(c, due);
    }

    /** A próxima data depois de `now`, mantendo a hora do relógio (um dia ou uma semana de cada vez). */
    private static long next(long at, int repeat, long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(at);
        while (cal.getTimeInMillis() <= now + 1000) cal.add(Calendar.DAY_OF_YEAR, repeat == DAILY ? 1 : 7);
        return cal.getTimeInMillis();
    }

    private static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(c, 0, new Intent(c, Alarm.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancel(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(alarm(c));
    }

    private static void arm(Context c, List<Item> items) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long first = Long.MAX_VALUE;
        for (Item i : items) first = Math.min(first, i.at);
        if (first == Long.MAX_VALUE) {
            am.cancel(alarm(c));
            return;
        }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, first, alarm(c));
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, first, alarm(c));   // Android 12+ sem o alarme exato
        }
    }
}
