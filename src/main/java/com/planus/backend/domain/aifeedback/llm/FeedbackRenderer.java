package com.planus.backend.domain.aifeedback.llm;

import com.planus.backend.domain.aifeedback.cause.CauseSignals;
import com.planus.backend.domain.aifeedback.cause.MemoEvidence;
import com.planus.backend.domain.aifeedback.core.BandWidth;
import com.planus.backend.domain.aifeedback.core.Confidence;
import com.planus.backend.domain.aifeedback.core.FeedbackType;
import com.planus.backend.domain.aifeedback.core.PacingComparator.PacingResult;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * [7] 분석 재료 → 사용자에게 보일 한국어 피드백 문장.
 *
 * <p>핵심 안전장치:
 * <ul>
 *   <li>LLM 출력의 모든 금액 숫자를 파싱하여 페이로드 값과 대조 → 불일치 시 템플릿 폴백
 *   <li>LOW_DATA → LLM 호출 없이 결정적 메시지
 *   <li>LLM 호출 실패 → 결정적 템플릿 폴백
 * </ul>
 */
@Service
public class FeedbackRenderer {

    private static final Logger log = LoggerFactory.getLogger(FeedbackRenderer.class);

    /**
     * 금액("N만원", "N만 원", "N,NNN원") 추출 정규식. 퍼센트·배율·건수·회 등은 매칭 안 함.
     * "만\\s*원" 으로 띄어쓰기 허용 — LLM이 "5만 원"처럼 쓰는 경우를 검증 대상으로 포함한다.
     */
    private static final Pattern MONEY_PATTERN = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)만\\s*원|(\\d[\\d,]*)\\s*원");

    /** 숫자 후검증 허용 오차. LLM이 자연스럽게 반올림하는 것을 허용. */
    private static final double NUMBER_TOLERANCE = 0.05;

    private final ObjectProvider<LlmClient> llm;

    public FeedbackRenderer(ObjectProvider<LlmClient> llm) {
        this.llm = llm;
    }

    private static final String BASE_SYSTEM =
            """
        너는 한국 20대를 위한 친근하고 간결한 가계부 코치다. 규칙:
        - 1순위 메시지는 '예산 진척률과 저축 목표 영향'이다. 항상 이걸 중심에 둔다.
        - 이번 주에 일어난 이벤트를 중심으로 피드백한다. 월말 예측은 맥락으로만 사용한다.
        - 숫자는 주어진 값만 쓴다. 과장/추측 금지. 없는 사실을 만들지 않는다.
        - 금액은 제공된 숫자를 그대로 사용할 것. 임의로 반올림하거나 바꾸지 않는다.
        - 절약 제안은 '어느 항목에서 얼마를 줄이면 저축 목표에 어떤 효과'인지 구체적으로 연결한다.
        - [절약 액션] 항목이 없으면 절약 금액을 임의로 만들지 않는다. 이상 신호 내용만 언급한다.
        - 초과 카테고리의 'MTD 초과' 금액은 이번 주 신규 금액을 이미 포함한 누적값이다. 두 숫자를 합산하지 않는다. '이번 주 신규'가 MTD의 일부임을 인식하고 맥락에 맞게 사용한다.
        - 초과 카테고리의 '이번 주 신규' 금액이 작으면 그 카테고리는 이번 주 원인이 아니라 이전 주 누적임을 인식하고 이번 주 피드백에서 주요 원인으로 다루지 않는다.
        - 원인 신뢰도가 '높음'이 아니면 원인을 단정하지 말고 가볍게만 언급하거나 생략한다.
        - 이상 신호 거래의 감정 태그에 따라 피드백 톤을 달리한다:
          • REGRET / IMPULSE → 명확하게 절제를 권고한다. "이런 소비는 줄여가는 게 좋겠어요" 수준으로 직접적으로 말한다.
          • NECESSARY → 지출이 불가피했음을 인정하되, 해당 카테고리 예산을 조정하거나 다른 항목을 줄이는 방향을 제안한다.
          • STRESS_RELIEF → 감정에 공감하되, 예산 영향을 짚고 다른 스트레스 해소 방법을 가볍게 제안한다.
          • SATISFIED → 사실을 담담하게 전달하고 과도한 칭찬이나 권고 없이 마무리한다.
        - 예산에 여유가 있어도 '더 써도 된다'는 식의 조언은 하지 않는다. 저축 목표 달성을 기준으로 판단한다.
        - POSITIVE 주차(초과 카테고리 없음)에도 MEDIUM 이상의 이상 신호가 있으면 마지막 문장에서 1문장만 가볍게 언급한다.
        - 항상 '~요/~어요' 체 존댓말로 일관되게 작성한다.
        - 2~4문장, 따뜻하지만 담백하게. 이모지는 1~2개 이내로 적절히 사용.
        """;

    private static final String MONTH_END_SYSTEM =
            """
        너는 한국 20대를 위한 친근하고 간결한 가계부 코치다. 이번 달 결산 피드백을 작성한다. 규칙:
        - 이번 달 확정 지출과 가용예산을 비교해서 결산한다.
        - 잘 아낀 카테고리가 있으면 칭찬한다.
        - 다음 달 개선할 것을 1가지만 구체적으로 제안한다.
        - 숫자는 주어진 값만 쓴다. 없는 사실을 만들지 않는다.
        - 금액은 제공된 숫자를 그대로 사용할 것. 임의로 반올림하거나 바꾸지 않는다.
        - 항상 '~요/~어요' 체 존댓말로 일관되게 작성한다.
        - 3~5문장, 따뜻하지만 담백하게. 이모지는 1~2개 이내로 적절히 사용.
        """;

    /**
     * 분석 재료로 피드백 텍스트를 생성한다.
     *
     * <p>LOW_DATA → 결정적 메시지. LLM 호출 후 숫자 후검증 실패 → 템플릿 폴백.
     */
    public Rendered render(FeedbackContext c) {
        if (c.feedbackType() == FeedbackType.LOW_DATA) {
            return renderLowData();
        }

        String text;
        try {
            LlmClient client = llm.getIfAvailable();
            if (client == null) {
                text = template(c);
            } else {
                text = callWithRetry(client, c);
            }
        } catch (Exception e) {
            log.warn("LLM 호출 실패 — 템플릿 폴백 사용: {}", e.getMessage());
            text = template(c);
        }
        return new Rendered(text, c.overallConfidence());
    }

    /**
     * 숫자 후검증 실패 시 최대 {@code MAX_LLM_ATTEMPTS}회 재시도 후 템플릿 폴백.
     * LLM의 확률적 특성상 재시도만으로도 검증을 통과하는 경우가 많다.
     */
    private static final int MAX_LLM_ATTEMPTS = 3;

    private String callWithRetry(LlmClient client, FeedbackContext c) {
        String system = systemPrompt(c);
        String user = userPrompt(c);
        for (int attempt = 1; attempt <= MAX_LLM_ATTEMPTS; attempt++) {
            String candidate = client.complete(system, user);
            if (candidate == null || candidate.isBlank()) {
                break;
            }
            if (verifyNumbers(candidate, c)) {
                return candidate;
            }
            if (attempt < MAX_LLM_ATTEMPTS) {
                log.warn("LLM 숫자 불일치 — 재시도 {}/{}", attempt, MAX_LLM_ATTEMPTS - 1);
            } else {
                log.warn("LLM 숫자 불일치 — 템플릿 폴백 사용 ({}회 시도)", MAX_LLM_ATTEMPTS);
            }
        }
        return template(c);
    }

    private String callMonthEndWithRetry(LlmClient client, MonthEndContext c) {
        String user = monthEndUserPrompt(c);
        for (int attempt = 1; attempt <= MAX_LLM_ATTEMPTS; attempt++) {
            String candidate = client.complete(MONTH_END_SYSTEM, user);
            if (candidate == null || candidate.isBlank()) {
                break;
            }
            if (verifyMonthEndNumbers(candidate, c)) {
                return candidate;
            }
            if (attempt < MAX_LLM_ATTEMPTS) {
                log.warn("월말 LLM 숫자 불일치 — 재시도 {}/{}", attempt, MAX_LLM_ATTEMPTS - 1);
            } else {
                log.warn("월말 LLM 숫자 불일치 — 템플릿 폴백 사용 ({}회 시도)", MAX_LLM_ATTEMPTS);
            }
        }
        return monthEndTemplate(c);
    }

    /** LOW_DATA: LLM 호출 없이 결정적 기록 리마인드. */
    private Rendered renderLowData() {
        return new Rendered("이번 주는 기록이 평소보다 적어서 분석이 어려워요. " + "지출을 꾸준히 기록하면 더 정확한 피드백을 드릴 수 있어요.", Confidence.LOW);
    }

    // ── 숫자 후검증 ──

    /**
     * LLM 출력의 금액 숫자를 추출하고 페이로드 값과 허용 오차 이내인지 대조한다.
     * 불일치가 하나라도 있으면 false.
     */
    boolean verifyNumbers(String text, FeedbackContext c) {
        return checkExtracted(text, collectAllowedValues(c));
    }

    /** 추출된 금액이 허용값 집합 안에 있는지 확인하는 공통 헬퍼. */
    private boolean checkExtracted(String text, Set<Long> allowed) {
        List<Long> extracted = extractMoneyValues(text);
        if (extracted.isEmpty()) return true;
        return extracted.stream().allMatch(v -> allowed.stream().anyMatch(a -> isWithinTolerance(v, a)));
    }

    /**
     * 반올림 허용: 5% 이내면 통과.
     * "약 8만원"(80,000) vs 84,000 → 4.8% 차이 → 통과.
     */
    private boolean isWithinTolerance(long extracted, long allowed) {
        if (allowed == 0) return extracted == 0;
        return Math.abs(extracted - allowed) <= Math.abs(allowed) * NUMBER_TOLERANCE;
    }

    /**
     * FeedbackContext에서 검증 대상 금액을 수집한다.
     * 프롬프트에서 {@code exactWon()}으로 정확한 값을 전달하므로 LLM이 반올림할 가능성은 낮지만,
     * 만원 반올림 변환값도 포함하여 {@code won()} 폴백 시에도 안전하게 통과.
     */
    private Set<Long> collectAllowedValues(FeedbackContext c) {
        Set<Long> values = new HashSet<>();
        addWithRounded(values, c.predictedMonthEndWon());
        addWithRounded(values, c.availableBudgetWon());
        addWithRounded(values, Math.abs(c.savingsImpactWon()));

        addWithRounded(values, c.weekTotalWon());
        addWithRounded(values, c.prevWeekTotalWon());
        if (c.weekTotalWon() > 0 && c.prevWeekTotalWon() > 0) {
            addWithRounded(values, Math.abs(c.weekTotalWon() - c.prevWeekTotalWon()));
        }
        addWithRounded(values, c.anomalyWeeklyAmountWon());
        c.additionalHighAnomalies().forEach(a -> addWithRounded(values, a.weeklyAmountWon()));
        if (c.weekEmotion() != null) {
            c.weekEmotion().amounts().values().forEach(v -> addWithRounded(values, v));
            addWithRounded(values, c.weekEmotion().untaggedWon());
        }
        for (CategoryOverspend ov : c.overspendCategories()) {
            addWithRounded(values, ov.overAmountWon());
            addWithRounded(values, ov.thisWeekWon());
            if (ov.causeSignals() != null) {
                for (MemoEvidence memo : ov.causeSignals().memos()) {
                    addWithRounded(values, memo.amount());
                }
            }
        }
        for (ActionSummary a : c.actions()) {
            addWithRounded(values, a.reductionWon());
        }
        for (TransactionHighlight h : c.weekHighlights()) {
            addWithRounded(values, h.amountWon());
        }
        return values;
    }

    /**
     * 원본 값과 만원 단위 반올림값을 모두 추가한다.
     * 프롬프트가 "84,000원"을 주지만 LLM이 "약 8만원"이라 쓸 수 있으므로,
     * 80,000(= Math.round(84,000 / 10,000) × 10,000)도 허용 값으로 등록.
     */
    private void addWithRounded(Set<Long> set, long v) {
        set.add(v);
        if (Math.abs(v) >= 10_000) {
            set.add(Math.round(v / 10_000.0) * 10_000);
        }
    }

    /**
     * 텍스트에서 금액(만원/원)을 추출한다.
     * "12만원" → 120,000 / "8.4만원" → 84,000 / "5,000원" → 5,000.
     * 정규식이 "원" 접미사 있는 것만 매칭 → 퍼센트·배율·건수 무시.
     */
    List<Long> extractMoneyValues(String text) {
        List<Long> values = new ArrayList<>();
        Matcher m = MONEY_PATTERN.matcher(text);
        while (m.find()) {
            try {
                if (m.group(1) != null) {
                    double manWon = Double.parseDouble(m.group(1).replace(",", ""));
                    values.add(Math.round(manWon * 10_000));
                } else if (m.group(2) != null) {
                    long won = Long.parseLong(m.group(2).replace(",", ""));
                    values.add(won);
                }
            } catch (NumberFormatException ignore) {
                // 파싱 실패 → 무시
            }
        }
        return values;
    }

    // ── 프롬프트 ──

    /** BandWidth에 따라 톤 지시를 동적으로 추가한 시스템 프롬프트. */
    private String systemPrompt(FeedbackContext c) {
        StringBuilder sb = new StringBuilder(BASE_SYSTEM);
        if (c.bandWidth() != null) {
            switch (c.bandWidth()) {
                case WIDE -> sb.append("\n- 아직 월초이므로 단정적 표현을 피하고 '~일 수 있어요' 톤 사용.");
                case MEDIUM -> sb.append("\n- 중간 시점이므로 범위로 표현하고 추이 관찰을 안내.");
                case NARROW -> sb.append("\n- 월말이라 데이터가 충분하므로 구체적이고 확신 있는 톤 사용.");
            }
        }
        return sb.toString();
    }

    /**
     * 분석 재료를 LLM 유저 프롬프트 형식으로 조합한다.
     *
     * <p>금액은 {@code exactWon()}으로 정확한 값("84,000원")을 전달한다.
     * {@code won()}("8만원")은 사용자 대면 템플릿 폴백에서만 사용.
     * LLM이 정확한 값을 받으면 반올림을 최소화하여 숫자 후검증 통과율이 올라간다.
     */
    private String userPrompt(FeedbackContext c) {
        StringBuilder sb = new StringBuilder();
        sb.append("아래 분석 재료로 이번 주 소비 피드백을 작성해줘.\n\n");

        // ── 1순위: 예산·저축 ──
        sb.append("[예산·저축 — 1순위]\n");
        sb.append("- 예상 월말 총지출: ").append(exactWon(c.predictedMonthEndWon())).append("\n");
        sb.append("- 가용예산: ").append(exactWon(c.availableBudgetWon())).append("\n");
        if (c.weekTotalWon() > 0) {
            sb.append("- 이번 주 총 변동지출: ").append(exactWon(c.weekTotalWon()));
            if (c.prevWeekTotalWon() > 0) {
                long diff = c.weekTotalWon() - c.prevWeekTotalWon();
                sb.append(" (전주 ")
                        .append(exactWon(c.prevWeekTotalWon()))
                        .append(" 대비 ")
                        .append(diff >= 0 ? "+" : "")
                        .append(exactWon(diff))
                        .append(")");
            }
            sb.append("\n");
        }
        if (c.complianceRate() > 0.0) {
            sb.append("- 최근 12주 예산 준수율: ")
                    .append(String.format("%.0f%%", c.complianceRate() * 100))
                    .append("\n");
        }
        if (c.savingsImpactWon() < 0) {
            sb.append("- 이대로면 저축 목표에서 ")
                    .append(exactWon(Math.abs(c.savingsImpactWon())))
                    .append(" 멀어짐\n");
        } else {
            sb.append("- 현재 페이스는 예산 안. 저축 목표 달성 가능\n");
        }

        // ── 페이싱 [2] ──
        if (c.pacing() != null) {
            sb.append("\n[페이싱 비교]\n");
            PacingResult p = c.pacing();
            switch (p.displayType()) {
                case SINGLE ->
                    sb.append("- 지난달 이맘때는 예산의 ")
                            .append(pct(p.historicalAvgRate()))
                            .append("를 썼는데, 지금은 ")
                            .append(pct(p.currentRate()))
                            .append("\n");
                case RANGE ->
                    sb.append("- 보통 이맘때 ")
                            .append(pct(p.referenceMin()))
                            .append("~")
                            .append(pct(p.referenceMax()))
                            .append(" 쓰셨는데, 지금은 ")
                            .append(pct(p.currentRate()))
                            .append("\n");
                case MEDIAN ->
                    sb.append("- 보통 이맘때 ")
                            .append(pct(p.historicalAvgRate()))
                            .append(" 쓰셨는데, 지금은 ")
                            .append(pct(p.currentRate()))
                            .append("\n");
            }
        }

        // ── 이번 주 소비 감정 분포 ──
        if (c.weekEmotion() != null && !c.weekEmotion().counts().isEmpty()) {
            sb.append("\n[이번 주 소비 감정]\n");
            c.weekEmotion().counts().forEach((emo, cnt) -> {
                Long amt = c.weekEmotion().amounts().get(emo);
                sb.append("- ").append(emo).append(" ").append(cnt).append("건");
                if (amt != null) sb.append(" / ").append(exactWon(amt));
                sb.append("\n");
            });
            if (c.weekEmotion().untaggedWon() > 0) {
                sb.append("- 태그 없음 / ")
                        .append(exactWon(c.weekEmotion().untaggedWon()))
                        .append("\n");
            }
        }

        // ── 초과 카테고리 + 원인 [5] ──
        if (!c.overspendCategories().isEmpty()) {
            sb.append("\n[초과 예상 카테고리]\n");
            for (CategoryOverspend ov : c.overspendCategories()) {
                sb.append("- ")
                        .append(ov.categoryName())
                        .append(": MTD 초과 +")
                        .append(exactWon(ov.overAmountWon()))
                        .append(" (이 중 이번 주 신규 ")
                        .append(exactWon(ov.thisWeekWon()))
                        .append(")");

                CauseSignals cs = ov.causeSignals();
                if (cs != null) {
                    if (cs.selfRatio() != null) {
                        sb.append(" (과거 대비 ")
                                .append(String.format("%.1f", cs.selfRatio()))
                                .append("배)");
                    }
                    if (cs.dominantFactor() != null) {
                        String factor =
                                switch (cs.dominantFactor()) {
                                    case "FREQUENCY" -> "횟수 증가";
                                    case "UNIT_PRICE" -> "건당 단가 증가";
                                    default -> "횟수+단가 모두 증가";
                                };
                        sb.append(" — 원인: ").append(factor);
                    }
                    // streak 값만 전달 — LLM이 맥락에 맞게 표현
                    if (cs.repeatStreak() != null && cs.repeatStreak() >= 1) {
                        sb.append(" [").append(cs.repeatStreak()).append("주 연속 초과]");
                    }
                }
                sb.append("\n");

                // 감정태그 — 건수와 금액 합계 함께 표시
                if (cs != null && !cs.emotionCounts().isEmpty()) {
                    sb.append("  감정: ");
                    cs.emotionCounts().forEach((emo, cnt) -> {
                        sb.append(emo).append(" ").append(cnt).append("건");
                        Long amt = cs.emotionAmounts() != null
                                ? cs.emotionAmounts().get(emo)
                                : null;
                        if (amt != null) sb.append("/").append(exactWon(amt));
                        sb.append("  ");
                    });
                    sb.append("\n");
                }

                // 메모 — 날짜+금액+텍스트 (LLM이 시간 맥락 판단)
                if (cs != null && cs.hasMemo()) {
                    sb.append("  메모:\n");
                    for (MemoEvidence memo :
                            cs.memos().subList(0, Math.min(3, cs.memos().size()))) {
                        sb.append("    [")
                                .append(memo.date())
                                .append("] \"")
                                .append(memo.text())
                                .append("\" (")
                                .append(exactWon(memo.amount()))
                                .append(")\n");
                    }
                }
            }
        }

        // ── 이상치 [3] ──
        if (c.hasAnomaly()) {
            if (c.anomalyConfidence() == Confidence.HIGH) {
                sb.append("\n[이상 신호 — 신뢰도 높음, 노출 가능]\n");
                sb.append("- 카테고리: ")
                        .append(c.anomalyCategoryName())
                        .append(", 평소 대비 약 ")
                        .append(String.format("%.1f", c.anomalyMagnitude()))
                        .append("배");
                if (c.anomalyWeeklyAmountWon() > 0) {
                    sb.append(", 이번 주 지출: ").append(exactWon(c.anomalyWeeklyAmountWon()));
                }
                if (c.anomalyEmotion() != null && !c.anomalyEmotion().isBlank()) {
                    sb.append(", 감정 태그: ").append(c.anomalyEmotion());
                }
                sb.append("\n");
            } else {
                sb.append("\n[이상 신호 — 신뢰도 낮음/중간: 원인 단정 금지, 언급은 선택]\n");
                if (c.anomalyEmotion() != null && !c.anomalyEmotion().isBlank()) {
                    sb.append("- 감정 태그: ").append(c.anomalyEmotion()).append("\n");
                }
            }
        }

        // ── 추가 HIGH 이상치 (각각 1문장씩 언급) ──
        if (!c.additionalHighAnomalies().isEmpty()) {
            sb.append("\n[추가 HIGH 이상치 — 피드백에 각각 1문장씩 자연스럽게 언급]\n");
            for (AnomalyInfo a : c.additionalHighAnomalies()) {
                sb.append("- 카테고리: ")
                        .append(a.categoryName())
                        .append(", 평소 대비 약 ")
                        .append(String.format("%.1f", a.magnitude()))
                        .append("배");
                if (a.weeklyAmountWon() > 0) {
                    sb.append(", 이번 주 지출: ").append(exactWon(a.weeklyAmountWon()));
                }
                if (a.emotion() != null && !a.emotion().isBlank()) {
                    sb.append(", 감정 태그: ").append(a.emotion());
                }
                sb.append("\n");
            }
        }

        // ── 이번 주 주목 거래 ──
        if (!c.weekHighlights().isEmpty()) {
            sb.append("\n[이번 주 주목 거래]\n");
            for (TransactionHighlight h : c.weekHighlights()) {
                sb.append("- [")
                        .append(h.date())
                        .append("] ")
                        .append(h.categoryName())
                        .append(" ")
                        .append(exactWon(h.amountWon()));
                if (h.memo() != null && !h.memo().isBlank()) {
                    sb.append(" \"").append(h.memo()).append("\"");
                }
                if (h.emotion() != null && !h.emotion().isBlank()) {
                    sb.append(" (").append(h.emotion()).append(")");
                }
                sb.append("\n");
            }
        }

        // ── 절약 액션 [6] ──
        if (!c.actions().isEmpty()) {
            sb.append("\n[절약 액션]\n");
            for (ActionSummary a : c.actions()) {
                sb.append("- ")
                        .append(a.categoryName())
                        .append("에서 ")
                        .append(exactWon(a.reductionWon()))
                        .append(" 줄이기");
                if (a.dominantFactor() != null) {
                    String tip =
                            switch (a.dominantFactor()) {
                                case "FREQUENCY" -> " (횟수를 줄이는 방향)";
                                case "UNIT_PRICE" -> " (건당 금액을 낮추는 방향)";
                                default -> "";
                            };
                    sb.append(tip);
                }
                sb.append("\n");
            }
        }

        // 추가 HIGH 이상치 언급 강제 — 프롬프트 끝에 명시적 지시
        if (!c.additionalHighAnomalies().isEmpty()) {
            sb.append("\n※ 위 [추가 HIGH 이상치] 항목들을 피드백에 자연스럽게 포함할 것.\n");
        }

        return sb.toString();
    }

    // ── 월말 결산 렌더링 ──

    /**
     * 월말 확정 데이터를 바탕으로 결산 피드백을 생성한다.
     *
     * <p>확정 수치를 사용하므로 LOW_DATA 분기 없이 항상 LLM 호출을 시도한다.
     * 숫자 후검증 실패 또는 LLM 오류 시 결정적 템플릿으로 폴백.
     */
    public Rendered renderMonthEnd(MonthEndContext c) {
        String text;
        try {
            LlmClient client = llm.getIfAvailable();
            if (client == null) {
                text = monthEndTemplate(c);
            } else {
                text = callMonthEndWithRetry(client, c);
            }
        } catch (Exception e) {
            log.warn("월말 LLM 호출 실패 — 템플릿 폴백 사용: {}", e.getMessage());
            text = monthEndTemplate(c);
        }
        // 월말은 확정 데이터 기반이므로 항상 HIGH
        return new Rendered(text, Confidence.HIGH);
    }

    /** 월말 컨텍스트의 금액 숫자를 추출하고 허용 값과 대조한다. */
    boolean verifyMonthEndNumbers(String text, MonthEndContext c) {
        Set<Long> allowed = new HashSet<>();
        addWithRounded(allowed, c.actualTotalWon());
        addWithRounded(allowed, c.availableBudgetWon());
        addWithRounded(allowed, Math.abs(c.budgetDiffWon()));
        for (CategoryResult r : c.overspendCategories()) {
            addWithRounded(allowed, r.amountWon());
        }
        for (CategoryResult r : c.savingCategories()) {
            addWithRounded(allowed, r.amountWon());
        }
        return checkExtracted(text, allowed);
    }

    private String monthEndUserPrompt(MonthEndContext c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.yearMonth().getYear())
                .append("년 ")
                .append(c.yearMonth().getMonthValue())
                .append("월 결산 피드백을 작성해줘.\n\n");

        sb.append("[결산]\n");
        sb.append("- 실제 총지출: ").append(exactWon(c.actualTotalWon())).append("\n");
        sb.append("- 가용예산: ").append(exactWon(c.availableBudgetWon())).append("\n");
        if (c.budgetDiffWon() < 0) {
            sb.append("- 예산 대비 초과: ")
                    .append(exactWon(Math.abs(c.budgetDiffWon())))
                    .append("\n");
        } else {
            sb.append("- 예산 대비 절약: ").append(exactWon(c.budgetDiffWon())).append("\n");
        }

        if (!c.overspendCategories().isEmpty()) {
            sb.append("\n[초과 카테고리]\n");
            for (CategoryResult r : c.overspendCategories()) {
                sb.append("- ")
                        .append(r.categoryName())
                        .append(": +")
                        .append(exactWon(r.amountWon()))
                        .append("\n");
            }
        }

        if (!c.savingCategories().isEmpty()) {
            sb.append("\n[잘 아낀 카테고리]\n");
            for (CategoryResult r : c.savingCategories()) {
                sb.append("- ")
                        .append(r.categoryName())
                        .append(": -")
                        .append(exactWon(r.amountWon()))
                        .append("\n");
            }
        }

        return sb.toString();
    }

    private String monthEndTemplate(MonthEndContext c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.yearMonth().getMonthValue())
                .append("월 결산: 총 ")
                .append(won(c.actualTotalWon()))
                .append(" 지출.");
        if (c.budgetDiffWon() >= 0) {
            sb.append(" 예산보다 ").append(won(c.budgetDiffWon())).append(" 아꼈어요. 이번 달 수고하셨어요!");
        } else {
            sb.append(" 예산보다 ").append(won(Math.abs(c.budgetDiffWon()))).append(" 초과했어요.");
            if (!c.overspendCategories().isEmpty()) {
                CategoryResult top = c.overspendCategories().get(0);
                sb.append(" 다음 달에는 ").append(top.categoryName()).append(" 지출을 줄여보세요.");
            }
        }
        return sb.toString();
    }

    /** LLM 없이도 동작하는 결정적 폴백. 사용자 대면이므로 {@code won()}으로 자연스러운 표현. */
    private String template(FeedbackContext c) {
        StringBuilder sb = new StringBuilder();

        if (c.savingsImpactWon() < 0) {
            sb.append("이번 달 이대로 가면 약 ")
                    .append(won(c.predictedMonthEndWon()))
                    .append("을 써서 저축 목표에서 ")
                    .append(won(Math.abs(c.savingsImpactWon())))
                    .append(" 멀어질 것 같아요.");
            if (!c.actions().isEmpty()) {
                ActionSummary top = c.actions().get(0);
                sb.append(" ")
                        .append(top.categoryName())
                        .append("에서 ")
                        .append(won(top.reductionWon()))
                        .append("만 줄여도 목표에 한결 가까워져요.");
            }
        } else {
            sb.append("이번 달은 예산 안에서 잘 가고 있어요. 지금 페이스면 저축 목표도 무리 없어요.");
        }

        if (c.hasAnomaly() && c.anomalyConfidence() == Confidence.HIGH) {
            sb.append(" 참고로 ").append(c.anomalyCategoryName()).append(" 지출이 평소보다 눈에 띄게 늘었어요.");
        }
        for (AnomalyInfo a : c.additionalHighAnomalies()) {
            sb.append(" ").append(a.categoryName()).append(" 지출도 평소보다 늘었어요.");
        }

        return sb.toString();
    }

    /**
     * 프롬프트 전용 정확한 금액 표현. "84,000원" 형식.
     * LLM이 정확한 값을 받으면 반올림을 최소화하여 숫자 후검증 통과율이 올라간다.
     */
    private static String exactWon(long v) {
        return String.format(java.util.Locale.KOREA, "%,d원", v);
    }

    /** 사용자 대면 친화적 금액. 만원 이상이면 "N만원" 형식. 템플릿 폴백에서만 사용. */
    private static String won(long v) {
        if (Math.abs(v) >= 10_000) return String.format(java.util.Locale.KOREA, "%,d만원", Math.round(v / 10_000.0));
        return String.format(java.util.Locale.KOREA, "%,d원", v);
    }

    /** 소진율을 퍼센트 문자열로 변환한다. */
    private static String pct(double rate) {
        return String.format("%.0f%%", rate * 100);
    }

    // ── 레코드 ──

    /** 오케스트레이터가 채워 넘기는 모든 재료. */
    public record FeedbackContext(
            long predictedMonthEndWon,
            long availableBudgetWon,
            long savingsImpactWon,
            double burnRate,
            PacingResult pacing,
            boolean hasAnomaly,
            String anomalyCategoryName,
            double anomalyMagnitude,
            long anomalyWeeklyAmountWon,
            Confidence anomalyConfidence,
            String anomalyEmotion,
            List<AnomalyInfo> additionalHighAnomalies,
            WeekEmotionSummary weekEmotion,
            long weekTotalWon,
            long prevWeekTotalWon,
            double complianceRate,
            List<CategoryOverspend> overspendCategories,
            List<ActionSummary> actions,
            List<TransactionHighlight> weekHighlights,
            FeedbackType feedbackType,
            BandWidth bandWidth,
            Confidence overallConfidence) {}

    /**
     * PRIMARY를 제외한 추가 HIGH 이상치 정보.
     * confidence는 모두 HIGH로 필터링된 상태이므로 별도로 보관하지 않는다.
     */
    public record AnomalyInfo(String categoryName, double magnitude, long weeklyAmountWon, String emotion) {}

    /**
     * 카테고리별 초과 예상.
     *
     * @param overAmountWon  MTD 기준 예상 초과액 (이전 주 누적 포함)
     * @param thisWeekWon    이번 주에 새로 발생한 해당 카테고리 변동 지출
     */
    public record CategoryOverspend(
            String categoryName, long overAmountWon, long thisWeekWon, CauseSignals causeSignals) {}

    /**
     * 이번 주 전체 변동지출의 감정 태그 분포 요약.
     * POSITIVE 주차에도 감정 맥락을 LLM에 전달하기 위해 초과 카테고리 여부와 무관하게 조립한다.
     *
     * @param counts     감정 태그별 건수  {"REGRET": 2, "SATISFIED": 3}
     * @param amounts    감정 태그별 금액 합계
     * @param untaggedWon 태그 없는 변동지출 합계
     */
    public record WeekEmotionSummary(Map<String, Long> counts, Map<String, Long> amounts, long untaggedWon) {}

    public record ActionSummary(
            String categoryName, long reductionWon, String dominantFactor, double feasibilityScore) {}

    public record Rendered(String text, Confidence confidence) {}

    /** 월말 결산 컨텍스트. {@code budgetDiffWon} > 0 이면 절약, < 0 이면 초과. */
    public record MonthEndContext(
            YearMonth yearMonth,
            long actualTotalWon,
            long availableBudgetWon,
            long budgetDiffWon,
            List<CategoryResult> overspendCategories,
            List<CategoryResult> savingCategories) {}

    /** 카테고리별 결산 결과. {@code amountWon}은 항상 양수(초과/절약 절댓값). */
    public record CategoryResult(String categoryName, long amountWon) {}

    /**
     * 이번 주 주목 거래. 이상치 감지됐거나 REGRET·IMPULSE 감정 태그가 있거나 메모가 있는 거래.
     * 금액 내림차순 상위 5건으로 제한.
     */
    public record TransactionHighlight(
            LocalDate date, String categoryName, long amountWon, String memo, String emotion) {}
}
