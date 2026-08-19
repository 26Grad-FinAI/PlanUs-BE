package com.planus.backend.domain.income.dto;

import java.time.LocalDateTime;

public record IncomeResponse(
        Long id,
        long amount,
        String title,
        LocalDateTime incomeDate,
        Integer categoryId,
        String memo,
        LocalDateTime createdAt) {}
