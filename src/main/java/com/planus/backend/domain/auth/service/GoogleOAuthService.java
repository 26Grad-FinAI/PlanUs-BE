package com.planus.backend.domain.auth.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.planus.backend.domain.auth.converter.AuthConverter;
import com.planus.backend.domain.auth.dto.LoginResponse;
import com.planus.backend.domain.auth.dto.SocialLoginRequest;
import com.planus.backend.domain.user.UserAccountPersister;
import com.planus.backend.domain.user.entity.AuthProvider;
import com.planus.backend.domain.user.entity.UserAccount;
import com.planus.backend.domain.user.repository.UserAccountRepository;
import com.planus.backend.global.apiPayload.code.GeneralErrorCode;
import com.planus.backend.global.apiPayload.exception.GeneralException;
import com.planus.backend.global.security.JwtProvider;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Google OAuth2 SDK(id_token 검증) 방식 소셜 로그인 서비스. */
@Slf4j
@Service
public class GoogleOAuthService {

    private final UserAccountRepository userAccountRepository;
    private final UserAccountPersister userAccountPersister;
    private final JwtProvider jwtProvider;
    private final GoogleIdTokenVerifier verifier;

    public GoogleOAuthService(
            UserAccountRepository userAccountRepository,
            UserAccountPersister userAccountPersister,
            JwtProvider jwtProvider,
            @Value("${planus.oauth2.google.client-id}") String clientId) {
        this.userAccountRepository = userAccountRepository;
        this.userAccountPersister = userAccountPersister;
        this.jwtProvider = jwtProvider;
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(clientId))
                .build();
    }

    /**
     * Google SDK가 발급한 id_token을 검증하고 로그인을 처리한다.
     *
     * <p>id_token의 서명·만료·audience를 검증한 뒤, 신규 사용자면 가입 처리하고
     * 기존 사용자면 로그인 처리하여 JWT를 발급한다.</p>
     *
     * @param request 클라이언트 SDK가 발급한 id_token
     * @return 사용자 정보 및 JWT 토큰
     */
    @Transactional
    public LoginResponse login(SocialLoginRequest request) {
        GoogleIdToken.Payload payload = verifyIdToken(request.idToken());

        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new GeneralException(GeneralErrorCode.UNVERIFIED_SOCIAL_EMAIL);
        }

        String sub = payload.getSubject();
        String email = payload.getEmail();
        String name = (String) payload.get("name");

        UserAccount user = findOrCreateUser(sub, email, name);

        String jwtAccessToken = jwtProvider.generateAccessToken(user.getId());
        String jwtRefreshToken = jwtProvider.generateRefreshToken(user.getId());
        user.updateRefreshToken(jwtProvider.hashToken(jwtRefreshToken));

        return AuthConverter.toLoginResponse(user, jwtAccessToken, jwtRefreshToken);
    }

    /**
     * Google id_token을 검증하고 payload를 반환한다.
     *
     * @throws GeneralException 서명·만료·audience 검증 실패 시 INVALID_ID_TOKEN,
     *                          공개키 조회 실패 시 SOCIAL_LOGIN_UNAVAILABLE
     */
    GoogleIdToken.Payload verifyIdToken(String idTokenString) {
        try {
            GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken == null) {
                throw new GeneralException(GeneralErrorCode.INVALID_ID_TOKEN);
            }
            return idToken.getPayload();
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("[GoogleOAuth] id_token verification failed. cause={}", e.getMessage());
            throw new GeneralException(GeneralErrorCode.INVALID_ID_TOKEN, e);
        } catch (IOException e) {
            log.warn("[GoogleOAuth] Google public key fetch failed. cause={}", e.getMessage());
            throw new GeneralException(GeneralErrorCode.SOCIAL_LOGIN_UNAVAILABLE, e);
        }
    }

    /** 기존 Google 사용자를 조회하고, 없으면 신규 가입 처리한다. */
    private UserAccount findOrCreateUser(String sub, String email, String name) {
        return userAccountRepository
                .findByProviderAndProviderId(AuthProvider.GOOGLE, sub)
                .orElseGet(() -> createUser(sub, email, name));
    }

    /**
     * 신규 Google 사용자를 저장한다.
     *
     * <p>동시 요청으로 중복 삽입이 발생하면 이미 저장된 사용자를 반환한다.</p>
     *
     * @throws GeneralException 동일 이메일로 다른 provider 계정이 존재하면 SOCIAL_LOGIN_EMAIL_CONFLICT
     */
    private UserAccount createUser(String sub, String email, String name) {
        userAccountRepository.findByEmail(email).ifPresent(existing -> {
            throw new GeneralException(GeneralErrorCode.SOCIAL_LOGIN_EMAIL_CONFLICT);
        });

        try {
            userAccountPersister.saveAndFlush(UserAccount.builder()
                    .email(email)
                    .nickname(name)
                    .provider(AuthProvider.GOOGLE)
                    .providerId(sub)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 중복 삽입 발생 시 이미 저장된 사용자를 반환한다.
        }
        return userAccountRepository
                .findByProviderAndProviderId(AuthProvider.GOOGLE, sub)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.INTERNAL_SERVER_ERROR));
    }
}
