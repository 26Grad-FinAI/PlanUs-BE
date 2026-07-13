package com.planus.backend.domain.aifeedback.core;

import org.springframework.stereotype.Component;

/**
 * [6] 예산 진척률 + 저축 목표 진단 (1순위, 항상 실행).
 *   예상 월말 = 누적 실측 + 남은 예정소비 + 변동 일평균 × 남은 일수
 *   변동 일평균 = w·(이번달 변동률) + (1-w)·(과거 동월 변동률),  w = 경과일/총일수
 *   저축 영향 = 예상 총지출 − 가용예산  (저축 목표에서 멀어지는 금액)
 * 백테스트 MAPE ~10%.
 */
@Component
public class BudgetProjector {

    /** 단일 카테고리(또는 전체) 월말 예측. 금액 단위 원. */
    public Projection project(
            long variableMtdWon,
            long fixedPlannedMtdWon,
            long remainingFixedPlannedWon,
            int dayOfMonth,
            int daysInMonth,
            double priorVariableDailyRateWon) {
        double w = (double) dayOfMonth / daysInMonth;
        double currentRate = (dayOfMonth > 0) ? (double) variableMtdWon / dayOfMonth : 0.0;
        double blendedRate =
                (priorVariableDailyRateWon > 0) ? w * currentRate + (1 - w) * priorVariableDailyRateWon : currentRate;
        long remainingVariable = Math.round(blendedRate * (daysInMonth - dayOfMonth));
        long mtdAll = variableMtdWon + fixedPlannedMtdWon;
        long predictedMonthEnd = mtdAll + remainingFixedPlannedWon + remainingVariable;
        return new Projection(predictedMonthEnd, remainingVariable, blendedRate);
    }

    /**
     * 저축 영향: 가용예산 − 예상총지출.
     * 양수면 여유(목표보다 더 저축), 음수면 초과 위험.
     */
    public long savingsImpact(long predictedTotalSpendWon, long availableBudgetWon) {
        return availableBudgetWon - predictedTotalSpendWon;
    }

    /** 진척률 = 예상 월말 / 카테고리 예산. 예산 0원인데 지출이 있으면 무한대(초과 확정). */
    public double burnRate(long predictedCategorySpendWon, long categoryBudgetWon) {
        if (categoryBudgetWon <= 0) {
            return predictedCategorySpendWon > 0 ? Double.POSITIVE_INFINITY : 0.0;
        }
        return (double) predictedCategorySpendWon / categoryBudgetWon;
    }

    public record Projection(long predictedMonthEndWon, long remainingVariableWon, double dailyRateWon) {}
}
