package com.planus.backend.domain.aifeedback.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MerchantNamesTest {

    @Test
    @DisplayName("메모 접두어·(주)·잘린 괄호를 떼고 첫 단어를 거래처 이름으로 — 실제 거래 메모")
    void key_fromRealMemos() {
        assertThat(MerchantNames.key("FBS 출금 카카오페이")).isEqualTo("카카오페이");
        assertThat(MerchantNames.key("오픈뱅킹출금 토스페이")).isEqualTo("토스페이");
        assertThat(MerchantNames.key("FBS 출금 네이버페이결제")).isEqualTo("네이버페이결제");
        assertThat(MerchantNames.key("체크카드 (주)여백스튜디오")).isEqualTo("여백스튜디오");
        assertThat(MerchantNames.key("체크카드 카카오T일반택시(법")).isEqualTo("카카오T일반택시");
        assertThat(MerchantNames.key("오픈뱅킹출금 토스 후불결제")).isEqualTo("토스");
    }

    @Test
    @DisplayName("메모가 없으면 '메모 없음'")
    void key_blank() {
        assertThat(MerchantNames.key(null)).isEqualTo("메모 없음");
        assertThat(MerchantNames.key("  ")).isEqualTo("메모 없음");
    }

    @Test
    @DisplayName("거래처별 건수: 건수 내림차순, 같으면 처음 등장 순서 (34주차 기타 7건)")
    void countByMerchant_sortedByCount() {
        List<String> memos = Arrays.asList(
                "FBS 출금 카카오페이",
                "오픈뱅킹출금 토스페이",
                "FBS 출금 카카오페이",
                "오픈뱅킹출금 토스페이",
                "FBS 출금 카카오페이",
                "오픈뱅킹출금 토스페이",
                "FBS 출금 네이버페이결제");

        assertThat(MerchantNames.countByMerchant(memos))
                .containsExactly(
                        java.util.Map.entry("카카오페이", 3),
                        java.util.Map.entry("토스페이", 3),
                        java.util.Map.entry("네이버페이결제", 1));
    }
}
