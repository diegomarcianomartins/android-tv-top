package io.freemytv;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Uma conexão local com um aparelho Tuya (porta 6668), nas versões 3.3, 3.4 e 3.5: pergunta o estado e muda pontos de
 * dados. A 3.3 usa a chave local direto (TuyaProtocol). A 3.4 e a 3.5 começam com um aperto de mão de três mensagens,
 * em que cada lado manda um número aleatório, e dali sai uma chave de sessão: a 3.4 manda AES-ECB com HMAC-SHA256 no
 * fim de cada quadro, a 3.5 manda AES-GCM (quadros 0x6699). Sem rede nem Android, para poder ser conferida contra o
 * tinytuya e contra um aparelho de verdade. O JSON entra e sai como texto.
 */
final class TuyaConnection {
    private static final int PREFIX = 0x000055AA, SUFFIX = 0x0000AA55, PREFIX_6699 = 0x00006699, SUFFIX_6699 = 0x00009966;
    private static final int CMD_SESS_START = 3, CMD_SESS_RESP = 4, CMD_SESS_FINISH = 5, CMD_CONTROL_NEW = 13,
            CMD_STATUS = 8, CMD_QUERY_NEW = 16, MAX_FRAME = 64 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataInputStream in;
    private final OutputStream out;
    private final int version;   // 33, 34 ou 35
    private final String devId;
    private final byte[] localKey;
    private byte[] key;
    private int seq = 1;

    private static final class Frame {
        int cmd;
        byte[] plain = new byte[0];
    }

    TuyaConnection(InputStream in, OutputStream out, String version, String devId, byte[] localKey) {
        this.in = new DataInputStream(in);
        this.out = out;
        this.version = "3.5".equals(version) ? 35 : "3.4".equals(version) ? 34 : 33;
        this.devId = devId;
        this.localKey = localKey;
        this.key = localKey;
    }

