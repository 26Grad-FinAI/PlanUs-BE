package com.planus.backend.domain.aifeedback.llm;

import com.planus.backend.domain.aifeedback.core.Confidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * [5]·[7] 재료 → 사용자에게 보일 한국어 피드백 문장.
 *  - 1순위 메시지는 예산·저축. 이상치/원인은 신뢰도 '높음'일 때만 단독 노출.
 *  - LLM 호출 실패/미설정 시 결정적 템플릿으로 폴백(키 없이도 데모 가능).
 */
@Service
public class FeedbackRenderer {

    private static final Logger log = LoggerFactory.getLogger(FeedbackRenderer.class);

    private final ObjectProvider<LlmClient> llm;

    /** @param llm LLM 클라이언트 (미설정 시 null, 템플릿 폴백) */
    public FeedbackRenderer(ObjectProvider<LlmClient> llm) { this.llm = llm; }

    private static final String SYSTEM = """
        너는 한국 20대를 위한 친근하고 간결한 가계부 코치다. 규칙:
        - 1순위 메시지는 '예산 진척률과 저축 목표 영향'이다. 항상 이걸 중심에 둔다.
        - 숫자는 주어진 값만 쓴다. 과장/추측 금지. 없는 사실을 만들지 않는다.
        - 절약 제안은 '어느 항목에서 얼마를 줄이면 저축 목표에 어떤 효과'인지 구체적으로 연결한다.
        - 원인 신뢰도가 '높음'이 아니면 원인을 단정하지 말고 가볍게만 언급하거나 생략한다.
        - 2~4문장, 따뜻하지만 담백하게. 이모지 남발 금지.
        """;

    /**
     * 분석 재료로 피드백 텍스트를 생성한다. LLM 호출 실패 시 결정적 템플릿으로 폴백.
     *
     * @param c 오케스트레이터가 조립한 피드백 재료
     * @return 렌더링된 피드백 텍스트와 신뢰도
     */
    public Rendered render(FeedbackContext c) {
        String text;
        try {
            LlmClient client = llm.getIfAvailable();
            if (client == null) {
                text = template(c);
            } else {
                text = client.complete(SYSTEM, userPrompt(c));
                if (text == null || text.isBlank()) text = template(c);
            }
        } catch (Exception e) {
            log.warn("LLM 호출 실패 — 템플릿 폴백 사용: {}", e.getMessage());
            text = template(c);
        }
        return new Rendered(text, c.overallConfidence());
    }

    /** 분석 재료를 LLM 유저 프롬프트 형식으로 조합한다. */
    private String userPrompt(FeedbackContext c) {
        StringBuilder sb = new StringBuilder();
        sb.append("아래 분석 재료로 이번 주 소비 피드백을 작성해줘.\n\n");
        sb.append("[예산·저축 — 1순위]\n");
        sb.append("- 예상 월말 총지출: ").append(won(c.predictedMonthEndWon())).append("\n");
        sb.append("- 가용예산: ").append(won(c.availableBudgetWon())).append("\n");
        if (c.savingsImpactWon() > 0)
            sb.append("- 이대로면 저축 목표에서 ").append(won(c.savingsImpactWon())).append(" 멀어짐\n");
        else
            sb.append("- 현재 페이스는 예산 안. 저축 목표 달성 가능\n");
        if (c.topOverCategoryName() != null)
            sb.append("- 가장 초과 예상 카테고리: ").append(c.topOverCategoryName())
              .append(" (+").append(won(c.topOverAmountWon())).append(")\n");

        if (c.hasAnomaly() && c.anomalyConfidence() == Confidence.HIGH) {
            sb.append("\n[이상 신호 — 신뢰도 높음, 노출 가능]\n");
            sb.append("- 카테고리: ").append(c.anomalyCategoryName())
              .append(", 평소 대비 약 ").append(String.format("%.1f", c.anomalyMagnitude())).append("배\n");
            if (c.anomalyBaselineAvgWon() > 0)
                sb.append("- 비교: 평소 평균 ").append(won(c.anomalyBaselineAvgWon()))
                  .append(" → 이번 주 ").append(won(c.anomalyCurrentWon())).append("\n");
            if (!c.precedentMemos().isEmpty())
                sb.append("- 과거 비슷한 상황 메모: ").append(String.join(" / ", c.precedentMemos()))
                  .append(" (이걸 근거로 원인을 자연스럽게 추정)\n");
        } else if (c.hasAnomaly()) {
            sb.append("\n[이상 신호 — 신뢰도 낮음/중간: 원인 단정 금지, 언급은 선택]\n");
        }

        if (!c.stableCategories().isEmpty()) {
            sb.append("\n[안정 카테고리 — 짧게 칭찬]\n");
            sb.append("- 예산 내 유지 중: ").append(String.join(", ", c.stableCategories())).append("\n");
        }

        if (c.hasAction()) {
            sb.append("\n[절약 액션]\n");
            sb.append("- 제안: ").append(c.actionCategoryName())
              .append("에서 ").append(won(c.actionAmountWon())).append(" 줄이면 저축 목표에 도움\n");
            if (c.varyMessage())
                sb.append("- (이 사용자는 과거 같은 제안을 '반복적'이라며 거부 → 표현을 새롭게)\n");
        }
        return sb.toString();
    }

