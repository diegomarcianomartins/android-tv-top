package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;

/**
 * Os controles que o Free My TV conhece por inteiro: cada botão configurável (o código de tecla que a TV recebe) e um
 * desenho simples do controle, para a tela Botões do Controle mostrar onde fica o botão que está sendo configurado.
 * Os essenciais (ligar, OK, setas, voltar, início, volume, mudo) só aparecem no desenho e nunca são configuráveis:
 * assim a TV nunca fica sem como navegar.
 * Códigos lidos de cada aparelho: na TCL, o log do TclPhoneWindowManager; no projetor, o dumpsys input.
 */
final class Remotes {
    private Remotes() {}

    static final int CIRCLE = 0, PILL = 1, RING = 2;
    private static final int KEY_TV_INPUT = 178, KEY_MENU = 82, KEY_CHANNEL_UP = 166, KEY_CHANNEL_DOWN = 167,
            KEY_F10 = 140, KEY_F11 = 141, KEY_MOUSE = 305;

    /** Um botão: o código (0 num essencial, que só aparece no desenho), o nome e o lugar no desenho. */
    static final class Button {
        final int key;
        final String name;      // em inglês, para o Ui.tr; vazio num essencial
        final int shape;
        final float x, y, w, h; // centro e tamanho, em unidades do desenho (o controle tem 100 de largura)
        final String text;      // o que vai escrito no botão

        Button(int key, String name, int shape, float x, float y, float w, float h, String text) {
            this.key = key;
            this.name = name;
            this.shape = shape;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.text = text;
        }
    }

    static final class Remote {
        final float height;
        final Button[] buttons;
        private final int[] keys;
        /**
         * Lido direto do receptor infravermelho (RawKeys), sem o serviço de acessibilidade: o nome do dispositivo de
         * entrada e, em pares, código do Linux → código do Android. Vazio: pelo serviço de acessibilidade.
         */
        String rawDevice = "";
        float corner = 50;   // raio dos cantos: metade da largura num controle fino, menos num largo
        int[] rawCodes = {};

        Remote(float height, Button... buttons) {
            this.height = height;
            this.buttons = buttons;
            int n = 0;
            for (Button b : buttons) if (b.key != 0) n++;
            keys = new int[n];
            n = 0;
            for (Button b : buttons) if (b.key != 0) keys[n++] = b.key;
        }

        /** Os códigos dos botões configuráveis, na ordem da lista (de cima para baixo no controle). */
        int[] keys() {
            return keys.clone();
        }

        /** Os nomes dos botões configuráveis, traduzidos, na mesma ordem. */
        String[] names() {
            String[] out = new String[keys.length];
            int i = 0;
            for (Button b : buttons) if (b.key != 0) out[i++] = translated(b.name);
            return out;
        }
    }

    /** Cada nome escrito por extenso: o tools/translate.py só acha textos dentro de Ui.tr("..."). */
    private static String translated(String name) {
        switch (name) {
            case "Apps button": return Ui.tr("Apps button");
            case "Bookmark button": return Ui.tr("Bookmark button");
            case "Channel down button": return Ui.tr("Channel down button");
            case "Channel up button": return Ui.tr("Channel up button");
            case "Colors button": return Ui.tr("Colors button");
            case "Disney+ button": return Ui.tr("Disney+ button");
            case "Globoplay button": return Ui.tr("Globoplay button");
            case "Google button": return Ui.tr("Google button");
            case "Input button": return Ui.tr("Input button");
            case "Layers button": return Ui.tr("Layers button");
            case "Live TV button": return Ui.tr("Live TV button");
            case "Menu button": return Ui.tr("Menu button");
            case "Mirroring button": return Ui.tr("Mirroring button");
            case "Mouse button": return Ui.tr("Mouse button");
            case "Netflix button": return Ui.tr("Netflix button");
            case "Prime Video button": return Ui.tr("Prime Video button");
            case "Screen button": return Ui.tr("Screen button");
            case "Settings button": return Ui.tr("Settings button");
            case "TCL Channel button": return Ui.tr("TCL Channel button");
            case "YouTube button": return Ui.tr("YouTube button");
            default: return name;
        }
    }

    private static Button key(int key, String name, float x, float y, String text) {
        return new Button(key, name, CIRCLE, x, y, 25, 25, text);
    }

    private static Button app(int key, String name, float x, float y, String text) {
        return new Button(key, name, PILL, x, y, 42, 22, text);
    }

