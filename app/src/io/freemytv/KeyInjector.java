package io.freemytv;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Roda no shell da própria TV (o PhoneRemote o abre pelo ADB com `app_process`, a partir deste APK) e injeta as teclas
 * que o celular pede, uma por linha ("23" = OK). Um processo só, que fica vivo: o `input keyevent` sobe uma JVM a cada
 * tecla, e numa TV de 1,4 GB isso leva quase 2 s. Usa o InputManager por reflexão, como o scrcpy: só o shell tem a
 * permissão de injetar. Nunca roda dentro do app.
 */
public final class KeyInjector {
    private KeyInjector() {}

    public static void main(String[] args) throws Exception {
        Class<?> manager = Class.forName("android.hardware.input.InputManager");
        Object input = manager.getMethod("getInstance").invoke(null);
        Method inject = manager.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            int code;
            try {
                code = Integer.parseInt(line.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            long now = SystemClock.uptimeMillis();
            for (int action = KeyEvent.ACTION_DOWN; action <= KeyEvent.ACTION_UP; action++) {
                KeyEvent e = new KeyEvent(now, SystemClock.uptimeMillis(), action, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
                inject.invoke(input, e, 0);   // 0: sem esperar o resultado
            }
        }
    }
}
