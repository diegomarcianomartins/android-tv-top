package io.freemytv;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * O protocolo local da Tuya, versão 3.3 (porta 6668 do aparelho): quadros 0x000055AA com o payload em AES-128-ECB
 * (a chave local do aparelho) e um CRC32 no fim. Só montagem e leitura de quadros, sem rede e sem Android, para
 * poder ser conferida contra o tinytuya. As versões 3.4 e 3.5 (aperto de mão com chave de sessão) ficam para depois.
 * O JSON entra e sai como texto: quem chama o monta e o lê.
 */
final class TuyaProtocol {
    private TuyaProtocol() {}

    static final int CMD_CONTROL = 7, CMD_STATUS = 8, CMD_DP_QUERY = 10;
    private static final int PREFIX = 0x000055AA, SUFFIX = 0x0000AA55, MAX_FRAME = 64 * 1024;
    /** "3.3" e 12 zeros, antes do payload cifrado nos comandos de controle. */
    private static final byte[] VERSION_HEADER = new byte[15];

    static {
        VERSION_HEADER[0] = '3';
        VERSION_HEADER[1] = '.';
        VERSION_HEADER[2] = '3';
    }

    /** Uma resposta: o comando, o código de retorno e o JSON já decifrado ("" se vier sem payload). */
    static final class Reply {
        int cmd, retcode;
        String json = "";
    }

    /** Pede o estado de todos os pontos de dados. */
    static byte[] query(String devId, byte[] key, int seq, long nowSeconds) throws GeneralSecurityException {
        String json = "{\"gwId\":\"" + devId + "\",\"devId\":\"" + devId + "\",\"uid\":\"" + devId + "\",\"t\":\"" + nowSeconds + "\"}";
        return frame(seq, CMD_DP_QUERY, encrypt(key, json.getBytes(StandardCharsets.UTF_8)));
    }

    /** Muda pontos de dados: `dps` é o objeto JSON, como {"1":true}. */
    static byte[] control(String devId, byte[] key, int seq, long nowSeconds, String dps) throws GeneralSecurityException {
        String json = "{\"devId\":\"" + devId + "\",\"uid\":\"" + devId + "\",\"t\":\"" + nowSeconds + "\",\"dps\":" + dps + "}";
        byte[] cipher = encrypt(key, json.getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[VERSION_HEADER.length + cipher.length];
        System.arraycopy(VERSION_HEADER, 0, payload, 0, VERSION_HEADER.length);
        System.arraycopy(cipher, 0, payload, VERSION_HEADER.length, cipher.length);
        return frame(seq, CMD_CONTROL, payload);
    }

    private static byte[] frame(int seq, int cmd, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(16 + payload.length + 8);   // big-endian
        b.putInt(PREFIX).putInt(seq).putInt(cmd).putInt(payload.length + 8).put(payload);
        CRC32 crc = new CRC32();
        crc.update(b.array(), 0, 16 + payload.length);
        b.putInt((int) crc.getValue()).putInt(SUFFIX);
        return b.array();
    }

    /** Lê um quadro inteiro do aparelho e o decifra. Lança IOException se o quadro estiver quebrado. */
    static Reply read(InputStream in, byte[] key) throws IOException {
        DataInputStream data = new DataInputStream(in);
        byte[] header = new byte[16];
        data.readFully(header);
        ByteBuffer h = ByteBuffer.wrap(header);
        if (h.getInt() != PREFIX) throw new IOException("not a Tuya frame");
        h.getInt();   // seq
        Reply reply = new Reply();
        reply.cmd = h.getInt();
        int length = h.getInt();
        if (length < 8 || length > MAX_FRAME) throw new IOException("bad Tuya frame length");
        byte[] rest = new byte[length];
        data.readFully(rest);
        ByteBuffer r = ByteBuffer.wrap(rest);
        CRC32 crc = new CRC32();
        crc.update(header);
        crc.update(rest, 0, length - 8);
        if ((int) crc.getValue() != r.getInt(length - 8) || r.getInt(length - 4) != SUFFIX) throw new IOException("bad Tuya checksum");
        int start = 0, end = length - 8;
        if (end - start >= 4) {   // o aparelho responde com um código de retorno antes do payload
            reply.retcode = r.getInt(0);
            start = 4;
        }
        if (end - start >= VERSION_HEADER.length && rest[start] == '3' && rest[start + 1] == '.') start += VERSION_HEADER.length;
        if (end > start) {
            byte[] cipher = new byte[end - start];
            System.arraycopy(rest, start, cipher, 0, cipher.length);
            try {
                reply.json = new String(decrypt(key, cipher), StandardCharsets.UTF_8);
            } catch (GeneralSecurityException e) {
                throw new IOException("could not decrypt the Tuya reply");
            }
        }
        return reply;
    }

    /**
     * O aviso que cada aparelho manda em broadcast (portas 6667 e 7000) como JSON: gwId, ip e version. Vem cifrado com a
     * chave pública de descoberta da Tuya (md5 de uma constante, a mesma do tinytuya): AES-ECB nos quadros 55AA e
     * AES-GCM nos 6699 (versão 3.5).
     */
    static String broadcast(byte[] raw, int length) throws IOException {
        try {
            byte[] key = MessageDigest.getInstance("MD5").digest("yGAdlopoPVldABfn".getBytes(StandardCharsets.US_ASCII));
            int prefix = length >= 4 ? ByteBuffer.wrap(raw).getInt() : 0;
            if (prefix == PREFIX && length > 28) {
                return new String(decrypt(key, Arrays.copyOfRange(raw, 20, length - 8)), StandardCharsets.UTF_8);
            }
            if (prefix == 0x00006699 && length > 34) {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, raw, 18, 12));
                c.updateAAD(raw, 4, 14);
                byte[] plain = c.doFinal(raw, 30, length - 34);   // o texto e a etiqueta, sem o sufixo
                int start = plain.length >= 4 && plain[0] == 0 && plain[1] == 0 && plain[2] == 0 && plain[3] == 0 ? 4 : 0;
                int end = plain.length;
                while (end > start && plain[end - 1] == 0) end--;
                return new String(plain, start, end - start, StandardCharsets.UTF_8);
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("could not read the Tuya broadcast");
        }
        throw new IOException("not a Tuya broadcast");
    }

    static byte[] key(String localKey) {
        return localKey.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encrypt(byte[] key, byte[] plain) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(plain);
    }

    private static byte[] decrypt(byte[] key, byte[] cipher) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(cipher);
    }
}
