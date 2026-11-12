package com.example.aimindmap.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 修订第 44 条（§16.5）：登录令牌使用标准 JWE 紧凑序列化（RFC 7516），
 * {@code alg=dir} 直接密钥 + {@code enc=A256GCM}，用 JDK 自带 AES/GCM 实现，不引入第三方库。
 *
 * <p>令牌五段为 {@code 保护头.空.密文IV.密文.认证标签}；旧的明文 JWS 令牌会被直接判为无效
 * （切换后全体用户需要重新登录一次）。内容密钥由 {@code app.jwt.secret} 经 SHA-256 派生。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** JWE Protected Header：{"alg":"dir","enc":"A256GCM"} */
    private static final String PROTECTED_HEADER =
            Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"alg\":\"dir\",\"enc\":\"A256GCM\"}".getBytes(StandardCharsets.UTF_8));
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;

    private final SecretKey key;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper mapper = new ObjectMapper();

    public JwtService(
            @Value("${app.jwt.secret:aimindmap-local-dev-jwt-secret-please-change-in-production-0123456789}")
            String secret) {
        this.key = deriveKey(secret);
        this.ttl = Duration.ofDays(7);
    }

    private static SecretKey deriveKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.jwt.secret 不能为空");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception ex) {
            throw new IllegalStateException("无法派生 JWE 内容密钥", ex);
        }
    }

    public String issue(UserEntity user) {
        try {
            Instant now = Instant.now();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", String.valueOf(user.getId()));
            claims.put("username", user.getUsername());
            claims.put("iat", now.getEpochSecond());
            claims.put("exp", now.plus(ttl).getEpochSecond());
            byte[] plaintext = mapper.writeValueAsBytes(claims);

            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(PROTECTED_HEADER.getBytes(StandardCharsets.US_ASCII));
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] ciphertext = new byte[encrypted.length - TAG_BYTES];
            byte[] tag = new byte[TAG_BYTES];
            System.arraycopy(encrypted, 0, ciphertext, 0, ciphertext.length);
            System.arraycopy(encrypted, ciphertext.length, tag, 0, TAG_BYTES);

            return PROTECTED_HEADER
                    + ".." + b64(iv)
                    + "." + b64(ciphertext)
                    + "." + b64(tag);
        } catch (Exception ex) {
            throw new IllegalStateException("签发登录令牌失败", ex);
        }
    }

    public AuthUser parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 5 || !parts[1].isEmpty() || !PROTECTED_HEADER.equals(parts[0])) {
                return null;
            }
            byte[] iv = unb64(parts[2]);
            byte[] ciphertext = unb64(parts[3]);
            byte[] tag = unb64(parts[4]);
            if (iv.length != IV_LENGTH || tag.length != TAG_BYTES) {
                return null;
            }

            byte[] encrypted = new byte[ciphertext.length + TAG_BYTES];
            System.arraycopy(ciphertext, 0, encrypted, 0, ciphertext.length);
            System.arraycopy(tag, 0, encrypted, ciphertext.length, TAG_BYTES);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(PROTECTED_HEADER.getBytes(StandardCharsets.US_ASCII));
            byte[] plaintext = cipher.doFinal(encrypted);

            JsonNode claims = mapper.readTree(plaintext);
            long exp = claims.path("exp").asLong(0);
            if (exp <= 0 || Instant.ofEpochSecond(exp).isBefore(Instant.now())) {
                return null;
            }
            JsonNode sub = claims.get("sub");
            JsonNode username = claims.get("username");
            if (sub == null || username == null || !sub.isTextual()) {
                return null;
            }
            long userId;
            try {
                userId = Long.parseLong(sub.asText());
            } catch (NumberFormatException ex) {
                return null;
            }
            return new AuthUser(userId, username.asText());
        } catch (Exception ex) {
            // 解密失败通常是令牌被篡改或用错密钥；禁止打印令牌内容
            log.warn("登录令牌解析失败：{}", ex.getClass().getSimpleName());
            return null;
        }
    }

    private static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] unb64(String value) {
        String padded = value;
        int mod = padded.length() % 4;
        if (mod == 2 || mod == 3) {
            padded = padded + "=".repeat(4 - mod);
        } else if (mod == 1) {
            throw new IllegalArgumentException("非法 Base64URL 长度");
        }
        return Base64.getUrlDecoder().decode(padded);
    }
}