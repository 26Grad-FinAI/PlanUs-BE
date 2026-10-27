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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Auth", description = "회원가입·로그인·소셜로그인·토큰재발급·로그아웃 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final GoogleOAuthService googleOAuthService;
    private final KakaoOAuthService kakaoOAuthService;

    @Operation(
            summary = "회원가입",
            description = """
                    이메일·비밀번호로 신규 회원을 등록하고 JWT 토큰을 발급한다.

                    **비밀번호 정책:** 8자 이상, 영문+숫자 필수 포함
                    **주의사항:**
                    - password와 confirmPassword가 일치해야 한다.
                    - agreeToTerms가 true여야 한다.
                    - 소셜 로그인으로 이미 가입된 이메일은 사용할 수 없다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "회원가입 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패 (이메일 형식, 비밀번호 정책, 약관 미동의 등)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일")
    })
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignUpResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        SignUpResponse response = authService.signUp(request);
        return ApiResponse.onSuccess(GeneralSuccessCode.CREATED, response);
    }

    @Operation(
            summary = "로그인",
            description = """
                    이메일·비밀번호로 로그인하고 JWT 토큰을 발급한다.

                    **주의사항:**
                    - 소셜 로그인으로 가입된 이메일은 일반 로그인 불가 (409 응답).
                    - profileCompleted가 false이면 프로필 저장 API를 먼저 호출해야 한다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호 불일치"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "소셜 로그인으로 가입된 이메일")
    })
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, authService.login(request));
    }

    @Operation(
            summary = "Google 소셜 로그인",
            description = """
                    Google SDK가 발급한 id_token으로 소셜 로그인하고 JWT 토큰을 발급한다.
                    신규 사용자는 자동 회원가입 처리된다.

                    **idToken 필드:** Google SDK에서 발급받은 id_token 값을 전달한다.
                    **주의사항:**
                    - 이메일 인증이 완료된 Google 계정만 사용 가능하다.
                    - 다른 방식(일반/카카오)으로 이미 가입된 이메일은 409 응답.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "소셜 로그인 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "유효하지 않은 id_token 또는 이메일 미인증"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "다른 방식으로 가입된 이메일"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Google 인증 서비스 일시 불가")
    })
    @PostMapping("/oauth2/google")
    public ApiResponse<LoginResponse> googleLogin(@Valid @RequestBody SocialLoginRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, googleOAuthService.login(request));
    }

    @Operation(
            summary = "Kakao 소셜 로그인",
            description = """
                    Kakao SDK가 발급한 access_token으로 소셜 로그인하고 JWT 토큰을 발급한다.
                    신규 사용자는 자동 회원가입 처리된다.

                    **idToken 필드:** Kakao SDK에서 발급받은 access_token 값을 전달한다.
                    **주의사항:**
                    - 다른 방식(일반/구글)으로 이미 가입된 이메일은 409 응답.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "소셜 로그인 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "유효하지 않은 access_token"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "다른 방식으로 가입된 이메일"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Kakao 인증 서비스 일시 불가")
    })
    @PostMapping("/oauth2/kakao")
    public ApiResponse<LoginResponse> kakaoLogin(@Valid @RequestBody SocialLoginRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, kakaoOAuthService.login(request));
    }

    @Operation(
            summary = "토큰 재발급",
            description = """
                    리프레시 토큰을 검증하고 새 액세스·리프레시 토큰을 발급한다.

                    **주의사항:**
                    - 재발급 시 기존 리프레시 토큰은 즉시 폐기된다 (Refresh Token Rotation).
                    - 동일 리프레시 토큰으로 중복 요청 시 401 응답.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "토큰 재발급 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "유효하지 않거나 만료된 리프레시 토큰")
    })
    @PostMapping("/reissue")
    public ApiResponse<LoginResponse> reissue(@Valid @RequestBody ReissueRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, authService.reissue(request));
    }

    @Operation(
            summary = "로그아웃",
            description = """
                    로그아웃하고 서버에 저장된 리프레시 토큰을 삭제한다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal Long userId) {
        authService.logout(userId);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK);
    }
}
