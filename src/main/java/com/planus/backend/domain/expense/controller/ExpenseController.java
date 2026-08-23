package com.planus.backend.domain.expense.controller;

import com.planus.backend.domain.expense.dto.ExpenseRequest;
import com.planus.backend.domain.expense.dto.ExpenseResponse;
import com.planus.backend.domain.expense.service.ExpenseService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 지출 관련 REST API 컨트롤러. */
@RestController
@RequestMapping("/api/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;

    /**
     * 지출을 등록한다.
     *
     * @param userId  JWT 인증된 사용자 ID
     * @param request 금액·내역·날짜·카테고리·메모·감정태그·고정지출여부·계획지출여부
     * @return 201 Created, 등록된 지출 정보
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ExpenseResponse> createExpense(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody ExpenseRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.CREATED, expenseService.createExpense(userId, request));
    }

    /**
     * 지출을 수정한다.
     *
     * @param userId    JWT 인증된 사용자 ID
     * @param expenseId 수정할 지출 ID
     * @param request   수정할 지출 정보
     * @return 수정된 지출 정보
     */
    @PutMapping("/{expenseId}")
    public ApiResponse<ExpenseResponse> updateExpense(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long expenseId,
            @Valid @RequestBody ExpenseRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, expenseService.updateExpense(userId, expenseId, request));
    }

    /**
     * 지출을 삭제한다.
     *
     * @param userId    JWT 인증된 사용자 ID
     * @param expenseId 삭제할 지출 ID
     * @return 삭제 완료 응답
     */
    @DeleteMapping("/{expenseId}")
    public ApiResponse<Void> deleteExpense(@AuthenticationPrincipal Long userId, @PathVariable Long expenseId) {
        expenseService.deleteExpense(userId, expenseId);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, null);
    }
}
