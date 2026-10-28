package com.planus.backend.domain.user.controller;

import com.planus.backend.domain.aibudget.dto.FcmTokenRequest;
import com.planus.backend.domain.auth.service.AuthService;
import com.planus.backend.domain.user.dto.UserProfileGetResponse;
import com.planus.backend.domain.user.dto.UserProfileRequest;
import com.planus.backend.domain.user.dto.UserProfileResponse;
import com.planus.backend.domain.user.dto.UserProfileUpdateResponse;
import com.planus.backend.domain.user.service.UserService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
                    사용자 프로필을 저장한다. 이미 프로필이 존재하면 덮어쓴다.
                    가용예산(monthlyIncome - monthlyFixedExpenses - monthlySavingsGoal)이 자동 계산되어 반환된다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **spendingHabit:** 서버에서 별도 값 검증을 하지 않으며 자유 문자열로 저장된다. 권장값: SAVING / BALANCED / SPENDING.
                    **hobbies:** 1개 이상 필수, 각 항목은 공백 불가.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "프로필 저장 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "입력값 검증 실패",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "인증되지 않은 사용자",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
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
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "입력값 검증 실패",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "인증되지 않은 사용자",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
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
                    - 프로필 미저장 시 profileCompleted=false이며, 문자열·객체 필드(nickname, gender, residence, hobbies 등)는 null, 숫자 필드(monthlyIncome, monthlyFixedExpenses 등)는 0으로 반환된다. userId, email, createdAt은 프로필 저장 여부와 무관하게 항상 반환된다.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "프로필 조회 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "인증되지 않은 사용자",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @GetMapping("/me/profile")
    public ApiResponse<UserProfileGetResponse> getProfile(@AuthenticationPrincipal Long userId) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, userService.getProfile(userId));
    }

    @Operation(summary = "FCM 디바이스 토큰 등록", description = "푸시 알림을 위한 FCM 디바이스 토큰을 등록한다.")
    @PostMapping("/me/fcm-token")
    public ApiResponse<Void> registerFcmToken(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody FcmTokenRequest request) {
        userService.updateFcmToken(userId, request.fcmToken());
        return ApiResponse.onSuccess(GeneralSuccessCode.OK);
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
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "인증되지 않은 사용자",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal Long userId) {
        authService.withdraw(userId);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK);
    }
}
