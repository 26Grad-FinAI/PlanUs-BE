package com.planus.backend.domain.aifeedback.core;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CategoriesTest {

    @Nested
    @DisplayName("name()")
    class Name {

        @Test
        @DisplayName("유효한 카테고리 ID → 한국어 이름 반환")
        void validIds() {
            assertThat(Categories.name(1)).isEqualTo("식료품");
            assertThat(Categories.name(2)).isEqualTo("의류신발");
            assertThat(Categories.name(3)).isEqualTo("보건");
            assertThat(Categories.name(4)).isEqualTo("교통");
            assertThat(Categories.name(5)).isEqualTo("오락문화");
            assertThat(Categories.name(6)).isEqualTo("교육");
            assertThat(Categories.name(7)).isEqualTo("식대");
            assertThat(Categories.name(8)).isEqualTo("숙박여행");
            assertThat(Categories.name(9)).isEqualTo("정보통신");
            assertThat(Categories.name(10)).isEqualTo("기타");
        }

        @Test
        @DisplayName("null → 기타")
        void nullId() {
            assertThat(Categories.name(null)).isEqualTo("기타");
        }

        @Test
        @DisplayName("미등록 ID → 기타")
        void unknownId() {
            assertThat(Categories.name(99)).isEqualTo("기타");
            assertThat(Categories.name(0)).isEqualTo("기타");
            assertThat(Categories.name(-1)).isEqualTo("기타");
        }
    }

    @Nested
    @DisplayName("id()")
    class Id {

        @Test
        @DisplayName("유효한 카테고리명 → ID 반환")
        void validNames() {
            assertThat(Categories.id("식료품")).isEqualTo(1);
            assertThat(Categories.id("의류신발")).isEqualTo(2);
            assertThat(Categories.id("보건")).isEqualTo(3);
            assertThat(Categories.id("교통")).isEqualTo(4);
            assertThat(Categories.id("오락문화")).isEqualTo(5);
            assertThat(Categories.id("교육")).isEqualTo(6);
            assertThat(Categories.id("식대")).isEqualTo(7);
            assertThat(Categories.id("숙박여행")).isEqualTo(8);
            assertThat(Categories.id("정보통신")).isEqualTo(9);
            assertThat(Categories.id("기타")).isEqualTo(10);
        }

        @Test
        @DisplayName("미등록 카테고리명 → ETC_CATEGORY_ID")
        void unknownName() {
            assertThat(Categories.id("없는카테고리")).isEqualTo(Categories.ETC_CATEGORY_ID);
        }
    }

    @Test
    @DisplayName("AI_CATEGORY_COUNT = 9, ETC_CATEGORY_ID = 10")
    void constants() {
        assertThat(Categories.AI_CATEGORY_COUNT).isEqualTo(9);
        assertThat(Categories.ETC_CATEGORY_ID).isEqualTo(10);
    }
}
