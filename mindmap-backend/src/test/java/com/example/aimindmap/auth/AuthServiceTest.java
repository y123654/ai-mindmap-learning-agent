package com.example.aimindmap.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aimindmap.common.error.ApiException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtService jwtService = new JwtService(
            "test-secret-key-test-secret-key-test-secret-key-0123456789");
    private final ExternalIdCodec idCodec = new ExternalIdCodec("unit-test-external-id-salt");
    private final AuthService authService =
            new AuthService(userRepository, passwordEncoder, jwtService, idCodec);

    @Test
    void firstRegisteredUserClaimsHistoricalDataAndReceivesToken() {
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            try {
                var idField = UserEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(user, 1L);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return user;
        });

        AuthDtos.AuthResponse response = authService.register(new AuthDtos.RegisterRequest(
                "alice", "secret123", "secret123", "计算机科学与技术"));

        assertThat(response.token()).isNotBlank();
        assertThat(response.user().username()).isEqualTo("alice");
        assertThat(response.user().major()).isEqualTo("计算机科学与技术");
        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo("secret123");
        assertThat(passwordEncoder.matches("secret123", captor.getValue().getPasswordHash())).isTrue();
        verify(userRepository).claimDocuments(1L);
        verify(userRepository).claimMindmaps(1L);
        verify(userRepository).claimQuestions(1L);
    }

    @Test
    void passwordMismatchIsRejectedBeforeSaving() {
        assertThatThrownBy(() -> authService.register(new AuthDtos.RegisterRequest(
                "alice", "secret123", "secret999", "计算机科学与技术")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("AUTH_PASSWORD_MISMATCH"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void wrongPasswordIsUnauthorized() {
        UserEntity user = new UserEntity();
        user.setUsername("alice");
        user.setPasswordHash(passwordEncoder.encode("secret123"));
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new AuthDtos.LoginRequest("alice", "wrong")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("AUTH_BAD_CREDENTIALS"));
    }
}
