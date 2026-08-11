package com.planus.backend.domain.user.controller;

import com.planus.backend.domain.auth.service.AuthService;
import com.planus.backend.domain.user.dto.UserProfileRequest;
import com.planus.backend.domain.user.dto.UserProfileResponse;
import com.planus.backend.domain.user.service.UserService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 관련 REST API 컨트롤러. */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final UserService userService;

    /**
     * 사용자 프로필을 최초 저장하고 가용예산을 반환한다.
     *
     * @param userId  JWT 필터가 주입한 인증 정보 (userId 포함)
     * @param request 나이·성별·거주지·재무정보·라이프스타일 프로필
     * @return 201 Created, userId·닉네임·가용예산
     */
    @PostMapping("/me/profile")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserProfileResponse> saveProfile(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody UserProfileRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.CREATED, userService.saveProfile(userId, request));
    }

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
