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
 *   <li>1개월: 단일 케이스 → pacingRatio 계산하되 bandWidth=WIDE
 *   <li>2개월+: 범위로 비교
 *   <li>3개월+: 중앙값으로 비교 (안정)
 * </ul>
 */
@Component
public class PacingComparator {

    private static final double WIDE_THRESHOLD = 0.4; // 경과일/총일 비율이 이 미만이면 WIDE

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

        double currentRate = totalBudget > 0 ? (double) currentMtdSpend / totalBudget : 0.0;

        double[] historicalRates = pastMonths.stream()
                .filter(pm -> pm.budget() > 0)
                .mapToDouble(pm -> {
                    double rate = (double) pm.totalSpendAtDay() / pm.budget();
                    // 경과일 비율로 보정: 과거 경과일과 현재 경과일이 다를 수 있음
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

        BandWidth bandWidth = determineBandWidth(elapsedDays, totalDays, historicalRates.length);

        return new PacingResult(currentRate, historicalAvgRate, pacingRatio, bandWidth);
    }

    private BandWidth determineBandWidth(int elapsedDays, int totalDays, int pastMonthCount) {
        double progress = totalDays > 0 ? (double) elapsedDays / totalDays : 0.0;
        if (progress < WIDE_THRESHOLD || pastMonthCount < 2) {
            return BandWidth.WIDE;
        }
        return BandWidth.NARROW;
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
     */
    public record PacingResult(double currentRate, double historicalAvgRate, double pacingRatio, BandWidth bandWidth) {}
}
