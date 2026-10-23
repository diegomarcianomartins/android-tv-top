package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

/**
 * One DVR camera full screen (OK on a camera of the home screen). ← and → go to the previous and next camera, Back
 * returns. It asks for the camera's main stream (sharper) where the address says which one it is, then the substream
 * when the main one doesn't play.
 * Um botão do controle definido como Câmeras (Botões do Controle) abre a primeira câmera de qualquer lugar; apertado de
 * novo aqui, a câmera da tela continua na janela flutuante e o app que estava aberto antes volta. Fica numa tarefa
 * própria, para que esse app esteja logo atrás dela.
 */
public final class CameraActivity extends Activity {
    private static final String EXTRA_INDEX = "index";
    /** O botão do controle definido como Câmeras (RemoteKeys, no processo :keys). */
    static final String ACTION_KEY = "io.freemytv.action.CAMERA_KEY";

    private List<String> urls;
    private int index;
    private FrameLayout root;
    private TextureView view;
    private Tile tile;
    private TextView title, status;
    private boolean resumed, visible;

    static void open(Context c, int index) {
        Intent i = new Intent(c, CameraActivity.class).putExtra(EXTRA_INDEX, index);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    /** The main stream of the same camera: Intelbras and Dahua "subtype=1" → 0, Hikvision "Channels/102" → 101. */
    static String mainStream(String url) {
        return url.replace("subtype=1", "subtype=0").replaceAll("(?i)(/Channels/\\d+)02\\b", "$101");
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        CameraPopup.dismiss();     // full screen replaces the popup
        urls = Cameras.urls();
        if (urls.isEmpty()) {
            finish();
            return;
        }
        index = clamp(getIntent().getIntExtra(EXTRA_INDEX, 0));
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        title = label(36 * k, Gravity.TOP | Gravity.START, Math.round(64 * k));
        status = label(34 * k, Gravity.CENTER, 0);
        setContentView(root);
    }

    private TextView label(float size, int gravity, int margin) {
        TextView t = new TextView(this);
        t.setTextColor(0xF2FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setTypeface(Ui.semibold);
        t.setShadowLayer(size / 4, 0, size / 16, 0x99000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, gravity);
        lp.setMargins(margin, margin, margin, margin);
        root.addView(t, lp);
        return t;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Com a tela à mostra, o Android pausa a activity antes de entregar o intent (onPause, onNewIntent,
        // onResume): por isso "visível" (onStart/onStop) e não "em primeiro plano".
        if (!visible || urls == null) {
            if (urls != null && intent.hasExtra(EXTRA_INDEX)) index = clamp(intent.getIntExtra(EXTRA_INDEX, 0));
            return;
        }
        if (ACTION_KEY.equals(intent.getAction())) {
            // O botão de novo: esta câmera vai para a janela flutuante, e volta o app que estava aberto.
            int i = index;
            drop();
            CameraPopup.press(this, i);
            finish();
        } else if (intent.hasExtra(EXTRA_INDEX)) {
            index = clamp(intent.getIntExtra(EXTRA_INDEX, 0));     // o onResume logo depois mostra esta
        }
    }

    private int clamp(int i) {
        return Math.max(0, Math.min(urls.size() - 1, i));
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override protected void onStop() {
        super.onStop();
        visible = false;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        show(index);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        drop();
    }

    /** A new TextureView for every camera: the stream that's ending lets go of its own one. */
    private void show(int i) {
        drop();
        index = i;
        title.setText(urls.size() > 1 ? Ui.trf("Camera {n} of {count}", "n", i + 1, "count", urls.size())
                : Ui.trf("Camera {n}", "n", i + 1));
        status.setText(Ui.tr("Connecting…"));
        status.setVisibility(View.VISIBLE);
        title.setAlpha(1);
        title.animate().alpha(0).setStartDelay(4000).setDuration(600).start();
        TextureView v = new TextureView(this);
        view = v;
        tile = new Tile(v);
        v.setSurfaceTextureListener(tile);
        root.addView(v, 0, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** Stops the camera on screen and takes its view away. */
    private void drop() {
        if (tile != null) tile.stop();
        tile = null;
        TextureView v = view;
        view = null;
        if (v != null) root.removeView(v);
    }

    /** One camera's view and stream; the stream's thread releases the texture once the decoder let go of it. */
    private final class Tile implements TextureView.SurfaceTextureListener, CameraStream.Listener {
        private final TextureView v;
        private CameraStream stream;
        private boolean stopped;

        Tile(TextureView v) { this.v = v; }

        void stop() {
            stopped = true;
            if (stream != null) stream.stop(true);
        }

        @Override public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
            if (stopped || !resumed) return;
            stream = new CameraStream(urls.get(index), true, false, index, t, this);
        }

        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {}

        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
            if (stream == null) return true;
            if (!stopped) stream.stop(true);
            stopped = true;
            return false;
        }

        @Override public void onSurfaceTextureUpdated(SurfaceTexture t) {}

        @Override public void onVideoSize(int vw, int vh) { v.post(() -> fit(v, vw, vh)); }

        @Override public void onLive(boolean live) {
            v.post(() -> {
                if (stopped) return;
                status.setText(Ui.tr("No picture right now"));
                status.setVisibility(live ? View.GONE : View.VISIBLE);
                v.setVisibility(live ? View.VISIBLE : View.INVISIBLE);
            });
        }
    }

    /** The whole picture on screen, in its own proportions (black bars if they differ). */
    private static void fit(TextureView v, int videoW, int videoH) {
        float vw = v.getWidth(), vh = v.getHeight();
        if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) return;
        float s = Math.min(vw / videoW, vh / videoH);
        Matrix m = new Matrix();
        m.setScale(s * videoW / vw, s * videoH / vh, vw / 2, vh / 2);
        v.setTransform(m);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (urls.size() > 1 && (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT)) {
            int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            show((index + dir + urls.size()) % urls.size());
            return true;
        }
        return super.onKeyDown(code, e);
    }
}
