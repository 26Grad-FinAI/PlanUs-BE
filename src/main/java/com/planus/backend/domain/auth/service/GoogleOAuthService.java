package com.planus.backend.domain.auth.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.planus.backend.domain.auth.converter.AuthConverter;
import com.planus.backend.domain.auth.dto.LoginResponse;
import com.planus.backend.domain.auth.dto.SocialLoginRequest;
import com.planus.backend.domain.user.entity.AuthProvider;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Google OAuth2 인가코드 방식 소셜 로그인 서비스. */
@Service
public class GoogleOAuthService {

    private final UserAccountRepository userAccountRepository;
    private final JwtProvider jwtProvider;
    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private final String tokenUri;
    private final String userinfoUri;

    public GoogleOAuthService(
            UserAccountRepository userAccountRepository,
            JwtProvider jwtProvider,
            RestClient restClient,
            @Value("${planus.oauth2.google.client-id}") String clientId,
            @Value("${planus.oauth2.google.client-secret}") String clientSecret,
            @Value("${planus.oauth2.google.token-uri}") String tokenUri,
            @Value("${planus.oauth2.google.userinfo-uri}") String userinfoUri) {
        this.userAccountRepository = userAccountRepository;
        this.jwtProvider = jwtProvider;
        this.restClient = restClient;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tokenUri = tokenUri;
        this.userinfoUri = userinfoUri;
    }

    /**
     * Google 인가코드로 로그인을 처리한다.
     *
     * <p>인가코드로 Google access_token을 교환하고 userinfo를 조회한 뒤,
     * 신규 사용자면 가입 처리하고 기존 사용자면 로그인 처리하여 JWT를 발급한다.</p>
     *
     * @param request 인가코드, 리다이렉트 URI
     * @return 사용자 정보 및 JWT 토큰
     */
    @Transactional
    public LoginResponse login(SocialLoginRequest request) {
        String accessToken = fetchAccessToken(request.code(), request.redirectUri());
        GoogleUserInfo userInfo = fetchUserInfo(accessToken);
        UserAccount user = findOrCreateUser(userInfo);

        String jwtAccessToken = jwtProvider.generateAccessToken(user.getId());
        String jwtRefreshToken = jwtProvider.generateRefreshToken(user.getId());
        user.updateRefreshToken(jwtProvider.hashToken(jwtRefreshToken));

        return AuthConverter.toLoginResponse(user, jwtAccessToken, jwtRefreshToken);
    }

    /**
     * Google token endpoint에 인가코드를 전송해 access_token을 교환한다.
     *
     * @throws GeneralException Google이 오류 응답을 반환하면 INVALID_CREDENTIALS
     */
    protected String fetchAccessToken(String code, String redirectUri) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("code", code);
        params.add("client_id", clientId);
        params.add("client_secret", clientSecret);
        params.add("redirect_uri", redirectUri);
        params.add("grant_type", "authorization_code");

        try {
            GoogleTokenResponse response = restClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(GoogleTokenResponse.class);

            if (response == null || response.accessToken() == null) {
                throw new GeneralException(GeneralErrorCode.INVALID_CREDENTIALS);
            }
            return response.accessToken();
        } catch (RestClientResponseException e) {
            throw new GeneralException(GeneralErrorCode.INVALID_CREDENTIALS, e);
        }
    }

    /**
     * Google userinfo endpoint를 호출해 사용자 정보를 조회한다.
     *
     * @throws GeneralException Google이 오류 응답을 반환하면 INVALID_CREDENTIALS
     */
    protected GoogleUserInfo fetchUserInfo(String accessToken) {
        try {
            GoogleUserInfo userInfo = restClient.get()
                    .uri(userinfoUri)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(GoogleUserInfo.class);

            if (userInfo == null || userInfo.sub() == null || userInfo.email() == null) {
                throw new GeneralException(GeneralErrorCode.INVALID_CREDENTIALS);
            }
            return userInfo;
        } catch (RestClientResponseException e) {
            throw new GeneralException(GeneralErrorCode.INVALID_CREDENTIALS, e);
        }
    }

    private UserAccount findOrCreateUser(GoogleUserInfo userInfo) {
        return userAccountRepository
                .findByProviderAndProviderId(AuthProvider.GOOGLE, userInfo.sub())
                .orElseGet(() -> createUser(userInfo));
    }

    private UserAccount createUser(GoogleUserInfo userInfo) {
        userAccountRepository.findByEmail(userInfo.email()).ifPresent(existing -> {
            throw new GeneralException(GeneralErrorCode.SOCIAL_LOGIN_EMAIL_CONFLICT);
        });

        return userAccountRepository.save(UserAccount.builder()
                .email(userInfo.email())
                .nickname(userInfo.name())
                .provider(AuthProvider.GOOGLE)
                .providerId(userInfo.sub())
                .build());
    }

    record GoogleTokenResponse(@JsonProperty("access_token") String accessToken) {}

    record GoogleUserInfo(String sub, String email, String name) {}
}
