package com.planus.backend.domain.auth.controller;

import com.planus.backend.domain.auth.dto.LoginRequest;
import com.planus.backend.domain.auth.dto.LoginResponse;
import com.planus.backend.domain.auth.dto.ReissueRequest;
import com.planus.backend.domain.auth.dto.SignUpRequest;
import com.planus.backend.domain.auth.dto.SignUpResponse;
import com.planus.backend.domain.auth.dto.SocialLoginRequest;
import com.planus.backend.domain.auth.service.AuthService;
import com.planus.backend.domain.auth.service.GoogleOAuthService;
import com.planus.backend.domain.auth.service.KakaoOAuthService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 인증 관련 REST API 컨트롤러. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final GoogleOAuthService googleOAuthService;
    private final KakaoOAuthService kakaoOAuthService;

    /**
     * 신규 회원을 등록하고 JWT 액세스/리프레시 토큰을 발급한다.
     *
     * @param request 이메일, 비밀번호, 닉네임, 약관 동의 여부
     * @return 201 Created, userId·email·토큰·프로필 완료 여부
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignUpResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        SignUpResponse response = authService.signUp(request);
        return ApiResponse.onSuccess(GeneralSuccessCode.CREATED, response);
    }

    /**
     * 이메일/비밀번호로 로그인하고 JWT 액세스/리프레시 토큰을 발급한다.
     *
     * @param request 이메일, 비밀번호
     * @return 200 OK, userId·email·토큰·프로필 완료 여부
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, authService.login(request));
    }

    /**
     * Google 인가코드로 소셜 로그인하고 JWT 액세스/리프레시 토큰을 발급한다.
     *
     * @param request 인가코드, 리다이렉트 URI
     * @return 200 OK, userId·email·토큰·프로필 완료 여부
     */
    @PostMapping("/oauth2/google")
    public ApiResponse<LoginResponse> googleLogin(@Valid @RequestBody SocialLoginRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, googleOAuthService.login(request));
    }

    /**
     * Kakao 인가코드로 소셜 로그인하고 JWT 액세스/리프레시 토큰을 발급한다.
     *
     * @param request 인가코드, 리다이렉트 URI
     * @return 200 OK, userId·email·토큰·프로필 완료 여부
     */
    @PostMapping("/oauth2/kakao")
    public ApiResponse<LoginResponse> kakaoLogin(@Valid @RequestBody SocialLoginRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, kakaoOAuthService.login(request));
    }

    /**
     * 리프레시 토큰을 검증하고 새 액세스/리프레시 토큰을 발급한다.
     *
     * @param request 리프레시 토큰
     * @return 200 OK, userId·email·새 토큰·프로필 완료 여부
     */
    @PostMapping("/reissue")
    public ApiResponse<LoginResponse> reissue(@Valid @RequestBody ReissueRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, authService.reissue(request));
    }

    /**
     * 로그아웃하고 DB의 리프레시 토큰 해시를 삭제한다.
     *
     * @param authentication JWT 필터가 주입한 인증 정보 (userId 포함)
     * @return 200 OK
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal Long userId) {
        authService.logout(userId);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK);
    }
}
