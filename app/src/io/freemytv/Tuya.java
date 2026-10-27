package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Aparelhos Tuya / Smart Life na rede local: ligar, desligar e ver o estado, sem a nuvem. Os nomes, os IDs e as chaves
 * locais vêm do devices.json do `python -m tinytuya wizard`, que a pessoa envia por ADB para tuya-devices.json na pasta
 * do app (Android/data/io.freemytv/files): ele é copiado para a área privada e o original apagado, para as chaves
 * não ficarem onde outros apps leem. O IP não é guardado: cada aparelho avisa o seu em broadcast (portas 6667 e 7000),
 * e o ID liga o aviso ao aparelho. Só liga e desliga o que é interruptor ou tomada (a categoria da Tuya); o resto
 * (hubs infravermelho, sensores, controles virtuais) aparece como "Not a switch". Hoje só o protocolo 3.3.
 * Nada é logado com chave ou ID.
 */
final class Tuya {
    static final int UNKNOWN = 0, ON = 1, OFF = 2, OFFLINE = 3, NOT_SWITCH = 4, NEEDS_NEWER = 5, WORKING = 6, NO_ANSWER = 7;
    private static final int PORT = 6668, TIMEOUT_MS = 3000;
    /** As categorias da Tuya que ligam e desligam: tomada, interruptor, fita de LED, módulo, lâmpada, ventilador, régua. */
    private static final Set<String> SWITCHES = new HashSet<>(Arrays.asList("cz", "kg", "dd", "tdq", "dj", "dc", "fs", "fsd", "pc"));
    /** Os pontos de dados que ligam e desligam, na ordem em que se procura (o 1 é o padrão; o 20, as lâmpadas e fitas). */
    private static final String[] SWITCH_DPS = {"1", "20"};
    private static Tuya instance;

    static final class Device {
        final String id, name, key, category;
        volatile String ip = "", version = "", dp = "";
        volatile int state = UNKNOWN;

        Device(String id, String name, String key, String category) {
            this.id = id;
            this.name = name;
            this.key = key;
            this.category = category;
        }

        boolean switchable() {
            return SWITCHES.contains(category);
        }
    }

    interface Listener {
        void changed();
    }

    private final Context app;
    private final List<Device> devices = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile Listener listener;
    private volatile boolean listening;
    private final List<DatagramSocket> sockets = new ArrayList<>();

    static synchronized Tuya get(Context c) {
        if (instance == null) instance = new Tuya(c.getApplicationContext());
        return instance;
    }

    /** Há aparelhos configurados (ou um arquivo esperando para ser importado). */
    static boolean configured(Context c) {
        File ext = importFile(c);
        return new File(c.getFilesDir(), "tuya-devices.json").isFile() || (ext != null && ext.isFile());
    }

    private Tuya(Context c) {
        app = c;
    }

    List<Device> devices() {
        synchronized (devices) {
            return new ArrayList<>(devices);
        }
    }

    void setListener(Listener l) {
        listener = l;
    }

    private void changed() {
        Listener l = listener;
        if (l != null) l.changed();
    }

    // MARK: - O arquivo

