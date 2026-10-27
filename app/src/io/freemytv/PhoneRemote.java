package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Controle pelo celular: um servidor HTTP minúsculo, só na rede local, que serve uma página de controle remoto (o QR
 * em Configurações → Home Settings leva até ela). Só roda com o controle ligado (Prefs.phoneRemote), num serviço em
 * primeiro plano no processo principal.
 * Nada do celular vira comando: as teclas vêm de uma lista fixa (KEYS) e os apps do índice da barra de apps. Cada
 * pedido leva o código do QR, e quem erra muitas vezes é recusado por um tempo.
 */
public final class PhoneRemote extends Service {
    static final int PORT = 8765;
    private static final String CHANNEL = "phone-remote";
    private static final int NOTIFICATION = 9;

    /** Nome na página → código de tecla do Android. */
    private static final String[][] KEYS = {
            {"up", "19"}, {"down", "20"}, {"left", "21"}, {"right", "22"}, {"ok", "23"}, {"back", "4"}, {"home", "3"},
            {"menu", "82"}, {"volup", "24"}, {"voldown", "25"}, {"mute", "164"}};
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_.]+"),
            COMPONENT = Pattern.compile("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+");
    private static final int MAX_FAILURES = 20;
    private static final long FAILURE_WINDOW_MS = 60_000;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Object adbLock = new Object();
    private Adb adb;
    private Adb.Stream injector;      // KeyInjector, vivo na TV (adbLock)
    private boolean injectorBroken;   // não funcionou nesta TV: as teclas vão pelo `input keyevent`
    private boolean askedApproval;
    private volatile boolean running;
    private volatile ServerSocket server;
    private int failures;
    private long failuresSince;

    /** Liga ou desliga o serviço conforme a escolha guardada (as configurações, o boot e a atualização do app). */
    static void sync(Context c) {
        Intent i = new Intent(c, PhoneRemote.class);
        if (!Prefs.phoneRemote()) {
            c.stopService(i);
            return;
        }
        try {
            c.startForegroundService(i);
            AppLog.w("phone remote on");
        } catch (RuntimeException e) {
            AppLog.w("phone remote: " + e.getMessage());
        }
    }

    static void set(Context c, boolean on) {
        Prefs.setPhoneRemote(on);
        sync(c);
    }

    /** O IPv4 da TV na rede local (Wi-Fi ou cabo), ou null sem rede. */
    static String address() {
        try {
            String other = null;
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback() || n.isVirtual()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (n.getName().startsWith("wlan") || n.getName().startsWith("eth")) return a.getHostAddress();
                    if (other == null) other = a.getHostAddress();
                }
            }
            return other;
        } catch (SocketException e) {
            return null;
        }
    }

    /** O endereço que o QR leva, ou null com o controle desligado ou sem rede. */
    static String url() {
        String ip = address(), code = Prefs.phoneRemoteCode();
        return ip == null || code.isEmpty() ? null : "http://" + ip + ":" + PORT + "/?k=" + code;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Ui.init(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Free My TV", NotificationManager.IMPORTANCE_MIN));
        startForeground(NOTIFICATION, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(Ui.tr("Phone Remote"))
                .build());
        if (!running) {
            running = true;
            Thread t = new Thread(this::loop, "FreeMyTV phone remote");
            t.setDaemon(true);
            t.start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        ServerSocket s = server;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
        synchronized (adbLock) {
            reset();
        }
        AppLog.w("phone remote off");
        super.onDestroy();
    }

    private void loop() {
        while (running) {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                server = s;
                while (running) {
                    Socket client = s.accept();
                    pool.execute(() -> serve(client));
                }
            } catch (IOException e) {
                if (!running) return;
                AppLog.w("phone remote: " + e.getMessage());
                try { Thread.sleep(10_000); } catch (InterruptedException ex) { return; }
            } catch (RuntimeException e) {
                return;   // o pool foi desligado
            }
        }
    }

    // MARK: - HTTP

    private void serve(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(5000);
            BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = r.readLine();
            if (line == null) return;
            int size = line.length();
            for (String h = r.readLine(); h != null && !h.isEmpty(); h = r.readLine()) {
                size += h.length();
                if (size > 8192) {
                    reply(socket, 431, "text/plain", "too large");
                    return;
                }
            }
            String[] parts = line.split(" ");
            if (parts.length < 2 || !parts[0].equals("GET")) {
                reply(socket, 405, "text/plain", "GET only");
                return;
            }
            String path = parts[1];
            Map<String, String> query = new HashMap<>();
            int q = path.indexOf('?');
            if (q >= 0) {
                for (String pair : path.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) query.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                }
                path = path.substring(0, q);
            }
            if (!path.equals("/") && !path.equals("/key") && !path.equals("/app")) {
                reply(socket, 404, "text/plain", "not found");
                return;
            }
            if (!allowed(query.get("k"))) {
                reply(socket, 403, "text/html", notice(Ui.tr("Scan the QR code on the TV again.")));
                return;
            }
            if (path.equals("/")) {
                reply(socket, 200, "text/html", page(query.get("k")));
                return;
            }
            try {
                if (path.equals("/key")) press(query.get("n")); else open(query.get("i"));
                reply(socket, 200, "text/plain", "ok");
            } catch (IOException | IllegalArgumentException e) {
                reply(socket, 503, "text/plain", String.valueOf(e.getMessage()));
            }
        } catch (IOException ignored) {
        }
    }

    /** O código certo, e sem passar de MAX_FAILURES erros por minuto (de qualquer um). */
    private boolean allowed(String given) {
        String code = Prefs.phoneRemoteCode();
        boolean ok = !code.isEmpty() && given != null
                && MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (now - failuresSince > FAILURE_WINDOW_MS) {
                failuresSince = now;
                failures = 0;
            }
            if (failures >= MAX_FAILURES) return false;
            if (!ok) failures++;
        }
        return ok;
    }

    private static void reply(Socket s, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\nContent-Type: " + type
                + "; charset=utf-8\r\nContent-Length: " + bytes.length
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    // MARK: - Ações

    private void press(String name) throws IOException {
        for (String[] k : KEYS) {
            if (k[0].equals(name)) {
                inject(k[1]);
                return;
            }
        }
        throw new IllegalArgumentException("?");
    }

    /** Abre o app de um lugar da barra de apps: o índice, nunca um nome de pacote vindo do celular. */
    private void open(String index) throws IOException {
        List<String[]> shelf = HomeApps.shelfNames(this);
        int i;
        try {
            i = Integer.parseInt(index);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("?");
        }
        if (i < 0 || i >= shelf.size() || !PACKAGE.matcher(shelf.get(i)[0]).matches()) throw new IllegalArgumentException("?");
        String pkg = shelf.get(i)[0];
        // Pelo componente exato: uma intent com só o pacote exige a categoria DEFAULT, que as telas de lançamento não têm.
        Intent launch = HomeApps.launchIntent(getPackageManager(), pkg);
        if (launch == null || launch.getComponent() == null) throw new IllegalArgumentException("?");
        String component = launch.getComponent().flattenToShortString();
        if (!COMPONENT.matcher(component).matches()) throw new IllegalArgumentException("?");
        run("am start -n " + component);
        AppLog.w("phone remote -> " + pkg);
    }

    private Adb connection() throws IOException {
        if (adb == null) adb = Adb.connect(this, 0, null);
        return adb;
    }

    private void reset() {
        if (adb != null) adb.close();
        adb = null;
        injector = null;
    }

    /**
     * A tecla pelo KeyInjector, um processo que fica vivo na TV (o `input keyevent` sobe uma JVM por tecla: perto de 2 s
     * na UnionTV). Se ele não funcionar nesta TV, cai para o `input keyevent`.
     */
    private void inject(String code) throws IOException {
        synchronized (adbLock) {
            if (!injectorBroken) {
                try {
                    if (injector == null) {
                        injector = connection().stream("CLASSPATH=$(pm path " + getPackageName()
                                + " | head -1 | cut -d: -f2) app_process /system/bin io.freemytv.KeyInjector");
                    }
                    injector.write(code);
                    String printed = injector.output();
                    if (!printed.contains("Exception") && !printed.contains("Error")) return;
                    injectorBroken = true;
                    AppLog.w("phone remote: key injector failed, using input keyevent: " + printed.trim());
                } catch (IOException e) {
                    reset();
                }
            }
        }
        run("input keyevent " + code);
    }

    /** Roda um comando fixo no ADB da própria TV, que fica conectado entre um toque e outro. */
    private String run(String command) throws IOException {
        synchronized (adbLock) {
            IOException last = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    return connection().shell(command);
                } catch (IOException e) {
                    last = e;
                    reset();
                }
            }
            askApprovalOnce(last);
            throw last;
        }
    }

    /** A TV ainda não conhece a chave do app: pede a permissão (a TV pergunta uma vez) e a página avisa. */
    private void askApprovalOnce(IOException e) throws IOException {
        if (askedApproval || !String.valueOf(e.getMessage()).equals(Ui.tr("the TV rejected the app's key"))) return;
        askedApproval = true;
        pool.execute(() -> {
            try (Adb a = Adb.connect(this, 60_000, null)) {
                AppLog.w("phone remote: debugging allowed");
            } catch (IOException ignored) {
            }
            askedApproval = false;
        });
        throw new IOException(Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”)."));
    }

    // MARK: - A página

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String notice(String text) {
        return "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
                + "<body style=\"font:20px sans-serif;background:#111;color:#eee;padding:32px\">" + escape(text);
    }

    private String page(String code) {
        StringBuilder apps = new StringBuilder();
        List<String[]> shelf = HomeApps.shelfNames(this);
        for (int i = 0; i < shelf.size(); i++) {
            apps.append("<button data-p=\"/app?i=").append(i).append("\">").append(escape(shelf.get(i)[1])).append("</button>");
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,user-scalable=no\">"
                + "<title>" + escape(Ui.tr("Phone Remote")) + "</title><style>"
                + "body{margin:0;background:#111;color:#f2f2f7;font:18px -apple-system,Roboto,sans-serif;"
                + "-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;touch-action:manipulation}"
                + "main{max-width:420px;margin:0 auto;padding:16px}"
                + "h1{font-size:20px;margin:8px 0 16px;text-align:center;color:#aaa;font-weight:600}"
                + ".g{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:14px}"
                + "button{height:64px;border:0;border-radius:16px;background:#2c2c2e;color:#f2f2f7;font:inherit;"
                + "-webkit-tap-highlight-color:transparent}button:active{background:#5a5a5e}"
                + ".ok{background:#f2f2f7;color:#1c1c1e;font-weight:600}.a{grid-template-columns:repeat(2,1fr)}"
                + ".a button{height:56px}h2{font-size:15px;color:#888;margin:18px 4px 8px;font-weight:600}"
                + "#st{min-height:22px;text-align:center;color:#ffc266;font-size:15px;margin-bottom:8px}"
                + "</style></head><body><main><h1>" + escape(Ui.tr("Phone Remote")) + "</h1><div id=\"st\"></div>"
                + "<div class=\"g\"><i></i><button data-p=\"/key?n=up\" data-r=\"1\">&#9650;</button><i></i>"
                + "<button data-p=\"/key?n=left\" data-r=\"1\">&#9664;</button>"
                + "<button class=\"ok\" data-p=\"/key?n=ok\">OK</button>"
                + "<button data-p=\"/key?n=right\" data-r=\"1\">&#9654;</button>"
                + "<i></i><button data-p=\"/key?n=down\" data-r=\"1\">&#9660;</button><i></i></div>"
                + "<div class=\"g\"><button data-p=\"/key?n=back\">&#8592;</button>"
                + "<button data-p=\"/key?n=home\">&#8962;</button><button data-p=\"/key?n=menu\">&#8801;</button>"
                + "<button data-p=\"/key?n=voldown\" data-r=\"1\">&#128265; &minus;</button>"
                + "<button data-p=\"/key?n=mute\">&#128263;</button>"
                + "<button data-p=\"/key?n=volup\" data-r=\"1\">&#128266; +</button></div>"
                + (shelf.isEmpty() ? "" : "<h2>" + escape(Ui.tr("Apps")) + "</h2><div class=\"g a\">" + apps + "</div>")
                + "<script>(function(){var K='" + code + "',st=document.getElementById('st'),busy=0,timer=0;"
                + "function fire(b){if(busy>1)return;busy++;if(navigator.vibrate)navigator.vibrate(12);"
                + "var x=new XMLHttpRequest(),p=b.getAttribute('data-p');"
                + "x.open('GET',p+(p.indexOf('?')<0?'?':'&')+'k='+K,true);x.timeout=8000;"
                + "x.onload=function(){busy--;st.textContent=x.status==200?'':x.responseText};"
                + "x.onerror=x.ontimeout=function(){busy--};x.send()}"
                + "function down(b){fire(b);if(b.getAttribute('data-r')){timer=setTimeout(function again(){fire(b);"
                + "timer=setTimeout(again,220)},420)}}function up(){clearTimeout(timer)}"
                + "var bs=document.getElementsByTagName('button');for(var i=0;i<bs.length;i++){(function(b){"
                + "b.addEventListener('touchstart',function(e){e.preventDefault();down(b)});"
                + "b.addEventListener('touchend',up);b.addEventListener('touchcancel',up);"
                + "b.addEventListener('mousedown',function(){down(b)});b.addEventListener('mouseup',up);"
                + "b.addEventListener('mouseleave',up)})(bs[i])}})()</script></main></body></html>";
    }
}
