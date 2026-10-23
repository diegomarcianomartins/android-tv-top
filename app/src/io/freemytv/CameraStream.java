package io.freemytv;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Base64;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One live camera of a DVR or IP camera (Config.DVR_CAMERAS), drawn on a Surface: its own RTSP client (RTP inside
 * the RTSP connection, over TCP), with each H.264 or H.265 frame handed straight to the TV's hardware decoder.
 * Android's MediaPlayer plays RTSP like a movie: it pauses to buffer every few frames (an Amlogic projector stalled
 * 124 times in 15 s). Runs on its own thread until stopped. A substream that doesn't answer (turned off in the DVR)
 * is followed by the camera's main stream right away. A camera that played well for a minute and dropped is tried
 * again in 5 s; one that doesn't answer or fails quickly, only after 5 minutes (no work spent on a dead camera).
 */
final class CameraStream implements Runnable {
    interface Listener {
        /** The picture's size, once the decoder knows it (on the camera's thread). */
        void onVideoSize(int width, int height);

        /** Pictures are coming (true), or the camera stopped sending them (false). On the camera's thread. */
        void onLive(boolean live);
    }

    private static final long RETRY_MS = 5000, DEAD_RETRY_MS = 5 * 60_000, GOOD_MS = 60_000;

    private final String[] urls;        // the address, then its other stream (main or sub)
    private final String label;
    private String user, password, host, uri;
    private int port;
    private final SurfaceTexture texture;
    private final Listener listener;
    private final Thread thread;
    private volatile boolean running = true, releaseTexture;
    private volatile Socket socket;

    // RTSP
    private DataInputStream in;
    private OutputStream out;
    private int cseq;
    private String session, authHeader, realm, nonce;
    private boolean digest;
    private final List<String> challenges = new ArrayList<>();

    // Video
    private boolean hevc;
    private byte[] vps, sps, pps;
    private MediaCodec codec;
    private Surface surface;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private byte[] frame = new byte[256 * 1024], packet = new byte[64 * 1024];
    private int frameLen, nalStart = -1;
    private long frameTs = -1;
    private boolean frameKey, fuActive, waitKey = true, live;
    private long liveAt;

    CameraStream(String url, int index, SurfaceTexture texture, Listener listener) {
        this(url, false, index, texture, listener);
    }

    /**
     * `url`: rtsp://user:password@host:port/path (a substream). The password never goes to the log. With `preferMain`
     * the main stream is tried first and the substream follows it (a TV with no H.265 decoder: a TCL on Android 9).
     */
    CameraStream(String url, boolean preferMain, int index, SurfaceTexture texture, Listener listener) {
        String main = CameraActivity.mainStream(url);
        urls = main.equals(url) ? new String[] {url} : preferMain ? new String[] {main, url} : new String[] {url, main};
        label = "camera " + (index + 1);
        this.texture = texture;
        this.listener = listener;
        thread = new Thread(this, "FreeMyTV " + label);
        thread.start();
    }

    private void use(String url) {
        String rest = url.substring(url.indexOf("://") + 3);
        int at = rest.lastIndexOf('@');     // the last one: a password may hold '@' or '/'
        String auth = at >= 0 ? rest.substring(0, at) : "";
        rest = rest.substring(at + 1);
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        int colon = auth.indexOf(':');
        user = colon < 0 ? auth : auth.substring(0, colon);
        password = colon < 0 ? "" : auth.substring(colon + 1);
        int pc = hostPort.lastIndexOf(':');
        host = pc < 0 ? hostPort : hostPort.substring(0, pc);
        port = pc < 0 ? 554 : parsePort(hostPort.substring(pc + 1));
        uri = "rtsp://" + hostPort + (slash < 0 ? "/" : rest.substring(slash));
    }

