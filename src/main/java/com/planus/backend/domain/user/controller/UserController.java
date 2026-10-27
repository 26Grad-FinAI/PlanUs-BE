package com.planus.backend.domain.user.controller;

import com.planus.backend.domain.auth.service.AuthService;
import com.planus.backend.domain.user.dto.UserProfileGetResponse;
import com.planus.backend.domain.user.dto.UserProfileRequest;
import com.planus.backend.domain.user.dto.UserProfileResponse;
import com.planus.backend.domain.user.dto.UserProfileUpdateResponse;
import com.planus.backend.domain.user.service.UserService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 관련 REST API 컨트롤러. */
@Tag(name = "User", description = "사용자 프로필 저장·수정·조회·회원탈퇴 API")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final UserService userService;

    @Operation(
            summary = "프로필 최초 저장",
            description =
                    """
                    회원가입 후 사용자 프로필을 최초 저장한다.
                    가용예산(monthlyIncome - monthlyFixedExpenses - monthlySavingsGoal)이 자동 계산되어 반환된다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **spendingHabit:** SAVING / BALANCED / SPENDING 중 하나.
                    **hobbies:** 1개 이상 필수, 각 항목은 공백 불가.
                    **주의사항:**
                    - 이미 프로필이 저장된 상태에서 호출하면 409 응답.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "프로필 저장 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 프로필이 존재함")
    })
    @PostMapping("/me/profile")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserProfileResponse> saveProfile(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody UserProfileRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.CREATED, userService.saveProfile(userId, request));
    }

    @Operation(
            summary = "프로필 수정",
            description =
                    """
                    사용자 프로필을 수정하고 갱신된 가용예산과 수정일시를 반환한다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **주의사항:**
                    - 전체 필드를 다시 전달해야 한다 (부분 수정 불가).""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "프로필 수정 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @PutMapping("/me/profile")
    public ApiResponse<UserProfileUpdateResponse> updateProfile(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody UserProfileRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, userService.updateProfile(userId, request));
    }

    @Operation(
            summary = "프로필 조회",
            description =
                    """
                    사용자 프로필 전체 정보를 조회한다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **주의사항:**
                    - 프로필 미저장 시 profileCompleted가 false이고, 프로필 필드들은 null로 반환된다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "프로필 조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @GetMapping("/me/profile")
    public ApiResponse<UserProfileGetResponse> getProfile(@AuthenticationPrincipal Long userId) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, userService.getProfile(userId));
    }

    @Operation(
            summary = "회원 탈퇴",
            description =
                    """
                    회원 탈퇴를 처리하고 관련 데이터를 삭제한다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **주의사항:**
                    - 탈퇴 후 데이터 복구가 불가능하다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "탈퇴 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal Long userId) {
        authService.withdraw(userId);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK);
    }
}
