package com.planus.backend.domain.aifeedback.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 피드백 파이프라인 설정.
 * application-aifeedback.yml 의 planus.ai.* 로 덮어쓸 수 있다.
 */
@ConfigurationProperties(prefix = "planus.ai")
public class AiFeedbackProperties {

    // ── [3] 단발 이상치 ──
    private double pointZ = 3.5;
    private double pointFrac = 0.05;
    private long pointMin = 10_000;
    private double sparseFrac = 0.80;
    private long sparseMin = 80_000;
    private double lowSpreadMult = 4.0;

    // ── [3] 주간 추세(4+4 대칭 윈도우) — 고빈도 카테고리만 ──
    private double trendRatio = 1.40;
    private double trendFrac = 0.06;
    private int[] highFreqCategories = {1, 6, 8, 10};

    // ── 기준선 ──
    private int baselineWeeks = 8;
    private int minWeeks = 4;

    // ── [1.5] 활동성 가드 ──
    private double activityThreshold = 0.50;

    // ── [3] 이상치 신뢰도 표본 기준 ──
    private int confHighMinSamples = 30;
    private int confMediumMinSamples = 15;

    // ── [6] 절약 액션 ──
    private double reductionRatio = 0.50;

    // ── [3.5] 메모 질문 큐 ──
    private int weeklyQuestionCap = 3;

    // ── 월말 검증 배치 ──
    private String monthEndCron = "0 0 3 1 * *";

    // ── 카테고리 속성 (2단 레이어: 시스템 기본) ──
    private CategoryProperties category = new CategoryProperties();

    /**
     * 카테고리 속성 설정 (2단 레이어의 시스템 기본값).
     *
     * <ul>
     *   <li>{@code essential} — 필수 지출 카테고리 (의료, 교육 등). 절약 제안 대상에서 제외.
     *   <li>{@code semiEssential} — 준필수 지출 카테고리 (식료품, 교통 등). 제안 순위 하락.
     *   <li>{@code spikeProne} — 스파이크성 카테고리 (의료, 경조사 등). 단발 이상치 탐지에서 제외.
     * </ul>
     *
     * <p>사용자별 오버라이드는 {@code user_profile.sensitive_areas}(DB)로 관리한다.
     */
    public static class CategoryProperties {
        private List<Long> essential = List.of(5L, 9L, 15L, 20L);
        private List<Long> semiEssential = List.of(1L, 3L, 7L);
        private List<Long> spikeProne = List.of(5L, 20L);

        public List<Long> getEssential() {
            return essential;
        }

        public void setEssential(List<Long> v) {
            essential = v;
        }

        public List<Long> getSemiEssential() {
            return semiEssential;
        }

        public void setSemiEssential(List<Long> v) {
            semiEssential = v;
        }

        public List<Long> getSpikeProne() {
            return spikeProne;
        }

        public void setSpikeProne(List<Long> v) {
            spikeProne = v;
        }
    }

    // getters/setters
    public double getPointZ() {
        return pointZ;
    }

    public void setPointZ(double v) {
        pointZ = v;
    }

    public double getPointFrac() {
        return pointFrac;
    }

    public void setPointFrac(double v) {
        pointFrac = v;
    }

    public long getPointMin() {
        return pointMin;
    }

    public void setPointMin(long v) {
        pointMin = v;
    }

    public double getSparseFrac() {
        return sparseFrac;
    }

    public void setSparseFrac(double v) {
        sparseFrac = v;
    }

    public long getSparseMin() {
        return sparseMin;
    }

    public void setSparseMin(long v) {
        sparseMin = v;
    }

    public double getLowSpreadMult() {
        return lowSpreadMult;
    }

    public void setLowSpreadMult(double v) {
        lowSpreadMult = v;
    }

    public double getTrendRatio() {
        return trendRatio;
    }

    public void setTrendRatio(double v) {
        trendRatio = v;
    }

    public double getTrendFrac() {
        return trendFrac;
    }

    public void setTrendFrac(double v) {
        trendFrac = v;
    }

    public int[] getHighFreqCategories() {
        return highFreqCategories;
    }

    public void setHighFreqCategories(int[] v) {
        highFreqCategories = v;
    }

    public int getBaselineWeeks() {
        return baselineWeeks;
    }

    public void setBaselineWeeks(int v) {
        baselineWeeks = v;
    }

    public int getMinWeeks() {
        return minWeeks;
    }

    public void setMinWeeks(int v) {
        minWeeks = v;
    }

    public double getActivityThreshold() {
        return activityThreshold;
    }

    public void setActivityThreshold(double v) {
        activityThreshold = v;
    }

    public int getConfHighMinSamples() {
        return confHighMinSamples;
    }

    public void setConfHighMinSamples(int v) {
        confHighMinSamples = v;
    }

    public int getConfMediumMinSamples() {
        return confMediumMinSamples;
    }

    public void setConfMediumMinSamples(int v) {
        confMediumMinSamples = v;
    }

    public double getReductionRatio() {
        return reductionRatio;
    }

    public void setReductionRatio(double v) {
        reductionRatio = v;
    }

    public int getWeeklyQuestionCap() {
        return weeklyQuestionCap;
    }

    public void setWeeklyQuestionCap(int v) {
        weeklyQuestionCap = v;
    }

    public String getMonthEndCron() {
        return monthEndCron;
    }

    public void setMonthEndCron(String v) {
        monthEndCron = v;
    }

    public CategoryProperties getCategory() {
        return category;
    }

    public void setCategory(CategoryProperties v) {
        category = v;
    }
}
