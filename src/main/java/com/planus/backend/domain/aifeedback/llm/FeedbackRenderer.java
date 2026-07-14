package com.planus.backend.domain.aifeedback.llm;

import com.planus.backend.domain.aifeedback.cause.CauseSignals;
import com.planus.backend.domain.aifeedback.cause.MemoEvidence;
import com.planus.backend.domain.aifeedback.core.BandWidth;
import com.planus.backend.domain.aifeedback.core.Confidence;
import com.planus.backend.domain.aifeedback.core.FeedbackType;
import com.planus.backend.domain.aifeedback.core.PacingComparator.PacingResult;
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

    /** 금액("N만원", "N,NNN원") 추출 정규식. 퍼센트·배율·건수·회 등은 매칭 안 함. */
    private static final Pattern MONEY_PATTERN = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)만원|(\\d[\\d,]*)원");

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
        - 숫자는 주어진 값만 쓴다. 과장/추측 금지. 없는 사실을 만들지 않는다.
        - 금액은 제공된 숫자를 그대로 사용할 것. 임의로 반올림하거나 바꾸지 않는다.
        - 절약 제안은 '어느 항목에서 얼마를 줄이면 저축 목표에 어떤 효과'인지 구체적으로 연결한다.
        - 원인 신뢰도가 '높음'이 아니면 원인을 단정하지 말고 가볍게만 언급하거나 생략한다.
        - 2~4문장, 따뜻하지만 담백하게. 이모지 남발 금지.
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
                text = client.complete(systemPrompt(c), userPrompt(c));
                if (text == null || text.isBlank()) {
                    text = template(c);
                } else if (!verifyNumbers(text, c)) {
                    log.warn("LLM 숫자 불일치 — 템플릿 폴백 사용");
                    text = template(c);
                }
            }
        } catch (Exception e) {
            log.warn("LLM 호출 실패 — 템플릿 폴백 사용: {}", e.getMessage());
            text = template(c);
        }
        return new Rendered(text, c.overallConfidence());
    }

    /** LOW_DATA: LLM 호출 없이 결정적 기록 리마인드. */
    private Rendered renderLowData() {
        return new Rendered(
                "이번 주는 기록이 평소보다 적어서 분석이 어려워요. "
                        + "지출을 꾸준히 기록하면 더 정확한 피드백을 드릴 수 있어요.",
                Confidence.LOW);
    }

    // ── 숫자 후검증 ──

    /**
     * LLM 출력의 금액 숫자를 추출하고 페이로드 값과 허용 오차 이내인지 대조한다.
     * 불일치가 하나라도 있으면 false.
     */
    boolean verifyNumbers(String text, FeedbackContext c) {
        Set<Long> allowed = collectAllowedValues(c);
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

        for (CategoryOverspend ov : c.overspendCategories()) {
            addWithRounded(values, ov.overAmountWon());
        }
        for (ActionSummary a : c.actions()) {
            addWithRounded(values, a.reductionWon());
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
                case SINGLE -> sb.append("- 지난달 이맘때는 예산의 ")
                        .append(pct(p.historicalAvgRate()))
                        .append("를 썼는데, 지금은 ")
                        .append(pct(p.currentRate()))
                        .append("\n");
                case RANGE -> sb.append("- 보통 이맘때 ")
                        .append(pct(p.referenceMin()))
                        .append("~")
                        .append(pct(p.referenceMax()))
                        .append(" 쓰셨는데, 지금은 ")
                        .append(pct(p.currentRate()))
                        .append("\n");
                case MEDIAN -> sb.append("- 보통 이맘때 ")
                        .append(pct(p.historicalAvgRate()))
                        .append(" 쓰셨는데, 지금은 ")
                        .append(pct(p.currentRate()))
                        .append("\n");
            }
        }

        // ── 초과 카테고리 + 원인 [5] ──
        if (!c.overspendCategories().isEmpty()) {
            sb.append("\n[초과 예상 카테고리]\n");
            for (CategoryOverspend ov : c.overspendCategories()) {
                sb.append("- ")
                        .append(ov.categoryName())
                        .append(": +")
                        .append(exactWon(ov.overAmountWon()));

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

                // 감정태그
                if (cs != null && !cs.emotionCounts().isEmpty()) {
                    sb.append("  감정: ");
                    cs.emotionCounts()
                            .forEach((emo, cnt) ->
                                    sb.append(emo).append("(").append(cnt).append(") "));
                    sb.append("\n");
                }

                // 메모 — 날짜+금액+텍스트 (LLM이 시간 맥락 판단)
                if (cs != null && cs.hasMemo()) {
                    sb.append("  메모:\n");
                    for (MemoEvidence memo : cs.memos().subList(0, Math.min(3, cs.memos().size()))) {
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
                        .append("배\n");
            } else {
                sb.append("\n[이상 신호 — 신뢰도 낮음/중간: 원인 단정 금지, 언급은 선택]\n");
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
            sb.append(" 참고로 ")
                    .append(c.anomalyCategoryName())
                    .append(" 지출이 평소보다 눈에 띄게 늘었어요.");
        }

        return sb.toString();
    }

    /**
     * 프롬프트 전용 정확한 금액 표현. "84,000원" 형식.
     * LLM이 정확한 값을 받으면 반올림을 최소화하여 숫자 후검증 통과율이 올라간다.
     */
    private static String exactWon(long v) {
        return String.format("%,d원", v);
    }

    /** 사용자 대면 친화적 금액. 만원 이상이면 "N만원" 형식. 템플릿 폴백에서만 사용. */
    private static String won(long v) {
        if (Math.abs(v) >= 10_000) return String.format("%,d만원", Math.round(v / 10_000.0));
        return String.format("%,d원", v);
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
            Confidence anomalyConfidence,
            List<CategoryOverspend> overspendCategories,
            List<ActionSummary> actions,
            FeedbackType feedbackType,
            BandWidth bandWidth,
            Confidence overallConfidence) {}

    public record CategoryOverspend(String categoryName, long overAmountWon, CauseSignals causeSignals) {}

    public record ActionSummary(
            String categoryName, long reductionWon, String dominantFactor, double feasibilityScore) {}

    public record Rendered(String text, Confidence confidence) {}
}
