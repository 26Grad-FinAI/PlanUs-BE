package com.planus.backend.domain.aifeedback.eval;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** 사람 판정 집계용 통계 (#140, 테스트 전용). */
public final class JudgingStats {

    private static final double Z95 = 1.959963984540054;

    private JudgingStats() {}

    /** 구간 [lo, hi] (비율, 0~1). */
    public record Interval(double lo, double hi) {}

    /**
     * Wilson 점수 구간 (95%). 표본이 작거나 비율이 0·1에 가까워도 범위를 벗어나지 않는다.
     *
     * @param k 사건 수 (예: 틀린 응답 수)
     * @param n 전체 수
     */
    public static Interval wilson(int k, int n) {
        if (n == 0) return new Interval(0, 1);
        double p = (double) k / n;
        double z2 = Z95 * Z95;
        double center = (p + z2 / (2 * n)) / (1 + z2 / n);
        double half = Z95 * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n);
        return new Interval(Math.max(0, center - half), Math.min(1, center + half));
    }

    /**
     * Fisher 정확 검정 (양측). 2×2 표 [[a, b], [c, d]]에서 관측 표보다 확률이 같거나 작은 표의 확률 합.
     *
     * <p>예: a = A 버전 틀림, b = A 버전 맞음, c = B 버전 틀림, d = B 버전 맞음.
     */
    public static double fisherTwoSided(int a, int b, int c, int d) {
        int row1 = a + b, row2 = c + d, col1 = a + c, n = a + b + c + d;
        double[] logFact = logFactorials(n);
        double pObs = hypergeom(a, row1, row2, col1, logFact);
        double sum = 0;
        int lo = Math.max(0, col1 - row2), hi = Math.min(row1, col1);
        for (int x = lo; x <= hi; x++) {
            double px = hypergeom(x, row1, row2, col1, logFact);
            if (px <= pObs * (1 + 1e-7)) sum += px;
        }
        return Math.min(1.0, sum);
    }

    /**
     * Cohen's κ (두 판정자, 이진 판정). 판정이 모두 한쪽으로 몰려 우연 일치가 1이면 κ를 정의할 수 없어 NaN.
     *
     * @param x 판정자 1 (true = 틀림)
     * @param y 판정자 2 (같은 항목 순서)
     */
    public static double cohenKappa(List<Boolean> x, List<Boolean> y) {
        if (x.size() != y.size() || x.isEmpty()) throw new IllegalArgumentException("판정 수가 다르거나 비어 있음");
        int n = x.size(), agree = 0, x1 = 0, y1 = 0;
        for (int i = 0; i < n; i++) {
            if (x.get(i).equals(y.get(i))) agree++;
            if (x.get(i)) x1++;
            if (y.get(i)) y1++;
        }
        double po = (double) agree / n;
        double pe = ((double) x1 / n) * ((double) y1 / n) + ((double) (n - x1) / n) * ((double) (n - y1) / n);
        if (pe >= 1.0) return Double.NaN;
        return (po - pe) / (1 - pe);
    }

    /**
     * 군집 부트스트랩 95% 구간 (백분위). 같은 컨텍스트(주차)를 여러 번 렌더한 응답은 서로 독립이 아니므로, 응답이 아니라 컨텍스트 단위로 복원 추출한다.
     *
     * @param clusters   컨텍스트별 {틀림 수, 전체 수}
     * @param iterations 반복 횟수
     * @param seed       난수 시드 (재현용)
     */
    public static Interval clusterBootstrap(List<int[]> clusters, int iterations, long seed) {
        if (clusters.isEmpty()) return new Interval(0, 1);
        Random rnd = new Random(seed);
        int m = clusters.size();
        double[] rates = new double[iterations];
        for (int it = 0; it < iterations; it++) {
            int k = 0, n = 0;
            for (int j = 0; j < m; j++) {
                int[] c = clusters.get(rnd.nextInt(m));
                k += c[0];
                n += c[1];
            }
            rates[it] = n == 0 ? 0 : (double) k / n;
        }
        Arrays.sort(rates);
        return new Interval(
                rates[(int) Math.floor(0.025 * (iterations - 1))], rates[(int) Math.ceil(0.975 * (iterations - 1))]);
    }

    private static double hypergeom(int x, int row1, int row2, int col1, double[] lf) {
        int n = row1 + row2;
        return Math.exp(logChoose(row1, x, lf) + logChoose(row2, col1 - x, lf) - logChoose(n, col1, lf));
    }

    private static double logChoose(int n, int k, double[] lf) {
        if (k < 0 || k > n) return Double.NEGATIVE_INFINITY;
        return lf[n] - lf[k] - lf[n - k];
    }

    private static double[] logFactorials(int n) {
        double[] lf = new double[n + 1];
        for (int i = 2; i <= n; i++) lf[i] = lf[i - 1] + Math.log(i);
        return lf;
    }

    /**
     * 짝지은 부호 뒤집기 순열 검정 (양측, 정확 계산). 버전 비교에서 같은 주차의 반복 응답을 독립 표본으로 세지 않기 위해 쓴다.
     *
     * <p>주차마다 d = (A 버전 틀린 수) − (B 버전 틀린 수)를 구한다. 두 버전이 같다면 주차 안에서 버전 라벨을 바꿔도 분포가 같으므로, 각 d의 부호를
     * 독립적으로 뒤집은 합 S의 분포에서 |S| ≥ |관측 합|일 확률을 p로 한다. d는 정수이므로 합의 분포를 동적 계획법으로 정확히 구한다.
     *
     * @param diffs 주차별 틀린 수 차이 (두 버전에 모두 있는 주차만)
     */
    public static double pairedSignFlip(List<Integer> diffs) {
        int max = diffs.stream().mapToInt(Math::abs).sum();
        if (max == 0) return 1.0;
        double[] dist = new double[2 * max + 1]; // 인덱스 = 합 + max
        dist[max] = 1.0;
        for (int d : diffs) {
            if (d == 0) continue;
            double[] next = new double[dist.length];
            for (int s = 0; s < dist.length; s++) {
                if (dist[s] == 0) continue;
                next[s + d] += dist[s] / 2;
                next[s - d] += dist[s] / 2;
            }
            dist = next;
        }
        int observed = Math.abs(diffs.stream().mapToInt(Integer::intValue).sum());
        double p = 0;
        for (int s = 0; s < dist.length; s++) if (Math.abs(s - max) >= observed) p += dist[s];
        return Math.min(1.0, p);
    }

    /** 테스트·보고서용: 구간을 "a–b%"로. 소수점 표기가 실행 환경 로캘에 따라 바뀌지 않게 고정한다. */
    public static String pct(Interval i) {
        return String.format(Locale.ROOT, "%.0f–%.0f%%", i.lo() * 100, i.hi() * 100);
    }
}
