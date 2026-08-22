package com.planus.backend.domain.income.controller;

import com.planus.backend.domain.income.dto.IncomeRequest;
import com.planus.backend.domain.income.dto.IncomeResponse;
import com.planus.backend.domain.income.service.IncomeService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 수입 등록 API 컨트롤러. */
@RestController
@RequestMapping("/api/incomes")
@RequiredArgsConstructor
public class IncomeController {

    private final IncomeService incomeService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IncomeResponse> createIncome(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody IncomeRequest request) {
        return ApiResponse.onSuccess(GeneralSuccessCode.CREATED, incomeService.createIncome(userId, request));
    }
}
