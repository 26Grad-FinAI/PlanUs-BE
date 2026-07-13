package com.planus.backend.domain.aifeedback.core;

import java.util.Arrays;

/** 이상치에 견고한 통계. 평균·표준편차 대신 중앙값·MAD 사용. */
public final class RobustStats {
    private RobustStats() {}

    /**
     * 배열의 중앙값을 구한다. 원본 배열은 변경하지 않는다.
     *
     * @param a 대상 배열
     * @return 중앙값, 빈 배열이면 0.0
     */
    public static double median(double[] a) {
        if (a.length == 0) return 0.0;
        double[] s = a.clone();
        Arrays.sort(s);
        int n = s.length;
        return (n % 2 == 1) ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
    }

    /** 중앙값 절대편차(MAD). */
    public static double mad(double[] a) {
        if (a.length == 0) return 0.0;
        double m = median(a);
        double[] dev = new double[a.length];
        for (int i = 0; i < a.length; i++) dev[i] = Math.abs(a[i] - m);
        return median(dev);
    }

    /** modified z-score = 0.6745 · (x − median) / MAD. MAD=0이면 0 반환(호출부에서 분기). */
    public static double modifiedZ(double x, double med, double madVal) {
        if (madVal <= 0) return 0.0;
        return 0.6745 * (x - med) / madVal;
    }

    /**
     * 배열의 산술 평균을 구한다.
     *
     * @param a 대상 배열
     * @return 산술 평균, 빈 배열이면 0.0
     */
    public static double mean(double[] a) {
        if (a.length == 0) return 0.0;
        double s = 0;
        for (double v : a) s += v;
        return s / a.length;
    }
}
