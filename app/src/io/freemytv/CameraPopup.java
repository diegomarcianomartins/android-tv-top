package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.List;

/**
 * One DVR camera in a small window over everything, other apps included (holding OK on a camera of the home screen).
 * Each hold on the same camera moves it to the next corner (top left, top right, bottom right, bottom left), then
 * closes it; a hold on another camera swaps the picture. Only one at a time. A foreground service, so it stays while
 * another app is open; the home screen's own cameras stop then, this one goes on, on the main stream (sharper). On
 * a TCL it decodes the substream on the processor: those TVs have too few video decoders, and YouTube's picture
 * stayed black next to it.
 */
public final class CameraPopup extends Service {
    private static final String EXTRA_INDEX = "index", CHANNEL = "camera-popup";
    private static final int NOTIFICATION = 7;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private FrameLayout frame;
    private TextureView view;
    private CameraStream stream;
    private int index = -1, corner = -1, tries;
    private static CameraPopup running;     // the one showing, in this process (:home)

    /** Closes the popup, if one is showing (a camera opened full screen: no need for both). Main thread. */
    static void dismiss() {
        if (running != null) running.close();
    }

    /** A hold on camera `index` of the home screen. */
    static void press(Context c, int index) {
        c.startService(new Intent(c, CameraPopup.class).putExtra(EXTRA_INDEX, index));
    }

    @Override public void onCreate() {
        super.onCreate();
        Ui.init(this);
        running = this;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        press(intent.getIntExtra(EXTRA_INDEX, 0));
        return START_NOT_STICKY;
    }

    private void press(int i) {
        List<String> urls = Cameras.urls();
        if (i < 0 || i >= urls.size()) return;
        if (!Settings.canDrawOverlays(this)) {
            // Drawing over other apps is a permission the TV's own ADB gives (HomeMode.allowOverlayNow); then again.
            if (tries++ < 3) {
                ModeJob.schedule(this, ModeJob.OVERLAY, null, 0);
                main.postDelayed(() -> press(i), 2500);
            } else {
                AppLog.w("camera popup: not allowed to draw over other apps");
                if (frame == null) stopSelf();
            }
            return;
        }
        tries = 0;
        if (frame == null) {
            index = i;
            corner = 0;
            show();
        } else if (i == index) {
            if (++corner > 3) {
                close();
                return;
            }
            wm.updateViewLayout(frame, params());
        } else {
            index = i;
            play();
        }
    }

    private void show() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Free My TV", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(Ui.trf("Camera {n}", "n", index + 1))
                .build();
        startForeground(NOTIFICATION, n);
        wm = getSystemService(WindowManager.class);
        frame = new FrameLayout(this);
        float r = radius();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF000000);
        bg.setCornerRadius(r);
        bg.setStroke(Math.max(2, Math.round(r / 8)), 0x8CFFFFFF);
        frame.setBackground(bg);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        frame.setClipToOutline(true);
        wm.addView(frame, params());
        play();
    }

    /** A new TextureView for every camera: the stream that's ending lets go of its own one. */
    private void play() {
        if (stream != null) stream.stop(true);
        stream = null;
        if (view != null) frame.removeView(view);
        TextureView v = new TextureView(this);
        view = v;
        String url = Cameras.urls().get(index);
        int i = index;
        v.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            private CameraStream mine;

            @Override public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                if (v != view) return;
                mine = new CameraStream(url, !RemoteKeys.TCL, RemoteKeys.TCL, i, t, new CameraStream.Listener() {
                    @Override public void onVideoSize(int vw, int vh) { v.post(() -> fill(v, vw, vh)); }

                    @Override public void onLive(boolean live) {}
                });
                stream = mine;
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {}

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                if (mine == null) return true;
                mine.stop(true);      // its thread releases the texture once the decoder let go of it
                if (stream == mine) stream = null;
                return false;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture t) {}
        });
        int inset = Math.max(2, Math.round(radius() / 8));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        lp.setMargins(inset, inset, inset, inset);
        frame.addView(v, lp);
    }

    /** Fills the window with the picture (cropped at the center, not stretched). */
    private static void fill(TextureView v, int videoW, int videoH) {
        float vw = v.getWidth(), vh = v.getHeight();
        if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) return;
        float s = Math.max(vw / videoW, vh / videoH);
        Matrix m = new Matrix();
        m.setScale(s * videoW / vw, s * videoH / vh, vw / 2, vh / 2);
        v.setTransform(m);
    }

    private float scale() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        return Math.max(m.widthPixels, m.heightPixels) / 1920f;
    }

    private float radius() {
        return 20 * scale();
    }

    /** 16:9, a little over a quarter of the screen's width, in `corner` (0 top left, then clockwise). */
    private WindowManager.LayoutParams params() {
        float k = scale();
        int w = Math.round(560 * k), h = Math.round(315 * k), margin = Math.round(40 * k);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Not touchable would cap it at 80% opacity (Android 12's rule for overlays that let touches through);
                // a TV has no touch screen, so it stays touchable and solid. Never focusable: the keys go to the app.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = (corner == 0 || corner == 3 ? Gravity.START : Gravity.END)
                | (corner <= 1 ? Gravity.TOP : Gravity.BOTTOM);
        p.x = margin;
        p.y = margin;
        p.setTitle("Free My TV camera");
        return p;
    }

    private void close() {
        if (stream != null) stream.stop(true);
        stream = null;
        if (frame != null) {
            frame.removeAllViews();
            wm.removeView(frame);
        }
        frame = null;
        view = null;
        index = corner = -1;
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (running == this) running = null;
        if (frame != null) close();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
