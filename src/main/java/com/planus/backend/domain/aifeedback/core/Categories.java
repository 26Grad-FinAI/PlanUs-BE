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
    public static String name(Integer id) { return NAMES.getOrDefault(id, "기타"); }
}
