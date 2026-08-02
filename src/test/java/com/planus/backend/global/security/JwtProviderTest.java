package com.planus.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-32-bytes!!";
    private static final long ACCESS_EXPIRY = 3600000L;
    private static final long REFRESH_EXPIRY = 1209600000L;

    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(SECRET, ACCESS_EXPIRY, REFRESH_EXPIRY);
    }

    @Nested
    @DisplayName("validateToken")
    class ValidateToken {

        @Test
        @DisplayName("유효한 토큰이면 true를 반환한다")
        void validateToken_validToken_returnsTrue() {
            String token = jwtProvider.generateAccessToken(1L);

            assertThat(jwtProvider.validateToken(token)).isTrue();
        }

        @Test
        @DisplayName("만료된 토큰이면 EXPIRED_TOKEN 예외가 발생한다")
        void validateToken_expiredToken_throwsExpiredToken() {
            JwtProvider expiredProvider = new JwtProvider(SECRET, -1L, REFRESH_EXPIRY);
            String token = expiredProvider.generateAccessToken(1L);

            assertThatThrownBy(() -> jwtProvider.validateToken(token))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.EXPIRED_TOKEN));
        }

        @Test
        @DisplayName("서명이 다른 토큰이면 INVALID_TOKEN 예외가 발생한다")
        void validateToken_wrongSignature_throwsInvalidToken() {
            JwtProvider otherProvider =
                    new JwtProvider("other-secret-key-must-be-at-least-32-bytes!!", ACCESS_EXPIRY, REFRESH_EXPIRY);
            String token = otherProvider.generateAccessToken(1L);

            assertThatThrownBy(() -> jwtProvider.validateToken(token))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_TOKEN));
        }

        @Test
        @DisplayName("형식이 올바르지 않은 토큰이면 INVALID_TOKEN 예외가 발생한다")
        void validateToken_malformedToken_throwsInvalidToken() {
            assertThatThrownBy(() -> jwtProvider.validateToken("not.a.jwt"))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                            .isEqualTo(GeneralErrorCode.INVALID_TOKEN));
        }
    }

    @Nested
    @DisplayName("getUserId")
    class GetUserId {

        @Test
        @DisplayName("유효한 토큰에서 userId를 정확히 추출한다")
        void getUserId_validToken_returnsUserId() {
            String token = jwtProvider.generateAccessToken(42L);

            assertThat(jwtProvider.getUserId(token)).isEqualTo(42L);
        }
    }
}
