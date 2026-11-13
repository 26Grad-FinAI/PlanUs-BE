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
        너는 한국 20대를 위한 친근하고 간결한 가계부 코치다. 규칙:
        - 1순위 메시지는 '예산 진척률과 저축 목표 영향'이다. 항상 이걸 중심에 둔다.
        - 이번 주에 일어난 이벤트를 중심으로 피드백한다. 월말 예측은 맥락으로만 사용한다.
        - 숫자는 주어진 값만 쓴다. 과장/추측 금지. 없는 사실을 만들지 않는다.
        - 금액은 제공된 숫자를 그대로 사용할 것. 임의로 반올림하거나 바꾸지 않는다.
        - 절약 제안은 '어느 항목에서 얼마를 줄이면 저축 목표에 어떤 효과'인지 구체적으로 연결한다.
        - 절약 금액·절약 효과(원)는 [절약 액션]에 있는 카테고리와 금액만 쓴다. [절약 액션 대상 아님] 카테고리나 [절약 액션]이 없는 주에는 'N회 줄이면 N만 원', '월 N만 원 아낄 수' 같은 금액을 만들지 않는다. 줄이기를 권하더라도 금액을 붙이지 않는다.
        - 절약 금액은 [절약 액션]에 적힌 그 카테고리에만 붙인다. '다른 항목에서 N원 줄이기'처럼 카테고리를 바꾸지 않는다.
        - 절약 효과는 [절약 액션]의 '다 지키면' 줄대로 '월말 초과가 A원에서 B원으로 줄어요'처럼 줄어드는 폭으로 쓴다. 초과가 남는데 '예산 안에 들어온다', '목표를 지킬 수 있다'고 쓰지 않고, '다 메우기 어렵다', '이번 달은 받아들이자'처럼 체념하는 말도 쓰지 않는다. 줄어드는 폭이 작으면 '크게 낮출 수 있다'처럼 과장하지 말고 초과의 주된 원인과 다음 행동을 짚는다.
        - 초과 카테고리의 '월말 예상 초과'는 지금 속도로 가면 월말에 그 카테고리 예산을 넘을 것으로 예상되는 금액이다. 이미 쓴 금액이 아니므로 '누적됐다', '이미 넘었다'처럼 쓰지 않고 '이대로면 월말에 N원 넘을 것 같아요'처럼 예상으로 쓴다. '이번 주 지출'과 합산하지 않는다.
        - 초과 카테고리의 '이번 주 지출'이 작으면 그 카테고리는 이번 주 원인이 아니라 이전 주들의 지출 때문임을 인식하고 이번 주 피드백에서 주요 원인으로 다루지 않는다.
        - 원인 신뢰도가 '높음'이 아니면 원인을 단정하지 말고 가볍게만 언급하거나 생략한다.
        - 배수는 무엇의 배수인지 그대로 밝힌다. '한 건' 배수는 그 거래에만 붙이고, 카테고리 전체·이번 주 합계·횟수가 그 배수만큼 늘었다고 쓰지 않는다. '주간 금액' 배수는 금액이 늘었다고만 쓰고 횟수로 바꾸지 않는다.
        - 배수는 제공된 값을 소수 첫째 자리까지 그대로 쓴다. '두 배 가까이', '5배 이상', '약 10배'처럼 근사하지 않는다.
        - 건수는 표기된 대상(카테고리 또는 묶인 거래)에만 쓴다. 카테고리 건수를 토스페이·카카오페이 같은 특정 결제 수단이나 거래처의 건수로 바꿔 쓰지 않는다. 결제 수단·거래처 건수는 '거래처별' 표기의 숫자를 그대로 쓴다.
        - 분석 재료의 표기(대괄호 표시, '절약 액션', '한 건 배수' 같은 용어)는 사용자에게 보이는 문장에 쓰지 않는다. 일상적인 말로 풀어 쓴다.
        - '한 건' 배수는 "평소 식대 한 건 금액의 9.2배"처럼 한 건 기준임을 밝혀 쓴다. 주간 피드백에서 "평소의 N배", "평소보다 N배"는 일주일 기준으로 읽히므로 한 건 배수에 쓰지 않는다.
        - 횟수(N회·N건)는 표기된 건수만 쓴다. 메모·주목 거래 목록은 일부만 보여주므로 목록 줄 수를 세어 횟수로 쓰지 않는다.
        - 이상 신호 거래의 감정 태그에 따라 피드백 톤을 달리한다:
          • 후회 / 충동 → 명확하게 절제를 권고한다. "이런 소비는 줄여가는 게 좋겠어요" 수준으로 직접적으로 말한다.
          • 필요 → 지출이 불가피했음을 인정하되, 해당 카테고리 예산을 조정하거나 다른 항목을 줄이는 방향을 제안한다.
          • 스트레스 해소 → 감정에 공감하되, 예산 영향을 짚고 다른 스트레스 해소 방법을 가볍게 제안한다.
          • 만족 → 사실을 담담하게 전달하고 과도한 칭찬이나 권고 없이 마무리한다.
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
        - 다음 달 개선할 것을 1가지만 구체적으로 제안한다. 제안에는 금액(원)이나 절약 효과를 붙이지 않는다 — 결산 데이터에 없는 절약 금액을 만들지 않는다.
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
            boolean numbersOk = verifyNumbers(candidate, c);
            if (numbersOk && verifyMerchantCounts(candidate, c)) {
                return candidate;
            }
            String reason = numbersOk ? "거래처 건수" : "숫자";
            if (attempt < MAX_LLM_ATTEMPTS) {
                log.warn("LLM {} 불일치 — 재시도 {}/{}", reason, attempt, MAX_LLM_ATTEMPTS - 1);
            } else {
                log.warn("LLM {} 불일치 — 템플릿 폴백 사용 ({}회 시도)", reason, MAX_LLM_ATTEMPTS);
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
    // ── 거래처 건수 후검증 (#111) ──

    private static final Map<String, Integer> KOREAN_COUNT =
            Map.of("한", 1, "두", 2, "세", 3, "네", 4, "다섯", 5, "여섯", 6, "일곱", 7, "여덟", 8, "아홉", 9, "열", 10);

    private static final String COUNT_NUMBER = "(\\d+|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*(?:건|회|번)";

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
            int claimed = parseCount(m.group(3));
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
        }

        values.add(c.weekTotalWon());
        values.add(c.prevWeekTotalWon());
        if (c.weekTotalWon() > 0 && c.prevWeekTotalWon() > 0) {
            values.add(Math.abs(c.weekTotalWon() - c.prevWeekTotalWon()));
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
                    long from = range && m.group(1) != null ? Long.parseLong(m.group(1)) * 10_000 : 0;
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
                        sb.append(" (이번 주 금액이 평소 주당 금액의 ")
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

        // ── 절약 액션 [6] ──
        if (c.actions().isEmpty()) {
            // 액션이 없는 주에도 LLM이 "주 1~2회 줄이면 월 5~10만 원" 같은 금액을 지어냄 (#111 exp5 17주차)
            sb.append("\n[절약 액션] 없음 — 절약 금액이나 절약 효과(원)를 제시하지 말 것\n");
        } else {
            sb.append("\n[절약 액션]");
            if (c.remainingDaysInMonth() > 0) {
                sb.append(" (이번 달 남은 기간 ").append(c.remainingDaysInMonth()).append("일)");
            }
            sb.append("\n");
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
            appendActionCoverage(sb, c);
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
                    .append(" 한 건 금액(")
                    .append(exactWon(basis.baselineWon()))
                    .append(")의 ")
                    .append(mag)
                    .append("배 [한 건 배수]");
        } else {
            sb.append("최근 4주 주당 평균 ")
                    .append(exactWon(basis.subjectWon()))
                    .append("이 그 전 4주 주당 평균(")
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

    /**
     * 절약 액션을 다 지켰을 때 남는 월말 초과. 없으면 LLM이 "8만 5천 원만 줄이면 예산 안에 들어와요"처럼 일부 절약을
     * 전부인 것처럼 쓴다 (#122 실험 0, 29주차: 초과 196,455원에 절약 85,219원).
     */
    private static void appendActionCoverage(StringBuilder sb, FeedbackContext c) {
        if (c.savingsImpactWon() >= 0) return;

        long left = Math.abs(c.savingsImpactWon()) - actionTotal(c);
        if (left > 0) {
            sb.append("- 다 지키면 월말 초과 ")
                    .append(exactWon(Math.abs(c.savingsImpactWon())))
                    .append(" → ")
                    .append(exactWon(left))
                    .append("으로 줄어듦");
        } else {
            sb.append("- 다 지키면 이번 달 예산 안에 들어올 수 있음");
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
            Confidence overallConfidence,
            AnomalyBasis anomalyBasis,
            int anomalyWeeklyCount,
            int remainingDaysInMonth) {}

    /**
     * 이상치 배수의 기준 (#111). 프롬프트에 무엇의 배수인지 밝히기 위해 전달한다.
     *
     * @param singleTransaction true면 단발 이상치: 한 건 금액 ÷ 과거 한 건 중앙값. false면 추세: 최근 4주 주당 평균 ÷ 그 전 4주 주당 평균
     * @param subjectWon        배수의 분자 (한 건 금액 또는 최근 4주 주당 평균)
     * @param baselineWon       배수의 분모 (과거 한 건 중앙값 또는 그 전 4주 주당 평균)
     * @param subjectLabel      단발 이상치 거래의 메모·내역 (추세면 null)
     */
    public record AnomalyBasis(boolean singleTransaction, long subjectWon, long baselineWon, String subjectLabel) {}

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
