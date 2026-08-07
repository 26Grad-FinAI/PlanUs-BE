package com.planus.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.security.JwtProvider;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceLogoutTest {

    private UserAccountRepository userAccountRepository;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        authService =
                new AuthService(
                        userAccountRepository,
                        mock(PasswordEncoder.class),
                        mock(JwtProvider.class),
                        mock(EntityManager.class));
    }

    @Test
    @DisplayName("로그아웃 시 DB의 리프레시 토큰 해시가 null로 갱신된다")
    void logout_clearsRefreshToken() {
        UserAccount spyUser =
                spy(UserAccount.builder().id(1L).refreshToken("stored-hash").build());
        when(userAccountRepository.findById(1L)).thenReturn(Optional.of(spyUser));

        authService.logout(1L);

        verify(spyUser).updateRefreshToken(null);
    }

    @Test
    @DisplayName("존재하지 않는 userId로 로그아웃 시 NOT_FOUND 예외가 발생한다")
    void logout_userNotFound_throwsNotFound() {
        when(userAccountRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.logout(999L))
                .isInstanceOf(GeneralException.class)
                .satisfies(
                        ex ->
                                assertThat(((GeneralException) ex).getErrorCode())
                                        .isEqualTo(GeneralErrorCode.NOT_FOUND));
    }
}
