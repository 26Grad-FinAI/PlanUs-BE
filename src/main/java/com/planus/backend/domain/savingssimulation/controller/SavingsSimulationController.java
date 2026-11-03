package com.planus.backend.domain.savingssimulation.controller;

import com.planus.backend.domain.savingssimulation.dto.*;
import com.planus.backend.domain.savingssimulation.service.SavingsSimulationService;
import com.planus.backend.global.apiPayload.ApiResponse;
import com.planus.backend.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 절약 시뮬레이션 API. 제안 조회 → 시뮬레이션 → 예산 반영 흐름을 제공한다. */
@Tag(name = "Savings Simulation", description = "절약 시뮬레이션 제안·시뮬레이션·예산 반영 API")
@RestController
@RequestMapping("/api/savings-simulation")
@RequiredArgsConstructor
public class SavingsSimulationController {

    private final SavingsSimulationService service;

    @Operation(summary = "절약 제안 조회", description = "해당 월의 피드백에서 행동 기반 절약 제안 목록을 조회한다.")
    @GetMapping("/suggest")
    public ApiResponse<SuggestResponse> suggest(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "대상 연월 (yyyy-MM)", example = "2026-11") @RequestParam String yearMonth) {

        SuggestResponse response = service.suggest(userId, yearMonth);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, response);
    }

    @Operation(summary = "절약 효과 시뮬레이션", description = "선택한 절약 제안의 효과를 시뮬레이션한다.")
    @PostMapping("/simulate")
    public ApiResponse<SimulateResponse> simulate(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody SimulateRequest request) {

        SimulateResponse response = service.simulate(userId, request);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, response);
    }

    @Operation(summary = "시뮬레이션 결과 예산 반영", description = "시뮬레이션 결과를 이번 달 카테고리별 예산에 반영한다.")
    @PostMapping("/apply")
    public ApiResponse<ApplyResponse> apply(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody ApplyRequest request) {

        ApplyResponse response = service.apply(userId, request);
        return ApiResponse.onSuccess(GeneralSuccessCode.OK, response);
    }
}
