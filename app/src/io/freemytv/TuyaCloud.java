package io.freemytv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * A API da nuvem da Tuya (platform.tuya.com), só para os aparelhos que a TV não alcança na rede local: ler o estado das
 * teclas e ligar ou desligar. Precisa do Access ID e do Access Secret do projeto de nuvem da pessoa (os mesmos do
 * `tinytuya wizard`), guardados na área privada do app. Nada disso vai para o log. Cada chamada vale na cota da conta.
 */
final class TuyaCloud {
    private static final int TIMEOUT_MS = 8000;

    private final String clientId, secret, host;
    private String token = "";
    private long tokenUntil;

    TuyaCloud(String clientId, String secret, String region) {
        this.clientId = clientId;
        this.secret = secret;
        this.host = host(region);
    }

    /** O servidor do centro de dados: us (Western America), us-e, eu, eu-w, in, sg ou cn. */
    static String host(String region) {
        switch (region == null ? "" : region.toLowerCase(java.util.Locale.ROOT)) {
            case "us-e": return "openapi-ueaz.tuyaus.com";
            case "eu": return "openapi.tuyaeu.com";
            case "eu-w": return "openapi-weaz.tuyaeu.com";
            case "in": return "openapi.tuyain.com";
            case "sg": return "openapi-sg.iotbing.com";
            case "cn": return "openapi.tuyacn.com";
            default: return "openapi.tuyaus.com";
        }
    }

    /** O estado de um aparelho: uma lista de {code, value}. */
    JSONArray status(String deviceId) throws IOException {
        JSONArray list = call("GET", "/v1.0/iot-03/devices/" + deviceId + "/status", null).optJSONArray("result");
        if (list == null) throw new IOException("unexpected Tuya cloud reply");
        return list;
    }

    /** Liga ou desliga o ponto de dados de código `code` (switch_1, switch_led...). */
    void command(String deviceId, String code, boolean on) throws IOException {
        String body = "{\"commands\":[{\"code\":\"" + code + "\",\"value\":" + on + "}]}";
        call("POST", "/v1.0/iot-03/devices/" + deviceId + "/commands", body);
    }

    // MARK: - Os pedidos

    private synchronized JSONObject call(String method, String path, String body) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (token.isEmpty() || System.currentTimeMillis() > tokenUntil) fetchToken();
            JSONObject r = send(method, path, body, token);
            if (r.optBoolean("success")) return r;
            if (r.optInt("code") == 1010 || r.optInt("code") == 1011) {   // token inválido ou vencido: pede outro e tenta de novo
                token = "";
                continue;
            }
            throw new IOException("the Tuya cloud refused (code " + r.optInt("code") + ")");
        }
        throw new IOException("the Tuya cloud refused the token");
    }

    private void fetchToken() throws IOException {
        JSONObject r = send("GET", "/v1.0/token?grant_type=1", null, null);
        JSONObject result = r.optJSONObject("result");
        if (!r.optBoolean("success") || result == null || result.optString("access_token").isEmpty()) {
            throw new IOException("the Tuya cloud refused the credentials (code " + r.optInt("code") + ")");
        }
        token = result.optString("access_token");
        // um pouco antes de vencer (o token vale umas duas horas)
        tokenUntil = System.currentTimeMillis() + Math.max(60, result.optLong("expire_time", 7200) - 120) * 1000L;
    }

    private JSONObject send(String method, String path, String body, String accessToken) throws IOException {
        long now = System.currentTimeMillis();
        String sign;
        try {
            sign = TuyaCloudSign.sign(clientId, secret, accessToken, now, method, path, body, body != null);
        } catch (GeneralSecurityException e) {
            throw new IOException("could not sign the Tuya cloud request");
        }
        HttpURLConnection c = (HttpURLConnection) new URL("https://" + host + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("client_id", clientId);
            c.setRequestProperty("sign", sign);
            c.setRequestProperty("t", String.valueOf(now));
            c.setRequestProperty("sign_method", "HMAC-SHA256");
            if (accessToken != null) c.setRequestProperty("access_token", accessToken);
            if (body != null) {
                c.setRequestProperty("Content-type", "application/json");
                c.setRequestProperty("Signature-Headers", "Content-type");
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("empty Tuya cloud reply");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) buffer.write(chunk, 0, n);
            return new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("unreadable Tuya cloud reply");
        } finally {
            c.disconnect();
        }
    }
}
