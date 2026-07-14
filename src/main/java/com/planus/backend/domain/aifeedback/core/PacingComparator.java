package com.planus.backend.domain.aifeedback.core;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * [2] 페이싱 비교 — 주력 진단.
 *
 * <p>과거 달들의 같은 경과일 시점 소진율 평균과 현재 소진율을 비교한다.
 * 외삽이 아닌 비교이므로 월초에도 안정적이다.
 *
 * <p>콜드스타트 처리:
 * <ul>
 *   <li>0개월: 비교 불가 → null 반환
 *   <li>1개월: 단일 케이스 → pacingRatio 계산하되 bandWidth=WIDE, displayType=SINGLE
 *   <li>2개월: 범위로 비교 → displayType=RANGE, referenceMin/Max 제공
 *   <li>3개월+: 중앙값으로 비교 (안정) → displayType=MEDIAN
 * </ul>
 */
@Component
public class PacingComparator {

    private static final double WIDE_THRESHOLD = 0.4;
    private static final double MEDIUM_THRESHOLD = 0.6;

    /** LLM 렌더러가 과거 참조를 어떤 형식으로 표현할지 결정한다. */
    public enum PacingDisplay {
        /** 1개월: "지난달 이맘때는 예산의 22%를 썼는데" */
        SINGLE,
        /** 2개월: "보통 이맘때 20~24% 쓰셨는데" */
        RANGE,
        /** 3개월+: "보통 이맘때 22% 쓰셨는데" */
        MEDIAN
    }

    /**
     * 현재 소진율과 과거 평균 소진율을 비교한다.
     *
     * @param currentMtdSpend 이번 달 현재까지 총지출 (원)
     * @param totalBudget     이번 달 총 예산 (원)
     * @param elapsedDays     이번 달 경과 일수
     * @param totalDays       이번 달 총 일수
     * @param pastMonths      과거 달들의 같은 경과일 시점 데이터
     * @return 비교 결과, 과거 데이터 없으면 null
     */
    public PacingResult compare(
            long currentMtdSpend, long totalBudget, int elapsedDays, int totalDays, List<PastMonth> pastMonths) {
        if (pastMonths == null || pastMonths.isEmpty() || totalBudget <= 0) {
            return null;
        }

        double currentRate = (double) currentMtdSpend / totalBudget;

        double[] historicalRates = pastMonths.stream()
                .filter(pm -> pm.budget() > 0)
                .mapToDouble(pm -> {
                    double rate = (double) pm.totalSpendAtDay() / pm.budget();
                    if (pm.elapsedDays() > 0 && elapsedDays > 0) {
                        rate = rate * ((double) elapsedDays / pm.elapsedDays());
                    }
                    return rate;
                })
                .toArray();

        if (historicalRates.length == 0) {
            return null;
        }

        double historicalAvgRate =
                historicalRates.length >= 3 ? RobustStats.median(historicalRates) : RobustStats.mean(historicalRates);

        double pacingRatio = historicalAvgRate > 0 ? currentRate / historicalAvgRate : 0.0;

        int monthsAvailable = historicalRates.length;
        BandWidth bandWidth = determineBandWidth(elapsedDays, totalDays, monthsAvailable);
        PacingDisplay displayType = determineDisplayType(monthsAvailable);

        Double referenceMin = null;
        Double referenceMax = null;
        if (monthsAvailable == 2) {
            referenceMin = Math.min(historicalRates[0], historicalRates[1]);
            referenceMax = Math.max(historicalRates[0], historicalRates[1]);
        }

        return new PacingResult(
                currentRate, historicalAvgRate, pacingRatio, bandWidth, monthsAvailable, displayType, referenceMin,
                referenceMax);
    }

    private BandWidth determineBandWidth(int elapsedDays, int totalDays, int pastMonthCount) {
        double progress = totalDays > 0 ? (double) elapsedDays / totalDays : 0.0;
        if (progress < WIDE_THRESHOLD || pastMonthCount < 2) {
            return BandWidth.WIDE;
        }
        if (pastMonthCount < 3 || progress < MEDIUM_THRESHOLD) {
            return BandWidth.MEDIUM;
        }
        return BandWidth.NARROW;
    }

    private PacingDisplay determineDisplayType(int monthsAvailable) {
        if (monthsAvailable >= 3) return PacingDisplay.MEDIAN;
        if (monthsAvailable == 2) return PacingDisplay.RANGE;
        return PacingDisplay.SINGLE;
    }

    /**
     * 과거 한 달의 같은 경과일 시점 데이터.
     *
     * @param totalSpendAtDay 해당 경과일까지의 총지출 (원)
     * @param elapsedDays     경과 일수
     * @param budget          해당 월 총 예산 (원)
     */
    public record PastMonth(long totalSpendAtDay, int elapsedDays, long budget) {}

    /**
     * 페이싱 비교 결과.
     *
     * @param currentRate       현재 소진율 (지출/예산)
     * @param historicalAvgRate 과거 평균 소진율 (같은 경과일 기준)
     * @param pacingRatio       currentRate / historicalAvgRate (1.0 초과 = 빠름)
     * @param bandWidth         불확실성 밴드 폭
     * @param monthsAvailable   과거 참조 가능 월 수
     * @param displayType       LLM 표현 형식 (SINGLE/RANGE/MEDIAN)
     * @param referenceMin      2개월일 때 범위 하한 (그 외 null)
     * @param referenceMax      2개월일 때 범위 상한 (그 외 null)
     */
    public record PacingResult(
            double currentRate,
            double historicalAvgRate,
            double pacingRatio,
            BandWidth bandWidth,
            int monthsAvailable,
            PacingDisplay displayType,
            Double referenceMin,
            Double referenceMax) {}
}
