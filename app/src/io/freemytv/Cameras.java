package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * The live cameras of a DVR (Config.DVR_CAMERAS, up to 6) on the home screen: a grid of rounded tiles (2×2 for up to
 * 4, 3×2 for 5 or 6), each one a CameraStream drawn on a TextureView. A choice above the grid shows 4 or 6 of them
 * (with more than 4), in their order, or turns them all off (no video work at all; the choice stays to turn them
 * on). They play only while the home screen shows (HomeView
 * resume/pause). The remote focuses a camera or the choice (HomeView's camera zones); OK on a camera opens it full
 * screen (CameraActivity).
 * SLIDE: uma câmera só, no espaço do mosaico inteiro, trocando a cada 10 s (um decodificador só, o que alivia as TVs
 * de 1 GB). Os 10 s contam de quando a imagem aparece. Uma câmera que desistiu (nenhum stream deu imagem) é pulada
 * na hora; uma que demora, depois de 12 s.
 */
final class Cameras extends ViewGroup {
    static final int MAX = 6;
    /** Right after the TV starts, the cameras wait: on a 1 GB projector the boot already takes all four cores. */
    private static final long AFTER_BOOT_MS = 150_000;
    /** A escolha SLIDE (guardada no lugar do número de câmeras), e quanto tempo cada câmera fica nela. */
    static final int SLIDE = -1;
    private static final long SLIDE_MS = 10_000, SLIDE_WAIT_MS = 12_000;

    private final List<String> urls = urls();
    /** How many cameras each choice shows: {4, 6, 0} with more than 4, else {all, 0}; 0 is "Turn off". */
    private final int[] grids;
    /** The 4-or-6 choice, in the home screen's own file: the main process keeps "settings" cached and would write it back. */
    private final android.content.SharedPreferences ui;
    private final TextureView[] views;
    private final CameraStream[] streams;
    private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillText = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF[] pills;
    private final RectF pillBar = new RectF();
    private final String[] pillLabels;
    private float radius, gap, pad, band;     // pad: room for the focused tile to grow; band: the choice's row
    private boolean on;
    private int shown, cols, focused = -1, pillFocused = -1;
    private boolean slide, slideLive;   // slideLive: a câmera do slide já mostrou imagem
    private int slideAt;                // a câmera do slide agora

    /** The cameras in config.properties: rtsp:// addresses, comma separated. */
    static List<String> urls() {
        List<String> out = new ArrayList<>();
        for (String u : Config.DVR_CAMERAS.split(",")) {
            u = u.trim();
            if (u.regionMatches(true, 0, "rtsp://", 0, 7) && out.size() < MAX) out.add(u);
        }
        return out;
    }

    Cameras(Context c) {
        super(c);
        ui = c.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        setWillNotDraw(false);
        tile.setColor(0x59000000);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        pillText.setTypeface(Ui.semibold);
        pillText.setTextAlign(Paint.Align.CENTER);
        views = new TextureView[urls.size()];
        streams = new CameraStream[urls.size()];
        grids = urls.size() > 4 ? new int[] {4, 6, SLIDE, 0}
                : urls.size() > 1 ? new int[] {urls.size(), SLIDE, 0} : new int[] {urls.size(), 0};
        pills = new RectF[grids.length];
        for (int i = 0; i < pills.length; i++) pills[i] = new RectF();
        pillLabels = new String[grids.length];
        ViewOutlineProvider rounded = new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        };
        for (int i = 0; i < views.length; i++) {
            TextureView v = new TextureView(c);
            v.setOutlineProvider(rounded);
            v.setClipToOutline(true);
            v.setSurfaceTextureListener(new Tile(i));
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            views[i] = v;
            addView(v);
        }
        if (views.length == 0) setVisibility(GONE);
        applyGrid();
    }

    boolean any() {
        return views.length > 0;
    }

    /** How many cameras are on screen now. */
    int count() {
        return shown;
    }

    /** A câmera no lugar `slot` do mosaico (no SLIDE, o único lugar mostra a câmera da vez). */
    int cameraAt(int slot) {
        return slide ? slideAt : slot;
    }

    private boolean isShown(int i) {
        return slide ? i == slideAt : i < shown;
    }

    int columns() {
        return cols;
    }

    /** Whether the choice above the grid shows (there are cameras). */
    boolean hasChoice() {
        return views.length > 0;
    }

    int choices() {
        return grids.length;
    }

    /** Which choice is in use (an index of grids). */
    int choice() {
        int saved = ui.getInt("cameraGrid", grids[0]);
        for (int i = 0; i < grids.length; i++) if (grids[i] == saved) return i;
        return 0;
    }

    /** Shows that many cameras, or none: the ones that leave stop, the new ones start. */
    void choose(int choice) {
        ui.edit().putInt("cameraGrid", grids[choice]).apply();
        applyGrid();
        requestLayout();
        invalidate();
    }

    private void applyGrid() {
        slide = grids[choice()] == SLIDE;
        shown = slide ? 1 : Math.min(views.length, grids[choice()]);
        cols = shown <= 1 ? 1 : shown <= 4 ? 2 : 3;
        removeCallbacks(nextSlide);
        removeCallbacks(slideTimeout);
        if (slide) scheduleSlide();
        for (int i = 0; i < views.length; i++) {
            if (isShown(i)) {
                if (views[i].getVisibility() == GONE) views[i].setVisibility(VISIBLE);
                startOne(i);
            } else {
                views[i].setVisibility(GONE);
                if (streams[i] != null) streams[i].stop(false);
                streams[i] = null;
            }
        }
    }

    /** Center of camera `i`, in the parent's coordinates (for moving the focus to the nearest one). */
    float centerX(int slot) {
        int i = cameraAt(slot);
        return getLeft() + (views[i].getLeft() + views[i].getRight()) / 2f;
    }

    /** Center of choice `i`, in the parent's coordinates. */
    float pillCenterX(int i) {
        return getLeft() + pills[i].centerX();
    }

    /** The focused camera grows a little and gets a white ring; the focused choice turns white (-1: none). */
    void setFocus(int camera, int pill) {
        if (pill != pillFocused) {
            pillFocused = pill;
            invalidate();
        }
        if (camera == focused) return;
        focused = camera;
        int view = camera < 0 ? -1 : cameraAt(camera);
        for (int i = 0; i < views.length; i++) {
            float s = i == view ? 1.06f : 1f;
            views[i].animate().scaleX(s).scaleY(s).setDuration(160).setUpdateListener(a -> invalidate()).start();
        }
        invalidate();
    }

    void start() {
        if (on) return;
        long wait = AFTER_BOOT_MS - android.os.SystemClock.elapsedRealtime();
        removeCallbacks(startLater);
        if (wait > 0) {
            postDelayed(startLater, wait);
            return;
        }
        on = true;
        for (int i = 0; i < views.length; i++) if (isShown(i)) startOne(i);
        if (slide) scheduleSlide();
    }

    // MARK: - SLIDE

    private final Runnable nextSlide = () -> advanceSlide();
    /** A câmera da vez ainda sem imagem depois de SLIDE_WAIT_MS: a próxima. */
    private final Runnable slideTimeout = () -> {
        if (!slideLive) advanceSlide();
    };

    private void scheduleSlide() {
        removeCallbacks(nextSlide);
        removeCallbacks(slideTimeout);
        if (!on) return;
        postDelayed(slideTimeout, SLIDE_WAIT_MS);   // e o nextSlide quando a imagem aparecer
    }

    private void advanceSlide() {
        if (!slide || views.length == 0) return;
        int old = slideAt;
        if (streams[old] != null) streams[old].stop(false);
        streams[old] = null;
        views[old].setVisibility(GONE);
        slideAt = (old + 1) % views.length;
        slideLive = false;
        views[slideAt].setVisibility(VISIBLE);
        requestLayout();
        invalidate();
        startOne(slideAt);    // sem textura ainda, o Tile começa quando ela existir
        scheduleSlide();
    }

    private final Runnable startLater = this::start;

    void stop() {
        removeCallbacks(startLater);
        removeCallbacks(nextSlide);
        removeCallbacks(slideTimeout);
        on = false;
        for (int i = 0; i < streams.length; i++) {
            if (streams[i] != null) streams[i].stop(false);
            streams[i] = null;
        }
    }

    private void startOne(int i) {
        SurfaceTexture t = views[i].getSurfaceTexture();
        if (!on || !isShown(i) || t == null || streams[i] != null) return;
        TextureView v = views[i];
        streams[i] = new CameraStream(urls.get(i), i, t, new CameraStream.Listener() {
            @Override public void onVideoSize(int w, int h) { v.post(() -> crop(v, w, h)); }

            // A camera with no picture shows its dark tile, not its last frame. (Hidden only once its texture exists:
            // a TextureView makes its texture when it's first drawn.)
            @Override public void onLive(boolean live) {
                v.post(() -> {
                    if (!isShown(i)) return;
                    v.setVisibility(live ? VISIBLE : INVISIBLE);
                    if (live && slide && i == slideAt && !slideLive) {
                        slideLive = true;
                        removeCallbacks(slideTimeout);
                        postDelayed(nextSlide, SLIDE_MS);
                    }
                });
            }

            // No SLIDE, uma câmera que não conseguiu imagem dá a vez na hora.
            @Override public void onGaveUp() {
                v.post(() -> {
                    if (slide && i == slideAt && !slideLive) advanceSlide();
                });
            }
        });
    }

    /** Fills the tile with the picture (cropped at the center, not stretched). */
    private static void crop(TextureView v, int videoW, int videoH) {
        float vw = v.getWidth(), vh = v.getHeight();
        if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) return;
        float s = Math.max(vw / videoW, vh / videoH);
        Matrix m = new Matrix();
        m.setScale(s * videoW / vw, s * videoH / vh, vw / 2, vh / 2);
        v.setTransform(m);
    }

    private final class Tile implements TextureView.SurfaceTextureListener {
        private final int i;

        Tile(int i) { this.i = i; }

        @Override public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) { startOne(i); }

        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {}

        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
            CameraStream s = streams[i];
            streams[i] = null;
            if (s == null) return true;
            s.stop(true);     // its thread releases the texture once the decoder let go of it
            return false;
        }

        @Override public void onSurfaceTextureUpdated(SurfaceTexture t) {}
    }

    // MARK: - Layout: the choice on top (with more than 4 cameras), then the grid

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float k = width / 1116f;
        radius = 20 * k;
        gap = 22 * k;
        pad = 18 * k;
        band = hasChoice() ? 76 * k : 0;
        ring.setStrokeWidth(5 * k);
        pillText.setTextSize(26 * k);
        int c = Math.max(1, cols), rows = Math.max(1, (shown + c - 1) / c);
        int tw = Math.round((width - 2 * pad - gap * (c - 1)) / c);
        int th = Math.round((height - band - 2 * pad - gap * (rows - 1)) / rows);
        for (TextureView v : views) {
            v.measure(MeasureSpec.makeMeasureSpec(tw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(th, MeasureSpec.EXACTLY));
        }
        if (hasChoice()) {
            float h = 52 * k, x = pad + 6 * k, top = pad / 2 + 6 * k;
            for (int i = 0; i < grids.length; i++) {
                pillLabels[i] = grids[i] == 0 ? Ui.tr("cameras|OFF") : grids[i] == SLIDE ? Ui.tr("cameras|SLIDE")
                        : Ui.trf("cameras|{n} CAM", "n", grids[i]);
                float w = pillText.measureText(pillLabels[i]) + 52 * k;
                pills[i].set(x, top, x + w, top + h);
                x += w + 6 * k;
            }
            pillBar.set(pills[0].left - 6 * k, top - 6 * k, pills[grids.length - 1].right + 6 * k, top + h + 6 * k);
        }
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int c = Math.max(1, cols);
        for (int i = 0; i < views.length; i++) {
            TextureView v = views[i];
            int at = slide ? 0 : i;   // no SLIDE, a câmera da vez fica no único lugar
            int x = Math.round(pad + (at % c) * (v.getMeasuredWidth() + gap));
            int y = Math.round(band + pad + (at / c) * (v.getMeasuredHeight() + gap));
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
            v.invalidateOutline();
        }
    }

    /** Dark tiles under the pictures (what shows while a camera connects, or when it's offline), and the choice. */
    @Override protected void onDraw(Canvas c) {
        for (int i = 0; i < views.length; i++) {
            if (!isShown(i)) continue;
            TextureView v = views[i];
            float s = v.getScaleX(), cx = (v.getLeft() + v.getRight()) / 2f, cy = (v.getTop() + v.getBottom()) / 2f;
            float hw = v.getWidth() * s / 2, hh = v.getHeight() * s / 2;
            c.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh, radius * s, radius * s, tile);
        }
        if (!hasChoice()) return;
        float r = pillBar.height() / 2;
        c.drawRoundRect(pillBar, r, r, tile);
        int chosen = choice();
        for (int i = 0; i < grids.length; i++) {
            RectF p = pills[i];
            float pr = p.height() / 2;
            if (i == pillFocused || i == chosen) {
                tile.setColor(i == pillFocused ? 0xFFFFFFFF : 0x40FFFFFF);
                c.drawRoundRect(p, pr, pr, tile);
                tile.setColor(0x59000000);
            }
            pillText.setColor(i == pillFocused ? 0xFF1C1C1E : i == chosen ? 0xFFFFFFFF : 0xB3FFFFFF);
            c.drawText(pillLabels[i], p.centerX(), p.centerY() + Ui.halfCap(pillText), pillText);
        }
    }

    /** The focused camera's ring, over its picture. */
    @Override protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (focused < 0 || focused >= shown) return;
        TextureView v = views[cameraAt(focused)];
        float s = v.getScaleX(), cx = (v.getLeft() + v.getRight()) / 2f, cy = (v.getTop() + v.getBottom()) / 2f;
        float hw = v.getWidth() * s / 2, hh = v.getHeight() * s / 2, inset = ring.getStrokeWidth() / 2;
        ring.setAlpha(Math.round(255 * Math.min(1, (s - 1) / 0.06f)));
        c.drawRoundRect(cx - hw + inset, cy - hh + inset, cx + hw - inset, cy + hh - inset, radius * s, radius * s, ring);
    }
}