    private static File importFile(Context c) {
        File dir = c.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, "tuya-devices.json");
    }

    /** Importa o arquivo enviado por ADB (se houver) e lê a lista. Fora da thread da interface. */
    void load() {
        File priv = new File(app.getFilesDir(), "tuya-devices.json"), ext = importFile(app);
        if (ext != null && ext.isFile()) {
            try {
                byte[] data = readAll(ext);
                new JSONArray(new String(data, StandardCharsets.UTF_8));   // só importa um JSON que serve
                File tmp = new File(app.getFilesDir(), "tuya-devices.tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(data);
                }
                if (tmp.renameTo(priv)) {
                    //noinspection ResultOfMethodCallIgnored
                    ext.delete();
                    AppLog.w("tuya: devices file imported");
                }
            } catch (Exception e) {
                AppLog.w("tuya: could not import the devices file");
            }
        }
        List<Device> found = new ArrayList<>();
        try {
            JSONArray all = new JSONArray(new String(readAll(priv), StandardCharsets.UTF_8));
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o == null || o.optString("id").isEmpty() || o.optString("key").isEmpty()) continue;
                found.add(new Device(o.optString("id"), o.optString("name", o.optString("id")), o.optString("key"), o.optString("category")));
            }
        } catch (Exception ignored) {
        }
        synchronized (devices) {
            devices.clear();
            devices.addAll(found);
        }
        for (Device d : found) d.state = d.switchable() ? UNKNOWN : NOT_SWITCH;
        changed();
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n = in.read(buf); n >= 0; n = in.read(buf)) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    // MARK: - Achar os aparelhos pelo aviso em broadcast

    /** Começa a ouvir os avisos e, a cada aviso novo, consulta o aparelho. Chamar ao abrir a lista. */
    synchronized void start() {
        if (listening) return;
        listening = true;
        for (int port : new int[] {6667, 7000}) {
            Thread t = new Thread(() -> listen(port), "FreeMyTV tuya " + port);
            t.setDaemon(true);
            t.start();
        }
    }

    synchronized void stop() {
        listening = false;
        for (DatagramSocket s : sockets) s.close();
        sockets.clear();
    }

    private void listen(int port) {
        try (DatagramSocket s = new DatagramSocket(null)) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
            synchronized (this) {
                if (!listening) return;
                sockets.add(s);
            }
            byte[] buf = new byte[2048];
            while (listening) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                heard(p.getData(), p.getLength());
            }
        } catch (IOException e) {
            if (listening) AppLog.w("tuya listen " + port + ": " + e.getClass().getSimpleName());
        }
    }

    private void heard(byte[] raw, int length) {
        try {
            JSONObject o = new JSONObject(TuyaProtocol.broadcast(raw, length));
            String id = o.optString("gwId"), ip = o.optString("ip"), version = o.optString("version");
            for (Device d : devices()) {
                if (!d.id.equals(id) || ip.isEmpty()) continue;
                boolean fresh = !ip.equals(d.ip) || d.state == UNKNOWN || d.state == OFFLINE;
                d.ip = ip;
                d.version = version;
                if (fresh && d.switchable() && d.state != WORKING) refresh(d);
                return;
            }
        } catch (IOException | org.json.JSONException ignored) {
        }
    }

    // MARK: - Estado e controle

    /** Marca como sem aviso quem ainda não se anunciou (depois de alguns segundos de escuta). */
    void settle() {
        for (Device d : devices()) {
            if (d.state == UNKNOWN && d.switchable() && d.ip.isEmpty()) d.state = OFFLINE;
        }
        changed();
    }

    /** Pergunta de novo o estado de quem já se anunciou (a lista aberta faz isso de tempos em tempos). */
    void refreshAll() {
        for (Device d : devices()) if (d.switchable() && !d.ip.isEmpty() && d.state != WORKING) refresh(d);
    }

    void refresh(Device d) {
        if (!d.switchable()) return;
        if (d.ip.isEmpty()) {
            d.state = OFFLINE;
            changed();
            return;
        }
        if (!"3.3".equals(d.version)) {
            d.state = NEEDS_NEWER;
            changed();
            return;
        }
        pool.execute(() -> {
            try {
                readState(d);
            } catch (Exception e) {
                d.state = NO_ANSWER;
                AppLog.w("tuya: no answer (" + d.category + ", v" + d.version + "): " + e.getClass().getSimpleName());
            }
            changed();
        });
    }

    /** Liga o desligado e desliga o ligado. */
    void toggle(Device d) {
        if ((d.state != ON && d.state != OFF) || d.dp.isEmpty()) return;
        boolean turnOn = d.state == OFF;
        d.state = WORKING;
        changed();
        pool.execute(() -> {
            try (Socket s = open(d)) {
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();
                long now = System.currentTimeMillis() / 1000;
                out.write(TuyaProtocol.control(d.id, TuyaProtocol.key(d.key), 1, now, "{\"" + d.dp + "\":" + turnOn + "}"));
                out.flush();
                for (int i = 0; i < 4; i++) {   // a confirmação (ou o estado) chega em até alguns quadros
                    TuyaProtocol.Reply r = TuyaProtocol.read(in, TuyaProtocol.key(d.key));
                    if (r.cmd == TuyaProtocol.CMD_CONTROL || r.cmd == TuyaProtocol.CMD_STATUS) break;
                }
            } catch (Exception e) {
                AppLog.w("tuya: no answer to a switch");
                d.state = NO_ANSWER;
                changed();
                return;
            }
            try {
                Thread.sleep(300);   // dá tempo de o aparelho gravar o novo estado
                readState(d);
            } catch (Exception e) {
                d.state = turnOn ? ON : OFF;
            }
            changed();
        });
    }

    private Socket open(Device d) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(d.ip, PORT), TIMEOUT_MS);
        s.setSoTimeout(TIMEOUT_MS);
        return s;
    }

    /** Pergunta todos os pontos de dados e acha o que liga e desliga. */
    private void readState(Device d) throws Exception {
        try (Socket s = open(d)) {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            byte[] key = TuyaProtocol.key(d.key);
            out.write(TuyaProtocol.query(d.id, key, 1, System.currentTimeMillis() / 1000));
            out.flush();
            for (int i = 0; i < 6; i++) {
                TuyaProtocol.Reply r = TuyaProtocol.read(in, key);
                if (r.json.isEmpty()) continue;
                JSONObject dps = new JSONObject(r.json).optJSONObject("dps");
                if (dps == null) continue;
                for (String dp : SWITCH_DPS) {
                    if (dps.opt(dp) instanceof Boolean) {
                        d.dp = dp;
                        d.state = dps.getBoolean(dp) ? ON : OFF;
                        return;
                    }
                }
                d.state = NOT_SWITCH;
                return;
            }
        }
        d.state = NO_ANSWER;
    }

    /** A lista em ordem: os que ligam e desligam primeiro, depois o resto, cada grupo por nome. */
    static List<Device> sorted(List<Device> in) {
        List<Device> out = new ArrayList<>(in);
        Collections.sort(out, (a, b) -> {
            if (a.switchable() != b.switchable()) return a.switchable() ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }
}