    /** Só nas versões 3.4 e 3.5: o aperto de mão que dá a chave de sessão. A 3.3 não precisa. */
    void negotiate() throws IOException {
        if (version == 33) return;
        try {
            byte[] localNonce = new byte[16];
            RANDOM.nextBytes(localNonce);
            send(CMD_SESS_START, localNonce, false, localKey);
            Frame r = receive(localKey);
            if (r.cmd != CMD_SESS_RESP) throw new IOException("unexpected reply in the Tuya handshake");
            byte[] plain = r.plain;
            int at = plain.length >= 52 && plain[0] == 0 && plain[1] == 0 && plain[2] == 0 && plain[3] == 0 ? 4 : 0;
            if (plain.length - at < 48) throw new IOException("short reply in the Tuya handshake");
            byte[] remoteNonce = Arrays.copyOfRange(plain, at, at + 16);
            byte[] proof = Arrays.copyOfRange(plain, at + 16, at + 48);
            if (!MessageDigest.isEqual(proof, hmac(localKey, localNonce))) throw new IOException("the Tuya device failed the handshake check");
            send(CMD_SESS_FINISH, hmac(localKey, remoteNonce), false, localKey);
            byte[] mixed = new byte[16];
            for (int i = 0; i < 16; i++) mixed[i] = (byte) (localNonce[i] ^ remoteNonce[i]);
            if (version == 34) {
                Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(localKey, "AES"));
                key = c.doFinal(mixed);
            } else {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(localKey, "AES"), new GCMParameterSpec(128, Arrays.copyOf(localNonce, 12)));
                key = Arrays.copyOf(c.doFinal(mixed), 16);   // o texto cifrado, sem a etiqueta
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Tuya handshake failed");
        }
    }

    /** O JSON do estado: {"dps":{...}}. */
    String query(long nowSeconds) throws IOException {
        try {
            if (version == 33) {
                out.write(TuyaProtocol.query(devId, localKey, seq++, nowSeconds));
                out.flush();
                for (int i = 0; i < 6; i++) {
                    TuyaProtocol.Reply r = TuyaProtocol.read(in, localKey);
                    if (!r.json.isEmpty()) return r.json;
                }
                throw new IOException("no Tuya state");
            }
            send(CMD_QUERY_NEW, "{}".getBytes(StandardCharsets.UTF_8), false, key);
            for (int i = 0; i < 6; i++) {
                Frame f = receive(key);
                String json = json(f.plain);
                if (!json.isEmpty() && json.contains("dps")) return json;
            }
            throw new IOException("no Tuya state");
        } catch (GeneralSecurityException e) {
            throw new IOException("could not talk to the Tuya device");
        }
    }

    /** Muda pontos de dados: `dps` é o objeto JSON, como {"1":true}. Espera a confirmação (ou o estado novo). */
    void control(String dps, long nowSeconds) throws IOException {
        try {
            if (version == 33) {
                out.write(TuyaProtocol.control(devId, localKey, seq++, nowSeconds, dps));
                out.flush();
                for (int i = 0; i < 4; i++) {
                    TuyaProtocol.Reply r = TuyaProtocol.read(in, localKey);
                    if (r.cmd == TuyaProtocol.CMD_CONTROL || r.cmd == TuyaProtocol.CMD_STATUS) return;
                }
                return;
            }
            String json = "{\"protocol\":5,\"t\":" + nowSeconds + ",\"data\":{\"dps\":" + dps + "}}";
            send(CMD_CONTROL_NEW, json.getBytes(StandardCharsets.UTF_8), true, key);
            for (int i = 0; i < 4; i++) {
                Frame f = receive(key);
                if (f.cmd == CMD_CONTROL_NEW || f.cmd == CMD_STATUS) return;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("could not talk to the Tuya device");
        }
    }

    // MARK: - Os quadros

    private byte[] versionHeader() {
        byte[] h = new byte[15];
        h[0] = '3';
        h[1] = '.';
        h[2] = (byte) (version == 35 ? '5' : '4');
        return h;
    }

    private void send(int cmd, byte[] plain, boolean withHeader, byte[] k) throws IOException, GeneralSecurityException {
        byte[] data = plain;
        if (withHeader) {
            byte[] h = versionHeader();
            data = new byte[h.length + plain.length];
            System.arraycopy(h, 0, data, 0, h.length);
            System.arraycopy(plain, 0, data, h.length, plain.length);
        }
        out.write(version == 35 ? frame6699(seq++, cmd, data, k) : frame55aa(seq++, cmd, data, k));
        out.flush();
    }

    private Frame receive(byte[] k) throws IOException, GeneralSecurityException {
        return version == 35 ? read6699(k) : read55aa(k);
    }

    /** Quadro 0x55AA da 3.4: o payload em AES-ECB e um HMAC-SHA256 de 32 bytes no lugar do CRC. */
    static byte[] frame55aa(int seq, int cmd, byte[] plain, byte[] key) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] payload = c.doFinal(plain);
        ByteBuffer b = ByteBuffer.allocate(16 + payload.length + 32 + 4);
        b.putInt(PREFIX).putInt(seq).putInt(cmd).putInt(payload.length + 32 + 4).put(payload);
        b.put(hmac(key, Arrays.copyOf(b.array(), 16 + payload.length))).putInt(SUFFIX);
        return b.array();
    }

    private Frame read55aa(byte[] k) throws IOException, GeneralSecurityException {
        byte[] header = new byte[16];
        in.readFully(header);
        ByteBuffer h = ByteBuffer.wrap(header);
        if (h.getInt() != PREFIX) throw new IOException("not a Tuya frame");
        h.getInt();
        Frame f = new Frame();
        f.cmd = h.getInt();
        int length = h.getInt();
        if (length < 36 || length > MAX_FRAME) throw new IOException("bad Tuya frame length");
        byte[] rest = new byte[length];
        in.readFully(rest);
        byte[] signed = new byte[16 + length - 36];
        System.arraycopy(header, 0, signed, 0, 16);
        System.arraycopy(rest, 0, signed, 16, length - 36);
        if (!MessageDigest.isEqual(hmac(k, signed), Arrays.copyOfRange(rest, length - 36, length - 4))) throw new IOException("bad Tuya checksum");
        int from = 4;   // o código de retorno, antes do payload
        if (length - 36 > from) {
            byte[] cipher = Arrays.copyOfRange(rest, from, length - 36);
            f.plain = decryptEcb(k, cipher);
        }
        return f;
    }

    /** Quadro 0x6699 da 3.5: o payload em AES-GCM (a etiqueta de 16 bytes no fim), com o cabeçalho como dado autenticado. */
    static byte[] frame6699(int seq, int cmd, byte[] plain, byte[] key) throws GeneralSecurityException {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        ByteBuffer header = ByteBuffer.allocate(18);
        header.putInt(PREFIX_6699).putShort((short) 0).putInt(seq).putInt(cmd).putInt(12 + plain.length + 16);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(header.array(), 4, 14);
        byte[] sealed = c.doFinal(plain);   // o texto cifrado e a etiqueta
        ByteBuffer b = ByteBuffer.allocate(18 + 12 + sealed.length + 4);
        b.put(header.array()).put(iv).put(sealed).putInt(SUFFIX_6699);
        return b.array();
    }

    private Frame read6699(byte[] k) throws IOException, GeneralSecurityException {
        byte[] header = new byte[18];
        in.readFully(header);
        ByteBuffer h = ByteBuffer.wrap(header);
        if (h.getInt() != PREFIX_6699) throw new IOException("not a Tuya frame");
        h.getShort();
        h.getInt();
        Frame f = new Frame();
        f.cmd = h.getInt();
        int length = h.getInt();
        if (length < 28 || length > MAX_FRAME) throw new IOException("bad Tuya frame length");
        byte[] rest = new byte[length + 4];   // o IV, o texto, a etiqueta e o sufixo
        in.readFully(rest);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, rest, 0, 12));
        c.updateAAD(header, 4, 14);
        byte[] plain = c.doFinal(rest, 12, length - 12);
        int start = plain.length >= 4 && plain[0] == 0 && plain[1] == 0 && plain[2] == 0 && plain[3] == 0 ? 4 : 0;
        f.plain = Arrays.copyOfRange(plain, start, plain.length);
        return f;
    }

    // MARK: - Utilitários

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** Decifra em AES-ECB e tira o enchimento, se houver (a 3.4 às vezes manda o enchimento, às vezes não). */
    private static byte[] decryptEcb(byte[] key, byte[] cipher) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] plain = c.doFinal(cipher);
        int pad = plain.length == 0 ? 0 : plain[plain.length - 1] & 0xff;
        if (pad >= 1 && pad <= 16 && pad <= plain.length) {
            boolean ok = true;
            for (int i = plain.length - pad; i < plain.length; i++) ok &= (plain[i] & 0xff) == pad;
            if (ok) return Arrays.copyOf(plain, plain.length - pad);
        }
        return plain;
    }

    /** O JSON dentro de um payload: pula o cabeçalho de versão ("3.4" e 12 zeros), se houver. */
    static String json(byte[] plain) {
        int start = -1;
        for (int i = 0; i < Math.min(plain.length, 20); i++) {
            if (plain[i] == '{') {
                start = i;
                break;
            }
        }
        if (start < 0) return "";
        int end = plain.length;
        while (end > start && plain[end - 1] != '}') end--;
        return end > start ? new String(plain, start, end - start, StandardCharsets.UTF_8) : "";
    }
}
