package com.planus.backend.domain.auth.controller;

import com.planus.backend.domain.auth.dto.LoginRequest;
import com.planus.backend.domain.auth.dto.LoginResponse;
import com.planus.backend.domain.auth.dto.SignUpRequest;
import com.planus.backend.domain.auth.dto.SignUpResponse;
import com.planus.backend.domain.auth.service.AuthService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
}
