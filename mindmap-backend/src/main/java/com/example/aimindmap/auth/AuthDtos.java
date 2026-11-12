package com.example.aimindmap.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "账号不能为空")
            @Size(min = 3, max = 64, message = "账号长度必须在 3 到 64 个字符之间")
            String username,
            @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 64, message = "密码长度至少 6 位")
            String password,
            @NotBlank(message = "请再次输入密码")
            String confirmPassword,
            @NotBlank(message = "专业不能为空")
            @Size(min = 1, max = 128, message = "专业长度不能超过 128 个字符")
            String major
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "账号不能为空") String username,
            @NotBlank(message = "密码不能为空") String password
    ) {
    }

    /** 修订第 44 条：id 对外为混淆字符串。 */
    public record UserProfile(String id, String username, String major) {
        public static UserProfile from(UserEntity entity, ExternalIdCodec idCodec) {
            return new UserProfile(idCodec.encode(entity.getId()), entity.getUsername(), entity.getMajor());
        }
    }

    public record AuthResponse(String token, UserProfile user) {
    }
}