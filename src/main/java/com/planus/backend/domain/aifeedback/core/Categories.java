package com.planus.backend.domain.aifeedback.core;

import java.util.Map;

/** 10개 카테고리 id → 표시명 (AI서버 9개 + 기타). */
public final class Categories {
    private Categories() {}

    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(1, "식료품"),
            Map.entry(2, "의류신발"),
            Map.entry(3, "보건"),
            Map.entry(4, "교통"),
            Map.entry(5, "오락문화"),
            Map.entry(6, "교육"),
            Map.entry(7, "식대"),
            Map.entry(8, "숙박여행"),
            Map.entry(9, "정보통신"),
            Map.entry(10, "기타"));

    /** AI 서버가 인식하는 카테고리 수 (기타 제외). */
    public static final int AI_CATEGORY_COUNT = 9;

    /** 기타 카테고리 ID. AI 서버 전송 시 제외된다. */
    public static final int ETC_CATEGORY_ID = 10;

    /**
     * 카테고리 ID에 대응하는 한국어 표시명을 반환한다.
     *
     * @param id 카테고리 ID (1~10), null 허용
     * @return 표시명, null 또는 미등록 ID이면 "기타"
     */
    public static String name(Integer id) {
        if (id == null) return "기타";
        return NAMES.getOrDefault(id, "기타");
    }

    /**
     * 한국어 카테고리명에 대응하는 카테고리 ID를 반환한다.
     *
     * @param name 카테고리 한국어명
     * @return 카테고리 ID, 미등록이면 {@link #ETC_CATEGORY_ID}
     */
    public static int id(String name) {
        for (Map.Entry<Integer, String> entry : NAMES.entrySet()) {
            if (entry.getValue().equals(name)) {
                return entry.getKey();
            }
        }
        return ETC_CATEGORY_ID;
    }
}