    /** LLM 없이도 동작하는 결정적 폴백. */
    private String template(FeedbackContext c) {
        StringBuilder sb = new StringBuilder();
        if (c.savingsImpactWon() > 0) {
            sb.append("이번 달 이대로 가면 약 ").append(won(c.predictedMonthEndWon()))
              .append("을 써서 저축 목표에서 ").append(won(c.savingsImpactWon())).append(" 멀어질 것 같아요.");
            if (c.hasAction())
                sb.append(" ").append(c.actionCategoryName()).append("에서 ")
                  .append(won(c.actionAmountWon())).append("만 줄여도 목표에 한결 가까워져요.");
        } else {
            sb.append("이번 달은 예산 안에서 잘 가고 있어요. 지금 페이스면 저축 목표도 무리 없어요.");
        }
        if (c.hasAnomaly() && c.anomalyConfidence() == Confidence.HIGH) {
            sb.append(" 참고로 ").append(c.anomalyCategoryName())
              .append(" 지출이 평소보다 눈에 띄게 늘었어요");
            if (c.anomalyBaselineAvgWon() > 0)
                sb.append(" (평소 ").append(won(c.anomalyBaselineAvgWon()))
                  .append(" → 이번 주 ").append(won(c.anomalyCurrentWon())).append(")");
            else if (!c.precedentMemos().isEmpty())
                sb.append(" (예: ").append(c.precedentMemos().get(0)).append(")");
            sb.append(".");
        }
        if (!c.stableCategories().isEmpty()) {
            sb.append(" 반면 ").append(String.join("·", c.stableCategories()))
              .append("은 예산 안에서 안정적이에요.");
        }
        return sb.toString();
    }

    /** 금액(원)을 사용자 친화적 문자열로 변환한다. 만원 이상이면 "N만원" 형식. */
    private static String won(long v) {
        if (Math.abs(v) >= 10_000) return String.format("%,d만원", Math.round(v / 10_000.0));
        return String.format("%,d원", v);
    }

    /** 오케스트레이터가 채워 넘기는 모든 재료. category는 이름으로 전달. */
    public record FeedbackContext(
            long predictedMonthEndWon, long availableBudgetWon, long savingsImpactWon,
            String topOverCategoryName, long topOverAmountWon,
            boolean hasAnomaly, String anomalyCategoryName, double anomalyMagnitude,
            List<String> precedentMemos, Confidence anomalyConfidence,
            boolean hasAction, String actionCategoryName, long actionAmountWon, boolean varyMessage,
            Confidence overallConfidence,
            List<String> stableCategories,
            long anomalyBaselineAvgWon, long anomalyCurrentWon) {}

    public record Rendered(String text, Confidence confidence) {}
}
