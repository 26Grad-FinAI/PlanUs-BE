package com.planus.backend.domain.user.controller;

import com.planus.backend.domain.auth.service.AuthService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 관련 REST API 컨트롤러. */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    /**
     * 회원 탈퇴를 처리한다.
     *
     * @param userId JWT 필터가 주입한 인증 정보 (userId 포함)
     * @return 200 OK
     */
    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal Long userId) {
        authService.withdraw(userId);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK);
    }
}
