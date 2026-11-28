package com.planus.backend.domain.savingssimulation.core;

import com.planus.backend.domain.aifeedback.entity.Expense;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 절약 방법과 실현 가능성을 거래 데이터에서 계산한다 (#127). LLM이 만든 문장이 아니라 숫자·근거가 전부 코드로 나온다.
 *
 * <p>방법: 이번 달 거래 횟수·건당 금액으로 남은 기간 예상 횟수를 구하고, 감축액을 "몇 번 덜"(빈도) 또는 "한 번에 얼마"(단가)로 바꾼다.
 * <p>실현 가능성: 필요 감축 비율, 과거 달성 이력, 감정 구성, 거절 이력을 점수로 더해 쉬움·보통·어려움으로 나누고 근거 문장을 함께 낸다.
 */
public final class SavingsMethodAnalyzer {

    private SavingsMethodAnalyzer() {}

    public enum Level {
        EASY,
        NORMAL,
        HARD
    }

    /**
     * @param categoryId         카테고리
     * @param dominantFactor     초과 원인 (FREQUENCY / UNIT_PRICE / BOTH / null)
     * @param reductionWon       이번 달 남은 기간에 줄일 금액
     * @param asOf               기준일
     * @param thisMonth          이번 달 1일~기준일의 이 카테고리 변동 지출
     * @param past               직전 3개월의 이 카테고리 변동 지출
     * @param remainingExpected  남은 기간 예상 지출 (원). 0 이하면 줄일 여지가 없다
     * @param rejectedBefore     사용자가 이 카테고리를 줄이고 싶지 않다고 한 적이 있는지
     */
    public record Input(
            int categoryId,
            String dominantFactor,
            long reductionWon,
            LocalDate asOf,
            List<Expense> thisMonth,
            List<Expense> past,
            long remainingExpected,
            boolean rejectedBefore) {}

    /**
     * @param method             "남은 N일간 M번 덜 쓰기 (예상 A번 → B번)" 같은 방법. 계산할 수 없으면 null
     * @param level              실현 가능성 등급
     * @param reasons            등급의 근거 문장들
     * @param remainingDays      남은 일수
     * @param expectedCount      남은 기간 예상 거래 횟수
     * @param cutCount           덜 쓸 횟수 (빈도 방식), 단가 방식이면 0
     */
    public record Plan(
            String method, Level level, List<String> reasons, int remainingDays, int expectedCount, int cutCount) {}

    public static Plan analyze(Input in) {
        int remainingDays =
                YearMonth.from(in.asOf()).lengthOfMonth() - in.asOf().getDayOfMonth();
        Method m = method(in, remainingDays);
        return new Plan(
                m == null ? null : m.text,
                level(in),
                reasonsOf(in),
                remainingDays,
                m == null ? 0 : m.expectedCount,
                m == null ? 0 : m.cutCount);
    }

    // ── 방법 ──

    private record Method(String text, int expectedCount, int cutCount) {}

    private static Method method(Input in, int remainingDays) {
        if (remainingDays <= 0 || in.reductionWon() <= 0) return null;
        int day = in.asOf().getDayOfMonth();
        int thisCount = in.thisMonth().size();
        long thisTotal = in.thisMonth().stream().mapToLong(Expense::getAmount).sum();

        double ratePerDay;
        long avg;
        if (thisCount > 0) {
            ratePerDay = (double) thisCount / day;
            avg = Math.round((double) thisTotal / thisCount);
        } else if (!in.past().isEmpty()) {
            ratePerDay = in.past().size() / 90.0;
            avg = Math.round(
                    in.past().stream().mapToLong(Expense::getAmount).average().orElse(0));
        } else {
            return null;
        }
        int expected = (int) Math.round(ratePerDay * remainingDays);
        if (expected < 1 || avg <= 0) return null;

        boolean unitPrice = "UNIT_PRICE".equals(in.dominantFactor());
        if (unitPrice) {
            long cutPerTxn = Math.round((double) in.reductionWon() / expected);
            if (cutPerTxn > 0 && cutPerTxn < avg) {
                return new Method(
                        String.format(
                                "남은 %d일간 한 번에 %,d원꼴 → %,d원꼴로 (예상 %d번)", remainingDays, avg, avg - cutPerTxn, expected),
                        expected,
                        0);
            }
        }
        int cut = (int) Math.min(expected, Math.max(1, Math.round((double) in.reductionWon() / avg)));
        return new Method(
                String.format("남은 %d일간 %d번 덜 쓰기 (예상 %d번 → %d번)", remainingDays, cut, expected, expected - cut),
                expected,
                cut);
    }

    // ── 실현 가능성 ──

    /** 근거 수치. 점수와 문장이 같은 값에서 나오도록 한 곳에서 계산한다. */
    private record Facts(
            double cutRatio,
            long targetMonthly,
            int pastMonths,
            int achievedMonths,
            double satisfiedShare,
            double regretImpulseShare) {}

    private static Facts facts(Input in) {
        long mtd = in.thisMonth().stream().mapToLong(Expense::getAmount).sum();
        long remaining = Math.max(0, in.remainingExpected());
        double ratio = remaining > 0 ? Math.min(1.0, (double) in.reductionWon() / remaining) : 1.0;
        long target = Math.max(0, mtd + remaining - in.reductionWon());

        Map<YearMonth, Long> byMonth = new TreeMap<>();
        for (Expense e : in.past()) byMonth.merge(YearMonth.from(e.getDate()), e.getAmount(), Long::sum);
        int achieved = (int) byMonth.values().stream().filter(v -> v <= target).count();

        long total = mtd;
        long satisfied = in.thisMonth().stream()
                .filter(e -> "SATISFIED".equals(e.getEmotion()))
                .mapToLong(Expense::getAmount)
                .sum();
        long regretImpulse = in.thisMonth().stream()
                .filter(e -> "REGRET".equals(e.getEmotion()) || "IMPULSE".equals(e.getEmotion()))
                .mapToLong(Expense::getAmount)
                .sum();
        return new Facts(
                ratio,
                target,
                byMonth.size(),
                achieved,
                total > 0 ? (double) satisfied / total : 0,
                total > 0 ? (double) regretImpulse / total : 0);
    }

    private static Level level(Input in) {
        Facts f = facts(in);
        int points = 0;
        points += f.cutRatio() < 0.2 ? 0 : f.cutRatio() < 0.4 ? 1 : 2;
        points += f.pastMonths() == 0 ? 1 : f.achievedMonths() >= 2 ? 0 : f.achievedMonths() == 1 ? 1 : 2;
        if (f.satisfiedShare() >= 0.5) points += 1;
        if (f.regretImpulseShare() >= 0.5) points -= 1;
        if (in.rejectedBefore()) points += 1;
        return points <= 1 ? Level.EASY : points <= 3 ? Level.NORMAL : Level.HARD;
    }

    private static List<String> reasonsOf(Input in) {
        Facts f = facts(in);
        List<String> r = new ArrayList<>();
        if (in.remainingExpected() > 0) {
            r.add(String.format(
                    "남은 기간 예상 지출 %,d원 중 %d%%를 줄여야 해요", in.remainingExpected(), Math.round(f.cutRatio() * 100)));
        } else {
            r.add("남은 기간에 예상되는 지출이 없어 더 줄일 여지가 작아요");
        }
        if (f.pastMonths() == 0) {
            r.add("지난 3개월 기록이 없어 과거 달성 여부는 알 수 없어요");
        } else {
            r.add(String.format(
                    "지난 %d개월 중 %d개월은 월 %,d원 이하로 썼어요", f.pastMonths(), f.achievedMonths(), f.targetMonthly()));
        }
        if (f.satisfiedShare() >= 0.5) {
            r.add(String.format("이번 달 지출의 %d%%가 만족한 소비라 줄이기 아쉬울 수 있어요", Math.round(f.satisfiedShare() * 100)));
        }
        if (f.regretImpulseShare() >= 0.5) {
            r.add(String.format("이번 달 지출의 %d%%가 충동·후회 소비라 줄이기 쉬운 편이에요", Math.round(f.regretImpulseShare() * 100)));
        }
        if (in.rejectedBefore()) r.add("이전에 줄이고 싶지 않다고 한 카테고리예요");
        return r;
    }
}
