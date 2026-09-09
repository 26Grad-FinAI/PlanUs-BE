package com.planus.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.planus.backend.domain.user.UserAccountPersister;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.security.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

class KakaoOAuthServiceHttpContractTest {

    private static final String USERINFO_URI = "https://kapi.kakao.com/v2/user/me";

    private MockRestServiceServer mockServer;
    private KakaoOAuthService kakaoOAuthService;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        RestClient restClient = RestClient.create(restTemplate);
        kakaoOAuthService = new KakaoOAuthService(
                mock(UserAccountRepository.class),
                mock(UserAccountPersister.class),
                mock(JwtProvider.class),
                restClient,
                USERINFO_URI,
                "https://kapi.kakao.com/v1/user/access_token_info",
                12345L);
    }

    @Test
    @DisplayName("fetchUserInfo는 Authorization 헤더를 포함해 userinfo endpoint를 호출하고 중첩 JSON을 매핑한다")
    void fetchUserInfo_sendsAuthorizationHeaderAndMapsNestedJson() {
        mockServer
                .expect(requestTo(USERINFO_URI))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess(
                        "{\"id\":123456789,"
                                + "\"kakao_account\":{"
                                + "\"email\":\"user@kakao.com\","
                                + "\"is_email_verified\":true,"
                                + "\"is_email_valid\":true,"
                                + "\"profile\":{\"nickname\":\"홍길동\"}}}",
                        MediaType.APPLICATION_JSON));

        KakaoOAuthService.KakaoUserInfo userInfo = kakaoOAuthService.fetchUserInfo("test-token");

        assertThat(userInfo.id()).isEqualTo(123456789L);
        assertThat(userInfo.kakaoAccount().email()).isEqualTo("user@kakao.com");
        assertThat(userInfo.kakaoAccount().emailVerified()).isTrue();
        assertThat(userInfo.kakaoAccount().profile().nickname()).isEqualTo("홍길동");
        mockServer.verify();
    }

    @Test
    @DisplayName("Kakao userinfo endpoint가 4xx를 반환하면 INVALID_CREDENTIALS 예외가 발생한다")
    void fetchUserInfo_kakaoReturns4xx_throwsInvalidCredentials() {
        mockServer.expect(requestTo(USERINFO_URI)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> kakaoOAuthService.fetchUserInfo("bad-token"))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                        .isEqualTo(GeneralErrorCode.INVALID_CREDENTIALS));
        mockServer.verify();
    }

    @Test
    @DisplayName("Kakao userinfo endpoint가 5xx를 반환하면 SOCIAL_LOGIN_UNAVAILABLE 예외가 발생한다")
    void fetchUserInfo_kakaoReturns5xx_throwsSocialLoginUnavailable() {
        mockServer.expect(requestTo(USERINFO_URI)).andRespond(withServerError());

        assertThatThrownBy(() -> kakaoOAuthService.fetchUserInfo("token"))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                        .isEqualTo(GeneralErrorCode.SOCIAL_LOGIN_UNAVAILABLE));
        mockServer.verify();
    }
}
