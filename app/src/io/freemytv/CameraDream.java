package io.freemytv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

/**
 * A tela de descanso das câmeras (Tela de Descanso → Câmeras): uma câmera do DVR por vez, em tela cheia no stream
 * principal (o substream se o principal não tocar), 10 s cada uma, contados de quando a imagem aparece (o principal
 * demora alguns segundos para dar a primeira). Uma câmera que desistiu (nenhum stream deu imagem) dá a vez na hora;
 * uma que demora, depois de 12 s.
 */
final class CameraDream extends FrameLayout implements DreamContent {
    private static final long SHOW_MS = 10_000, WAIT_MS = 12_000;

    private final List<String> urls = Cameras.urls();
    private final TextView title;
    private TextureView view;
    private Tile tile;
    private int index = -1;
    private boolean on, live;

    /** A tela de descanso escolhida: as câmeras (se houver alguma) ou a do Free My TV. */
    static View create(Context c) {
        return Prefs.screensaver().equals(Prefs.SAVER_CAMERAS) && !Cameras.urls().isEmpty()
                ? new CameraDream(c) : new DreamView(c);
    }

    CameraDream(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        float k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        title = new TextView(c);
        title.setTextColor(0xF2FFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34 * k);
        title.setTypeface(Ui.semibold);
        title.setShadowLayer(9 * k, 0, 2 * k, 0x99000000);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        int m = Math.round(64 * k);
        lp.setMargins(m, m, m, m);
        addView(title, lp);
    }

    @Override public void start() {
        if (on || urls.isEmpty()) return;
        on = true;
        show(index < 0 ? 0 : index);
    }

    @Override public void stop() {
        on = false;
        removeCallbacks(next);
        removeCallbacks(timeout);
        drop();
    }

    private final Runnable next = () -> show((index + 1) % urls.size());
    private final Runnable timeout = () -> {
        if (!live) show((index + 1) % urls.size());
    };

    private void show(int i) {
        removeCallbacks(next);
        removeCallbacks(timeout);
        if (!on) return;
        drop();
        index = i;
        live = false;
        title.setText(Ui.trf("Camera {n}", "n", i + 1));
        TextureView v = new TextureView(getContext());
        view = v;
        tile = new Tile(v, i);
        v.setSurfaceTextureListener(tile);
        addView(v, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        postDelayed(timeout, WAIT_MS);
    }

    private void drop() {
        if (tile != null) tile.stop();
        tile = null;
        if (view != null) removeView(view);
        view = null;
    }

    /** Uma câmera na tela; a thread do stream solta a textura quando o decodificador largar dela. */
    private final class Tile implements TextureView.SurfaceTextureListener, CameraStream.Listener {
        private final TextureView v;
        private final int i;
        private CameraStream stream;
        private boolean stopped;

        Tile(TextureView v, int i) {
            this.v = v;
            this.i = i;
        }

        void stop() {
            stopped = true;
            if (stream != null) stream.stop(true);
        }

        @Override public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
            if (stopped) return;
            stream = new CameraStream(urls.get(i), true, false, i, t, this);
        }

        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {}

        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
            if (stream == null) return true;
            stream.stop(true);
            stream = null;
            return false;
        }

        @Override public void onSurfaceTextureUpdated(SurfaceTexture t) {}

        @Override public void onVideoSize(int width, int height) {
            v.post(() -> fill(v, width, height));
        }

        @Override public void onLive(boolean isLive) {
            v.post(() -> {
                if (stopped || !isLive || live) return;
                live = true;
                removeCallbacks(timeout);
                postDelayed(next, SHOW_MS);   // os 10 s contam daqui
            });
        }

        @Override public void onGaveUp() {
            v.post(() -> {
                if (!stopped && !live) show((i + 1) % urls.size());
            });
        }
    }

    /** A imagem enche a tela (cortada no centro, sem esticar). */
    private static void fill(TextureView v, int videoW, int videoH) {
        float vw = v.getWidth(), vh = v.getHeight();
        if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) return;
        float s = Math.max(vw / videoW, vh / videoH);
        Matrix m = new Matrix();
        m.setScale(s * videoW / vw, s * videoH / vh, vw / 2, vh / 2);
        v.setTransform(m);
    }
}