    private static int parsePort(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 554; }
    }

    /** Stops the camera; with `releaseTexture`, its SurfaceTexture is released too once the decoder let go of it. */
    void stop(boolean releaseTexture) {
        this.releaseTexture = releaseTexture;
        running = false;
        Socket s = socket;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
        thread.interrupt();
    }

    @Override public void run() {
        surface = new Surface(texture);
        int which = 0, failed = 0;
        while (running) {
            use(urls[which]);
            try {
                play();
            } catch (IOException | RuntimeException e) {
                if (running) AppLog.w(label + ": " + e.getMessage());
            } finally {
                close();
            }
            boolean wasLive = live, good = live && System.currentTimeMillis() - liveAt >= GOOD_MS;
            live = false;
            if (!running) break;
            listener.onLive(false);
            if (!good && !wasLive && ++failed < urls.length) {
                which = (which + 1) % urls.length;      // the other stream, right away
                continue;
            }
            failed = 0;
            if (!good) {
                which = 0;
                AppLog.w(label + ": no picture, trying again in 5 minutes");
            }
            try {
                Thread.sleep(good ? RETRY_MS : DEAD_RETRY_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
        surface.release();
        if (releaseTexture) texture.release();
    }

    private void close() {
        Socket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
        if (codec != null) {
            try { codec.stop(); } catch (RuntimeException ignored) {}
            codec.release();
            codec = null;
        }
        session = null;
        authHeader = null;
        frameLen = 0;
        nalStart = -1;
        frameTs = -1;
        fuActive = false;
        waitKey = true;
    }

    // MARK: - RTSP

    private void play() throws IOException {
        hevc = false;
        vps = sps = pps = null;
        cseq = 0;
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setSoTimeout(15000);
        s.setTcpNoDelay(true);
        socket = s;
        if (!running) return;
        in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
        out = s.getOutputStream();

        Response r = request("DESCRIBE", uri, "Accept: application/sdp\r\n");
        String base = r.header("content-base");
        if (base == null) base = uri;
        String track = describe(r.body, base);
        r = request("SETUP", track, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
        String sess = r.header("session");
        if (sess == null) throw new IOException("no session");
        int semi = sess.indexOf(';');
        session = (semi < 0 ? sess : sess.substring(0, semi)).trim();
        long keepAliveMs = 25_000;
        int t = sess.indexOf("timeout=");
        if (t >= 0) {
            try { keepAliveMs = Math.max(5, Integer.parseInt(sess.substring(t + 8).replaceAll("\\D.*", ""))) * 500L; }
            catch (NumberFormatException ignored) {}
        }
        request("PLAY", base, "Range: npt=0.000-\r\n");
        AppLog.w(label + ": playing " + (hevc ? "H.265" : "H.264"));

        long keptAt = System.currentTimeMillis();
        while (running) {
            int b = in.read();
            if (b < 0) throw new EOFException("the camera closed the connection");
            if (b == '$') {
                int channel = in.readUnsignedByte(), len = in.readUnsignedShort();
                if (len > packet.length) packet = new byte[len];
                in.readFully(packet, 0, len);
                if (channel == 0) rtp(packet, len);
            } else if (b == 'R') {
                readResponse(b);    // the answer to a keep-alive
            }
            long now = System.currentTimeMillis();
            if (now - keptAt > keepAliveMs) {
                keptAt = now;
                send("GET_PARAMETER", base, "");
            }
        }
    }

    private static final class Response {
        int status;
        final Map<String, String> headers = new HashMap<>();
        String body = "";

        String header(String name) { return headers.get(name); }
    }

    /** Sends a request and reads its answer (once more with the credentials, if the camera asks for them). */
    private Response request(String method, String target, String extra) throws IOException {
        send(method, target, extra);
        Response r = readResponse(-1);
        if (r.status == 401 && authHeader == null && !user.isEmpty()) {
            setUpAuth();
            send(method, target, extra);
            r = readResponse(-1);
        }
        if (r.status != 200) throw new IOException(method + " answered " + r.status);
        return r;
    }

    private void send(String method, String target, String extra) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(target).append(" RTSP/1.0\r\n");
        sb.append("CSeq: ").append(++cseq).append("\r\n");
        sb.append("User-Agent: FreeMyTV\r\n");
        if (authHeader != null) sb.append("Authorization: ").append(authorization(method, target)).append("\r\n");
        if (session != null) sb.append("Session: ").append(session).append("\r\n");
        sb.append(extra).append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Reads an RTSP answer; `first` is its first byte when the caller already read it (-1: not yet). */
    private Response readResponse(int first) throws IOException {
        Response r = new Response();
        challenges.clear();
        String line = readLine(first);
        // Video still on its way in (before the PLAY answer): skip whole RTP packets.
        while (line == null) {
            int len = in.readUnsignedShort();
            in.skipBytes(len);
            line = readLine(-1);
        }
        String[] parts = line.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("RTSP/")) throw new IOException("not an RTSP answer");
        try { r.status = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { throw new IOException("bad status"); }
        for (String h = readLine(-1); h != null && !h.isEmpty(); h = readLine(-1)) {
            int colon = h.indexOf(':');
            if (colon <= 0) continue;
            String name = h.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = h.substring(colon + 1).trim();
            if (name.equals("www-authenticate")) challenges.add(value);
            r.headers.put(name, value);
        }
        String length = r.header("content-length");
        if (length != null) {
            byte[] body = new byte[Math.max(0, Math.min(65536, Integer.parseInt(length.trim())))];
            in.readFully(body);
            r.body = new String(body, StandardCharsets.UTF_8);
        }
        return r;
    }

    /** One text line without its line break; null when an RTP packet ('$' and its channel) starts instead. */
    private String readLine(int first) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b = first >= 0 ? first : in.read();
        if (b == '$') {
            in.readUnsignedByte();
            return null;
        }
        while (b >= 0 && b != '\n') {
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 4096) throw new IOException("line too long");
            b = in.read();
        }
        if (b < 0) throw new EOFException("the camera closed the connection");
        return sb.toString();
    }

    // MARK: - Credentials (Digest, as DVRs ask; Basic otherwise)

    private void setUpAuth() throws IOException {
        String chosen = null;
        for (String c : challenges) if (c.regionMatches(true, 0, "Digest", 0, 6)) chosen = c;
        if (chosen == null) for (String c : challenges) if (c.regionMatches(true, 0, "Basic", 0, 5)) chosen = c;
        if (chosen == null) throw new IOException("the camera asked for an unknown kind of password");
        digest = chosen.regionMatches(true, 0, "Digest", 0, 6);
        realm = param(chosen, "realm");
        nonce = param(chosen, "nonce");
        authHeader = chosen;
    }

    private String authorization(String method, String target) {
        if (!digest) {
            return "Basic " + Base64.encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        }
        String ha1 = md5(user + ":" + realm + ":" + password), ha2 = md5(method + ":" + target);
        return "Digest username=\"" + user + "\", realm=\"" + realm + "\", nonce=\"" + nonce + "\", uri=\"" + target
                + "\", response=\"" + md5(ha1 + ":" + nonce + ":" + ha2) + "\"";
    }

    private static String param(String header, String name) {
        int i = header.toLowerCase(Locale.ROOT).indexOf(name + "=");
        if (i < 0) return "";
        i += name.length() + 1;
        if (i < header.length() && header.charAt(i) == '"') {
            int end = header.indexOf('"', i + 1);
            return end < 0 ? header.substring(i + 1) : header.substring(i + 1, end);
        }
        int end = header.indexOf(',', i);
        return (end < 0 ? header.substring(i) : header.substring(i, end)).trim();
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // MARK: - SDP: the video track, its codec and parameter sets

    /** Returns the video track's address. */
    private String describe(String sdp, String base) throws IOException {
        boolean video = false, found = false;
        String control = null;
        for (String raw : sdp.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("m=")) {
                if (found) break;
                video = line.startsWith("m=video");
                found = video;
                continue;
            }
            if (!video) continue;
            if (line.startsWith("a=control:")) {
                control = line.substring(10).trim();
            } else if (line.startsWith("a=rtpmap:")) {
                String up = line.toUpperCase(Locale.ROOT);
                if (up.contains("H265") || up.contains("HEVC")) hevc = true;
                else if (!up.contains("H264")) throw new IOException("the camera sends " + line.substring(line.indexOf(' ') + 1) + ", not H.264 or H.265");
            } else if (line.startsWith("a=fmtp:")) {
                String sprop = fmtp(line, "sprop-parameter-sets");
                if (!sprop.isEmpty()) {
                    String[] sets = sprop.split(",");
                    if (sets.length >= 2) {
                        sps = decode(sets[0]);
                        pps = decode(sets[1]);
                    }
                }
                String v = fmtp(line, "sprop-vps"), s = fmtp(line, "sprop-sps"), p = fmtp(line, "sprop-pps");
                if (!v.isEmpty()) vps = decode(v);
                if (!s.isEmpty()) sps = decode(s);
                if (!p.isEmpty()) pps = decode(p);
            }
        }
        if (!found) throw new IOException("the camera has no video");
        if (control == null || control.equals("*")) return base;
        if (control.regionMatches(true, 0, "rtsp://", 0, 7)) return control;
        return base.endsWith("/") ? base + control : base + "/" + control;
    }

    private static String fmtp(String line, String name) {
        int i = line.indexOf(name + "=");
        if (i < 0) return "";
        i += name.length() + 1;
        int end = line.indexOf(';', i);
        return (end < 0 ? line.substring(i) : line.substring(i, end)).trim();
    }

    private static byte[] decode(String b64) {
        try { return Base64.decode(b64.trim(), Base64.DEFAULT); } catch (IllegalArgumentException e) { return null; }
    }

    // MARK: - RTP → whole frames (Annex B: each NAL unit after 00 00 00 01)

    private void rtp(byte[] p, int len) throws IOException {
        if (len < 12 || (p[0] & 0xC0) != 0x80) return;
        int off = 12 + 4 * (p[0] & 0x0F);
        if ((p[0] & 0x10) != 0) {
            if (off + 4 > len) return;
            off += 4 + 4 * (((p[off + 2] & 0xFF) << 8) | (p[off + 3] & 0xFF));
        }
        int end = (p[0] & 0x20) != 0 ? len - (p[len - 1] & 0xFF) : len;
        if (off >= end) return;
        long ts = ((p[4] & 0xFFL) << 24) | ((p[5] & 0xFF) << 16) | ((p[6] & 0xFF) << 8) | (p[7] & 0xFF);
        if (ts != frameTs && frameLen > 0) flush();
        frameTs = ts;
        if (hevc) hevcPayload(p, off, end); else avcPayload(p, off, end);
        if ((p[1] & 0x80) != 0) flush();    // the marker bit: the frame's last packet
    }

    private void avcPayload(byte[] p, int off, int end) {
        int type = p[off] & 0x1F;
        if (type >= 1 && type <= 23) {
            nal(p, off, end - off);
        } else if (type == 24 && end - off > 1) {        // STAP-A: several NAL units
            for (int i = off + 1; i + 2 <= end; ) {
                int n = ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
                i += 2;
                if (n == 0 || i + n > end) break;
                nal(p, i, n);
                i += n;
            }
        } else if (type == 28 && end - off > 2) {        // FU-A: one NAL unit in pieces
            int fu = p[off + 1] & 0xFF;
            if ((fu & 0x80) != 0) {
                startNal();
                put((byte) ((p[off] & 0xE0) | (fu & 0x1F)));
                fuActive = true;
            }
            if (!fuActive) return;
            put(p, off + 2, end - off - 2);
            if ((fu & 0x40) != 0) {
                fuActive = false;
                endNal();
            }
        }
    }

    private void hevcPayload(byte[] p, int off, int end) {
        if (end - off < 3) return;
        int type = (p[off] >> 1) & 0x3F;
        if (type < 48) {
            nal(p, off, end - off);
        } else if (type == 48) {                          // aggregation packet
            for (int i = off + 2; i + 2 <= end; ) {
                int n = ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
                i += 2;
                if (n == 0 || i + n > end) break;
                nal(p, i, n);
                i += n;
            }
        } else if (type == 49) {                          // fragmentation unit
            int fu = p[off + 2] & 0xFF;
            if ((fu & 0x80) != 0) {
                startNal();
                put((byte) ((p[off] & 0x81) | ((fu & 0x3F) << 1)));
                put(p[off + 1]);
                fuActive = true;
            }
            if (!fuActive) return;
            put(p, off + 3, end - off - 3);
            if ((fu & 0x40) != 0) {
                fuActive = false;
                endNal();
            }
        }
    }

    private void nal(byte[] p, int off, int n) {
        startNal();
        put(p, off, n);
        endNal();
    }

    private void startNal() {
        put((byte) 0); put((byte) 0); put((byte) 0); put((byte) 1);
        nalStart = frameLen;
    }

    /** A whole NAL unit is in the frame: note keyframes, keep the parameter sets the decoder starts with. */
    private void endNal() {
        if (nalStart < 0 || nalStart >= frameLen) return;
        int n = frameLen - nalStart;
        if (hevc) {
            int type = (frame[nalStart] >> 1) & 0x3F;
            if (type >= 16 && type <= 21) frameKey = true;
            else if (type == 32) vps = copy(nalStart, n);
            else if (type == 33) sps = copy(nalStart, n);
            else if (type == 34) pps = copy(nalStart, n);
        } else {
            int type = frame[nalStart] & 0x1F;
            if (type == 5) frameKey = true;
            else if (type == 7) sps = copy(nalStart, n);
            else if (type == 8) pps = copy(nalStart, n);
        }
        nalStart = -1;
    }

    private byte[] copy(int off, int n) {
        byte[] b = new byte[n];
        System.arraycopy(frame, off, b, 0, n);
        return b;
    }

    private void put(byte b) {
        if (frameLen == frame.length) grow(1);
        frame[frameLen++] = b;
    }

    private void put(byte[] p, int off, int n) {
        if (n <= 0) return;
        if (frameLen + n > frame.length) grow(n);
        System.arraycopy(p, off, frame, frameLen, n);
        frameLen += n;
    }

    private void grow(int need) {
        if (frame.length >= 4 * 1024 * 1024) throw new IllegalStateException("frame too large");
        byte[] b = new byte[Math.max(frame.length * 2, frameLen + need)];
        System.arraycopy(frame, 0, b, 0, frameLen);
        frame = b;
    }

    // MARK: - Decoder

    /** Hands the finished frame to the decoder (after the first keyframe), then shows what it decoded. */
    private void flush() throws IOException {
        int len = frameLen;
        boolean key = frameKey;
        frameLen = 0;
        frameKey = false;
        nalStart = -1;
        fuActive = false;
        if (len == 0) return;
        if (codec == null) {
            if (!key || sps == null || pps == null || (hevc && vps == null)) return;
            startDecoder();
        }
        if (waitKey && !key) return;     // after a lost frame, the next ones are broken until a keyframe
        int index;
        try {
            index = codec.dequeueInputBuffer(20_000);
            if (index < 0) {
                waitKey = true;
                drain();
                return;
            }
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null || buffer.capacity() < len) {
                codec.queueInputBuffer(index, 0, 0, 0, 0);
                waitKey = true;
                return;
            }
            buffer.clear();
            buffer.put(frame, 0, len);
            codec.queueInputBuffer(index, 0, len, System.nanoTime() / 1000, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            waitKey = false;
            drain();
        } catch (IllegalStateException e) {
            throw new IOException("decoder: " + e.getMessage());
        }
    }

    private void startDecoder() throws IOException {
        String mime = hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat f = MediaFormat.createVideoFormat(mime, 1280, 720);
        if (hevc) {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(annexB(vps, sps, pps)));
        } else {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(annexB(sps)));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(annexB(pps)));
        }
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        try {
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(f, surface, null, 0);
            codec.start();
        } catch (IOException | RuntimeException e) {
            if (codec != null) codec.release();
            codec = null;
            throw new IOException("no decoder for " + mime + ": " + e.getMessage());
        }
    }

    private static byte[] annexB(byte[]... units) {
        int n = 0;
        for (byte[] u : units) n += 4 + u.length;
        byte[] out = new byte[n];
        int i = 0;
        for (byte[] u : units) {
            out[i + 3] = 1;
            System.arraycopy(u, 0, out, i + 4, u.length);
            i += 4 + u.length;
        }
        return out;
    }

    /** Shows every decoded frame right away (live: no waiting for its timestamp). */
    private void drain() {
        while (true) {
            int index = codec.dequeueOutputBuffer(info, 0);
            if (index >= 0) {
                codec.releaseOutputBuffer(index, true);
                if (!live) {
                    live = true;
                    liveAt = System.currentTimeMillis();
                    listener.onLive(true);
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
                if (f.containsKey("crop-right") && f.containsKey("crop-left")) w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
                if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
                listener.onVideoSize(w, h);
            } else if (index != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                return;
            }
        }
    }
}
