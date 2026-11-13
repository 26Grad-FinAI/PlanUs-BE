package com.planus.backend.domain.aifeedback.core;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 거래 메모에서 거래처(결제 수단) 이름을 뽑고 건수를 센다 (#111).
 *
 * <p>카테고리 건수만 주면 LLM이 메모 목록에 보이는 결제 수단에 그 건수를 붙인다 — 기타 7건(카카오페이 3·토스페이 3·네이버페이 1)을
 * "토스페이·카카오페이 7건"으로 씀 (실험 3·4·6·7). 거래처별 건수를 직접 제공해 이를 막는다.
 */
public final class MerchantNames {

    /** 은행·카드 거래 메모의 접두어 — 거래처 이름이 아니다. */
    private static final Pattern PREFIX = Pattern.compile("^(체크카드|오픈뱅킹출금|FBS 출금|제로페이출금|직불출금)\\s*");

    private static final Pattern CORP = Pattern.compile("^\\(주\\)\\s*");

    /** 이름 뒤에 잘려 붙은 괄호 조각 ("카카오T일반택시(법" → "카카오T일반택시"). */
    private static final Pattern TRAILING_BRACKET = Pattern.compile("[(\\[].*$");

    static final String NO_MEMO = "메모 없음";

    private MerchantNames() {}

    /**
     * 메모 → 거래처 이름. 접두어("체크카드", "오픈뱅킹출금", "FBS 출금" 등)와 "(주)"를 떼고 첫 단어를 쓴다.
     * "오픈뱅킹출금 토스페이" → "토스페이", "FBS 출금 카카오페이" → "카카오페이", "체크카드 (주)롯데월드" → "롯데월드".
     */
    public static String key(String memo) {
        if (memo == null || memo.isBlank()) return NO_MEMO;
        String m = CORP.matcher(PREFIX.matcher(memo.trim()).replaceFirst(""))
                .replaceFirst("")
                .trim();
        if (m.isEmpty()) return NO_MEMO;
        String first = m.split("\\s+")[0];
        String cleaned = TRAILING_BRACKET.matcher(first).replaceFirst("");
        return cleaned.isEmpty() ? first : cleaned;
    }

    /** 거래처별 건수. 건수 내림차순, 같으면 처음 등장한 순서. */
    public static Map<String, Integer> countByMerchant(List<String> memos) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String memo : memos) counts.merge(key(memo), 1, Integer::sum);
        List<Map.Entry<String, Integer>> entries = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .toList();
        return entries.stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }
}
