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
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Kakao OAuth2 인가코드 방식 소셜 로그인 서비스. */
@Service
public class KakaoOAuthService {

    private final UserAccountRepository userAccountRepository;
    private final JwtProvider jwtProvider;
    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private final String tokenUri;
    private final String userinfoUri;
    private final List<String> allowedRedirectUris;

    public KakaoOAuthService(
            UserAccountRepository userAccountRepository,
            JwtProvider jwtProvider,
            RestClient restClient,
            @Value("${planus.oauth2.kakao.client-id}") String clientId,
            @Value("${planus.oauth2.kakao.client-secret}") String clientSecret,
            @Value("${planus.oauth2.kakao.token-uri}") String tokenUri,
            @Value("${planus.oauth2.kakao.userinfo-uri}") String userinfoUri,
            @Value("${planus.oauth2.kakao.allowed-redirect-uris}") List<String> allowedRedirectUris) {
        this.userAccountRepository = userAccountRepository;
        this.jwtProvider = jwtProvider;
        this.restClient = restClient;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tokenUri = tokenUri;
        this.userinfoUri = userinfoUri;
        this.allowedRedirectUris = allowedRedirectUris;
    }

    /**
     * Kakao 인가코드로 로그인을 처리한다.
     *
     * <p>인가코드로 Kakao access_token을 교환하고 사용자 정보를 조회한 뒤,
     * 신규 사용자면 가입 처리하고 기존 사용자면 로그인 처리하여 JWT를 발급한다.</p>
     *
     * @param request 인가코드, 리다이렉트 URI
     * @return 사용자 정보 및 JWT 토큰
     */
    @Transactional
    public LoginResponse login(SocialLoginRequest request) {
        if (!allowedRedirectUris.contains(request.redirectUri())) {
            throw new GeneralException(GeneralErrorCode.INVALID_REDIRECT_URI);
        }
        String accessToken = fetchAccessToken(request.code(), request.redirectUri());
        KakaoUserInfo userInfo = fetchUserInfo(accessToken);
        if (!Boolean.TRUE.equals(userInfo.kakaoAccount().emailVerified())) {
            throw new GeneralException(GeneralErrorCode.UNVERIFIED_SOCIAL_EMAIL);
        }
        UserAccount user = findOrCreateUser(userInfo);

        String jwtAccessToken = jwtProvider.generateAccessToken(user.getId());
        String jwtRefreshToken = jwtProvider.generateRefreshToken(user.getId());
        user.updateRefreshToken(jwtProvider.hashToken(jwtRefreshToken));

        return AuthConverter.toLoginResponse(user, jwtAccessToken, jwtRefreshToken);
    }

    /**
     * Kakao token endpoint에 인가코드를 전송해 access_token을 교환한다.
     *
     * @throws GeneralException 4xx 응답이면 INVALID_CREDENTIALS, 5xx·타임아웃이면 SOCIAL_LOGIN_UNAVAILABLE
     */
    protected String fetchAccessToken(String code, String redirectUri) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("code", code);
        params.add("client_id", clientId);
        params.add("client_secret", clientSecret);
        params.add("redirect_uri", redirectUri);
        params.add("grant_type", "authorization_code");

        try {
            KakaoTokenResponse response = restClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(KakaoTokenResponse.class);

            if (response == null || response.accessToken() == null) {
                throw new GeneralException(GeneralErrorCode.INVALID_CREDENTIALS);
            }
            return response.accessToken();
        } catch (HttpClientErrorException e) {
            throw new GeneralException(GeneralErrorCode.INVALID_CREDENTIALS, e);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new GeneralException(GeneralErrorCode.SOCIAL_LOGIN_UNAVAILABLE, e);
        }
    }

    /**
     * Kakao userinfo endpoint를 호출해 사용자 정보를 조회한다.
     *
     * @throws GeneralException 4xx 응답이면 INVALID_CREDENTIALS, 5xx·타임아웃이면 SOCIAL_LOGIN_UNAVAILABLE
     */
    protected KakaoUserInfo fetchUserInfo(String accessToken) {
        try {
            KakaoUserInfo userInfo = restClient.get()
                    .uri(userinfoUri)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(KakaoUserInfo.class);

            if (userInfo == null || userInfo.id() == null || userInfo.kakaoAccount() == null
                    || userInfo.kakaoAccount().email() == null) {
                throw new GeneralException(GeneralErrorCode.INVALID_CREDENTIALS);
            }
            return userInfo;
        } catch (HttpClientErrorException e) {
            throw new GeneralException(GeneralErrorCode.INVALID_CREDENTIALS, e);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new GeneralException(GeneralErrorCode.SOCIAL_LOGIN_UNAVAILABLE, e);
        }
    }

    /** 기존 Kakao 사용자를 조회하고, 없으면 신규 가입 처리한다. */
    private UserAccount findOrCreateUser(KakaoUserInfo userInfo) {
        String providerId = String.valueOf(userInfo.id());
        return userAccountRepository
                .findByProviderAndProviderId(AuthProvider.KAKAO, providerId)
                .orElseGet(() -> createUser(userInfo, providerId));
    }

    /**
     * 신규 Kakao 사용자를 저장한다.
     *
     * <p>동시 요청으로 중복 삽입이 발생하면 이미 저장된 사용자를 반환한다.</p>
     *
     * @throws GeneralException 동일 이메일로 다른 provider 계정이 존재하면 SOCIAL_LOGIN_EMAIL_CONFLICT
     */
    private UserAccount createUser(KakaoUserInfo userInfo, String providerId) {
        String email = userInfo.kakaoAccount().email();
        userAccountRepository.findByEmail(email).ifPresent(existing -> {
            throw new GeneralException(GeneralErrorCode.SOCIAL_LOGIN_EMAIL_CONFLICT);
        });

        try {
            return userAccountRepository.save(UserAccount.builder()
                    .email(email)
                    .nickname(userInfo.kakaoAccount().profile().nickname())
                    .provider(AuthProvider.KAKAO)
                    .providerId(providerId)
                    .build());
        } catch (DataIntegrityViolationException e) {
            return userAccountRepository
                    .findByProviderAndProviderId(AuthProvider.KAKAO, providerId)
                    .orElseThrow(() -> new GeneralException(GeneralErrorCode.INTERNAL_SERVER_ERROR, e));
        }
    }

    record KakaoTokenResponse(@JsonProperty("access_token") String accessToken) {}

    record KakaoUserInfo(Long id, @JsonProperty("kakao_account") KakaoAccount kakaoAccount) {}

    record KakaoAccount(
            String email,
            @JsonProperty("is_email_verified") Boolean emailVerified,
            KakaoProfile profile) {}

    record KakaoProfile(String nickname) {}
}
