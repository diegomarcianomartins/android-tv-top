package io.freemytv;

import android.service.dreams.DreamService;
import android.view.View;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits.
 */
public class Screensaver extends DreamService {
    private View view;   // DreamView ou CameraDream (a escolha em Tela de Descanso)

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        view = CameraDream.create(this);
        setContentView(view);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) ((DreamContent) view).start();
    }

    @Override public void onDreamingStopped() {
        if (view != null) ((DreamContent) view).stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) ((DreamContent) view).stop();
        super.onDetachedFromWindow();
    }
}