    private static Button fixed(int shape, float x, float y, float w, float h, String text) {
        return new Button(0, "", shape, x, y, w, h, text);
    }

    /** O controle deste aparelho, ou null (outras TVs ficam com a lista de botões de app de antes). */
    static final Remote CURRENT = detect();

    private static Remote detect() {
        boolean tcl = "TCL".equalsIgnoreCase(Build.MANUFACTURER) || "TCL".equalsIgnoreCase(Build.BRAND);
        if (tcl && "C06".equalsIgnoreCase(Build.DEVICE)) return tclC06();
        if (tcl && "UnionTV".equalsIgnoreCase(Build.DEVICE)) return tclUnionTV();
        if (Build.DEVICE.startsWith("t950s")) return amlogicProjector();
        return null;
    }

    /**
     * TCL UnionTV (Realtek, Android 9): o controle com Netflix e Globoplay. O botão do Google (voz) não manda tecla
     * nenhuma para a TV, então só aparece no desenho.
     */
    private static Remote tclUnionTV() {
        return new Remote(322,
                fixed(CIRCLE, 17, 27, 22, 22, "POWER"),
                key(4077, "Settings button", 78, 27, "⚙"),
                fixed(CIRCLE, 17, 59, 25, 25, "⌂"),
                key(KEY_MENU, "Menu button", 78, 59, "≡"),
                fixed(RING, 47, 113, 86, 86, "OK"),
                fixed(CIRCLE, 17, 183, 25, 25, "←"),
                key(KEY_TV_INPUT, "Input button", 78, 183, "⇥"),
                fixed(CIRCLE, 17, 217, 25, 25, "G"),
                fixed(PILL, 47, 232, 25, 56, "±"),
                key(KEY_CHANNEL_UP, "Channel up button", 78, 217, "P+"),
                key(4056, "Colors button", 17, 248, "⌨"),
                key(KEY_CHANNEL_DOWN, "Channel down button", 78, 248, "P−"),
                app(Prefs.KEY_NETFLIX, "Netflix button", 27, 291, "NETFLIX"),
                app(Prefs.KEY_GLOBOPLAY, "Globoplay button", 70, 291, "globoplay"));
    }

    /** TCL C06 (MediaTek, Android 11): o controle com seis botões de app. */
    private static Remote tclC06() {
        return new Remote(430,
                fixed(CIRCLE, 17, 27, 22, 22, "🔇"), fixed(CIRCLE, 78, 27, 22, 22, "POWER"),
                key(4090, "Bookmark button", 17, 59, "🔖"),
                key(4048, "Google button", 47, 59, "G"),
                key(4077, "Settings button", 78, 59, "⚙"),
                fixed(RING, 47, 113, 86, 86, "OK"),
                fixed(CIRCLE, 17, 183, 25, 25, "←"), fixed(CIRCLE, 47, 183, 25, 25, "⌂"),
                key(4001, "Live TV button", 78, 183, "TV"),
                key(KEY_TV_INPUT, "Input button", 17, 217, "⇥"),
                fixed(PILL, 47, 232, 25, 56, "±"),
                key(KEY_CHANNEL_UP, "Channel up button", 78, 217, "P+"),
                key(KEY_MENU, "Menu button", 17, 248, "≡"),
                key(KEY_CHANNEL_DOWN, "Channel down button", 78, 248, "P−"),
                key(4139, "Layers button", 17, 281, "◇"),
                key(4056, "Colors button", 47, 281, "⌨"),
                key(4137, "Apps button", 78, 281, "▦"),
                app(Prefs.KEY_NETFLIX, "Netflix button", 27, 311, "NETFLIX"),
                app(Prefs.KEY_PRIME, "Prime Video button", 70, 311, "prime"),
                app(Prefs.KEY_GLOBOPLAY, "Globoplay button", 27, 340, "globoplay"),
                app(Prefs.KEY_DISNEY, "Disney+ button", 70, 340, "Disney+"),
                app(Prefs.KEY_YOUTUBE, "YouTube button", 27, 369, "YouTube"),
                app(Prefs.KEY_TCL_CHANNEL, "TCL Channel button", 70, 369, "TCL"));
    }

