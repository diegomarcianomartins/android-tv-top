package io.freemytv;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A assinatura dos pedidos à API da nuvem da Tuya (HMAC-SHA256, o algoritmo novo de 2021), sem rede e sem Android, para
 * poder ser conferida contra o tinytuya. A cadeia assinada é: o ID de acesso, o token (vazio no pedido do token), a hora
 * em milissegundos e o texto "MÉTODO, hash do corpo, cabeçalhos assinados, caminho com a consulta".
 */
final class TuyaCloudSign {
    private TuyaCloudSign() {}

    /** A assinatura, em maiúsculas. `path` começa com "/" e leva a consulta; `jsonBody` diz se o Content-type é assinado. */
    static String sign(String clientId, String secret, String token, long nowMillis, String method, String path,
                       String body, boolean jsonBody) throws GeneralSecurityException {
        String bodyHash = hex(MessageDigest.getInstance("SHA-256").digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)));
        String signedHeaders = jsonBody ? "Content-type:application/json\n" : "";
        String toSign = method + "\n" + bodyHash + "\n" + signedHeaders + "\n" + path;
        String payload = clientId + (token == null ? "" : token) + nowMillis + toSign;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return hex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))).toUpperCase(java.util.Locale.ROOT);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
