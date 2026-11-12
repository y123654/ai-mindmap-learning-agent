package com.example.aimindmap.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.aimindmap.common.error.ApiException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExternalIdCodecTest {

    private final ExternalIdCodec codec = new ExternalIdCodec("unit-test-external-id-salt");

    @Test
    void roundTripsSmallAndLargeIds() {
        long[] ids = {1L, 2L, 7L, 35L, 99L, 1000L, 123456789L, Long.MAX_VALUE};
        for (long id : ids) {
            String external = codec.encode(id);
            assertThat(codec.decode(external)).isEqualTo(id);
        }
    }

    @Test
    void externalIdsAreOpaqueAndMeetMinLength() {
        for (long id = 1; id <= 200; id++) {
            String external = codec.encode(id);
            assertThat(external).hasSizeGreaterThanOrEqualTo(8);
            // 不直接暴露数字：整个字符串不能是纯数字
            assertThat(external).matches(".*[A-Za-z].*");
        }
    }

    @Test
    void encodedIdsAreUniqueForDifferentNumbers() {
        Set<String> seen = new HashSet<>();
        for (long id = 1; id <= 100_000; id++) {
            assertThat(seen.add(codec.encode(id))).isTrue();
        }
    }

    @Test
    void encodingIsStableAndSaltSpecific() {
        ExternalIdCodec otherSalt = new ExternalIdCodec("another-salt-another-salt-000");
        assertThat(codec.encode(35L)).isEqualTo(codec.encode(35L));
        assertThat(otherSalt.encode(35L)).isNotEqualTo(codec.encode(35L));
        // 盐值不同时，对方编码的 ID 在本组件下必须解码失败
        long decoded;
        try {
            decoded = codec.decode(otherSalt.encode(35L));
            throw new AssertionError("应当解码失败，却得到 " + decoded);
        } catch (ApiException expected) {
            assertThat(expected.getStatus().value()).isEqualTo(404);
        }
    }

    @Test
    void tamperedOrMalformedExternalIdsBecome404() {
        String valid = codec.encode(123L);
        assertInvalid(valid.substring(0, valid.length() - 1) + "w");
        assertInvalid("1");
        assertInvalid("");
        assertInvalid(null);
        assertInvalid("aaaaaaaa"); // 没有分隔符
        assertInvalid("aaaa/wx"); // 非法字符
        assertInvalid("w" + valid.substring(1)); // 破坏校验段
        // 合法签名后拼接垃圾
        assertInvalid(valid + "zz");
        // 超长输入
        assertInvalid("a".repeat(65));
    }

    @Test
    void nullEncodesToNull() {
        assertThat(codec.encode(null)).isNull();
    }

    private void assertInvalid(String value) {
        assertThatThrownBy(() -> codec.decode(value))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
    }
}