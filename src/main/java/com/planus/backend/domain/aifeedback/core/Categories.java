package com.planus.backend.domain.aifeedback.core;

import java.util.Map;

/** 11개 카테고리 id → 표시명. */
public final class Categories {
    private Categories() {}
    private static final Map<Integer, String> NAMES = Map.ofEntries(
        Map.entry(1, "식료품"), Map.entry(2, "주류·담배"), Map.entry(3, "의류·신발"),
        Map.entry(4, "가정용품·가사"), Map.entry(5, "보건"), Map.entry(6, "교통"),
        Map.entry(7, "정보통신"), Map.entry(8, "오락·문화"), Map.entry(9, "교육"),
        Map.entry(10, "외식·숙박"), Map.entry(11, "기타"));
    /**
     * 카테고리 ID에 대응하는 한국어 표시명을 반환한다.
     *
     * @param id 카테고리 ID (1~11), null 허용
     * @return 표시명, null 또는 미등록 ID이면 "기타"
     */
    public static String name(Integer id) {
        if (id == null) return "기타";
        return NAMES.getOrDefault(id, "기타");
    }
}
