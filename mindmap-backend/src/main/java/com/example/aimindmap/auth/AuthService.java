package com.example.aimindmap.auth;

import com.example.aimindmap.common.error.ApiException;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** v1.1 修订第 16 条：注册、登录、当前用户；首个用户接管历史数据。 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ExternalIdCodec idCodec;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       ExternalIdCodec idCodec) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.idCodec = idCodec;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String username = request.username() == null ? "" : request.username().trim();
        String major = request.major() == null ? "" : request.major().trim();
        String password = request.password() == null ? "" : request.password();
        String confirm = request.confirmPassword() == null ? "" : request.confirmPassword();

        if (username.length() < 3 || username.length() > 64) {
            throw ApiException.badRequest("AUTH_USERNAME_INVALID", "账号长度必须在 3 到 64 个字符之间");
        }
        if (password.length() < 6 || password.length() > 64) {
            throw ApiException.badRequest("AUTH_PASSWORD_INVALID", "密码长度至少 6 位");
        }
        if (!password.equals(confirm)) {
            throw ApiException.badRequest("AUTH_PASSWORD_MISMATCH", "两次输入的密码不一致");
        }
        if (major.isBlank() || major.length() > 128) {
            throw ApiException.badRequest("AUTH_MAJOR_INVALID", "专业不能为空且不能超过 128 字");
        }
        if (userRepository.findByUsernameIgnoreCase(username).isPresent()) {
            throw ApiException.badRequest("AUTH_USERNAME_TAKEN", "该账号已被注册，请换一个");
        }

        boolean firstUser = userRepository.count() == 0;

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setMajor(major);
        UserEntity saved = userRepository.save(user);

        // 方案 2A：第一个注册成功的账号接管全部历史数据
        if (firstUser) {
            userRepository.claimDocuments(saved.getId());
            userRepository.claimMindmaps(saved.getId());
            userRepository.claimQuestions(saved.getId());
        }

        String token = jwtService.issue(saved);
        return new AuthDtos.AuthResponse(token, AuthDtos.UserProfile.from(saved, idCodec));
    }

    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        String username = request.username() == null ? "" : request.username().trim();
        String password = request.password() == null ? "" : request.password();
        if (username.isBlank() || password.isBlank()) {
            throw ApiException.unauthorized("AUTH_BAD_CREDENTIALS", "账号或密码不正确");
        }
        UserEntity user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> ApiException.unauthorized("AUTH_BAD_CREDENTIALS", "账号或密码不正确"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.unauthorized("AUTH_BAD_CREDENTIALS", "账号或密码不正确");
        }
        return new AuthDtos.AuthResponse(jwtService.issue(user), AuthDtos.UserProfile.from(user, idCodec));
    }

    public AuthDtos.UserProfile currentUser(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("AUTH_USER_NOT_FOUND", "账号不存在或登录已失效"));
        return AuthDtos.UserProfile.from(user, idCodec);
    }
}
