package com.planus.backend.domain.aifeedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI-01 파라미터. 합성 실험에서 확정한 값이 기본값.
 * application.yml 의 planus.ai.* 로 덮어쓸 수 있다.
 */
@ConfigurationProperties(prefix = "planus.ai")
public class AiFeedbackProperties {

    // ── [4] 단발 이상치 ──
    private double pointZ = 3.5;          // |modified z| 임계
    private double pointFrac = 0.05;      // 카테고리 예산 대비 최소 비율
    private long pointMin = 10_000;       // 절대 금액 하한(원)
    private double sparseFrac = 0.80;     // 과거 거래 부족 시: 예산 대비 큰 단건
    private long sparseMin = 80_000;
    private double lowSpreadMult = 4.0;   // 분산 미미 시: 개인 중앙값의 N배

    // ── [4] 주간 추세(2-윈도우) — 고빈도 카테고리만 ──
    private double trendRatio = 1.40;     // 최근2주 평균 ≥ 직전4주 평균 × ratio
    private double trendFrac = 0.06;      // (최근-직전) ≥ 예산 × frac
    private int[] highFreqCategories = {1, 6, 8, 10}; // 식료품·교통·오락문화·외식숙박

    // ── 기준선 ──
    private int baselineWeeks = 8;
    private int minWeeks = 4;             // 미만이면 통계 비활성

    // ── [5] RAG / 신뢰도 ──
    private double ragSimThreshold = 0.30;
    private int ragTopK = 5;
    private double confHighMag = 4.0;     // 신뢰도 '높음' 최소 이상치 배수
    private int confHighPrecedents = 2;   // 신뢰도 '높음' 최소 동일테마 선례 수

    // ── [7] 액션 ──
    // 절감액 = min(과거 대비 절감 가능폭, 저축 목표 갭). 별도 상수 없음.

    // getters/setters
    public double getPointZ() { return pointZ; } public void setPointZ(double v){ pointZ=v; }
    public double getPointFrac() { return pointFrac; } public void setPointFrac(double v){ pointFrac=v; }
    public long getPointMin() { return pointMin; } public void setPointMin(long v){ pointMin=v; }
    public double getSparseFrac() { return sparseFrac; } public void setSparseFrac(double v){ sparseFrac=v; }
    public long getSparseMin() { return sparseMin; } public void setSparseMin(long v){ sparseMin=v; }
    public double getLowSpreadMult() { return lowSpreadMult; } public void setLowSpreadMult(double v){ lowSpreadMult=v; }
    public double getTrendRatio() { return trendRatio; } public void setTrendRatio(double v){ trendRatio=v; }
    public double getTrendFrac() { return trendFrac; } public void setTrendFrac(double v){ trendFrac=v; }
    public int[] getHighFreqCategories() { return highFreqCategories; } public void setHighFreqCategories(int[] v){ highFreqCategories=v; }
    public int getBaselineWeeks() { return baselineWeeks; } public void setBaselineWeeks(int v){ baselineWeeks=v; }
    public int getMinWeeks() { return minWeeks; } public void setMinWeeks(int v){ minWeeks=v; }
    public double getRagSimThreshold() { return ragSimThreshold; } public void setRagSimThreshold(double v){ ragSimThreshold=v; }
    public int getRagTopK() { return ragTopK; } public void setRagTopK(int v){ ragTopK=v; }
    public double getConfHighMag() { return confHighMag; } public void setConfHighMag(double v){ confHighMag=v; }
    public int getConfHighPrecedents() { return confHighPrecedents; } public void setConfHighPrecedents(int v){ confHighPrecedents=v; }
}
