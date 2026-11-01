package com.planus.backend.domain.user.dto;

import java.util.List;

public record SavingsStatusResponse(long totalSavings, List<MonthlyRecord> monthlyRecords) {

    public record MonthlyRecord(String yearMonth, long savings) {}
}
