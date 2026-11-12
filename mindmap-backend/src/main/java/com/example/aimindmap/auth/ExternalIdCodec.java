package com.example.aimindmap.auth;

import com.example.aimindmap.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 修订第 44 条（§16.5）：对外资源 ID 加盐可逆混淆。
 *
 * <p>数据库内部仍使用自增 Long，只有进出 Controller 的外部形态经过本组件：
 * 外部 ID 为 {@code 校验段.数据段.填充段} 的短字符串，字符全部取自安全字母表
 * （去除 0/O/1/I/l 等易混字符），最短 {@value #MIN_LENGTH} 位；
 * 校验段为 HMAC-SHA256 截断，伪造、截断或用错盐值都会解码失败并按 404 处理。
 *
 * <p>安全定位：混淆只用于“不暴露自增顺序、提高猜测成本”，不是访问控制手段，
 * 越权访问仍由 OwnershipGuard 拦截。
 */
@Component
public class ExternalIdCodec {

    private static final Logger log = LoggerFactory.getLogger(ExternalIdCodec.class);

    /** 数据字母表（55 个字符，不含分隔符 {@link #SEP_A}/{@link #SEP_B}）。 */
    private static final String ALPHABET =
            "abcdefghijkmnopqrstuvyzABCDEFGHJKLMNPQRSTUVXY23456789";
    private static final char SEP_A = 'w';
    private static final char SEP_B = 'x';
    private static final int BASE = ALPHABET.length();
    private static final int SIG_CHARS = 4;
    private static final int MIN_LENGTH = 8;
    private static final int MAX_EXTERNAL_LENGTH = 64;

    private final byte[] key;

    public ExternalIdCodec(
            @Value("${app.security.external-id.salt:aimindmap-local-dev-external-id-salt-change-me}")
            String salt) {
        if (salt == null || salt.isBlank()) {
            throw new IllegalStateException("app.security.external-id.salt 不能为空");
        }
        this.key = ("external-id:" + salt).getBytes(StandardCharsets.UTF_8);
    }

    /** 数字主键 -> 外部字符串；入参为 null 时返回 null（用于可空关联字段）。 */
    public String encode(Long id) {
        if (id == null) {
            return null;
        }
        long value = id;
        String digits = toBase(value);
        String sig = signature(value);
        StringBuilder sb = new StringBuilder();
        sb.append(sig).append(SEP_A).append(digits).append(SEP_B);
        // 确定性填充：小 ID 也固定占满最短长度，避免从长度推断数据量
        byte[] fillSeed = hmac(("fill:" + value).getBytes(StandardCharsets.UTF_8));
        int fi = 0;
        while (sb.length() < MIN_LENGTH) {
            sb.append(ALPHABET.charAt(Byte.toUnsignedInt(fillSeed[fi % fillSeed.length]) % BASE));
            fi++;
        }
        return sb.toString();
    }

    /** 外部字符串 -> 数字主键；任何不合法形态都抛 404，与“资源不存在”不可区分。 */
    public long decode(String external) {
        if (external == null || external.isBlank()
                || external.length() > MAX_EXTERNAL_LENGTH
                || external.indexOf(SEP_A) < 0 || external.lastIndexOf(SEP_B) < 0) {
            throw invalid(external);
        }
        int firstSep = external.indexOf(SEP_A);
        int lastSep = external.lastIndexOf(SEP_B);
        if (firstSep >= lastSep || firstSep != SIG_CHARS) {
            throw invalid(external);
        }
        String sig = external.substring(0, firstSep);
        String digits = external.substring(firstSep + 1, lastSep);
        String fill = external.substring(lastSep + 1);
        if (!allInAlphabet(sig) || !allInAlphabet(digits) || !allInAlphabet(fill)
                || digits.isEmpty()) {
            throw invalid(external);
        }
        long value;
        try {
            value = fromBase(digits);
        } catch (RuntimeException ex) {
            throw invalid(external);
        }
        if (value <= 0 || !constantTimeEquals(sig, signature(value))) {
            throw invalid(external);
        }
        // 校验填充与规范长度：防止攻击者在合法签名后缀任意拼接
        String expected = encode(value);
        if (!expected.equals(external)) {
            throw invalid(external);
        }
        return value;
    }

    private ApiException invalid(String external) {
        String shown = external == null ? "null"
                : external.length() > 20 ? external.substring(0, 20) + "..." : external;
        log.warn("拒绝非法外部资源 ID（按 404 处理）：externalId={}", shown);
        return ApiException.notFound("资源不存在或链接无效");
    }

    private String signature(long value) {
        byte[] mac = hmac(longToBytes(value));
        int num = ((Byte.toUnsignedInt(mac[0])) << 16)
                | (Byte.toUnsignedInt(mac[1]) << 8)
                | Byte.toUnsignedInt(mac[2]);
        StringBuilder sb = new StringBuilder(SIG_CHARS);
        for (int i = 0; i < SIG_CHARS; i++) {
            sb.append(ALPHABET.charAt(num % BASE));
            num /= BASE;
        }
        return sb.toString();
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC 不可用", ex);
        }
    }

    private static byte[] longToBytes(long value) {
        return new byte[] {
                (byte) (value >>> 56), (byte) (value >>> 48),
                (byte) (value >>> 40), (byte) (value >>> 32),
                (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value
        };
    }

    private static String toBase(long value) {
        long v = value;
        StringBuilder sb = new StringBuilder();
        while (v > 0) {
            sb.append(ALPHABET.charAt((int) (v % BASE)));
            v /= BASE;
        }
        return sb.reverse().toString();
    }

    private static long fromBase(String digits) {
        long value = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = ALPHABET.indexOf(digits.charAt(i));
            value = Math.multiplyExact(value, BASE) + digit;
        }
        return value;
    }

    private static boolean allInAlphabet(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (ALPHABET.indexOf(text.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}