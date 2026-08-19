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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
}