    /**
     * Projetor Amlogic genérico (launcher HTC/JM): o controle pequeno com espelhamento e mouse. O app do mouse
     * (com.master.accessibility) apaga qualquer serviço de acessibilidade em 1 s, então aqui o receptor é lido direto
     * (/dev/input/eventN, que qualquer app lê nesse aparelho). Sem como segurar a tecla: o projetor ainda faz o que
     * já fazia com ela, além do que foi configurado.
     */
    private static Remote amlogicProjector() {
        Remote r = amlogicProjectorLayout();
        r.rawDevice = "ir_keypad";
        r.corner = 26;
        r.rawCodes = new int[] {68, KEY_F10, 87, KEY_F11, 139, KEY_MENU, 637, KEY_MOUSE};
        return r;
    }

    private static Remote amlogicProjectorLayout() {
        return new Remote(260,
                fixed(CIRCLE, 14, 16, 22, 22, "POWER"), fixed(CIRCLE, 84, 16, 22, 22, "🔇"),
                key(KEY_F10, "Mirroring button", 14, 49, "⧉"),
                key(KEY_F11, "Screen button", 49, 49, "▭"),
                key(KEY_MOUSE, "Mouse button", 84, 49, "🖱"),
                fixed(RING, 49, 118, 92, 92, "OK"),
                fixed(CIRCLE, 14, 193, 25, 25, "←"),
                key(KEY_MENU, "Menu button", 49, 193, "≡"),
                fixed(CIRCLE, 84, 193, 25, 25, "⌂"),
                fixed(PILL, 49, 225, 82, 20, "−   +"));
    }

    // MARK: - Desenho

    private static Paint body, rim, fill, label;

    /**
     * Desenha o controle centrado em `area` (na maior altura que couber), com o botão de código `focusKey` aceso,
     * `glow` de 0 a 1. Só na tela de configurações, sem pressa: os Paint são criados uma vez.
     */
    static void draw(Canvas c, Remote r, RectF area, int focusKey, float glow) {
        if (body == null) {
            body = new Paint(Paint.ANTI_ALIAS_FLAG);
            rim = new Paint(Paint.ANTI_ALIAS_FLAG);
            rim.setStyle(Paint.Style.STROKE);
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            label = new Paint(Paint.ANTI_ALIAS_FLAG);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTypeface(Ui.semibold);
        }
        float s = Math.min(area.height() / r.height, area.width() / 100f);
        float left = area.centerX() - 50 * s, top = area.centerY() - r.height / 2 * s;
        RectF box = new RectF(left, top, left + 100 * s, top + r.height * s);
        body.setColor(0xF21C1C1E);
        c.drawRoundRect(box, r.corner * s, r.corner * s, body);
        rim.setStrokeWidth(Math.max(1, 1.2f * s));
        rim.setColor(0x40FFFFFF);
        c.drawRoundRect(box, r.corner * s, r.corner * s, rim);
        for (Button b : r.buttons) {
            boolean on = b.key != 0 && b.key == focusKey;
            float cx = left + b.x * s, cy = top + b.y * s, bw = b.w * s, bh = b.h * s;
            box.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
            if (on) {
                // O botão escolhido: branco, com um halo que pulsa.
                fill.setColor(Ui.alpha(0xFFFFFFFF, 0.25f + 0.25f * glow));
                float g = 5 * s;
                c.drawRoundRect(box.left - g, box.top - g, box.right + g, box.bottom + g, bh / 2 + g, bh / 2 + g, fill);
            }
            boolean power = b.text.equals("POWER");   // vermelho, como no controle, sem texto
            fill.setColor(on ? 0xFFFFFFFF : power ? 0xCCE5484D : b.key != 0 ? 0x47FFFFFF : 0x1FFFFFFF);
            if (b.shape == RING) {
                c.drawOval(box, fill);
                fill.setColor(0xFF2C2C2E);
                float inner = bw * 0.38f;
                c.drawOval(cx - inner / 2, cy - inner / 2, cx + inner / 2, cy + inner / 2, fill);
            } else {
                c.drawRoundRect(box, bh / 2, bh / 2, fill);
            }
            if (b.text.isEmpty() || power) continue;
            float size = (b.shape == PILL && b.text.length() > 3 ? 6.2f : 8.5f) * s;
            label.setTextSize(size);
            float tw = label.measureText(b.text), max = (b.shape == RING ? bw * 0.38f : bw) - 3 * s;
            if (tw > max) label.setTextSize(size * max / tw);
            label.setColor(on ? 0xFF1C1C1E : b.key != 0 ? 0xE6FFFFFF : 0x80FFFFFF);
            c.drawText(b.text, cx, cy + Ui.halfCap(label), label);
        }
    }
}
