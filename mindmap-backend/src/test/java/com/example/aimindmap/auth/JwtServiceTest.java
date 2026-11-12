package com.example.aimindmap.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(
            "test-secret-key-test-secret-key-test-secret-key-0123456789");

    @Test
    void issueAndParseRoundTrip() {
        UserEntity user = new UserEntity();
        user.setUsername("alice");
        user.setMajor("计算机科学与技术");
        try {
            var idField = UserEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, 42L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }

        String token = jwtService.issue(user);
        AuthUser parsed = jwtService.parse(token);

        assertThat(parsed).isNotNull();
        assertThat(parsed.userId()).isEqualTo(42L);
        assertThat(parsed.username()).isEqualTo("alice");
    }

    @Test
    void invalidTokenReturnsNull() {
        assertThat(jwtService.parse("not-a-jwt")).isNull();
    }

    @Test
    void oldPlaintextJwsTokenIsRejected() {
        // 修订第 44 条前的明文 JWS（三段式）必须被拒绝，强制重新登录
        String oldStyle =
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwidXNlcm5hbWUiOiJhbGljZSJ9.invalid-signature";
        assertThat(jwtService.parse(oldStyle)).isNull();
    }

    @Test
    void jweTokenContentIsNotReadableAndTamperingIsRejected() {
        UserEntity user = new UserEntity();
        user.setUsername("alice");
        try {
            var idField = UserEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, 7L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }

        String token = jwtService.issue(user);
        // JWE 五段式（第二段加密密钥为空），且 payload 段不可读
        assertThat(token.split("\\.")).hasSize(5);
        assertThat(token).doesNotContain("alice");

        // 位置选择说明：JWE 第五段是 GCM 认证 tag，篡改 tag 必然导致认证失败。
        // 但 Base64URL 每字符只是 6 bit 的 sextet，必须选完整承载 tag/密文比特的位置；
        // 旧用例只替换密文段末尾 1 个字符，末字符低 bit 可能不承载密文，替换后解码
        // 字节不变时 GCM 仍会通过（实测约 1/4 概率假通过）。
        String[] parts = token.split("\\.", -1);

        // 先篡改 tag 段 parts[4]：固定下标 5（5 % 4 == 1，位于 Base64 组中部，
        // sextet 的 6 bit 全部参与解码字节），并先断言该段长度足够。
        String tag = parts[4];
        assertThat(tag.length()).isGreaterThan(5);
        char tagAt = tag.charAt(5);
        char tagReplacement = tagAt == 'A' ? 'B' : 'A';
        parts[4] = tag.substring(0, 5) + tagReplacement + tag.substring(6);

        // 双保险：再篡改密文段 parts[3] 中 i % 4 == 0 的位置（固定下标 0，
        // sextet 的 6 bit 全部落入解码首字节），规则相同、字符确定不同。
        String cipher = parts[3];
        assertThat(cipher.length()).isGreaterThan(0);
        char cipherAt = cipher.charAt(0);
        char cipherReplacement = cipherAt == 'A' ? 'B' : 'A';
        parts[3] = cipherReplacement + cipher.substring(1);

        // tag 与密文均被确定性篡改后，解析必须返回 null
        assertThat(jwtService.parse(String.join(".", parts))).isNull();
    }

    @Test
    void tokenFromAnotherSecretIsRejected() {
        UserEntity user = new UserEntity();
        user.setUsername("bob");
        try {
            var idField = UserEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, 9L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }

        String token = new JwtService("another-secret-another-secret-another-secret-9876543210")
                .issue(user);
        assertThat(jwtService.parse(token)).isNull();
    }
}
