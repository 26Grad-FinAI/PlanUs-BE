package com.planus.backend.domain.auth.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.planus.backend.domain.auth.dto.SignUpRequest;
import com.planus.backend.domain.auth.dto.SignUpResponse;
import com.planus.backend.domain.user.entity.AuthProvider;
import com.planus.backend.domain.user.entity.UserAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AuthConverterTest {

    @Nested
    @DisplayName("toUserAccount()")
    class ToUserAccount {

        @Test
        @DisplayName("요청 필드가 UserAccount에 올바르게 매핑된다")
        void mapsFieldsCorrectly() {
            SignUpRequest request = new SignUpRequest("user@example.com", "pass1234", "pass1234", "닉네임", true);
            String encodedPassword = "encoded_pass1234";

            UserAccount user = AuthConverter.toUserAccount(request, encodedPassword);

            assertThat(user.getEmail()).isEqualTo("user@example.com");
            assertThat(user.getNickname()).isEqualTo("닉네임");
            assertThat(user.getPassword()).isEqualTo("encoded_pass1234");
        }

        @Test
        @DisplayName("provider가 LOCAL로 설정된다")
        void setsProviderAsLocal() {
            SignUpRequest request = new SignUpRequest("user@example.com", "pass1234", "pass1234", "닉네임", true);

            UserAccount user = AuthConverter.toUserAccount(request, "encoded");

            assertThat(user.getProvider()).isEqualTo(AuthProvider.LOCAL);
        }
    }

    @Nested
    @DisplayName("toSignUpResponse()")
    class ToSignUpResponse {

        @Test
        @DisplayName("UserAccount와 토큰이 SignUpResponse에 올바르게 매핑된다")
        void mapsFieldsCorrectly() {
            UserAccount user = UserAccount.builder()
                    .id(1L)
                    .email("user@example.com")
                    .nickname("닉네임")
                    .password("encoded")
                    .build();

            SignUpResponse response = AuthConverter.toSignUpResponse(user, "access-token", "refresh-token");

            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.email()).isEqualTo("user@example.com");
            assertThat(response.accessToken()).isEqualTo("access-token");
            assertThat(response.refreshToken()).isEqualTo("refresh-token");
            assertThat(response.profileCompleted()).isFalse();
        }
    }
}
