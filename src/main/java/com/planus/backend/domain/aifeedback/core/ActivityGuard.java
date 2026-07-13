package com.planus.backend.domain.aifeedback.core;

import com.planus.backend.domain.aifeedback.config.AiFeedbackProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * [1.5] 기록 활동성 가드. 미기록을 "지출 적음"으로 오해석하는 것을 방지한다.
 *
 * <p>이번 주 등록 건수가 평소(주별 등록 건수 중앙값)의 {@code activityThreshold}(기본 50%) 미만이면
 * {@code LOW_DATA}로 분기하여 [2]~[6] 분석을 전부 스킵하고 기록 리마인드만 발송한다.
 *
 * <p>신규 사용자 보호: 관측 3주 미만이면 중앙값 산출 불가이므로 가드를 비활성화(항상 통과)한다.
 */
@Component
public class ActivityGuard {

    private static final int MIN_OBSERVATION_WEEKS = 3;

    private final AiFeedbackProperties p;

    /** @param p 활동성 임계값 설정 (activityThreshold) */
    public ActivityGuard(AiFeedbackProperties p) {
        this.p = p;
    }

    /**
     * 이번 주 기록이 부족한지 판정한다.
     *
     * @param thisWeekCount      이번 주 등록 건수
     * @param normalWeeklyCount  평소 주별 등록 건수 (중앙값 기반)
     * @return 기록 부족이면 true (LOW_DATA 분기)
     */
    public boolean isLowData(long thisWeekCount, double normalWeeklyCount) {
        if (normalWeeklyCount == Double.MAX_VALUE) return false;
        return thisWeekCount < normalWeeklyCount * p.getActivityThreshold();
    }

    /**
     * 과거 주별 등록 건수의 중앙값을 산출한다.
     *
     * <p>관측 3주 미만이면 {@link Double#MAX_VALUE}를 반환하여 가드를 비활성화한다.
     *
     * @param weeklyCountsList 과거 주별 등록 건수 리스트 (최신 주 제외)
     * @return 주별 등록 건수 중앙값, 또는 관측 부족 시 {@link Double#MAX_VALUE}
     */
    public double normalWeeklyCount(List<Long> weeklyCountsList) {
        if (weeklyCountsList.size() < MIN_OBSERVATION_WEEKS) {
            return Double.MAX_VALUE;
        }
        double[] counts =
                weeklyCountsList.stream().mapToDouble(Long::doubleValue).toArray();
        return RobustStats.median(counts);
    }
}
