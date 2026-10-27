package io.freemytv;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Casa inteligente (o botão do topo da tela inicial, Configurações → Botões do Topo): um painel no estilo do Home
 * Assistant, com um cartão por aparelho Tuya (o ícone num disco à esquerda, âmbar quando ligado, e o nome e o estado ao
 * lado), em duas seções: os que ligam e desligam e os outros. OK liga ou desliga; as setas seguem a posição dos
 * cartões. Ver Tuya.
 */
public class TuyaActivity extends Activity {
    private TuyaView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new TuyaView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
    }

    @Override protected void onPause() {
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    static final class TuyaView extends View {
        // Em unidades de uma tela de 1920 px de largura.
        private static final int COLS = 4;
        private static final float CARD_W = 410, CARD_H = 122, GAP_X = 24, GAP_Y = 18, HEAD_H = 62, SECTION_GAP = 18;
        private static final float LEFT = 104, VIEW_TOP = 212, VIEW_BOTTOM = 1030;
        private static final long REFRESH_MS = 10_000, SETTLE_MS = 6_000;
        private static final int CARD = 0xD92A2E35, CARD_FOCUS = 0xF23A4048, ON_ICON = 0xFFFFC107, OFF_ICON = 0xFFB4B9C2,
                AMBER = 0xFFFFC266;
        // Os tipos de ícone, desenhados à mão (só existem os símbolos do sistema; não há lâmpada nem tomada).
        private static final int BULB = 0, STRIP = 1, PLUG = 2, SWITCH = 3, FAN = 4, REMOTE = 5, SENSOR = 6, OTHER = 7;

        private final Tuya tuya;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final RectF card = new RectF(), part = new RectF();
        private List<Tuya.Entry> list = new ArrayList<>();
        // A posição de cada cartão (x, y em unidades, dentro da área que rola) e a de cada título de seção.
        private float[] cardX = new float[0], cardY = new float[0];
        private float firstTitleY = -1, otherTitleY = -1;
        private int switchCount;
        private int focus;
        private float scrollY;   // em unidades
        private boolean running, okDown, loaded;
        private long startedAt, lastSky;
        private int w;
        private float k = 1;
        private Paint title, hint, count, section, name, state, fill, ring, icon, badge;
        private TextPaint nameClip, stateClip;

        private final Runnable refresh = new Runnable() {
            @Override public void run() {
                if (!running) return;
                tuya.refreshAll();
                handler.postDelayed(this, REFRESH_MS);
            }
        };

        TuyaView(TuyaActivity a) {
            super(a);
            tuya = Tuya.get(a);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription(Ui.tr("Smart Home"));
            tuya.setListener(() -> handler.post(this::rebuild));
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            new Thread(() -> {
                tuya.load();
                List<Tuya.Entry> sorted = Tuya.entries(Tuya.sorted(tuya.devices()));
                handler.post(() -> {
                    list = sorted;
                    loaded = true;
                    layout();
                    invalidate();
                    tuya.start();
                    handler.postDelayed(() -> {
                        if (running) tuya.settle();
                    }, SETTLE_MS);
                    handler.postDelayed(refresh, REFRESH_MS);
                });
            }, "FreeMyTV tuya load").start();
        }

        void pause() {
            running = false;
            handler.removeCallbacksAndMessages(null);
            tuya.stop();
        }

        /** Põe os cartões em grade, em duas seções (os que ligam e desligam, e os outros), em unidades de 1920 px. */
        private void layout() {
            int n = list.size();
            cardX = new float[n];
            cardY = new float[n];
            switchCount = 0;
            for (Tuya.Entry e : list) if (e.device.switchable()) switchCount++;
            float y = 0;
            firstTitleY = otherTitleY = -1;
            for (int group = 0; group < 2; group++) {
                int from = group == 0 ? 0 : switchCount, to = group == 0 ? switchCount : n;
                if (from >= to) continue;
                if (group == 0) firstTitleY = y; else otherTitleY = y;
                y += HEAD_H;
                for (int i = from; i < to; i++) {
                    int j = i - from;
                    cardX[i] = LEFT + (j % COLS) * (CARD_W + GAP_X);
                    cardY[i] = y + (j / COLS) * (CARD_H + GAP_Y);
                }
                y += ((to - from + COLS - 1) / COLS) * (CARD_H + GAP_Y) + SECTION_GAP;
            }
            focus = Math.min(focus, Math.max(0, n - 1));
        }

        /**
         * Refaz a lista quando um aparelho revela quantas teclas tem (um interruptor de 4 teclas vira 4 cartões), sem
         * perder o cartão em foco; senão só redesenha.
         */
        private void rebuild() {
            if (!loaded) return;
            List<Tuya.Entry> next = Tuya.entries(Tuya.sorted(tuya.devices()));
            boolean same = next.size() == list.size();
            for (int i = 0; same && i < next.size(); i++) same = next.get(i).same(list.get(i));
            if (!same) {
                Tuya.Entry current = focus < list.size() ? list.get(focus) : null;
                int at = 0;
                for (int i = 0; current != null && i < next.size(); i++) {
                    if (next.get(i).same(current)) {
                        at = i;
                        break;
                    }
                    if (next.get(i).device == current.device && at == 0) at = i;
                }
                list = next;
                focus = at;
                layout();
                scrollY = Math.min(scrollY, Math.max(0, cardY.length == 0 ? 0 : cardY[cardY.length - 1] + CARD_H - (VIEW_BOTTOM - VIEW_TOP)));
            }
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            k = w / 1920f;
            scene.setSize(w, height);
            title = Ui.text(Ui.displaySemibold, 56 * k, 0xFFFFFFFF);
            hint = Ui.text(Ui.regular, 24 * k, 0x99FFFFFF);
            count = Ui.text(Ui.medium, 32 * k, 0xE6FFFFFF);
            count.setTextAlign(Paint.Align.RIGHT);
            section = Ui.text(Ui.semibold, 26 * k, 0xB3FFFFFF);
            section.setLetterSpacing(0.04f);
            name = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
            nameClip = new TextPaint(name);
            state = Ui.text(Ui.regular, 25 * k, 0xB3FFFFFF);
            stateClip = new TextPaint(state);
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            badge = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(4 * k);
            ring.setColor(0xFFFFFFFF);
            icon = new Paint(Paint.ANTI_ALIAS_FLAG);
            icon.setStrokeCap(Paint.Cap.ROUND);
            icon.setStrokeJoin(Paint.Join.ROUND);
        }

        private static String status(Tuya.Entry e) {
            switch (e.state()) {
                case Tuya.ON: return Ui.tr("On");
                case Tuya.OFF: return Ui.tr("Off");
                case Tuya.OFFLINE: return Ui.tr("Offline");
                case Tuya.NOT_SWITCH: return Ui.tr("Not a switch");
                case Tuya.NEEDS_NEWER: return Ui.tr("Not supported yet");
                case Tuya.NO_ANSWER: return Ui.tr("No response");
                default: return "…";
            }
        }

        private static int kind(String category) {
            switch (category) {
                case "dj": case "dc": return BULB;
                case "dd": return STRIP;
                case "cz": case "pc": return PLUG;
                case "kg": case "tdq": return SWITCH;
                case "fs": case "fsd": return FAN;
                case "mcs": return SENSOR;
                case "wnykq": case "qt": case "infrared_tv": case "infrared_ac": case "infrared_box": return REMOTE;
                default: return OTHER;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long up = SystemClock.uptimeMillis();
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance((up - startedAt) / 1000f);
            }
            scene.drawBackground(c);

            // A barra de cima: o símbolo e o título, e quantos estão ligados.
            Symbols.draw(getContext(), c, "home", (LEFT + 34) * k, 100 * k, 46 * k, 0xEBFFFFFF);
            c.drawText(Ui.tr("Smart Home"), (LEFT + 84) * k, 116 * k, title);
            int on = 0;
            for (Tuya.Entry e : list) if (e.state() == Tuya.ON) on++;
            if (!list.isEmpty()) c.drawText(Ui.trf("{n} on", "n", on), (1920 - LEFT) * k, 112 * k, count);
            c.drawText(Ui.tr("OK switches the device on or off."), (LEFT + 4) * k, 168 * k, hint);

            if (list.isEmpty()) {
                if (loaded) c.drawText(Ui.tr("No Tuya devices yet: see the README."), LEFT * k, (VIEW_TOP + 40) * k, hint);
                return;
            }
            c.save();
            c.clipRect(0, VIEW_TOP * k, w, VIEW_BOTTOM * k);
            c.translate(0, (VIEW_TOP - scrollY) * k);
            if (firstTitleY >= 0) c.drawText(Ui.tr("Devices").toUpperCase(Ui.locale()), LEFT * k, (firstTitleY + 38) * k, section);
            if (otherTitleY >= 0) c.drawText(Ui.tr("Other devices").toUpperCase(Ui.locale()), LEFT * k, (otherTitleY + 38) * k, section);
            for (int i = 0; i < list.size(); i++) {
                float top = cardY[i] - scrollY;
                if (top + CARD_H < 0 || top > VIEW_BOTTOM - VIEW_TOP) continue;
                card.set(cardX[i] * k, cardY[i] * k, (cardX[i] + CARD_W) * k, (cardY[i] + CARD_H) * k);
                drawCard(c, list.get(i), i == focus);
            }
            c.restore();
        }

        private void drawCard(Canvas c, Tuya.Entry e, boolean focused) {
            int st = e.state();
            boolean on = st == Tuya.ON, live = on || st == Tuya.OFF || st == Tuya.WORKING;
            float r = 26 * k;
            fill.setColor(focused ? CARD_FOCUS : CARD);
            c.drawRoundRect(card, r, r, fill);
            if (focused) {
                part.set(card);
                part.inset(-3 * k, -3 * k);
                c.drawRoundRect(part, r + 3 * k, r + 3 * k, ring);
            }
            // O ícone num disco, como nos cartões do Home Assistant: a cor do ícone, bem suave, no fundo.
            int tint = on ? ON_ICON : live ? OFF_ICON : 0x66FFFFFF;
            float bx = card.left + 24 * k + 34 * k, by = card.centerY();
            badge.setColor(on ? 0x2BFFC107 : live ? 0x1FFFFFFF : 0x12FFFFFF);
            c.drawCircle(bx, by, 40 * k, badge);
            drawIcon(c, kind(e.device.category), bx, by, 58 * k, tint, on);

            float textX = bx + 40 * k + 20 * k, room = card.right - 20 * k - textX;
            name.setColor(live ? 0xF2FFFFFF : 0x99FFFFFF);
            String shown = TextUtils.ellipsize(e.label(), nameClip, room, TextUtils.TruncateAt.END).toString();
            c.drawText(shown, textX, card.centerY() - 6 * k, name);
            state.setColor(on ? ON_ICON : st == Tuya.NO_ANSWER ? AMBER : live ? 0xB3FFFFFF : 0x73FFFFFF);
            c.drawText(TextUtils.ellipsize(status(e), stateClip, room, TextUtils.TruncateAt.END).toString(),
                    textX, card.centerY() + 32 * k, state);
        }

        /** Um ícone simples, desenhado com linhas, centrado em (cx, cy) dentro de um quadrado de lado s. */
        private void drawIcon(Canvas c, int kind, float cx, float cy, float s, int color, boolean on) {
            icon.setColor(color);
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeWidth(s * 0.075f);
            switch (kind) {
                case BULB:
                    c.drawCircle(cx, cy - 0.10f * s, 0.30f * s, icon);
                    part.set(cx - 0.12f * s, cy + 0.20f * s, cx + 0.12f * s, cy + 0.36f * s);
                    c.drawRoundRect(part, 0.04f * s, 0.04f * s, icon);
                    break;
                case STRIP:
                    icon.setStyle(Paint.Style.FILL);
                    part.set(cx - 0.42f * s, cy - 0.04f * s, cx + 0.42f * s, cy + 0.04f * s);
                    c.drawRoundRect(part, 0.04f * s, 0.04f * s, icon);
                    for (int i = 0; i < 5; i++) {
                        c.drawCircle(cx - 0.34f * s + i * 0.17f * s, cy + (i % 2 == 0 ? -0.18f : 0.18f) * s, 0.055f * s, icon);
                    }
                    break;
                case PLUG:
                    part.set(cx - 0.34f * s, cy - 0.34f * s, cx + 0.34f * s, cy + 0.34f * s);
                    c.drawRoundRect(part, 0.14f * s, 0.14f * s, icon);
                    icon.setStyle(Paint.Style.FILL);
                    part.set(cx - 0.15f * s, cy - 0.16f * s, cx - 0.09f * s, cy + 0.02f * s);
                    c.drawRect(part, icon);
                    part.set(cx + 0.09f * s, cy - 0.16f * s, cx + 0.15f * s, cy + 0.02f * s);
                    c.drawRect(part, icon);
                    c.drawCircle(cx, cy + 0.17f * s, 0.05f * s, icon);
                    break;
                case SWITCH:
                    part.set(cx - 0.26f * s, cy - 0.38f * s, cx + 0.26f * s, cy + 0.38f * s);
                    c.drawRoundRect(part, 0.09f * s, 0.09f * s, icon);
                    icon.setStyle(Paint.Style.FILL);
                    part.set(cx - 0.12f * s, on ? cy - 0.26f * s : cy + 0.02f * s, cx + 0.12f * s, on ? cy - 0.02f * s : cy + 0.26f * s);
                    c.drawRoundRect(part, 0.05f * s, 0.05f * s, icon);
                    break;
                case FAN:
                    icon.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, cy, 0.07f * s, icon);
                    icon.setStyle(Paint.Style.STROKE);
                    for (int a = 0; a < 360; a += 120) {
                        c.save();
                        c.rotate(a, cx, cy);
                        part.set(cx - 0.10f * s, cy - 0.40f * s, cx + 0.10f * s, cy - 0.10f * s);
                        c.drawOval(part, icon);
                        c.restore();
                    }
                    break;
                case REMOTE:
                    part.set(cx - 0.18f * s, cy - 0.38f * s, cx + 0.18f * s, cy + 0.38f * s);
                    c.drawRoundRect(part, 0.09f * s, 0.09f * s, icon);
                    icon.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, cy - 0.22f * s, 0.045f * s, icon);
                    c.drawCircle(cx - 0.065f * s, cy - 0.02f * s, 0.04f * s, icon);
                    c.drawCircle(cx + 0.065f * s, cy - 0.02f * s, 0.04f * s, icon);
                    c.drawCircle(cx, cy + 0.17f * s, 0.04f * s, icon);
                    break;
                case SENSOR:
                    part.set(cx - 0.36f * s, cy - 0.32f * s, cx - 0.02f * s, cy + 0.32f * s);
                    c.drawRoundRect(part, 0.06f * s, 0.06f * s, icon);
                    part.set(cx + 0.12f * s, cy - 0.16f * s, cx + 0.34f * s, cy + 0.32f * s);
                    c.drawRoundRect(part, 0.06f * s, 0.06f * s, icon);
                    break;
                default:
                    c.drawCircle(cx, cy, 0.30f * s, icon);
                    icon.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, cy, 0.07f * s, icon);
                    break;
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down) neighbor(0, -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) neighbor(0, 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) neighbor(-1, 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) neighbor(1, 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && focus < list.size()) tuya.toggle(list.get(focus));
                        okDown = false;
                    }
                    return true;
                default:
                    return false;
            }
        }

        /** O cartão mais próximo na direção (dx, dy): o que está bem ao lado, ou o mais perto em linha reta. */
        private void neighbor(int dx, int dy) {
            if (list.isEmpty()) return;
            int best = -1;
            float bestScore = Float.MAX_VALUE;
            for (int i = 0; i < list.size(); i++) {
                if (i == focus) continue;
                float ax = cardX[i] - cardX[focus], ay = cardY[i] - cardY[focus];
                float along = dx != 0 ? ax * dx : ay * dy, across = dx != 0 ? Math.abs(ay) : Math.abs(ax);
                if (along <= 1) continue;
                float score = along + across * 4;
                if (score < bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best < 0) return;
            focus = best;
            // Mantém o cartão à vista (e, na primeira linha de uma seção, o título dela).
            float first = focus < switchCount ? (firstTitleY >= 0 ? firstTitleY + HEAD_H : 0) : otherTitleY + HEAD_H;
            float top = cardY[focus] - (cardY[focus] == first ? HEAD_H : 0), bottom = cardY[focus] + CARD_H;
            float room = VIEW_BOTTOM - VIEW_TOP;
            if (top < scrollY) scrollY = Math.max(0, top);
            if (bottom > scrollY + room) scrollY = bottom - room + 8;
            invalidate();
        }
    }
}
