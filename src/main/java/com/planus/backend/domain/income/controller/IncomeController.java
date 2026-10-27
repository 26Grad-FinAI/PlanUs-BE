package com.planus.backend.domain.income.controller;

import com.planus.backend.domain.income.dto.IncomeRequest;
import com.planus.backend.domain.income.dto.IncomeResponse;
import com.planus.backend.domain.income.service.IncomeService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 수입 등록·수정·삭제 API 컨트롤러. */
@Tag(name = "Income", description = "수입 등록·수정·삭제 API")
@RestController
@RequestMapping("/api/incomes")
@RequiredArgsConstructor
public class IncomeController {

    private final IncomeService incomeService;

    @Operation(
            summary = "수입 등록",
            description =
                    """
                    새로운 수입을 등록한다. 등록 시 해당 월의 예산이 자동으로 증가한다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **categoryId:** 1~11 범위의 카테고리 ID.
                    **incomeDate 형식:** yyyy-MM-ddTHH:mm:ss (예: 2026-10-25T09:00:00)
                    **주의사항:**
                    - incomeDate가 속한 월의 Budget이 존재해야 한다 (없으면 404 응답).
                    - 해당 카테고리의 BudgetCategory도 존재해야 한다 (없으면 404 응답).""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "입력값 검증 실패 또는 예산 한도 초과"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "해당 월 예산 또는 카테고리 예산 미설정")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IncomeResponse> createIncome(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody IncomeRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.CREATED, incomeService.createIncome(userId, request));
    }

    @Operation(
            summary = "수입 수정",
            description =
                    """
                    기존 수입 내역을 수정한다. 금액·카테고리·월이 변경되면 예산도 자동 조정된다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **주의사항:**
                    - 본인의 수입만 수정 가능하다 (타인의 수입 수정 시 403 응답).
                    - 지출 내역을 이 API로 수정할 수 없다 (400 응답).""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "입력값 검증 실패 또는 지출 내역에 대한 잘못된 요청"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인의 수입이 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "수입 내역을 찾을 수 없음")
    })
    @PutMapping("/{incomeId}")
    public ApiResponse<IncomeResponse> updateIncome(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "수정할 수입 ID", example = "1") @PathVariable Long incomeId,
            @Valid @RequestBody IncomeRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, incomeService.updateIncome(userId, incomeId, request));
    }

    @Operation(
            summary = "수입 삭제",
            description =
                    """
                    수입 내역을 삭제한다. 삭제 시 해당 월의 예산이 자동으로 감소한다.

                    **인증:** Authorization 헤더에 Bearer 액세스 토큰 필요.
                    **주의사항:**
                    - 본인의 수입만 삭제 가능하다 (타인의 수입 삭제 시 403 응답).
                    - 지출 내역을 이 API로 삭제할 수 없다 (400 응답).""")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "지출 내역에 대한 잘못된 요청 또는 예산 한도 초과"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인의 수입이 아님"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "수입 내역을 찾을 수 없음")
    })
    @DeleteMapping("/{incomeId}")
    public ApiResponse<Void> deleteIncome(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "삭제할 수입 ID", example = "1") @PathVariable Long incomeId) {
        incomeService.deleteIncome(userId, incomeId);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK);
    }
}
