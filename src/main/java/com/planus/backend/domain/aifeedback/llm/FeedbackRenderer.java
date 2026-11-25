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
     * 금액 추출 정규식. 퍼센트·배율·건수·회 등은 매칭 안 함. 왼쪽부터 우선 매칭하므로 복합 표기가 먼저 잡힌다.
     *
     * <ol>
     *   <li>만원 단위 + 나머지: "5만 원", "8.4만원", "5만 8,970원", "26만 9천 원", "1만 5천 3백 원", "5~6만 원대"(그룹 1: 범위 시작)
     *   <li>천 단위: "8천 원", "7천 8백 원"
     *   <li>숫자: "5,000원"
     * </ol>
     *
     * <p>#111 exp4: "5만 8,970원"을 "8,970원"으로 읽어 오탐 4건, "26만 9천 원"은 아예 미검사였다.
     */
    private static final Pattern MONEY_PATTERN = Pattern.compile("(?:(\\d+)\\s*~\\s*)?(\\d[\\d,]*(?:\\.\\d+)?)만"
            + "(?:\\s*(\\d)천)?(?:\\s*(\\d)백)?(?:\\s*(\\d[\\d,]*))?\\s*원"
            + "|(\\d)천(?:\\s*(\\d)백)?(?:\\s*(\\d+))?\\s*원"
            + "|(\\d[\\d,]*)\\s*원");

    /** 숫자 후검증 허용 오차. LLM이 자연스럽게 반올림하는 것을 허용. */
    private static final double NUMBER_TOLERANCE = 0.05;

    /**
     * 만원 단위 표현 허용 오차 (#111).
     *
     * <p>"117,266원 → 11만 원 넘게"처럼 만원 단위로 내림·올림한 표현은 5% 오차를 넘어도 사실과 맞는다. 실측(exp0, 거절 52건)에서
     * 이런 표현이 오탐의 대부분이었고 실제 차이는 5.2~6.9%였다. 만원 단위 내림·올림이면서 차이 10% 이내일 때 허용한다.
     * 10% 상한은 작은 금액의 과도한 근사(14,400원 → "2만 원", 39%)와 무관한 항목 숫자에 우연히 맞는 경우(59,000원 → "5만 원", 15%)를 막는다.
     */
    private static final double MAN_WON_BRACKET_TOLERANCE = 0.10;

    private static final long MAN_WON = 10_000;

    private static final long CHEON_WON = 1_000;

    private final ObjectProvider<LlmClient> llm;

    public FeedbackRenderer(ObjectProvider<LlmClient> llm) {
        this.llm = llm;
    }

    private static final String BASE_SYSTEM =
            """
        너는 한국 20대를 위한 가계부 코치다. 가까운 선배가 카톡으로 한마디 해주듯 부드러운 존댓말로, 쉽고 담백하게 쓴다.

        [말투와 구조 — 가장 먼저 지킨다]
        - 문장은 최대 3개, 줄바꿈 없이 한 문단으로 쓴다. 이번 주 핵심 한 줄 → 가장 큰 이유 한 가지 → 지금 해볼 행동 한 가지 순서. 분석 재료가 많아도 3문장을 넘기지 않는다.
        - 첫 문장은 반드시 이번 주 총지출과 직전 4주 평균 대비로 시작한다 (예: "이번 주는 18만 5천 원을 써서 최근 4주 평균보다 13만 원 적게 썼어요."). 직전 4주 평균이 없으면 이번 주 총지출만 쓴다. 지난주와의 비교는 필요할 때만 덧붙인다.
        - 모든 문장은 '~요/~어요'로 끝나는 존댓말이다. 친근해도 반말('~했어', '~하자')은 쓰지 않는다.
        - 숫자(금액·비율·배수·건수)는 이번 주 총지출 외에 꼭 필요한 2개만 더 쓴다. 같은 금액을 반복하지 않고, 해당되는 카테고리를 전부 나열하지 않고 가장 중요한 한두 가지만 고른다.
        - 분석 보고서처럼 쓰지 않는다. '지출이 발생했어요', '비중을 차지했어요' 대신 '썼어요', '컸어요'처럼 말하듯 쓴다.
        - 이모지는 0~1개.
        - 예시 (문장 구조만 참고하고 숫자는 베끼지 않는다)
          나쁜 예: "이번 주는 평소보다 쓰는 속도가 빨랐어요. 기타에서 큰 결제 한 건이 가장 컸고, 이번 주 37만 원을 쓰면서 이번 달 예산의 30%를 썼어요. 기타에서 268,040원, 식료품에서 6,663원을 줄이면 83,335원으로 낮아져요." (이번 주 총지출과 평소 대비가 첫 문장에 없고, 이번 달 누적 비율을 이번 주에 붙였고, 숫자가 많다)
          좋은 예: "이번 주는 18만 5천 원을 써서 최근 4주 평균보다 13만 원 적게 썼지만, 이번 달 전체로는 보통 이맘때보다 빠르게 쓰고 있어요. 기타에서 큰 결제 한 건이 가장 컸고요. 남은 20일 동안 기타는 21만 원까지만 쓰면 월말 초과를 줄일 수 있어요."
          (예시의 속도·카테고리·금액을 그대로 옮기지 않는다. 이번 달 속도가 빠른지 느린지는 [예산 사용 비율]에 적힌 그대로 쓴다.)

        [숫자·사실 규칙 — 아래 규칙은 위 말투를 지키면서도 어기지 않는다]
        - 1순위 메시지는 '예산 진척률과 저축 목표 영향'이다. 항상 이걸 중심에 둔다.
        - 이번 주에 일어난 이벤트를 중심으로 피드백한다. 월말 예측은 맥락으로만 사용한다.
        - 숫자는 주어진 값만 쓴다. 과장/추측 금지. 없는 사실을 만들지 않는다.
        - 금액은 제공된 숫자를 그대로 사용할 것. 임의로 반올림하거나 바꾸지 않는다.
        - 절약 제안은 '어느 항목에서 얼마를 줄이면 저축 목표에 어떤 효과'인지 구체적으로 연결한다.
        - 절약 금액·절약 효과(원)는 [절약 액션]에 있는 카테고리와 금액만 쓴다. [절약 액션 대상 아님] 카테고리나 [절약 액션]이 없는 주에는 'N회 줄이면 N만 원', '월 N만 원 아낄 수' 같은 금액을 만들지 않는다. 줄이기를 권하더라도 금액을 붙이지 않는다.
        - 절약 금액은 [절약 액션]에 적힌 그 카테고리에만 붙인다. '다른 항목에서 N원 줄이기'처럼 카테고리를 바꾸지 않는다.
        - 카테고리의 '월말 예상 초과'와 '전체 월말 예상 초과'(저축 목표에서 멀어지는 금액)는 다른 값이다. 카테고리 초과에서 절약 금액을 빼면 전체 초과가 되는 것처럼 한 문장에 잇지 않는다.
        - 지금까지 쓴 비율과 월말 예상 비율('이대로면 월말엔 예산의 N%')을 구분한다. 예산 사용 비율은 쓸수록 늘기만 하므로 '내려왔다', '줄었다'고 쓰지 않는다.
        - 전체 월말 예상 초과가 있으면 '예산 안에서 잘 쓰고 있다'처럼 예산 안인 것으로 쓰지 않는다.
        - 여러 카테고리에 '각각 N원'을 붙이지 않는다. 금액이 다르면 '식료품 29,675원, 오락문화 14,143원'처럼 따로 쓴다.
        - [예산 사용 비율]의 N%는 이번 달 누적 비율이다. 쓸 때는 '지금까지 이번 달 예산의 N%'처럼 '지금까지'를 붙이고, 이번 주 금액과 한 문장에 붙여 이번 주에 쓴 비율처럼 쓰지 않는다. 이번 주에 쓴 비율은 [예산·저축]의 '이번 주 지출만은 이번 달 예산의 W%'만 쓴다.
        - 이번 달 속도(보통 이맘때보다 빠른/느린)는 이번 달 전체 이야기다. '이번 주는'에 붙이지 않는다. 이번 주 증감과 이번 달 속도가 반대면 "이번 주는 줄었지만 이번 달 전체로는 빨라요"처럼 둘을 구분한다.
        - '평소'는 직전 4주 주당 평균이다. 지난주와 비교할 때는 '평소보다'가 아니라 '지난주보다'라고 쓴다.
        - 예산 사용 비율(%)은 '이번 달 예산의 30%를 썼어요'처럼 무엇의 비율인지 밝혀 쓴다. '페이싱', '보통 이맘때 20%인데 지금은 30%'처럼 대상 없이 쓰지 않는다.
        - [절약 액션]의 '모두 지키면' 효과(월말 초과 A → B)는 적힌 카테고리를 모두 지켰을 때의 결과다. 일부 카테고리만 말하면 'X만 지키면'·'X·Y만 지키면' 줄 중 말한 카테고리와 같은 줄의 숫자를 쓰고, '모두 지키면' 숫자를 붙이지 않는다. 액션이 셋 이상이면 'X·Y만 지키면' 줄(줄일 금액이 큰 두 카테고리)을 쓴다. 숫자 개수 제한 때문에 카테고리를 줄였다면 효과 숫자도 빼고 말한다.
        - '가장 컸다', '가장 큰 원인'은 분석 재료에서 금액이 실제로 가장 큰 거래·카테고리에만 쓴다. 건수가 많거나 배수가 큰 것을 '가장 컸다'고 쓰지 않는다.
        - 절약 효과는 [절약 액션]의 '모두 지키면' 줄대로 '월말 초과가 A원에서 B원으로 줄어요'처럼 줄어드는 폭으로 쓴다. 초과가 남는데 '예산 안에 들어온다', '목표를 지킬 수 있다'고 쓰지 않고, '다 메우기 어렵다', '받아들이자', '받아들이고'처럼 체념하는 말은 쓰지 않는다. 남은 기간이 짧아 줄어드는 폭이 작으면 그 폭만 짧게 말하고 다음 달에 해볼 행동으로 넘어간다. 줄어드는 폭이 작으면 '크게 낮출 수 있다'처럼 과장하지 말고 초과의 주된 원인과 다음 행동을 짚는다.
        - 초과 카테고리의 '월말 예상 초과'는 지금 속도로 가면 월말에 그 카테고리 예산을 넘을 것으로 예상되는 금액이다. 이미 쓴 금액이 아니므로 '누적됐다', '이미 넘었다'처럼 쓰지 않고 '이대로면 월말에 N원 넘을 것 같아요'처럼 예상으로 쓴다. '이번 주 지출'과 합산하지 않는다.
        - 초과 카테고리의 '이번 주 지출'이 작으면 그 카테고리는 이번 주 원인이 아니라 이전 주들의 지출 때문임을 인식하고 이번 주 피드백에서 주요 원인으로 다루지 않는다.
        - 원인 신뢰도가 '높음'이 아니면 원인을 단정하지 말고 가볍게만 언급하거나 생략한다.
        - 배수는 무엇의 배수인지 그대로 밝힌다. '한 건' 배수는 그 거래에만 붙이고, 카테고리 전체·이번 주 합계·횟수가 그 배수만큼 늘었다고 쓰지 않는다. '주간 금액' 배수는 금액이 늘었다고만 쓰고 횟수로 바꾸지 않는다.
        - 배수는 '6배쯤'처럼 반올림해서 쉽게 말해도 되지만, 제공된 값에서 벗어나게(6.5배를 '10배'로) 쓰지 않는다.
        - 건수는 표기된 대상(카테고리 또는 묶인 거래)에만 쓴다. 카테고리 건수를 토스페이·카카오페이 같은 특정 결제 수단이나 거래처의 건수로 바꿔 쓰지 않는다. 결제 수단·거래처 건수는 '거래처별' 표기의 숫자를 그대로 쓴다.
        - 분석 재료의 표기(대괄호 표시, '절약 액션', '한 건 배수', '페이싱' 같은 용어)는 사용자에게 보이는 문장에 쓰지 않는다. 일상적인 말로 풀어 쓴다.
        - 감정은 '만족한 지출', '필요한 지출', '후회한 지출'처럼 풀어 쓴다. '태그'라는 말이나 영문 이름(NECESSARY 등)은 쓰지 않는다.
        - 줄일 금액은 [절약 액션]의 상한을 써서 '남은 N일 동안 기타는 C원까지만 쓰기'처럼 말하면 알아듣기 쉽다. 줄일 금액만 말할 때는 남은 기간 예상 지출에서 줄인다는 뜻이 드러나게 쓴다.
        - 마크다운 제목(#)이나 목록 기호 없이 문장만 쓴다.
        - '한 건' 배수는 "평소 식대 한 건 금액의 9.2배"처럼 한 건 기준임을 밝혀 쓴다. 주간 피드백에서 "평소의 N배", "평소보다 N배"는 일주일 기준으로 읽히므로 한 건 배수에 쓰지 않는다.
        - 횟수(N회·N건)는 표기된 건수만 쓴다. 메모·주목 거래 목록은 일부만 보여주므로 목록 줄 수를 세어 횟수로 쓰지 않는다.
        - 이상 신호 거래의 감정 태그에 따라 피드백 톤을 달리한다:
          • 후회 / 충동 → 명확하게 절제를 권고한다. "이런 소비는 줄여가는 게 좋겠어요" 수준으로 직접적으로 말한다.
          • 필요 → 지출이 불가피했음을 인정하되, 해당 카테고리 예산을 조정하거나 다른 항목을 줄이는 방향을 제안한다.
          • 스트레스 해소 → 감정에 공감하되, 예산 영향을 짚고 다른 스트레스 해소 방법을 가볍게 제안한다.
          • 만족 → 사실을 담담하게 전달하고 과도한 칭찬이나 권고 없이 마무리한다.
        - 예산에 여유가 있어도 '더 써도 된다'는 식의 조언은 하지 않는다. 저축 목표 달성을 기준으로 판단한다.
        - POSITIVE 주차(초과 카테고리 없음)에도 MEDIUM 이상의 이상 신호가 있으면 마지막 문장에서 1문장만 가볍게 언급한다.
        - '쓰셔서', '~하시며' 같은 높임 어미나 '~합니다' 보고서체는 쓰지 않는다.
        """;

    private static final String MONTH_END_SYSTEM =
            """
        너는 한국 20대를 위한 가계부 코치다. 가까운 선배가 카톡으로 한마디 해주듯 부드러운 존댓말로, 쉽고 담백하게 이번 달 결산을 써준다.

        [말투와 구조 — 가장 먼저 지킨다]
        - 문장은 최대 4개, 줄바꿈 없이 한 문단으로 쓴다. 모든 문장은 '~요/~어요'로 끝나는 존댓말이고, 반말('~했어', '~하자')은 쓰지 않는다.
        - 이번 달 결과 한 줄 → 잘한 점 한 가지(있을 때만) → 다음 달에 해볼 행동 한 가지 순서.
        - 숫자는 2개 이내(예: 총지출과 예산 대비 초과·절약액). 카테고리를 전부 나열하지 않고 가장 눈에 띄는 한두 가지만 고른다.
        - '더 잘할 수 있을 거예요', '작은 변화가 큰 차이를 만들 거예요' 같은 상투적인 격려는 쓰지 않는다. 구체적인 행동으로 마무리한다.
        - '~했네요'를 남발하지 말고 담백하게. 이모지는 0~1개.
        - '쓰셔서' 같은 높임 어미나 '~합니다' 보고서체는 쓰지 않는다.

        [숫자·사실 규칙]
        - 이번 달 확정 지출과 가용예산을 비교해서 결산한다.
        - 다음 달 개선할 것을 1가지만 구체적으로 제안한다. 제안에는 금액(원)이나 절약 효과를 붙이지 않는다 — 결산 데이터에 없는 절약 금액을 만들지 않는다.
        - 숫자는 주어진 값만 쓴다. 없는 사실을 만들지 않는다.
        - 금액은 제공된 숫자를 그대로 사용할 것. 임의로 반올림하거나 바꾸지 않는다.
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
        String lastReason = null;
        String onlyMissingTotal = null;
        for (int attempt = 1; attempt <= MAX_LLM_ATTEMPTS; attempt++) {
            String candidate = client.complete(system, lastReason == null ? user : user + retryHint(lastReason));
            if (candidate == null || candidate.isBlank()) {
                break;
            }
            // 절약 행동·효과 문장은 코드가 붙이므로 LLM 응답은 앞 두 문장만 쓴다 (#146)
            if (codeActionMode(c)) candidate = leadSentences(candidate);
            String reason = firstFailure(candidate, c);
            if (reason == null) {
                return withActionSentence(c, candidate);
            }
            // 첫 문장 총지출만 빠진 응답은 기억해 두었다가, 끝까지 고쳐지지 않으면 코드로 만든 첫 문장을 앞에 붙여 쓴다 (#144)
            if (reason.equals("첫 문장 총지출") && firstFailure(candidate, c, false) == null) onlyMissingTotal = candidate;
            lastReason = reason;
            if (attempt < MAX_LLM_ATTEMPTS) {
                log.warn("LLM {} 불일치 — 재시도 {}/{}", reason, attempt, MAX_LLM_ATTEMPTS - 1);
            } else {
                log.warn("LLM {} 불일치 — 템플릿 폴백 사용 ({}회 시도)", reason, MAX_LLM_ATTEMPTS);
            }
        }
        if (onlyMissingTotal != null) {
            log.warn("LLM 첫 문장 총지출 누락 — 코드로 만든 첫 문장으로 보정");
            return withActionSentence(c, withWeekTotalFirst(c, onlyMissingTotal));
        }
        return template(c);
    }

    /**
     * 절약 액션이 있고 월말 초과가 예상되면, 세 번째 문장(남은 기간 상한과 효과)은 LLM이 아니라 코드가 만든다 (#146).
     * LLM이 일부 카테고리만 말하면서 다른 조합의 효과 금액을 붙이거나 '쓰기'를 '쓰지 않기'로 바꾸는 오류를 구조적으로 없앤다.
     */
    static boolean codeActionMode(FeedbackContext c) {
        return !c.actions().isEmpty() && c.savingsImpactWon() < 0;
    }

    /** LLM 응답의 앞 두 문장. */
    static String leadSentences(String text) {
        List<String> parts = sentences(text);
        return String.join(" ", parts.subList(0, Math.min(2, parts.size())));
    }

    private static String withActionSentence(FeedbackContext c, String lead) {
        return codeActionMode(c) ? lead + " " + actionSentence(c) : lead;
    }

    /** 코드로 만든 절약 행동 문장 — 카테고리별 남은 기간 상한과, 말한 카테고리를 지켰을 때의 월말 초과. */
    static String actionSentence(FeedbackContext c) {
        List<ActionSummary> named = c.actions().stream()
                .sorted(Comparator.comparingLong(ActionSummary::reductionWon).reversed())
                .limit(3)
                .toList();
        long gap = Math.abs(c.savingsImpactWon());
        long left = gap - named.stream().mapToLong(ActionSummary::reductionWon).sum();
        List<String> clauses = new ArrayList<>();
        for (ActionSummary a : named) {
            if (a.remainingExpectedWon() > 0) {
                long cap = Math.max(0, a.remainingExpectedWon() - a.reductionWon());
                clauses.add(topic(a.categoryName()) + " " + koWon(cap, false) + "까지만");
            } else {
                clauses.add(topic(a.categoryName()) + " " + koWon(a.reductionWon(), false) + " 덜");
            }
        }
        String days = c.remainingDaysInMonth() > 0 ? "남은 " + c.remainingDaysInMonth() + "일 동안 " : "남은 기간 ";
        String effect =
                left > 0 ? "월말 초과가 " + koWon(gap, true) + "에서 " + koWon(left, true) + "으로 줄어요." : "이번 달 예산 안에 들어와요.";
        return days + String.join(", ", clauses) + " 쓰면 " + effect;
    }

    /** "21만 5천 원" 표기. 상한은 내림(초과하지 않게), 효과는 반올림. 천 원 미만은 그대로 쓴다. */
    static String koWon(long v, boolean round) {
        if (v < 1_000) return v + "원";
        long unit = round ? Math.round(v / 1_000.0) * 1_000 : v / 1_000 * 1_000;
        long man = unit / 10_000;
        long cheon = unit % 10_000 / 1_000;
        List<String> parts = new ArrayList<>();
        if (man > 0) parts.add(man + "만");
        if (cheon > 0) parts.add(cheon + "천");
        return String.join(" ", parts) + " 원";
    }

    /** 받침에 따라 '은/는'을 붙인 주제어. */
    private static String topic(String word) {
        char last = word.charAt(word.length() - 1);
        boolean batchim = last >= 0xAC00 && last <= 0xD7A3 && (last - 0xAC00) % 28 != 0;
        return word + (batchim ? "은" : "는");
    }

    /**
     * 재시도 때 붙이는 거절 이유. 이유를 알려주지 않으면 같은 오류를 3번 반복해 템플릿 폴백된다 (#122 실험 9: 21주차 "104%까지 내려왔어요" ×3,
     * 28주차 "이번 주는 예산의 69%" ×3).
     */
    static String retryHint(String reason) {
        String fix =
                switch (reason) {
                    case "숫자" -> "분석 재료에 있는 금액만 쓰고, 금액을 바꾸거나 새로 계산하지 말 것.";
                    case "거래처 건수" -> "결제 수단·거래처 건수는 '거래처별'에 적힌 숫자만 쓸 것. 카테고리 전체 건수를 거래처 건수로 쓰지 말 것.";
                    case "체념 표현" -> "'받아들이다', '메우기 어렵다' 같은 말을 쓰지 말고, 줄였을 때 줄어드는 폭과 다음 행동을 말할 것.";
                    case "내부 용어·기간 표기" ->
                        "'비율이 내려왔다·줄었다', '절약 액션', '페이싱', '태그', 영문 감정 이름을 쓰지 말고, 감정은 '만족한 지출'처럼 풀어 쓸 것.";
                    case "사실과 반대 표현" -> "전체 월말 예상 초과가 있으므로 '예산 안에서 잘 쓰고 있다'고 쓰지 말고, 'N원 넘게'는 실제 금액이 N원 이상일 때만 쓸 것.";
                    case "'각각' 금액" -> "여러 카테고리에 '각각 N원'을 붙이지 말고 카테고리마다 금액을 따로 쓸 것.";
                    case "절약 금액" -> "줄이라고 권하는 금액은 [절약 액션]에 적힌 그 카테고리의 금액만 쓸 것. 절약 후 남는 초과액을 줄일 금액으로 쓰지 말 것.";
                    case "첫 문장 총지출" -> "첫 문장을 이번 주 총지출([예산·저축]의 '이번 주 총 변동지출')과 직전 4주 평균 대비로 시작할 것.";
                    case "비율 대상" -> "이번 달 누적 비율은 '지금까지 이번 달 예산의 N%'로 쓰고, 이번 주에 쓴 비율은 '이번 주 지출만은 이번 달 예산의 W%'의 W만 쓸 것.";
                    case "속도 방향" ->
                        "이번 달 속도를 '이번 주는'에 붙이지 말 것. 이번 주 증감(직전 4주 평균 대비)과 이번 달 전체 속도를 구분하고,"
                                + " 이번 달 속도는 [예산 사용 비율]에 적힌 빠름·느림 그대로 쓸 것.";
                    case "절약 효과 범위" ->
                        "'모두 지키면' 효과 금액은 [절약 액션]의 카테고리를 모두 말할 때만 쓸 것. 일부만 말하면 'X만 지키면' 숫자를 쓰거나 효과 숫자를 빼고 쓸 것.";
                    case "가장 큰 항목" -> "'가장 컸다'는 [이번 주 가장 큰 한 건]에만 쓰고, 다른 카테고리나 건수·배수가 큰 것에 쓰지 말 것.";
                    case "평소 기준" -> "'평소'는 직전 4주 평균이다. 지난주와 비교한 금액에는 '지난주보다'라고 쓸 것.";
                    case "출력 형식" -> "마크다운 제목(#) 없이 문장만 쓰고, 단어 안에 영문·그리스 문자가 섞이지 않게 쓸 것.";
                    default -> "분석 재료와 맞지 않는 부분을 고칠 것.";
                };
        return "\n\n[직전 답변이 거절된 이유: " + reason + "]\n" + fix + " 나머지 규칙은 그대로 지켜서 다시 작성해줘.";
    }

    /** 검사를 차례로 돌려 처음 실패한 검사 이름을 반환한다. 모두 통과하면 null. */
    private String firstFailure(String candidate, FeedbackContext c) {
        return firstFailure(candidate, c, true);
    }

    /** @param checkFirstSentence false면 첫 문장 총지출 검사를 건너뛴다 (그 검사만 실패했는지 확인할 때) */
    private String firstFailure(String candidate, FeedbackContext c, boolean checkFirstSentence) {
        if (!verifyNumbers(candidate, c)) return "숫자";
        if (!verifyMerchantCounts(candidate, c)) return "거래처 건수";
        if (!verifyTone(candidate)) return "체념 표현";
        if (!verifyFormat(candidate)) return "출력 형식";
        if (!verifyWording(candidate)) return "내부 용어·기간 표기";
        if (!verifyRate(candidate, c)) return "비율 대상";
        if (!verifyPace(candidate, c) || !verifyMonthPace(candidate, c)) return "속도 방향";
        if (!verifyUsual(candidate, c)) return "평소 기준";
        if (!verifyFacts(candidate, c)) return "사실과 반대 표현";
        if (!verifyEach(candidate, c)) return "'각각' 금액";
        if (!verifySavingsAmounts(candidate, c)) return "절약 금액";
        if (!verifyEffectScope(candidate, c)) return "절약 효과 범위";
        if (!verifyLargest(candidate, c)) return "가장 큰 항목";
        if (checkFirstSentence && !verifyFirstSentence(candidate, c)) return "첫 문장 총지출";
        return null;
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
    // ── 거래처 건수 후검증 (#111) ──

    private static final Map<String, Integer> KOREAN_COUNT =
            Map.of("한", 1, "두", 2, "세", 3, "네", 4, "다섯", 5, "여섯", 6, "일곱", 7, "여덟", 8, "아홉", 9, "열", 10);

    private static final String COUNT_NUMBER = "(\\d+|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*(?:건|회|번)";

    /** "N건 중 M건"의 뒷부분. */
    private static final Pattern PART_OF_COUNT = Pattern.compile("\\s*중\\s*" + COUNT_NUMBER);

    /**
     * 체념 표현. 금지 규칙만으로는 7/60이 남음 (#122 실험 3: "이번 달은 있는 그대로 받아들이고 …"). "어렵"만 보면 "메우긴 어려워 보여요"를
     * 놓친다 (실험 4, 19주차).
     */
    private static final Pattern RESIGNED_TONE = Pattern.compile("받아들이|메우(?:기|긴|기는)\\s*어(?:려|렵)");

    /**
     * 사용자 판정으로 금지한 표기 (#122 실험 6). 규칙만으로는 남음.
     * <ul>
     *   <li>내부 용어 "절약 액션" (4/69: "절약 액션을 모두 지켜도")
     *   <li>이번 달 예산 사용 비율을 '이번 주'로 ("이번 주는 예산의 41%를 썼어요", 4/69)
     * </ul>
     */
    private static final Pattern BANNED_WORDING = Pattern.compile(
            "절약\\s*액션|페이싱|태그|NECESSARY|SATISFIED|IMPULSE|STRESS_RELIEF|REGRET", Pattern.CASE_INSENSITIVE);

    /**
     * '예산의 N%' 바로 앞에 이 말이 있으면 이번 달 누적 또는 월말 예상임이 분명하다 (실험 9 오탐: "월말엔 예산의 141%", "지금까지 예산의 57%").
     * '이번 달'만으로는 부족하다 — "이번 주는 이번 달 예산의 15%를 써서"는 15%가 이번 주 지출의 비율일 때만 맞다 (#140: 그 주의 15%는 누적).
     */
    private static final Pattern RATE_QUALIFIER = Pattern.compile("월말|지금까지|누적");

    private static final Pattern RATE_NUMBER = Pattern.compile("(?:예산(?:의|을|를)\\s*)(\\d+(?:\\.\\d+)?)\\s*%");

    private static final Pattern THIS_WEEK = Pattern.compile("이번\\s*주");

    private static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("(?<=[.!?])\\s+|\\n")) if (!s.isBlank()) out.add(s.strip());
        return out;
    }

    /** 이번 주 지출이 이번 달 가용예산의 몇 %인지 (반올림). 예산이 없으면 -1. */
    static long weekSharePct(FeedbackContext c) {
        if (c.availableBudgetWon() <= 0 || c.weekTotalWon() <= 0) return -1;
        return Math.round(100.0 * c.weekTotalWon() / c.availableBudgetWon());
    }

    /**
     * '이번 주'가 있는 문장의 '예산의 N%'는 '지금까지·누적·월말'이 앞에 붙었거나 N이 이번 주 지출 비율일 때만 통과 (#144).
     * "이번 주 37만 원을 써서 이번 달 예산의 30%를 썼어요"(30%는 누적)는 거절. #140에서 v3.9 사실 오류의 다수(27/69).
     */
    boolean verifyRate(String text, FeedbackContext c) {
        long share = weekSharePct(c);
        for (String sentence : sentences(text)) {
            if (!THIS_WEEK.matcher(sentence).find()) continue;
            Matcher m = RATE_NUMBER.matcher(sentence);
            while (m.find()) {
                double n;
                try {
                    n = Double.parseDouble(m.group(1));
                } catch (NumberFormatException e) {
                    return false;
                }
                String before = sentence.substring(Math.max(0, m.start() - 12), m.start());
                Matcher q = RATE_QUALIFIER.matcher(before);
                // 누적·월말임을 밝힌 비율도 그 값과 맞아야 한다 (밝히기만 하고 틀린 숫자를 쓰는 경우)
                long expected = !q.find() ? share : q.group().equals("월말") ? monthEndPct(c) : cumulativePct(c);
                if (expected < 0) {
                    if (q.reset().find()) continue; // 비교할 값이 없는 누적·월말 표기는 통과
                    return false;
                }
                if (Math.abs(n - expected) > 1) return false;
            }
        }
        return true;
    }

    /** 이번 달 누적 예산 사용 비율(%). 모르면 -1. */
    static long cumulativePct(FeedbackContext c) {
        return c.pacing() == null ? -1 : Math.round(c.pacing().currentRate() * 100);
    }

    /** 이대로면 월말 예산 사용 비율(%). 모르면 -1. */
    static long monthEndPct(FeedbackContext c) {
        return c.availableBudgetWon() <= 0 ? -1 : Math.round(100.0 * c.predictedMonthEndWon() / c.availableBudgetWon());
    }

    /** 직전 4주 평균 대비 이번 주 방향: 1 많음, -1 적음, 0 비슷하거나 기준 없음. */
    static int weekDirection(FeedbackContext c) {
        if (c.recent4WeekAvgWon() <= 0 || c.weekTotalWon() <= 0) return 0;
        double r = (double) c.weekTotalWon() / c.recent4WeekAvgWon();
        return r > 1.05 ? 1 : r < 0.95 ? -1 : 0;
    }

    /** 이번 달 예산 사용 속도: 1 보통 이맘때보다 빠름, -1 느림, 0 비슷하거나 기준 없음. {@link #speed}와 같은 기준. */
    static int monthDirection(FeedbackContext c) {
        if (c.pacing() == null || c.pacing().historicalAvgRate() <= 0) return 0;
        double r = c.pacing().currentRate() / c.pacing().historicalAvgRate();
        return r > 1.05 ? 1 : r < 0.95 ? -1 : 0;
    }

    private static final Pattern MONTH_SCOPE = Pattern.compile("이번\\s*달|전체|지금까지|누적|월말");
    private static final Pattern FAST_WORD = Pattern.compile("빠르|빠른|빨라|빨랐|빨리");
    private static final Pattern SLOW_WORD = Pattern.compile("느리|느린|느려|느렸|늦춘|늦췄|늦추");
    private static final Pattern SAME_AS_USUAL = Pattern.compile("평소처럼|평소와\\s*비슷");

    /**
     * 이번 달 범위를 밝히지 않은 '이번 주' 문장의 속도 표현은 이번 주 증감(직전 4주 평균 대비)과 맞아야 한다 (#144).
     * "이번 주는 평소보다 쓰는 속도가 빠른 편"(이번 주는 크게 줄었고 이번 달 누적이 빠름) 같은 B② 유형을 막는다.
     */
    boolean verifyPace(String text, FeedbackContext c) {
        int week = weekDirection(c);
        if (week == 0) return true;
        for (String sentence : sentences(text)) {
            if (!THIS_WEEK.matcher(sentence).find()) continue;
            for (String clause : clauses(sentence)) {
                // '이번 달 예산의 N%'의 '이번 달'은 예산의 기간이지 속도의 기간이 아니다
                if (MONTH_SCOPE
                        .matcher(MONTH_BUDGET.matcher(clause).replaceAll(""))
                        .find()) continue;
                if (week < 0 && FAST_WORD.matcher(clause).find()) return false;
                if (week > 0 && SLOW_WORD.matcher(clause).find()) return false;
                if (SAME_AS_USUAL.matcher(clause).find()) return false;
            }
        }
        return true;
    }

    private static final Pattern MONTH_BUDGET = Pattern.compile("이번\\s*달\\s*예산");

    /** 쉼표·'지만'·'는데'·'고,' 로 나눈 구절. */
    private static List<String> clauses(String sentence) {
        List<String> out = new ArrayList<>();
        for (String s : sentence.split("[,]|지만|는데")) if (!s.isBlank()) out.add(s);
        return out;
    }

    private static final Pattern MONTH_PACE_SCOPE = Pattern.compile("이번\\s*달|보통\\s*이맘때|지금까지");
    private static final Pattern FAST_STATE = Pattern.compile("빠르게|빠른|빨라요|빨랐");
    private static final Pattern SLOW_STATE = Pattern.compile("느리게|느린|느려요|느렸");

    /**
     * 이번 달 속도를 말한 구절("이번 달 전체로는 보통 이맘때보다 빠르게")은 실제 이번 달 속도와 같아야 한다 (#144 재측정: 좋은 예의 "빠르게"를 느린 주에도
     * 옮겨 씀). 구절은 쉼표·'지만'·'는데'로 나눈다.
     */
    boolean verifyMonthPace(String text, FeedbackContext c) {
        if (c.pacing() == null || c.pacing().historicalAvgRate() <= 0) return true;
        int month = monthDirection(c);
        for (String clause : text.split("[,.!?]|지만|는데")) {
            if (!MONTH_PACE_SCOPE
                    .matcher(MONTH_BUDGET.matcher(clause).replaceAll(""))
                    .find()) continue;
            boolean fast = FAST_STATE.matcher(clause).find(),
                    slow = SLOW_STATE.matcher(clause).find();
            if (fast && month <= 0) return false;
            if (slow && month >= 0) return false;
        }
        return true;
    }

    /**
     * '모두 지키면' 효과 금액(전체 월말 초과 − 액션 합)을 쓰면서 액션 카테고리 일부를 빠뜨리면 거절 (#144 재측정: 숫자 개수 제한으로 3개 중 2개만 말하고
     * 3개를 다 지켰을 때 효과를 붙임 ×5).
     */
    boolean verifyEffectScope(String text, FeedbackContext c) {
        if (c.actions().isEmpty() || c.savingsImpactWon() >= 0) return true;
        long allLeft = Math.abs(c.savingsImpactWon()) - actionTotal(c);
        for (String sentence : sentences(text)) {
            boolean claimsWithin = WITHIN_AFTER_ACTIONS.matcher(sentence).find();
            boolean claimsAllEffect = allLeft > 0
                    && EFFECT_VERB.matcher(sentence).find()
                    && extractMoneyMentions(sentence).stream().anyMatch(m -> matches(m, allLeft));
            if (!claimsWithin && !claimsAllEffect) continue;
            // '예산 안에 들어온다'는 액션을 다 지켜도 초과가 남으면 틀리고, 남지 않아도 모든 액션을 말해야 맞다
            if (claimsWithin && allLeft > 0) return false;
            if (c.actions().size() >= 2 && !c.actions().stream().allMatch(a -> sentence.contains(a.categoryName())))
                return false;
        }
        return true;
    }

    private static final Pattern WITHIN_AFTER_ACTIONS = Pattern.compile("예산\\s*안(?:에|으로)\\s*들어");
    private static final Pattern EFFECT_VERB = Pattern.compile("낮|줄|떨어");

    private static final Pattern USUAL = Pattern.compile("평소보다|평소의|평소\\s*대비");

    /** '평소보다 N원'의 N이 전주 대비 차이이고 직전 4주 평균 대비 차이가 아니면 거절 — "평소" = 직전 4주 평균 (#144). */
    boolean verifyUsual(String text, FeedbackContext c) {
        if (c.prevWeekTotalWon() <= 0 || c.weekTotalWon() <= 0) return true;
        long prevDiff = Math.abs(c.weekTotalWon() - c.prevWeekTotalWon());
        long avgDiff = c.recent4WeekAvgWon() > 0 ? Math.abs(c.weekTotalWon() - c.recent4WeekAvgWon()) : -1;
        Matcher u = USUAL.matcher(text);
        while (u.find()) {
            // '평소보다'가 수식하는 금액은 바로 뒤 구절 안에 있다 ("평소보다 40,000원 적게")
            String after = text.substring(u.end(), Math.min(text.length(), u.end() + 20))
                    .split("[,.!?]")[0];
            for (MoneyMention m : extractMoneyMentions(after)) {
                if (matches(m, prevDiff) && (avgDiff < 0 || !matches(m, avgDiff))) return false;
            }
        }
        return true;
    }

    /** 첫 문장에 이번 주 총지출(반올림 허용)이 있어야 한다 (#144, #140 v4.1 누락 87%). 이번 주 지출이 없으면 검사하지 않는다. */
    boolean verifyFirstSentence(String text, FeedbackContext c) {
        if (c.weekTotalWon() <= 0) return true;
        List<String> all = sentences(text);
        if (all.isEmpty()) return false;
        String first = all.getFirst();
        List<MoneyMention> mentions = extractMoneyMentions(first);
        // 금액이 이번 주 총지출을 뜻하는지: 같은 문장에 '이번 주'가 있어야 한다 (월말 초과 등 다른 값과 우연히 같을 수 있음)
        if (!THIS_WEEK.matcher(first).find() || mentions.stream().noneMatch(m -> matches(m, c.weekTotalWon())))
            return false;
        if (c.recent4WeekAvgWon() <= 0) return true;
        // 직전 4주 평균이 있으면 평균 대비(차이 금액 또는 평균 금액, 비슷하면 '비슷')도 첫 문장에
        long diff = Math.abs(c.weekTotalWon() - c.recent4WeekAvgWon());
        boolean comparesAverage = first.contains("평균") || first.contains("평소");
        boolean hasDiff = mentions.stream().anyMatch(m -> matches(m, diff) || matches(m, c.recent4WeekAvgWon()));
        return comparesAverage && (hasDiff || (weekDirection(c) == 0 && first.contains("비슷")));
    }

    private static final Pattern LARGEST = Pattern.compile("가장\s*(?:컸|큰|크)");

    /**
     * '가장 컸다'가 이번 주 가장 큰 한 건의 카테고리가 아닌 카테고리에 붙으면 거절 (#146, #144: 건수가 많거나 배수가 큰 것에 씀).
     * 쉼표로 나뉜 구절 안에서 다른 카테고리 이름과 함께 쓰인 경우만 본다.
     */
    boolean verifyLargest(String text, FeedbackContext c) {
        Optional<TransactionHighlight> top = c.largestTransaction();
        if (top.isEmpty()) return true;
        Set<String> names = new HashSet<>();
        c.weekHighlights().forEach(h -> names.add(h.categoryName()));
        c.overspendCategories().forEach(o -> names.add(o.categoryName()));
        c.actions().forEach(a -> names.add(a.categoryName()));
        if (c.anomalyCategoryName() != null) names.add(c.anomalyCategoryName());
        names.remove(top.get().categoryName());
        for (String sentence : sentences(text)) {
            for (String clause : sentence.split(",")) {
                Matcher m = LARGEST.matcher(clause);
                if (!m.find()) continue;
                // '가장 컸다'가 붙은 대상은 그 앞에 가장 가까이 나온 카테고리 이름이다.
                // "기타보다 식대 한 건이 가장 컸어요"는 식대가 대상이므로 비교 대상(기타)이 앞에 나와도 거절하지 않는다.
                String before = clause.substring(0, m.start());
                String subject = null;
                int at = -1;
                for (String n : names) {
                    int i = before.lastIndexOf(n);
                    if (i > at) {
                        at = i;
                        subject = n;
                    }
                }
                String topName = top.get().categoryName();
                int topAt = before.lastIndexOf(topName);
                if (subject != null && at > topAt) return false;
            }
        }
        return true;
    }

    /** 숫자 검증과 같은 반올림 기준으로 금액 언급이 원본 값의 표현인지. */
    private boolean matches(MoneyMention m, long original) {
        return m.manWonRange()
                ? isInManWonRange(m.rangeFrom() > 0 ? m.rangeFrom() : m.value(), m.value(), original)
                : isAllowedExpression(m.value(), original, true);
    }

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)^\\s*#{1,6}\\s");
    /** 한글 단어 안에 섞인 라틴 문자("카테go리") 또는 그리스 문자 — 생성 중 깨진 출력 (#140 J153). */
    private static final Pattern GARBLED = Pattern.compile("[가-힣][A-Za-z]+[가-힣]|[\\u0370-\\u03FF]");

    boolean verifyFormat(String text) {
        return !MARKDOWN_HEADING.matcher(text).find() && !GARBLED.matcher(text).find();
    }

    /**
     * 첫 문장을 코드로 만든 문장으로 보정한다. 원 응답이 3문장 이상이면 첫 문장(이번 주 핵심)을 바꾸고, 더 짧으면 앞에 붙인다 — 3문장 제한 유지.
     */
    static String withWeekTotalFirst(FeedbackContext c, String candidate) {
        List<String> parts = sentences(candidate);
        List<String> rest = parts.size() >= (codeActionMode(c) ? 2 : 3) ? parts.subList(1, parts.size()) : parts;
        return weekTotalSentence(c) + " " + String.join(" ", rest);
    }

    /** 코드로 만든 첫 문장. 이번 주 총지출과 직전 4주 평균 대비 (LLM이 끝까지 빠뜨리거나 템플릿 폴백일 때). */
    static String weekTotalSentence(FeedbackContext c) {
        if (c.weekTotalWon() <= 0) return "";
        if (c.recent4WeekAvgWon() <= 0) return "이번 주는 " + exactWon(c.weekTotalWon()) + "을 썼어요.";
        long diff = c.weekTotalWon() - c.recent4WeekAvgWon();
        if (weekDirection(c) == 0) return "이번 주는 " + exactWon(c.weekTotalWon()) + "을 써서 최근 4주 평균과 비슷하게 썼어요.";
        return "이번 주는 " + exactWon(c.weekTotalWon()) + "을 써서 최근 4주 평균보다 " + exactWon(Math.abs(diff))
                + (diff > 0 ? " 많이 썼어요." : " 적게 썼어요.");
    }

    /** "예산의 104%까지 내려왔어요", "113%로 줄었어요" — 사용 비율은 줄지 않음 (#122 실험 8, 21주차). */
    private static final Pattern RATE_DOWN = Pattern.compile("\\d+%\\s*(?:까지|로|으로)?\\s*(?:내려왔|내려갔|내려온|줄었|줄어들었|낮아졌)");

    /** 문맥 없이 판정하는 표기 검사. 이번 주와 비율·속도·평소 기준은 {@link #verifyRate}·{@link #verifyPace}·{@link #verifyUsual}. */
    boolean verifyWording(String text) {
        return !BANNED_WORDING.matcher(text).find() && !RATE_DOWN.matcher(text).find();
    }

    /** 전체 초과가 예상되는데 예산 안인 것처럼 쓰는 표현 (#122 실험 8, 26주차 "예산 안에서 잘 쓰고 계세요"). */
    private static final Pattern WITHIN_BUDGET = Pattern.compile("예산\\s*안에서\\s*잘|예산\\s*안에서\\s*쓰고|예산을\\s*잘\\s*지키");

    /** "N원 넘게" 바로 뒤가 지출 동사면 지출액이다 ("30만 원 넘게 썼고", "넘게 쓰면서"). */
    private static final Pattern SPENT_AFTER = Pattern.compile("\\s*(?:을|를)?\\s*(?:썼|쓰|써|쓴|지출|나갔|나가|사용|결제)");

    /** 쉼표·문장부호로 나뉜 구절 중 [start, end)를 포함하는 구절. */
    private static String clauseAround(String sentence, int start, int end) {
        int from = Math.max(sentence.lastIndexOf(',', start), sentence.lastIndexOf('，', start)) + 1;
        int to = sentence.indexOf(',', end);
        return sentence.substring(from, to < 0 ? sentence.length() : to);
    }

    /** "N원 넘게/이상" 뒤에 붙는 말. */
    private static final Pattern AT_LEAST_SUFFIX = Pattern.compile("\\s*(?:넘게|이상|넘는|넘어|넘었)");

    /**
     * 사실과 반대되는 표현 검사.
     * <ul>
     *   <li>전체 월말 예상 초과가 있는데 '예산 안에서 잘 쓰고 계세요'
     *   <li>초과를 말하는 문장의 'N원 넘게' — 초과 금액 중 N 이상이면서 N에 가까운 값이 없으면 거절 (#122 실험 8, 24주차 "식대 12만 원 넘게
     *       초과", 실제 119,176원. 숫자 검사는 식대 이번 주 지출 124,200원으로 통과시킴)
     * </ul>
     */
    boolean verifyFacts(String text, FeedbackContext c) {
        if (c.savingsImpactWon() < 0 && WITHIN_BUDGET.matcher(text).find()) return false;
        Set<Long> overs = new HashSet<>();
        if (c.savingsImpactWon() < 0) overs.add(Math.abs(c.savingsImpactWon()));
        c.overspendCategories().forEach(ov -> overs.add(ov.overAmountWon()));
        if (overs.isEmpty()) return true;
        for (String sentence : text.split("(?<=[.!?])\\s+|\\n")) {
            if (!sentence.contains("초과")) continue;
            Matcher m = MONEY_PATTERN.matcher(sentence);
            while (m.find()) {
                Matcher suffix = AT_LEAST_SUFFIX.matcher(sentence).region(m.end(), sentence.length());
                if (!suffix.lookingAt()) continue;
                // 초과를 설명하는 금액만 — "이번 주 30만 원 넘게 썼고, 월말 초과는 10만 원"의 30만은 지출액 (리뷰 지적)
                if (SPENT_AFTER
                        .matcher(sentence)
                        .region(suffix.end(), sentence.length())
                        .lookingAt()) continue;
                if (!clauseAround(sentence, m.start(), suffix.end()).contains("초과")) continue;
                List<MoneyMention> one = extractMoneyMentions(m.group());
                if (one.isEmpty() || one.getFirst().manWonRange()) continue;
                long claimed = one.getFirst().value();
                boolean ok = overs.stream().anyMatch(v -> v >= claimed && isAllowedExpression(claimed, v));
                if (!ok) return false;
            }
        }
        return true;
    }

    /**
     * "식료품과 오락문화에서 각각 2~3만 원씩 초과"처럼 여러 카테고리에 '각각' 금액 하나를 붙이면, 카테고리마다 그 금액이 맞는지 본다 (#122 실험 6,
     * 18주차: 오락문화는 14,143원). 숫자 검사는 한 카테고리(식료품 29,675원)만 맞아도 통과시킨다.
     *
     * <p>카테고리 금액 후보: 초과를 말하는 문장이면 월말 예상 초과만, 아니면 월말 예상 초과·이번 주 지출·절약 액션 금액. '각각' 뒤 금액이 둘 이상이면("각각 5만 원, 2만 원") 따로 쓴 것이므로
     * 검사하지 않는다.
     */
    boolean verifyEach(String text, FeedbackContext c) {
        Map<String, Set<Long>> overByCategory = new HashMap<>();
        Map<String, Set<Long>> byCategory = new HashMap<>();
        for (CategoryOverspend ov : c.overspendCategories()) {
            overByCategory
                    .computeIfAbsent(ov.categoryName(), k -> new HashSet<>())
                    .add(ov.overAmountWon());
            Set<Long> v = byCategory.computeIfAbsent(ov.categoryName(), k -> new HashSet<>());
            v.add(ov.overAmountWon());
            v.add(ov.thisWeekWon());
        }
        for (ActionSummary a : c.actions()) {
            byCategory.computeIfAbsent(a.categoryName(), k -> new HashSet<>()).add(a.reductionWon());
        }
        for (String sentence : text.split("(?<=[.!?])\\s+|\\n")) {
            int i = sentence.indexOf("각각");
            if (i < 0) continue;
            // 초과를 말하는 문장이면 초과 금액하고만 비교 — 이번 주 지출(오락문화 18,000원 ≈ 2만)로 통과하지 않게
            Map<String, Set<Long>> candidates =
                    sentence.matches(".*(초과|넘).*") && !overByCategory.isEmpty() ? overByCategory : byCategory;
            String before = sentence.substring(0, i);
            List<String> names =
                    candidates.keySet().stream().filter(before::contains).toList();
            if (names.size() < 2) continue;
            List<MoneyMention> mentions = extractMoneyMentions(sentence.substring(i));
            if (mentions.size() != 1) continue;
            MoneyMention m = mentions.getFirst();
            for (String name : names) {
                if (candidates.get(name).stream().noneMatch(v -> eachMatches(m, v))) return false;
            }
        }
        return true;
    }

    private boolean eachMatches(MoneyMention m, long v) {
        if (m.manWonRange()) return isInManWonRange(m.rangeFrom() > 0 ? m.rangeFrom() : m.value(), m.value(), v);
        if (m.rangeFrom() > 0) {
            // "2~3만 원": 구간 안이거나 양 끝 중 하나로 근사 가능
            return (v >= m.rangeFrom() && v <= m.value())
                    || isAllowedExpression(m.rangeFrom(), v)
                    || isAllowedExpression(m.value(), v);
        }
        return isAllowedExpression(m.value(), v);
    }

    /** 금액 바로 뒤에 붙는 '줄이다·아끼다·절약' — 그 금액이 절약 제안 금액이다. */
    private static final Pattern SAVE_VERB_AFTER =
            Pattern.compile("\\s*(?:정도|가량|쯤|씩)?\\s*(?:을|를)?\\s*(?:만|더)?\\s*(?:줄이|줄여|줄인|줄일|아끼|아껴|아낀|아낄|절약)");

    /**
     * 절약 제안 금액 검사 (리뷰 지적). 숫자 검사는 "다 지키면 남는 초과액"도 허용값에 넣으므로, "기타에서 150,000원을 줄여보세요"(액션 50,000원,
     * 남는 초과 150,000원)가 통과한다. 금액 바로 뒤가 줄이다·아끼다·절약이면 같은 구절에 나온 카테고리의 reductionWon(여럿이면 합)과 대조한다.
     * 카테고리 이름이 없으면 액션 금액 중 하나 또는 전체 합과 대조한다.
     */
    boolean verifySavingsAmounts(String text, FeedbackContext c) {
        if (c.actions().isEmpty()) return true;
        Map<String, Long> reduction = new LinkedHashMap<>();
        c.actions().forEach(a -> reduction.merge(a.categoryName(), a.reductionWon(), Long::sum));
        long total = actionTotal(c);
        for (String sentence : text.split("(?<=[.!?])\\s+|\\n")) {
            Matcher m = MONEY_PATTERN.matcher(sentence);
            while (m.find()) {
                if (!SAVE_VERB_AFTER
                        .matcher(sentence)
                        .region(m.end(), sentence.length())
                        .lookingAt()) continue;
                List<MoneyMention> one = extractMoneyMentions(m.group());
                if (one.isEmpty()) continue;
                MoneyMention mention = one.getFirst();
                String clause = clauseAround(sentence, m.start(), m.end());
                String before = clause.substring(0, Math.max(0, clause.indexOf(m.group())));
                List<String> named =
                        reduction.keySet().stream().filter(before::contains).toList();
                Set<Long> expected = new HashSet<>();
                if (named.isEmpty()) {
                    expected.addAll(reduction.values());
                    expected.add(total);
                } else {
                    expected.add(named.stream().mapToLong(reduction::get).sum());
                }
                boolean ok = expected.stream()
                        .anyMatch(v -> mention.manWonRange()
                                ? isInManWonRange(
                                        mention.rangeFrom() > 0 ? mention.rangeFrom() : mention.value(),
                                        mention.value(),
                                        v)
                                : isAllowedExpression(mention.value(), v));
                if (!ok) return false;
            }
        }
        return true;
    }

    /** 사용자 판정으로 금지한 체념 표현("이번 달은 받아들이고", "다 메우긴 어려워요")이 없으면 true. */
    boolean verifyTone(String text) {
        return !RESIGNED_TONE.matcher(text).find();
    }

    /** 이름과 건수 사이 허용 구간: 숫자·문장부호·'등' 없이 최대 15자 ("결제가", "거래가 합쳐", "로", "방문"). */
    private static final String COUNT_GAP = "([^\\d.!?\\n,(]{0,15}?)";

    /**
     * "카카오페이·토스페이 결제가 7건", "롯데월드 3회" 같은 거래처 건수 주장을 초과 카테고리의 거래처별 건수와 대조한다.
     *
     * <p>이름이 여러 개면 그 거래처들의 건수 합과 비교한다. "등"이 붙은 주장("토스페이 출금 등 13건")은 카테고리 전체를 가리키므로 검사하지 않는다.
     * 데이터가 없는 이름(예: "택시")은 건너뛴다. 실험 8: 거래처별 건수를 제공해도 "상위 결제 수단 2개 + 카테고리 전체 건수"로 요약하는 오류가
     * 34·37주차 ×20에서 5/40 — 프롬프트로는 못 막아 코드로 검사한다.
     */
    boolean verifyMerchantCounts(String text, FeedbackContext c) {
        List<Map<String, Integer>> perCategory = c.overspendCategories().stream()
                .map(CategoryOverspend::merchantCounts)
                .filter(m -> m != null && !m.isEmpty())
                .toList();
        if (perCategory.isEmpty()) return true;

        Map<String, List<String>> aliases = new LinkedHashMap<>(); // 문장에 쓰일 수 있는 이름 → 거래처 키
        for (Map<String, Integer> m : perCategory) {
            for (String key : m.keySet()) {
                if (key.length() < 2) continue;
                aliases.computeIfAbsent(key, k -> new ArrayList<>()).add(key);
                String shortName = key.replaceFirst("(결제|충전|출금)$", "");
                if (!shortName.equals(key) && shortName.length() >= 2) {
                    aliases.computeIfAbsent(shortName, k -> new ArrayList<>()).add(key);
                }
            }
        }
        String names = aliases.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|"));
        Pattern claim = Pattern.compile(
                "((?:" + names + ")(?:\\s*(?:·|/|와|과|및|랑|이랑|,)\\s*(?:" + names + "))*)" + COUNT_GAP + COUNT_NUMBER);
        Pattern single = Pattern.compile(names);

        Matcher m = claim.matcher(text);
        while (m.find()) {
            String gap = m.group(2);
            // "등·포함·같은·처럼·비롯" — 예시를 든 뒤 카테고리 전체 건수를 말하는 표현 (exp9b 30주차 "역전할머니맥주·욘카츠 같은 외식이 9건")
            if (gap.matches(".*(등|포함|같은|처럼|비롯).*")) continue;
            // 나열 뒤에 모르는 이름이 이어지면("케이스포츠·노래방 7건") 일부 거래처만 셀 수 있으므로 검사하지 않는다 (exp10 18주차)
            if (gap.matches("^\\s*(·|/|와|과|및|랑|이랑|,).*")) continue;
            int countAfterName = parseCount(m.group(3));
            // "토스페이·카카오페이 출금이 12건 중 10건" — 앞 숫자는 카테고리 전체, 거래처 건수는 '중' 뒤 숫자 (#122 실험 4, 26주차 오탐)
            Matcher partOf = PART_OF_COUNT.matcher(text).region(m.end(), text.length());
            int claimed = partOf.lookingAt() ? parseCount(partOf.group(1)) : countAfterName;
            // "카카오페이 한 건이 평소의 5.2배"처럼 1건 표현은 여러 거래 중 하나를 가리키므로 총 건수 주장으로 보지 않는다 (exp9 오탐 3건)
            if (claimed == 1) continue;
            Set<String> keys = new LinkedHashSet<>();
            Matcher n = single.matcher(m.group(1));
            while (n.find()) {
                String name = n.group();
                keys.addAll(aliases.getOrDefault(name, List.of()));
                // 다른 거래처 이름의 앞부분이면 계열 전체("토스" → 토스페이 + 토스 이체) — exp10 36주차 "토스·네이버페이 거래가 6건"(4+1+1) 오탐
                for (Map<String, Integer> cat : perCategory) {
                    for (String key : cat.keySet()) if (key.startsWith(name)) keys.add(key);
                }
            }
            if (keys.isEmpty()) continue;
            // "카카오페이, 네이버페이 충전이 각각 2건씩" — 합이 아니라 이름마다 (#122 실험 9, 27주차 오탐)
            if (gap.contains("각각")) {
                boolean each = true;
                Matcher e = single.matcher(m.group(1));
                while (e.find()) {
                    String name = e.group();
                    Set<String> one = new LinkedHashSet<>(aliases.getOrDefault(name, List.of()));
                    for (Map<String, Integer> cat : perCategory) {
                        for (String key : cat.keySet()) if (key.startsWith(name)) one.add(key);
                    }
                    if (one.isEmpty()) continue;
                    each &= perCategory.stream()
                            .anyMatch(cat -> one.stream()
                                            .mapToInt(k -> cat.getOrDefault(k, 0))
                                            .sum()
                                    == claimed);
                }
                if (!each) return false;
                continue;
            }
            boolean consistent = perCategory.stream()
                    .anyMatch(cat ->
                            keys.stream().mapToInt(k -> cat.getOrDefault(k, 0)).sum() == claimed);
            int total = perCategory.stream()
                    .mapToInt(cat ->
                            keys.stream().mapToInt(k -> cat.getOrDefault(k, 0)).sum())
                    .sum();
            if (!consistent && total != claimed) return false;
        }
        return true;
    }

    private static int parseCount(String g) {
        Integer k = KOREAN_COUNT.get(g);
        return k != null ? k : Integer.parseInt(g);
    }

    boolean verifyNumbers(String text, FeedbackContext c) {
        return verifyNumbers(text, c, true);
    }

    /**
     * @param allowManWonBracket false면 만원 단위 표현 허용(#111)을 끈 이전 규칙으로 검증한다. 측정 도구가 "새 규칙 덕분에 통과한 응답"을
     *                           구분하는 용도이며, 운영 경로는 항상 true다.
     */
    boolean verifyNumbers(String text, FeedbackContext c, boolean allowManWonBracket) {
        return checkExtracted(text, collectAllowedValues(c), allowManWonBracket);
    }

    /**
     * 추출된 금액이 모두 허용값 중 하나로 설명되는지 확인하는 공통 헬퍼.
     *
     * @param allowed 프롬프트에 제공한 원본 금액 (반올림하지 않은 값)
     */
    private boolean checkExtracted(String text, Set<Long> allowed, boolean allowManWonBracket) {
        List<MoneyMention> mentions = extractMoneyMentions(text);
        if (mentions.isEmpty()) return true;
        // "N만 원대"는 구간 진술이므로 구간 규칙으로만 판정 (근사 규칙으로 통과시키면 45,900원 → "5만 원대"가 올림으로 통과)
        return mentions.stream().allMatch(m -> allowed.stream()
                .anyMatch(a -> allowManWonBracket && m.manWonRange()
                        ? isInManWonRange(m.rangeFrom() > 0 ? m.rangeFrom() : m.value(), m.value(), a)
                        : isAllowedExpression(m.value(), a, allowManWonBracket)));
    }

    /**
     * "N만 원대" 범위 표현 (#111): 원본이 [N만, N만 + 9,999] 구간이면 사실과 맞으므로 허용한다. "N만 원 정도"와 달리 상대 오차 상한(10%)을 두지
     * 않는다 — 45,900원 → "4만 원대"는 맞지만(12.9%) "4만 원 정도"는 거절. exp2에서 "6만 원대"(66,700원)·"4만 원대"(45,900원)가 오탐.
     */
    /** 원본이 [from, last + 구간 폭) 안인지. "6만 원대"는 from = last = 6만, "5~6만 원대"는 from = 5만, last = 6만. */
    private static boolean isInManWonRange(long from, long last, long original) {
        long abs = Math.abs(original);
        return abs >= MAN_WON && abs >= from && abs < last + manWonRangeWidth(last);
    }

    /**
     * "N만 원대"의 구간 폭. N의 끝자리 0 개수만큼 넓힌다: "6만 원대" = 6만~6.9만, "70만 원대" = 70만~79만, "150만 원대" = 150만~159만.
     */
    static long manWonRangeWidth(long rangeStart) {
        long n = rangeStart / MAN_WON;
        long width = MAN_WON;
        while (n > 0 && n % 10 == 0) {
            n /= 10;
            width *= 10;
        }
        return width;
    }

    private static String countSuffix(int count) {
        return count > 1 ? " × " + count + "건" : "";
    }

    /**
     * LLM이 쓴 금액 {@code extracted}가 원본 금액 {@code original}의 허용 가능한 표현인지 판정한다.
     * <ol>
     *   <li>원본과 5% 이내
     *   <li>만원 단위 반올림값과 5% 이내 ("84,000원" → "8만원")
     *   <li>만원 단위 내림·올림이면서 원본과 10% 이내 ("117,266원" → "11만 원 넘게" / "12만 원")
     * </ol>
     */
    boolean isAllowedExpression(long extracted, long original) {
        return isAllowedExpression(extracted, original, true);
    }

    private boolean isAllowedExpression(long extracted, long original, boolean allowManWonBracket) {
        if (isWithinTolerance(extracted, original)) return true;
        long abs = Math.abs(original);
        // 천원 단위 내림·올림 + 10% 이내 (#111 exp5 준비: 8,557원 → "8천 원 정도", 사용자 판정 기준 — 만원 단위 규칙과 동일)
        if (allowManWonBracket && abs >= CHEON_WON && isUnitBracket(extracted, abs, CHEON_WON)) return true;
        if (abs < MAN_WON) return false;
        if (isWithinTolerance(extracted, Math.round(abs / (double) MAN_WON) * MAN_WON)) return true;
        return allowManWonBracket && isUnitBracket(extracted, abs, MAN_WON);
    }

    /** {@code unit} 단위 내림값 또는 올림값이면서 원본과 {@link #MAN_WON_BRACKET_TOLERANCE} 이내인지. */
    private static boolean isUnitBracket(long extracted, long original, long unit) {
        long floor = (original / unit) * unit;
        long ceil = original % unit == 0 ? floor : floor + unit;
        if (extracted != floor && extracted != ceil) return false;
        return Math.abs(extracted - original) <= original * MAN_WON_BRACKET_TOLERANCE;
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
     * FeedbackContext에서 검증 대상 원본 금액을 수집한다.
     * 반올림·만원 단위 표현 허용은 {@link #isAllowedExpression}에서 원본 기준으로 판정한다.
     * (반올림값을 집합에 섞으면 그 값을 기준으로 다시 만원 단위를 허용해 범위가 넓어지므로 원본만 담는다.)
     */
    private Set<Long> collectAllowedValues(FeedbackContext c) {
        Set<Long> values = new HashSet<>();
        values.add(c.predictedMonthEndWon());
        values.add(c.availableBudgetWon());
        values.add(Math.abs(c.savingsImpactWon()));
        if (!c.actions().isEmpty() && c.savingsImpactWon() < 0) {
            // 합계 자체는 허용하지 않는다 — "외식을 줄이면 10만 원 이상 절약"처럼 카테고리를 바꾼 금액이 통과함 (exp0 회귀 테스트)
            values.add(Math.abs(Math.abs(c.savingsImpactWon()) - actionTotal(c)));
            if (c.actions().size() >= 2) {
                c.actions().forEach(a -> values.add(Math.abs(Math.abs(c.savingsImpactWon()) - a.reductionWon())));
            }
        }

        values.add(c.weekTotalWon());
        values.add(c.prevWeekTotalWon());
        if (c.weekTotalWon() > 0 && c.prevWeekTotalWon() > 0) {
            values.add(Math.abs(c.weekTotalWon() - c.prevWeekTotalWon()));
        }
        if (c.recent4WeekAvgWon() > 0) {
            values.add(c.recent4WeekAvgWon());
            if (c.weekTotalWon() > 0) values.add(Math.abs(c.weekTotalWon() - c.recent4WeekAvgWon()));
        }
        values.add(c.anomalyWeeklyAmountWon());
        addBasis(values, c.anomalyBasis());
        c.additionalHighAnomalies().forEach(a -> {
            values.add(a.weeklyAmountWon());
            addBasis(values, a.basis());
        });
        if (c.weekEmotion() != null) {
            c.weekEmotion().amounts().values().forEach(v -> values.add(v));
            values.add(c.weekEmotion().untaggedWon());
        }
        for (CategoryOverspend ov : c.overspendCategories()) {
            values.add(ov.overAmountWon());
            values.add(ov.thisWeekWon());
            if (ov.causeSignals() != null) {
                for (MemoEvidence memo : ov.causeSignals().memos()) {
                    values.add(memo.amount());
                    if (memo.count() > 1) values.add(memo.amount() * memo.count());
                }
            }
        }
        for (ActionSummary a : c.actions()) {
            values.add(a.reductionWon());
            if (a.remainingExpectedWon() > 0) {
                values.add(a.remainingExpectedWon());
                values.add(Math.max(0, a.remainingExpectedWon() - a.reductionWon()));
            }
        }
        for (TransactionHighlight h : c.weekHighlights()) {
            values.add(h.amountWon());
            if (h.count() > 1) values.add(h.amountWon() * h.count());
        }
        return values;
    }

    private static void addBasis(Set<Long> values, AnomalyBasis basis) {
        if (basis == null) return;
        values.add(basis.subjectWon());
        values.add(basis.baselineWon());
    }

    /**
     * 텍스트에서 금액(만원/원)을 추출한다.
     * "12만원" → 120,000 / "8.4만원" → 84,000 / "5,000원" → 5,000.
     * 정규식이 "원" 접미사 있는 것만 매칭 → 퍼센트·배율·건수 무시.
     */
    List<Long> extractMoneyValues(String text) {
        return extractMoneyMentions(text).stream().map(MoneyMention::value).toList();
    }

    /**
     * 텍스트 속 금액 언급.
     *
     * @param manWonRange "N만 원대"처럼 만원 단위 정수 뒤에 '대'가 붙은 범위 표현인지 ("원 대비"는 제외)
     * @param rangeFrom   "5~6만 원대"의 시작(5만). 범위 시작이 없으면 0
     */
    record MoneyMention(long value, boolean manWonRange, long rangeFrom) {

        MoneyMention(long value, boolean manWonRange) {
            this(value, manWonRange, 0);
        }
    }

    List<MoneyMention> extractMoneyMentions(String text) {
        List<MoneyMention> mentions = new ArrayList<>();
        Matcher m = MONEY_PATTERN.matcher(text);
        while (m.find()) {
            try {
                if (m.group(2) != null) {
                    double manWon = Double.parseDouble(m.group(2).replace(",", ""));
                    long rest = digit(m.group(3)) * 1_000 + digit(m.group(4)) * 100 + number(m.group(5));
                    long value = Math.round(manWon * 10_000) + rest;
                    // 범위("N만 원대")는 만원 단위 정수만 — "5만 8천 원대" 같은 표현은 범위로 보지 않는다
                    boolean range = rest == 0 && manWon == Math.floor(manWon) && isRangeSuffix(text, m.end());
                    long from = m.group(1) != null && rest == 0 ? Long.parseLong(m.group(1)) * 10_000 : 0;
                    mentions.add(new MoneyMention(value, range, from));
                } else if (m.group(6) != null) {
                    long value = digit(m.group(6)) * 1_000 + digit(m.group(7)) * 100 + number(m.group(8));
                    mentions.add(new MoneyMention(value, false));
                } else if (m.group(9) != null) {
                    mentions.add(new MoneyMention(number(m.group(9)), false));
                }
            } catch (NumberFormatException ignore) {
                // 파싱 실패 → 무시
            }
        }
        return mentions;
    }

    private static long digit(String g) {
        return g == null ? 0 : Long.parseLong(g);
    }

    private static long number(String g) {
        return g == null ? 0 : Long.parseLong(g.replace(",", ""));
    }

    /** 금액 바로 뒤가 '대'(범위)인지. "원대비"·"원 대비"는 비교 표현이므로 제외. */
    private static boolean isRangeSuffix(String text, int end) {
        if (end >= text.length() || text.charAt(end) != '대') return false;
        return end + 1 >= text.length() || text.charAt(end + 1) != '비';
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
        if (codeActionMode(c)) {
            sb.append("\n\n[이번 응답의 문장 수 — 위 규칙보다 우선]")
                    .append("\n- 정확히 2문장만 쓴다. ① 이번 주 총지출과 직전 4주 평균 대비 ② 가장 큰 이유 한 가지.")
                    .append(
                            "\n- 지금 해볼 행동(세 번째 문장)은 시스템이 뒤에 붙인다. 절약 행동, 줄이라는 권유, 절약 금액, '월말 초과가 A에서 B로' 같은 효과를 쓰지 않는다.");
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
                sb.append(" (지난주 ")
                        .append(exactWon(c.prevWeekTotalWon()))
                        .append(" 대비 ")
                        .append(diff >= 0 ? "+" : "")
                        .append(exactWon(diff))
                        .append(")");
            }
            sb.append("\n");
            if (weekSharePct(c) >= 0) {
                sb.append("- 이번 주 지출만은 이번 달 예산의 ").append(weekSharePct(c)).append("%\n");
            }
            if (c.recent4WeekAvgWon() > 0) {
                long diff = c.weekTotalWon() - c.recent4WeekAvgWon();
                sb.append("- 직전 4주 주당 평균(= 평소): ")
                        .append(exactWon(c.recent4WeekAvgWon()))
                        .append(" → 이번 주는 평소보다 ")
                        .append(exactWon(Math.abs(diff)))
                        .append(diff >= 0 ? " 많음" : " 적음")
                        .append("\n");
            }
        }
        if (c.complianceRate() > 0.0) {
            sb.append("- 최근 12주 예산 준수율: ")
                    .append(String.format("%.0f%%", c.complianceRate() * 100))
                    .append("\n");
        }
        if (c.savingsImpactWon() < 0) {
            sb.append("- 이대로면 저축 목표에서 ")
                    .append(exactWon(Math.abs(c.savingsImpactWon())))
                    .append(" 멀어짐 (= 전체 월말 예상 초과. 카테고리별 초과의 합과 다름 — 예산보다 덜 쓰는 카테고리가 상쇄)\n");
        } else {
            sb.append("- 현재 페이스는 예산 안. 저축 목표 달성 가능\n");
        }

        // ── 페이싱 [2] ──
        if (c.pacing() != null) {
            sb.append("\n[예산 사용 비율 — 이번 달 예산 중 지금까지 쓴 누적 비율. 이번 주에 쓴 비율이 아님]\n");
            PacingResult p = c.pacing();
            switch (p.displayType()) {
                case SINGLE ->
                    sb.append("- 지금까지 이번 달 예산의 ")
                            .append(pct(p.currentRate()))
                            .append("를 씀 (지난달 이맘때는 ")
                            .append(pct(p.historicalAvgRate()))
                            .append(" — ")
                            .append(speed(p.currentRate(), p.historicalAvgRate()))
                            .append(")\n");
                case RANGE ->
                    sb.append("- 지금까지 이번 달 예산의 ")
                            .append(pct(p.currentRate()))
                            .append("를 씀 (보통 이맘때는 ")
                            .append(pct(p.referenceMin()))
                            .append("~")
                            .append(pct(p.referenceMax()))
                            .append(" — ")
                            .append(speed(p.currentRate(), p.historicalAvgRate()))
                            .append(")\n");
                case MEDIAN ->
                    sb.append("- 지금까지 이번 달 예산의 ")
                            .append(pct(p.currentRate()))
                            .append("를 씀 (보통 이맘때는 ")
                            .append(pct(p.historicalAvgRate()))
                            .append(" — ")
                            .append(speed(p.currentRate(), p.historicalAvgRate()))
                            .append(")\n");
            }
        }

        if (weekDirection(c) * monthDirection(c) < 0) {
            sb.append("- 주의: 이번 주는 평소(직전 4주 평균)보다 ")
                    .append(weekDirection(c) < 0 ? "적게" : "많이")
                    .append(" 썼지만 이번 달 전체로는 보통 이맘때보다 ")
                    .append(monthDirection(c) > 0 ? "빠른" : "느린")
                    .append(" 속도 — 둘을 구분해서 말할 것\n");
        }

        if (c.pacing() != null && c.availableBudgetWon() > 0) {
            // 월말 비율이 없으면 LLM이 지금 비율을 월말 값처럼 쓴다 (#122 실험 8, 31주차 "월말에 예산의 30%를 넘겨")
            sb.append("- 이대로면 월말엔 예산의 ")
                    .append(pct((double) c.predictedMonthEndWon() / c.availableBudgetWon()))
                    .append("\n");
        }

        // ── 이번 주 소비 감정 분포 ──
        if (c.weekEmotion() != null && !c.weekEmotion().counts().isEmpty()) {
            sb.append("\n[이번 주 소비 감정]\n");
            c.weekEmotion().counts().forEach((emo, cnt) -> {
                Long amt = c.weekEmotion().amounts().get(emo);
                sb.append("- ")
                        .append(emotionLabel(emo))
                        .append(" ")
                        .append(cnt)
                        .append("건");
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
            Set<String> actionCategories = new HashSet<>();
            c.actions().forEach(a -> actionCategories.add(a.categoryName()));
            for (CategoryOverspend ov : c.overspendCategories()) {
                sb.append("- ")
                        .append(ov.categoryName())
                        .append(": 월말 예상 초과 +")
                        .append(exactWon(ov.overAmountWon()))
                        .append(" (이번 주 지출 ")
                        .append(exactWon(ov.thisWeekWon()))
                        .append(", ")
                        .append(ov.thisWeekCount())
                        .append("건)");

                CauseSignals cs = ov.causeSignals();
                if (cs != null) {
                    if (cs.selfRatio() != null) {
                        sb.append(" (이번 주 금액이 최근 4주 주당 평균의 ")
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
                // 절약 액션 계산기가 제외한 카테고리(만족 소비 비중 70%+, 필수·민감 카테고리 등)임을 알린다 (#111: LLM이 식대 절약액을 지어냄)
                if (!actionCategories.contains(ov.categoryName())) {
                    sb.append(" [절약 액션 대상 아님]");
                }
                sb.append("\n");
                appendMerchantCounts(sb, ov);

                // 감정태그 — 건수와 금액 합계 함께 표시
                if (cs != null && !cs.emotionCounts().isEmpty()) {
                    sb.append("  감정: ");
                    cs.emotionCounts().forEach((emo, cnt) -> {
                        sb.append(emotionLabel(emo)).append(" ").append(cnt).append("건");
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
                    int shown = Math.min(3, cs.memos().size());
                    int shownTx = cs.memos().subList(0, shown).stream()
                            .mapToInt(MemoEvidence::count)
                            .sum();
                    // 목록이 잘리면 LLM이 줄 수를 횟수로 착각한다 (#111, exp3 기준선: 35주차 롯데월드 4건 → "3회" 5/10)
                    if (ov.thisWeekCount() > shownTx) {
                        sb.append("  메모 (이번 주 ")
                                .append(ov.thisWeekCount())
                                .append("건 중 ")
                                .append(shownTx)
                                .append("건만 표시):\n");
                    } else {
                        sb.append("  메모:\n");
                    }
                    for (MemoEvidence memo : cs.memos().subList(0, shown)) {
                        sb.append("    [")
                                .append(memo.date())
                                .append("] \"")
                                .append(memo.text())
                                .append("\" (")
                                .append(exactWon(memo.amount()))
                                .append(countSuffix(memo.count()))
                                .append(")\n");
                    }
                }
            }
        }

        // ── 이상치 [3] ──
        if (c.hasAnomaly()) {
            if (c.anomalyConfidence() == Confidence.HIGH) {
                sb.append("\n[이상 신호 — 신뢰도 높음, 노출 가능]\n");
                appendAnomalyLine(
                        sb,
                        c.anomalyCategoryName(),
                        c.anomalyMagnitude(),
                        c.anomalyWeeklyAmountWon(),
                        c.anomalyWeeklyCount(),
                        c.anomalyBasis(),
                        c.anomalyEmotion());
            } else {
                sb.append("\n[이상 신호 — 신뢰도 낮음/중간: 원인 단정 금지, 언급은 선택]\n");
                if (c.anomalyEmotion() != null && !c.anomalyEmotion().isBlank()) {
                    sb.append("- 감정: ").append(emotionLabel(c.anomalyEmotion())).append("\n");
                }
            }
        }

        // ── 추가 HIGH 이상치 (각각 1문장씩 언급) ──
        if (!c.additionalHighAnomalies().isEmpty()) {
            sb.append("\n[추가 HIGH 이상치 — 피드백에 각각 1문장씩 자연스럽게 언급]\n");
            for (AnomalyInfo a : c.additionalHighAnomalies()) {
                appendAnomalyLine(
                        sb,
                        a.categoryName(),
                        a.magnitude(),
                        a.weeklyAmountWon(),
                        a.weeklyCount(),
                        a.basis(),
                        a.emotion());
            }
        }

        // ── 이번 주 주목 거래 ──
        if (!c.weekHighlights().isEmpty()) {
            sb.append("\n[이번 주 주목 거래 — 금액 상위 일부, 전체 목록 아님]\n");
            for (TransactionHighlight h : c.weekHighlights()) {
                sb.append("- [")
                        .append(h.date())
                        .append("] ")
                        .append(h.categoryName())
                        .append(" ")
                        .append(exactWon(h.amountWon()))
                        .append(countSuffix(h.count()));
                if (h.memo() != null && !h.memo().isBlank()) {
                    sb.append(" \"").append(h.memo()).append("\"");
                }
                if (h.emotion() != null && !h.emotion().isBlank()) {
                    sb.append(" (").append(emotionLabel(h.emotion())).append(")");
                }
                sb.append("\n");
            }
        }

        // ── 가장 큰 한 건 — '가장 컸다'는 이 한 건에만 (#146) ──
        c.largestTransaction().ifPresent(h -> sb.append("\n[이번 주 가장 큰 한 건] ")
                .append(h.categoryName())
                .append(" ")
                .append(exactWon(h.amountWon()))
                .append(" — '가장 컸다'는 이 한 건에만 쓸 것. 카테고리 합계 순위는 재료에 없으므로 카테고리가 가장 컸다고 쓰지 말 것\n"));

        // ── 절약 액션 [6] ──
        if (codeActionMode(c)) {
            sb.append("\n[절약 액션] 마지막 문장은 시스템이 붙인다 — 절약 행동·절약 금액·효과(원)를 쓰지 말고 2문장으로 끝낼 것\n");
        } else {
            // 액션이 없는 주에도 LLM이 "주 1~2회 줄이면 월 5~10만 원" 같은 금액을 지어냄 (#111 exp5 17주차)
            sb.append("\n[절약 액션] 없음 — 절약 금액이나 절약 효과(원)를 제시하지 말 것\n");
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
        return verifyMonthEndNumbers(text, c, true);
    }

    /** @see #verifyNumbers(String, FeedbackContext, boolean) */
    boolean verifyMonthEndNumbers(String text, MonthEndContext c, boolean allowManWonBracket) {
        Set<Long> allowed = new HashSet<>();
        allowed.add(c.actualTotalWon());
        allowed.add(c.availableBudgetWon());
        allowed.add(Math.abs(c.budgetDiffWon()));
        for (CategoryResult r : c.overspendCategories()) {
            allowed.add(r.amountWon());
        }
        for (CategoryResult r : c.savingCategories()) {
            allowed.add(r.amountWon());
        }
        return checkExtracted(text, allowed, allowManWonBracket);
    }

    /**
     * 이상 신호 한 줄. 배수가 무엇의 배수인지(한 건 vs 주간 평균) 밝혀 LLM이 카테고리 전체가 N배라고 오해하지 않게 한다 (#111, exp1:
     * "한 건 배수를 카테고리 전체에 붙인 표현"이 주간 응답의 51%).
     */
    private void appendAnomalyLine(
            StringBuilder sb,
            String categoryName,
            double magnitude,
            long weeklyAmountWon,
            int weeklyCount,
            AnomalyBasis basis,
            String emotion) {
        String mag = String.format("%.1f", magnitude);
        sb.append("- ").append(categoryName).append(": ");
        if (basis == null) {
            sb.append("평소 대비 약 ").append(mag).append("배");
        } else if (basis.singleTransaction()) {
            sb.append("한 건");
            if (basis.subjectLabel() != null && !basis.subjectLabel().isBlank()) {
                sb.append(" \"").append(basis.subjectLabel()).append("\"");
            }
            sb.append(" ")
                    .append(exactWon(basis.subjectWon()))
                    .append("이 평소 ")
                    .append(categoryName)
                    .append(
                            basis.baselineWeeks() > 0
                                    ? " 한 건 금액(최근 " + basis.baselineWeeks() + "주 한 건 중앙값 "
                                    : " 한 건 금액(과거 한 건 중앙값 ")
                    .append(exactWon(basis.baselineWon()))
                    .append(")의 ")
                    .append(mag)
                    .append("배 [한 건 배수]");
        } else {
            sb.append("최근 2주 주당 평균 ")
                    .append(exactWon(basis.subjectWon()))
                    .append("이 그 앞 4주 주당 평균(")
                    .append(exactWon(basis.baselineWon()))
                    .append(")의 ")
                    .append(mag)
                    .append("배 [주간 금액 배수]");
        }
        if (weeklyAmountWon > 0) {
            sb.append(" / ").append(categoryName).append(" 이번 주 합계: ").append(exactWon(weeklyAmountWon));
            if (weeklyCount > 0) sb.append(" (").append(weeklyCount).append("건)");
        }
        if (emotion != null && !emotion.isBlank()) {
            sb.append(", 감정: ").append(emotionLabel(emotion));
        }
        sb.append("\n");
    }

    private static long actionTotal(FeedbackContext c) {
        return c.actions().stream().mapToLong(ActionSummary::reductionWon).sum();
    }

    /**
     * 감정 태그 코드를 사용자에게 보이는 말로 바꾼다. 프롬프트에 영문 코드가 있으면 LLM이 "SATISFIED 태그"처럼 그대로 옮기기 때문 (#122).
     * 모르는 값은 그대로 둔다.
     */
    static String emotionLabel(String code) {
        if (code == null) return null;
        return switch (code) {
            case "NECESSARY" -> "필요";
            case "SATISFIED" -> "만족";
            case "IMPULSE" -> "충동";
            case "STRESS_RELIEF" -> "스트레스 해소";
            case "REGRET" -> "후회";
            default -> code;
        };
    }

    /** 상위 거래처 표시 개수. 넘으면 "외 N건". */
    private static final int MERCHANT_LIMIT = 5;

    /**
     * 거래처별 건수 줄 (#111). 카테고리 건수만 주면 LLM이 메모에 보이는 결제 수단에 붙여 "토스페이·카카오페이 7건"처럼 쓴다
     * (실제 카카오페이 3·토스페이 3·네이버페이 1).
     */
    private static void appendMerchantCounts(StringBuilder sb, CategoryOverspend ov) {
        if (ov.thisWeekCount() < 2
                || ov.merchantCounts() == null
                || ov.merchantCounts().isEmpty()) return;
        List<String> parts = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<String, Integer> e : ov.merchantCounts().entrySet()) {
            if (parts.size() == MERCHANT_LIMIT) break;
            parts.add(e.getKey() + " " + e.getValue() + "건");
            shown += e.getValue();
        }
        sb.append("  거래처별: ").append(String.join(", ", parts));
        int rest = ov.merchantCounts().values().stream()
                        .mapToInt(Integer::intValue)
                        .sum()
                - shown;
        if (rest > 0) sb.append(" 외 ").append(rest).append("건");
        sb.append("\n");
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
                .append("월엔 총 ")
                .append(won(c.actualTotalWon()))
                .append("을 썼어요.");
        if (c.budgetDiffWon() >= 0) {
            sb.append(" 예산보다 ").append(won(c.budgetDiffWon())).append(" 아꼈어요. 이번 달도 수고했어요!");
        } else {
            sb.append(" 예산보다 ").append(won(Math.abs(c.budgetDiffWon()))).append(" 넘게 썼어요.");
            if (!c.overspendCategories().isEmpty()) {
                CategoryResult top = c.overspendCategories().get(0);
                sb.append(" 다음 달엔 ").append(top.categoryName()).append(" 지출부터 줄여봐요.");
            }
        }
        return sb.toString();
    }

    /** LLM 없이도 동작하는 결정적 폴백. 사용자 대면이므로 {@code won()}으로 자연스러운 표현. */
    private String template(FeedbackContext c) {
        StringBuilder sb = new StringBuilder();
        String first = weekTotalSentence(c);
        if (!first.isEmpty()) sb.append(first).append(" ");

        if (c.savingsImpactWon() < 0) {
            sb.append("이번 달 이대로 가면 약 ")
                    .append(won(c.predictedMonthEndWon()))
                    .append("을 써서 저축 목표에서 ")
                    .append(won(Math.abs(c.savingsImpactWon())))
                    .append(" 멀어질 것 같아요.");
            if (!c.actions().isEmpty()) {
                ActionSummary top = c.actions().get(0);
                if (top.remainingExpectedWon() > 0) {
                    sb.append(" 남은 기간 ")
                            .append(top.categoryName())
                            .append("는 예상 ")
                            .append(won(top.remainingExpectedWon()))
                            .append(" 중 ")
                            .append(won(Math.max(0, top.remainingExpectedWon() - top.reductionWon())))
                            .append("까지만 쓰면 목표에 한결 가까워져요.");
                } else {
                    sb.append(" ")
                            .append(top.categoryName())
                            .append("에서 ")
                            .append(won(top.reductionWon()))
                            .append("만 줄여도 목표에 한결 가까워져요.");
                }
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
    /** 평소 대비 속도. 비율이 '내려왔다'로 읽히지 않게 속도로 표현한다 (#122 실험 8·9, 21주차 "104%까지 내려왔어요"). */
    private static String speed(double current, double usual) {
        if (usual <= 0) return "비교 기준 없음";
        double r = current / usual;
        return r > 1.05 ? "평소보다 빠른 속도" : r < 0.95 ? "평소보다 느린 속도" : "평소와 비슷한 속도";
    }

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
            Confidence overallConfidence,
            AnomalyBasis anomalyBasis,
            int anomalyWeeklyCount,
            int remainingDaysInMonth,
            long recent4WeekAvgWon,
            TransactionHighlight weekLargest) {

        /** 이번 주 가장 큰 한 건을 따로 넘기지 않는 형식 — 주목 거래 중 가장 큰 건을 쓴다. */
        public FeedbackContext(
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
                Confidence overallConfidence,
                AnomalyBasis anomalyBasis,
                int anomalyWeeklyCount,
                int remainingDaysInMonth,
                long recent4WeekAvgWon) {
            this(
                    predictedMonthEndWon,
                    availableBudgetWon,
                    savingsImpactWon,
                    burnRate,
                    pacing,
                    hasAnomaly,
                    anomalyCategoryName,
                    anomalyMagnitude,
                    anomalyWeeklyAmountWon,
                    anomalyConfidence,
                    anomalyEmotion,
                    additionalHighAnomalies,
                    weekEmotion,
                    weekTotalWon,
                    prevWeekTotalWon,
                    complianceRate,
                    overspendCategories,
                    actions,
                    weekHighlights,
                    feedbackType,
                    bandWidth,
                    overallConfidence,
                    anomalyBasis,
                    anomalyWeeklyCount,
                    remainingDaysInMonth,
                    recent4WeekAvgWon,
                    null);
        }

        /** 이번 주 가장 큰 한 건: 따로 받은 값, 없으면 주목 거래 중 가장 큰 건. */
        Optional<TransactionHighlight> largestTransaction() {
            if (weekLargest != null) return Optional.of(weekLargest);
            return weekHighlights.stream().max(Comparator.comparingLong(TransactionHighlight::amountWon));
        }

        /** 직전 4주 평균 없이 만드는 이전 형식 (#144 이전 호출부·테스트). */
        public FeedbackContext(
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
                Confidence overallConfidence,
                AnomalyBasis anomalyBasis,
                int anomalyWeeklyCount,
                int remainingDaysInMonth) {
            this(
                    predictedMonthEndWon,
                    availableBudgetWon,
                    savingsImpactWon,
                    burnRate,
                    pacing,
                    hasAnomaly,
                    anomalyCategoryName,
                    anomalyMagnitude,
                    anomalyWeeklyAmountWon,
                    anomalyConfidence,
                    anomalyEmotion,
                    additionalHighAnomalies,
                    weekEmotion,
                    weekTotalWon,
                    prevWeekTotalWon,
                    complianceRate,
                    overspendCategories,
                    actions,
                    weekHighlights,
                    feedbackType,
                    bandWidth,
                    overallConfidence,
                    anomalyBasis,
                    anomalyWeeklyCount,
                    remainingDaysInMonth,
                    0L);
        }
    }

    /**
     * 이상치 배수의 기준 (#111). 프롬프트에 무엇의 배수인지 밝히기 위해 전달한다.
     *
     * @param singleTransaction true면 단발 이상치: 한 건 금액 ÷ 과거 한 건 중앙값. false면 추세: 최근 2주 주당 평균 ÷ 그 앞 4주 주당 평균
     * @param subjectWon        배수의 분자 (한 건 금액 또는 최근 2주 주당 평균)
     * @param baselineWon       배수의 분모 (과거 한 건 중앙값 또는 그 앞 4주 주당 평균)
     * @param subjectLabel      단발 이상치 거래의 메모·내역 (추세면 null)
     * @param baselineWeeks     단발 이상치 중앙값을 계산한 과거 기간(주). 0이면 모름
     */
    public record AnomalyBasis(
            boolean singleTransaction, long subjectWon, long baselineWon, String subjectLabel, int baselineWeeks) {

        /** 기준 기간을 모르는 이전 형식 — 문구에 기간을 쓰지 않는다. */
        public AnomalyBasis(boolean singleTransaction, long subjectWon, long baselineWon, String subjectLabel) {
            this(singleTransaction, subjectWon, baselineWon, subjectLabel, 0);
        }
    }

    /**
     * PRIMARY를 제외한 추가 HIGH 이상치 정보.
     * confidence는 모두 HIGH로 필터링된 상태이므로 별도로 보관하지 않는다.
     */
    public record AnomalyInfo(
            String categoryName,
            double magnitude,
            long weeklyAmountWon,
            String emotion,
            AnomalyBasis basis,
            int weeklyCount) {}

    /**
     * 카테고리별 초과 예상.
     *
     * @param overAmountWon  월말 예상 초과액 (이번 달 지금까지의 지출로 외삽, 이미 쓴 금액이 아님)
     * @param thisWeekWon    이번 주에 새로 발생한 해당 카테고리 변동 지출
     */
    /**
     * @param merchantCounts 이번 주 거래처(결제 수단)별 건수, 건수 내림차순 (#111)
     */
    public record CategoryOverspend(
            String categoryName,
            long overAmountWon,
            long thisWeekWon,
            CauseSignals causeSignals,
            int thisWeekCount,
            Map<String, Integer> merchantCounts) {

        public CategoryOverspend(
                String categoryName,
                long overAmountWon,
                long thisWeekWon,
                CauseSignals causeSignals,
                int thisWeekCount) {
            this(categoryName, overAmountWon, thisWeekWon, causeSignals, thisWeekCount, Map.of());
        }
    }

    /**
     * 이번 주 전체 변동지출의 감정 태그 분포 요약.
     * POSITIVE 주차에도 감정 맥락을 LLM에 전달하기 위해 초과 카테고리 여부와 무관하게 조립한다.
     *
     * @param counts     감정 태그별 건수  {"REGRET": 2, "SATISFIED": 3}
     * @param amounts    감정 태그별 금액 합계
     * @param untaggedWon 태그 없는 변동지출 합계
     */
    public record WeekEmotionSummary(Map<String, Long> counts, Map<String, Long> amounts, long untaggedWon) {}

    /** @param remainingExpectedWon 이번 달 남은 기간 그 카테고리의 예상 변동 지출 — 줄일 금액의 기준 (#144). 모르면 0 */
    public record ActionSummary(
            String categoryName,
            long reductionWon,
            String dominantFactor,
            double feasibilityScore,
            long remainingExpectedWon) {

        public ActionSummary(String categoryName, long reductionWon, String dominantFactor, double feasibilityScore) {
            this(categoryName, reductionWon, dominantFactor, feasibilityScore, 0L);
        }
    }

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
    /**
     * @param amountWon 거래 1건 금액
     * @param count     같은 날·카테고리·금액·메모·감정으로 묶인 거래 건수 (#111)
     */
    public record TransactionHighlight(
            LocalDate date, String categoryName, long amountWon, String memo, String emotion, int count) {

        /** 1건짜리 주목 거래. */
        public TransactionHighlight(LocalDate date, String categoryName, long amountWon, String memo, String emotion) {
            this(date, categoryName, amountWon, memo, emotion, 1);
        }

        /**
         * 동일 거래를 한 건으로 묶은 뒤 금액 내림차순 상위 {@code limit}개를 반환한다. 반드시 자르기 전에 묶어야 한다 — 상위 N개로 자른 뒤 묶으면 잘린
         * 건수만 세어 "× 3건"처럼 틀린 횟수가 프롬프트에 들어간다.
         */
        public static List<TransactionHighlight> groupIdenticalTopN(List<TransactionHighlight> highlights, int limit) {
            Map<List<Object>, TransactionHighlight> grouped = new LinkedHashMap<>();
            for (TransactionHighlight h : highlights) {
                List<Object> key = Arrays.asList(h.date(), h.categoryName(), h.amountWon(), h.memo(), h.emotion());
                grouped.merge(
                        key,
                        h,
                        (a, b) -> new TransactionHighlight(
                                a.date(),
                                a.categoryName(),
                                a.amountWon(),
                                a.memo(),
                                a.emotion(),
                                a.count() + b.count()));
            }
            return grouped.values().stream()
                    .sorted(Comparator.comparingLong(TransactionHighlight::amountWon)
                            .reversed())
                    .limit(limit)
                    .toList();
        }
    }
}
