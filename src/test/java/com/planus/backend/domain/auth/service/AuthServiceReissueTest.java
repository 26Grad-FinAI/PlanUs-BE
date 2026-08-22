package com.planus.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.planus.backend.domain.auth.dto.LoginResponse;
import com.planus.backend.domain.auth.dto.ReissueRequest;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.security.JwtProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceReissueTest {

    private UserAccountRepository userAccountRepository;
    private JwtProvider jwtProvider;
    private EntityManager entityManager;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        jwtProvider = mock(JwtProvider.class);
        entityManager = mock(EntityManager.class);
        authService = new AuthService(userAccountRepository, mock(PasswordEncoder.class), jwtProvider, entityManager);
    }

    @Nested
    @DisplayName("재발급 성공")
    class ReissueSuccess {

        @Test
        @DisplayName("유효한 리프레시 토큰으로 새 토큰이 발급되고 DB 해시가 갱신된다")
        void reissue_validToken_returnsNewTokens() {
            UserAccount spyUser = spy(UserAccount.builder()
                    .id(1L)
                    .email("user@example.com")
                    .refreshToken("stored-hash")
                    .build());
            when(jwtProvider.parseUserId("valid-refresh-token")).thenReturn(1L);
            when(entityManager.find(UserAccount.class, 1L, LockModeType.PESSIMISTIC_WRITE))
                    .thenReturn(spyUser);
            when(jwtProvider.hashToken("valid-refresh-token")).thenReturn("stored-hash");
            when(jwtProvider.generateAccessToken(1L)).thenReturn("new-access-token");
            when(jwtProvider.generateRefreshToken(1L)).thenReturn("new-refresh-token");
            when(jwtProvider.hashToken("new-refresh-token")).thenReturn("new-hash");

            LoginResponse response = authService.reissue(new ReissueRequest("valid-refresh-token"));

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.email()).isEqualTo("user@example.com");
            assertThat(response.accessToken()).isEqualTo("new-access-token");
            assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
            verify(spyUser).updateRefreshToken("new-hash");
        }
    }

    @Nested
    @DisplayName("재발급 실패")
    class ReissueFailure {

        @Test
        @DisplayName("만료된 리프레시 토큰이면 EXPIRED_TOKEN 예외가 발생한다")
        void reissue_expiredToken_throwsExpiredToken() {
            when(jwtProvider.parseUserId("expired-token"))
                    .thenThrow(new GeneralException(GeneralErrorCode.EXPIRED_TOKEN));

            assertThatThrownBy(() -> authService.reissue(new ReissueRequest("expired-token")))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.EXPIRED_TOKEN));
        }

        @Test
        @DisplayName("변조된 리프레시 토큰이면 INVALID_TOKEN 예외가 발생한다")
        void reissue_tamperedToken_throwsInvalidToken() {
            when(jwtProvider.parseUserId("tampered-token"))
                    .thenThrow(new GeneralException(GeneralErrorCode.INVALID_TOKEN));

            assertThatThrownBy(() -> authService.reissue(new ReissueRequest("tampered-token")))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_TOKEN));
        }

        @Test
        @DisplayName("DB 해시와 불일치하면 INVALID_TOKEN 예외가 발생한다")
        void reissue_hashMismatch_throwsInvalidToken() {
            UserAccount user =
                    UserAccount.builder().id(1L).refreshToken("stored-hash").build();
            when(jwtProvider.parseUserId("stolen-token")).thenReturn(1L);
            when(entityManager.find(UserAccount.class, 1L, LockModeType.PESSIMISTIC_WRITE))
                    .thenReturn(user);
            when(jwtProvider.hashToken("stolen-token")).thenReturn("different-hash");

            assertThatThrownBy(() -> authService.reissue(new ReissueRequest("stolen-token")))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_TOKEN));
        }

        @Test
        @DisplayName("userId에 해당하는 사용자가 없으면 INVALID_TOKEN 예외가 발생한다")
        void reissue_userNotFound_throwsInvalidToken() {
            when(jwtProvider.parseUserId("orphan-token")).thenReturn(999L);
            when(entityManager.find(UserAccount.class, 999L, LockModeType.PESSIMISTIC_WRITE))
                    .thenReturn(null);

            assertThatThrownBy(() -> authService.reissue(new ReissueRequest("orphan-token")))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_TOKEN));
        }
    }
}
