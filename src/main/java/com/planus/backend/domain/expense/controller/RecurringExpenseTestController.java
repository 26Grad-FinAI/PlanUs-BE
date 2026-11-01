package com.planus.backend.domain.expense.controller;

import com.planus.backend.domain.expense.batch.RecurringExpenseScheduler;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 반복 지출 스케줄러 수동 트리거 API. 개발/테스트 환경에서만 활성화된다. */
@Tag(name = "Expense", description = "지출 등록·수정·삭제 API")
@Profile({"dev", "local", "test"})
@RestController
@RequestMapping("/api/expenses")
@RequiredArgsConstructor
public class RecurringExpenseTestController {

    private final RecurringExpenseScheduler recurringExpenseScheduler;

    @Operation(summary = "반복 지출 스케줄러 수동 실행", description = "반복 지출 자동 등록 스케줄러를 즉시 실행한다. 오늘 일자 기준으로 동작한다.")
    @PostMapping("/recurring/trigger")
    public ApiResponse<String> triggerRecurringExpenses() {
        recurringExpenseScheduler.executeRecurringExpenses();
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, "반복 지출 스케줄러 실행 완료");
    }
}
