package com.planus.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

class GoogleOAuthServiceHttpContractTest {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URI = "https://www.googleapis.com/oauth2/v3/userinfo";

    private MockRestServiceServer mockServer;
    private GoogleOAuthService googleOAuthService;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        RestClient restClient = RestClient.create(restTemplate);
        googleOAuthService = new GoogleOAuthService(
                mock(UserAccountRepository.class),
                mock(UserAccountPersister.class),
                mock(JwtProvider.class),
                restClient,
                "test-client-id",
                "test-client-secret",
                TOKEN_URI,
                USERINFO_URI,
                List.of("http://localhost/callback"));
    }

    @Test
    @DisplayName("fetchAccessToken은 올바른 form 파라미터를 Google token endpoint로 전송한다")
    void fetchAccessToken_sendsCorrectFormParams() {
        mockServer.expect(requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(allOf(
                        containsString("code=auth-code"),
                        containsString("client_id=test-client-id"),
                        containsString("client_secret=test-client-secret"),
                        containsString("redirect_uri=http%3A%2F%2Flocalhost%2Fcallback"),
                        containsString("grant_type=authorization_code"))))
                .andRespond(withSuccess(
                        "{\"access_token\":\"google-access-token\"}",
                        MediaType.APPLICATION_JSON));

        String token = googleOAuthService.fetchAccessToken("auth-code", "http://localhost/callback");

        assertThat(token).isEqualTo("google-access-token");
        mockServer.verify();
    }

    @Test
    @DisplayName("fetchUserInfo는 Authorization 헤더를 포함해 userinfo endpoint를 호출하고 email_verified를 매핑한다")
    void fetchUserInfo_sendsAuthorizationHeaderAndMapsEmailVerified() {
        mockServer.expect(requestTo(USERINFO_URI))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess(
                        "{\"sub\":\"sub123\",\"email\":\"user@example.com\",\"name\":\"홍길동\",\"email_verified\":true}",
                        MediaType.APPLICATION_JSON));

        GoogleOAuthService.GoogleUserInfo userInfo = googleOAuthService.fetchUserInfo("test-token");

        assertThat(userInfo.sub()).isEqualTo("sub123");
        assertThat(userInfo.email()).isEqualTo("user@example.com");
        assertThat(userInfo.emailVerified()).isTrue();
        mockServer.verify();
    }

    @Test
    @DisplayName("Google token endpoint가 4xx를 반환하면 INVALID_CREDENTIALS 예외가 발생한다")
    void fetchAccessToken_googleReturns4xx_throwsInvalidCredentials() {
        mockServer.expect(requestTo(TOKEN_URI))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> googleOAuthService.fetchAccessToken("bad-code", "http://localhost/callback"))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                        .isEqualTo(GeneralErrorCode.INVALID_CREDENTIALS));
        mockServer.verify();
    }

    @Test
    @DisplayName("Google token endpoint가 5xx를 반환하면 SOCIAL_LOGIN_UNAVAILABLE 예외가 발생한다")
    void fetchAccessToken_googleReturns5xx_throwsSocialLoginUnavailable() {
        mockServer.expect(requestTo(TOKEN_URI))
                .andRespond(withServerError());

        assertThatThrownBy(() -> googleOAuthService.fetchAccessToken("code", "http://localhost/callback"))
                .isInstanceOf(GeneralException.class)
                .satisfies(ex -> assertThat(((GeneralException) ex).getErrorCode())
                        .isEqualTo(GeneralErrorCode.SOCIAL_LOGIN_UNAVAILABLE));
        mockServer.verify();
    }
}
