package com.planus.backend.domain.expense.controller;

import com.planus.backend.domain.expense.dto.ExpenseRequest;
import com.planus.backend.domain.expense.dto.ExpenseResponse;
import com.planus.backend.domain.expense.service.ExpenseService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 지출 관련 REST API 컨트롤러. */
@Tag(name = "Expense", description = "지출 등록·수정·삭제 API")
@RestController
@RequestMapping("/api/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;

    @Operation(
            summary = "지출 등록",
            description =
                    """
                    새로운 지출을 등록한다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **categoryId:** 1~11 범위의 카테고리 ID.
                    **expenseDate 형식:** yyyy-MM-ddTHH:mm:ss (예: 2026-10-27T12:30:00)
                    **emotion (선택):** NECESSARY / SATISFIED / IMPULSE / STRESS_RELIEF / REGRET 중 하나. null 허용.""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "입력값 검증 실패 (카테고리 범위 초과, 잘못된 감정 태그 등)",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "인증되지 않은 사용자",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ExpenseResponse> createExpense(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody ExpenseRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.CREATED, expenseService.createExpense(userId, request));
    }

    @Operation(
            summary = "지출 수정",
            description =
                    """
                    기존 지출 내역을 수정한다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **주의사항:**
                    - 본인의 지출만 수정 가능하다 (타인의 지출 수정 시 403 응답).
                    - 수입 내역을 이 API로 수정할 수 없다 (400 응답).""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "입력값 검증 실패 또는 수입 내역에 대한 잘못된 요청",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "인증되지 않은 사용자",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "본인의 지출이 아님",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "지출 내역을 찾을 수 없음",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PutMapping("/{expenseId}")
    public ApiResponse<ExpenseResponse> updateExpense(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "수정할 지출 ID", example = "1") @PathVariable Long expenseId,
            @Valid @RequestBody ExpenseRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, expenseService.updateExpense(userId, expenseId, request));
    }

    @Operation(
            summary = "지출 삭제",
            description =
                    """
                    지출 내역을 삭제한다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **주의사항:**
                    - 본인의 지출만 삭제 가능하다 (타인의 지출 삭제 시 403 응답).
                    - 수입 내역을 이 API로 삭제할 수 없다 (400 응답).""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "수입 내역에 대한 잘못된 요청",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "인증되지 않은 사용자",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "본인의 지출이 아님",
                content = @Content(schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "지출 내역을 찾을 수 없음",
                content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @DeleteMapping("/{expenseId}")
    public ApiResponse<Void> deleteExpense(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "삭제할 지출 ID", example = "1") @PathVariable Long expenseId) {
        expenseService.deleteExpense(userId, expenseId);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK);
    }
}
